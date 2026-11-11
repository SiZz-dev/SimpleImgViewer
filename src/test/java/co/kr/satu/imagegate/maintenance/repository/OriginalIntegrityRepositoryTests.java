package co.kr.satu.imagegate.maintenance.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OriginalIntegrityRepositoryTests {
    @TempDir Path temp;
    Path root(){return temp.resolve("originals");}
    OriginalIntegrityOptions options(){return new OriginalIntegrityOptions(root(),true,100,1000,1048576);}
    MediaRecord register() throws Exception {
        var image=new BufferedImage(12,10,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        return new LocalMediaStore(root(),1048576).register("tenant","owner","private-business.png",new ByteArrayInputStream(out.toByteArray()));
    }
    Path blob(MediaRecord m) throws Exception {return new LocalMediaStore(root(),1048576).resolveOriginal(m);}
    Map<Path,byte[]> contents() throws Exception {
        var result=new HashMap<Path,byte[]>();try(var paths=Files.walk(root())){for(var p:paths.filter(Files::isRegularFile).toList())result.put(root().relativize(p),Files.readAllBytes(p));}return result;
    }
    @Test void verifiesOriginalWithoutChangingAnyStoredBytesOrAddingFiles() throws Exception {
        var m=register();var before=contents();var report=new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH);
        assertThat(report.verified()).isEqualTo(1);assertThat(report.issues()).isZero();assertThat(report.hashedBytes()).isEqualTo(m.sizeBytes());
        assertThat(report.generatedAt()).isEqualTo(Instant.EPOCH);assertThat(report.limitReached()).isFalse();
        assertThat(report.items()).containsExactly(new OriginalIntegrityReport.Item(m.mediaId(),"VERIFIED"));
        var after=contents();assertThat(after.keySet()).isEqualTo(before.keySet());for(var path:before.keySet())assertThat(after.get(path)).isEqualTo(before.get(path));
    }
    @Test void recognizesOnlyEmptyCanonicalQuotaLockAndPreservesIt() throws Exception {
        var m=register();
        try(var store=new LocalMediaStore(root(),1048576,0,1048576)){}
        Path lock=root().resolve(".quota-writer.lock");
        var report=new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH);
        assertThat(report.verified()).isEqualTo(1);assertThat(report.issues()).isZero();assertThat(Files.size(lock)).isZero();
        Files.writeString(lock,"unexpected");
        assertThatThrownBy(()->new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);
    }
    @Test void sameSizeDamageIsDetectedByFullHash() throws Exception {
        var m=register();Path blob=blob(m);byte[] bytes=Files.readAllBytes(blob);bytes[bytes.length-1]^=1;Files.write(blob,bytes);
        var report=new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH);
        assertThat(report.items().getFirst().outcome()).isEqualTo("CHECKSUM_MISMATCH");assertThat(report.verified()).isZero();
    }
    @Test void truncationAndMissingMetadataAreReportedWithoutRepair() throws Exception {
        var m=register();Path blob=blob(m);Files.write(blob,new byte[]{1});
        assertThat(new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH).items().getFirst().outcome()).isEqualTo("SIZE_MISMATCH");
        Path metadata=blob.resolveSibling("metadata.properties");Files.delete(metadata);
        assertThat(new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH).items().getFirst().outcome()).isEqualTo("INVALID_OR_UNREADABLE");assertThat(Files.exists(metadata)).isFalse();
    }
    @Test void invalidCatalogIdentityAndEscapingBlobReferenceAreRejectedBeforeHashing() throws Exception {
        var m=register();Path meta=blob(m).resolveSibling("metadata.properties");var p=new Properties();try(var in=Files.newInputStream(meta)){p.load(in);}
        for(var field:List.of("tenantId","storedFileName","sha256")) {
            String previous=p.getProperty(field);p.setProperty(field,field.equals("storedFileName")?"../../secret":"wrong");
            try(var out=Files.newOutputStream(meta)){p.store(out,"fixture");}
            var report=new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH);
            assertThat(report.items().getFirst().outcome()).isEqualTo("INVALID_OR_UNREADABLE");assertThat(report.hashedBytes()).isZero();p.setProperty(field,previous);
        }
    }
    @Test void byteAndEnumerationBudgetsReturnPartialResults() throws Exception {
        register();register();
        var bytes=new OriginalIntegrityRepository().inspect(new OriginalIntegrityOptions(root(),true,10,100,1),Instant.EPOCH);
        assertThat(bytes.limitReached()).isTrue();assertThat(bytes.hashedBytes()).isZero();assertThat(bytes.verified()).isZero();
        var files=new OriginalIntegrityRepository().inspect(new OriginalIntegrityOptions(root(),true,1,100,1048576),Instant.EPOCH);
        assertThat(files.limitReached()).isTrue();assertThat(files.verified()).isEqualTo(1);assertThat(files.items()).hasSize(1);
        var entries=new OriginalIntegrityRepository().inspect(new OriginalIntegrityOptions(root(),true,10,1,1048576),Instant.EPOCH);
        assertThat(entries.limitReached()).isTrue();assertThat(entries.scanned()).isEqualTo(1);assertThat(entries.verified()).isZero();
    }
    @Test void unknownFilesAndUploadResidueAreReportedAndPreserved() throws Exception {
        var m=register();Path unknown=Files.writeString(blob(m).resolveSibling("unknown"),"keep");
        Path upload=Files.createDirectory(blob(m).getParent().getParent().resolve(".upload-"+UUID.randomUUID()));
        var report=new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH);
        assertThat(report.verified()).isZero();assertThat(report.items()).allMatch(i->i.outcome().equals("UNEXPECTED_ENTRY"));
        assertThat(Files.readString(unknown)).isEqualTo("keep");assertThat(Files.isDirectory(upload)).isTrue();
    }
    @Test void metadataChangeAfterSelectionCannotBeVerified() throws Exception {
        var m=register();Path meta=blob(m).resolveSibling("metadata.properties");
        var repo=new OriginalIntegrityRepository(()->{try{Files.writeString(meta,"changed=true");}catch(IOException e){throw new UncheckedIOException(e);}});
        var report=repo.inspect(options(),Instant.EPOCH);assertThat(report.items().getFirst().outcome()).isEqualTo("CHANGED_DURING_SCAN");assertThat(report.hashedBytes()).isZero();
    }
    @Test void absentRootIsNotCreated() {
        assertThatThrownBy(()->new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);assertThat(Files.exists(root())).isFalse();
    }
    @Test void junctionOrSymlinkTenantIsNotFollowed() throws Exception {
        var m=register();Path outside=Files.createDirectory(temp.resolve("outside"));
        Path link=root().resolve("tenant-"+"b".repeat(64));Path sentinel=Files.writeString(outside.resolve("sentinel"),"unchanged");
        if(System.getProperty("os.name").startsWith("Windows")) {
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }else Files.createSymbolicLink(link,outside);
        try {
            var report=new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH);
            assertThat(report.verified()).isEqualTo(1);assertThat(report.issues()).isEqualTo(1);
            assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
        }finally{Files.delete(link);}
    }

    void marker() throws Exception {try(var writer=new LocalMediaStore(root(),1048576,0,1048576)){} }
    void assertMarkerReleased() throws Exception {
        try(var channel=java.nio.channels.FileChannel.open(root().resolve(".quota-writer.lock"),StandardOpenOption.WRITE);var lock=channel.tryLock()){
            assertThat(lock).isNotNull();
        }
    }
    @Test void runningQuotaWriterBlocksStandaloneInspectionWithoutCreatingOrChangingFiles() throws Exception {
        register();marker();var before=contents();
        try(var writer=new LocalMediaStore(root(),1048576,0,1048576)){
            assertThatThrownBy(()->new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH))
                    .isInstanceOf(co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageLock.StorageInUseException.class);
        }
        var after=contents();assertThat(after.keySet()).isEqualTo(before.keySet());
        for(var path:before.keySet())assertThat(after.get(path)).isEqualTo(before.get(path));
        assertThat(new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH).issues()).isZero();assertMarkerReleased();
    }
    @Test void existingMarkerRemainsSharedLockedThroughFinalRecheck() throws Exception {
        register();marker();var callbacks=new java.util.concurrent.atomic.AtomicInteger();
        Runnable check=()->{
            try(var channel=java.nio.channels.FileChannel.open(root().resolve(".quota-writer.lock"),StandardOpenOption.WRITE)){
                assertThatThrownBy(()->channel.tryLock()).isInstanceOf(java.nio.channels.OverlappingFileLockException.class);callbacks.incrementAndGet();
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        };
        assertThat(new OriginalIntegrityRepository(check,check).inspect(options(),Instant.EPOCH).verified()).isEqualTo(1);
        assertThat(callbacks.get()).isEqualTo(2);assertMarkerReleased();
    }
    @Test void markerAppearingDuringInspectionInvalidatesResultWithoutRemovingIt() throws Exception {
        register();Path marker=root().resolve(".quota-writer.lock");
        assertThatThrownBy(()->new OriginalIntegrityRepository(()->{},()->{
            try{Files.createFile(marker);}catch(IOException failure){throw new UncheckedIOException(failure);}
        }).inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);
        assertThat(Files.isRegularFile(marker)).isTrue();assertThat(Files.size(marker)).isZero();
    }
    @Test void markerAttributeChangeDuringInspectionCannotBeReportedAsSuccess() throws Exception {
        register();marker();Path marker=root().resolve(".quota-writer.lock");
        assertThatThrownBy(()->new OriginalIntegrityRepository(()->{},()->{
            try{Files.setLastModifiedTime(marker,java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(marker).toMillis()+5000));}
            catch(IOException failure){throw new UncheckedIOException(failure);}
        }).inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);assertMarkerReleased();
    }
    @Test void alreadyVerifiedCatalogContentChangeIsCaughtEvenWithRestoredSizeAndMtime() throws Exception {
        var media=register();Path meta=blob(media).resolveSibling("metadata.properties");byte[] bytes=Files.readAllBytes(meta);
        var modified=Files.getLastModifiedTime(meta);
        assertThatThrownBy(()->new OriginalIntegrityRepository(()->{},()->{
            try{
                String text=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
                assertThat(text).contains("private-business.png");
                Files.write(meta,text.replace("private-business.png","changed-business.png").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                Files.setLastModifiedTime(meta,modified);
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        }).inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);
    }
    @Test void alreadyVerifiedBlobChangeAtEndCannotReturnVerifiedReport() throws Exception {
        var media=register();Path file=blob(media);byte[] bytes=Files.readAllBytes(file);marker();
        assertThatThrownBy(()->new OriginalIntegrityRepository(()->{},()->{
            try{bytes[bytes.length-1]^=1;Files.write(file,bytes);Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis()+5000));}
            catch(IOException failure){throw new UncheckedIOException(failure);}
        }).inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);assertMarkerReleased();
    }
    @Test void changesToEarlierBlobWhileLaterItemIsSelectedCannotReturnStaleVerifiedEntry() throws Exception {
        var one=register();var two=register();var files=List.of(blob(one),blob(two));var calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->new OriginalIntegrityRepository(()->{
            if(calls.incrementAndGet()==2)try{
                for(Path file:files){byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
                    Files.setLastModifiedTime(file,java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis()+5000));}
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        }).inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void tenantContentsAddedAfterEnumerationInvalidateWholeReport() throws Exception {
        var media=register();Path extra=blob(media).getParent().getParent().resolve(UUID.randomUUID().toString());
        assertThatThrownBy(()->new OriginalIntegrityRepository(()->{},()->{
            try{Files.createDirectory(extra);}catch(IOException failure){throw new UncheckedIOException(failure);}
        }).inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);assertThat(Files.isDirectory(extra)).isTrue();
    }
    @Test void markerIsReleasedAfterFindingsPartialReportAndUnexpectedFailure() throws Exception {
        var media=register();register();marker();Path file=blob(media);byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);
        assertThat(new OriginalIntegrityRepository().inspect(options(),Instant.EPOCH).issues()).isEqualTo(1);assertMarkerReleased();
        var partial=new OriginalIntegrityOptions(root(),true,1,1000,1048576);
        assertThat(new OriginalIntegrityRepository().inspect(partial,Instant.EPOCH).limitReached()).isTrue();assertMarkerReleased();
        assertThatThrownBy(()->new OriginalIntegrityRepository(()->{},()->{throw new IllegalStateException("synthetic");})
                .inspect(options(),Instant.EPOCH)).isInstanceOf(IllegalStateException.class);assertMarkerReleased();
    }
}
