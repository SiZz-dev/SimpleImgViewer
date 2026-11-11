package co.kr.satu.imagegate.maintenance.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.domain.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class CacheOrphanRepositoryTests {
    @TempDir Path directory;
    final Instant now=Instant.parse("2026-10-22T03:00:00Z");
    final FileTime old=FileTime.from(now.minus(Duration.ofHours(48)));
    Path converted,preview,playback;
    @BeforeEach void setup() throws Exception {
        converted=Files.createDirectory(directory.resolve("derived"));preview=Files.createDirectory(converted.resolve("previews-v1"));playback=Files.createDirectory(converted.resolve("playback-v1"));
        Files.createFile(preview.resolve("worker.lock"));Files.createFile(playback.resolve("writer.lock"));
    }
    MaintenanceOptions options(boolean apply){return new MaintenanceOptions(converted,apply,apply,24,100,10000);}
    Path key(boolean image,char c) throws Exception {Path entry=Files.createDirectory((image?preview:playback).resolve((""+c).repeat(64)));state(entry,image,"failed",null,null);return entry;}
    void state(Path entry,boolean image,String status,Path artifact,Path thumbnail) throws Exception {
        var p=new Properties();p.setProperty("key",entry.getFileName().toString());p.setProperty("state",status);if(image)p.setProperty("page","1");
        if(artifact!=null){p.setProperty("artifact",artifact.getFileName().toString());p.setProperty("bytes",""+Files.size(artifact));p.setProperty("modified",""+Files.getLastModifiedTime(artifact).toMillis());}
        if(thumbnail!=null){p.setProperty("thumbnailArtifact",thumbnail.getFileName().toString());p.setProperty("thumbnailBytes",""+Files.size(thumbnail));p.setProperty("thumbnailModified",""+Files.getLastModifiedTime(thumbnail).toMillis());}
        try(var out=Files.newOutputStream(entry.resolve("state.properties"))){p.store(out,"");}
    }
    Path file(Path parent,String name) throws Exception {Path p=Files.write(parent.resolve(name),new byte[]{1,2,3,4});Files.setLastModifiedTime(p,old);return p;}
    Path result(Path entry,String extension) throws Exception{return file(entry,"result-"+UUID.randomUUID()+"."+extension);}
    Path work(Path entry,boolean nativeWork) throws Exception {
        Path work=Files.createDirectory(entry.resolve("work-"+UUID.randomUUID()));file(work,"preview.png");file(work,"thumbnail.png");
        if(nativeWork){var nativeDir=Files.createDirectory(work.resolve("native"));file(nativeDir,"preview.png");file(nativeDir,"resized.tmp");var resource=Files.createDirectory(nativeDir.resolve("resource"));file(resource,"cmyk.icc");file(resource,"libc9i.xml");}
        age(work);return work;
    }
    void age(Path path) throws Exception {try(var paths=Files.walk(path)){for(var p:paths.sorted(Comparator.reverseOrder()).toList())Files.setLastModifiedTime(p,old);}}
    @Test void defaultInspectionLeavesAllBytesAndReportsExactKnownCandidates() throws Exception {
        var entry=key(true,'a');var current=result(entry,"png");var thumb=result(entry,"png");state(entry,true,"ready",current,thumb);
        var orphan=result(entry,"png");var temporary=file(entry,"state-"+UUID.randomUUID()+".tmp");var work=work(entry,true);
        byte[] before=Files.readAllBytes(entry.resolve("state.properties"));
        var report=new CacheOrphanRepository().run(options(false),now);
        assertThat(report.items()).hasSize(3).allSatisfy(i->assertThat(i.outcome()).isEqualTo("DRY_RUN"));
        assertThat(report.deleted()).isZero();assertThat(report.candidateBytes()).isEqualTo(32);
        for(var p:List.of(current,thumb,orphan,temporary,work))assertThat(Files.exists(p)).isTrue();
        assertThat(Files.readAllBytes(entry.resolve("state.properties"))).isEqualTo(before);
    }
    @Test void applyRemovesOnlyAllowlistedOldOrphansAcrossBothCaches() throws Exception {
        var entry=key(true,'a');var current=result(entry,"png");var thumb=result(entry,"png");state(entry,true,"ready",current,thumb);
        var orphan=result(entry,"png");var temporary=file(entry,"state-"+UUID.randomUUID()+".tmp");var work=work(entry,true);
        var media=key(false,'b');var ready=result(media,"webm");state(media,false,"ready",ready,null);var unused=result(media,"mp3");var write=file(media,"write-"+UUID.randomUUID()+".tmp");
        var video=Files.createDirectory(media.resolve("work-"+UUID.randomUUID()));file(video,"playback.webm");age(video);
        var original=file(directory,"original.png");var annotation=file(directory,"annotations.properties");var stamp=file(directory,"stamp.png");
        byte[] state=Files.readAllBytes(entry.resolve("state.properties"));var report=new CacheOrphanRepository().run(options(true),now);
        assertThat(report.deleted()).isEqualTo(6);assertThat(report.deletedBytes()).isEqualTo(44);assertThat(report.skipped()).isZero();
        for(var p:List.of(orphan,temporary,work,unused,write,video))assertThat(Files.exists(p)).isFalse();
        for(var p:List.of(current,thumb,ready,original,annotation,stamp,preview.resolve("worker.lock"),playback.resolve("writer.lock")))assertThat(Files.exists(p)).isTrue();
        assertThat(Files.readAllBytes(entry.resolve("state.properties"))).isEqualTo(state);
    }
    @Test void recentFutureUnknownAndUnidentifiedEntriesArePreserved() throws Exception {
        var entry=key(true,'c');var recent=result(entry,"png");Files.setLastModifiedTime(recent,FileTime.from(now));
        var future=file(entry,"state-"+UUID.randomUUID()+".tmp");Files.setLastModifiedTime(future,FileTime.from(now.plusSeconds(3600)));
        var unknownWork=work(entry,false);file(unknownWork,"private-business-name.bin");age(unknownWork);
        var unknown=file(entry,"other.png");var missing=Files.createDirectory(preview.resolve("d".repeat(64)));var noState=result(missing,"png");
        var report=new CacheOrphanRepository().run(options(true),now);
        assertThat(report.deleted()).isZero();assertThat(report.skipped()).isPositive();
        for(var p:List.of(recent,future,unknownWork,unknown,noState))assertThat(Files.exists(p)).isTrue();
    }
    @Test void previewWriterLockRejectsInspectionAndDeletion() throws Exception {
        var entry=key(true,'a');var orphan=result(entry,"png");
        try(var channel=FileChannel.open(preview.resolve("worker.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){
            for(boolean apply:List.of(false,true))assertThatThrownBy(()->new CacheOrphanRepository().run(options(apply),now)).isInstanceOf(CacheOrphanRepository.StorageInUseException.class);
            assertThat(Files.exists(orphan)).isTrue();
        }
        assertThat(new CacheOrphanRepository().run(options(false),now).items()).hasSize(1);
    }
    @Test void secondBusyRootReleasesFirstLockBeforeAnyScanningOrMutation() throws Exception {
        var entry=key(true,'a');var orphan=result(entry,"png");
        try(var channel=FileChannel.open(playback.resolve("writer.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){
            assertThatThrownBy(()->new CacheOrphanRepository().run(options(true),now)).isInstanceOf(CacheOrphanRepository.StorageInUseException.class);
            try(var first=FileChannel.open(preview.resolve("worker.lock"),StandardOpenOption.WRITE);var reclaimed=first.tryLock()){assertThat(reclaimed).isNotNull();}
            assertThat(Files.exists(orphan)).isTrue();
        }
    }
    @Test void unknownMetadataAndInvalidArtifactPathsBlockTheWholeKey() throws Exception {
        var a=key(true,'a');var orphan=result(a,"png");Files.writeString(a.resolve("state.properties"),"key="+"a".repeat(64)+"\npage=1\nstate=failed\nnewArtifact=hidden.png\n");
        var b=key(false,'b');var second=result(b,"mp3");Files.writeString(b.resolve("state.properties"),"key="+"b".repeat(64)+"\nstate=ready\nartifact=../../original.wav\n");
        var report=new CacheOrphanRepository().run(options(true),now);assertThat(report.deleted()).isZero();assertThat(report.skipped()).isEqualTo(2);
        assertThat(Files.exists(orphan)).isTrue();assertThat(Files.exists(second)).isTrue();
    }
    @Test void selectionAndEntryBudgetsAreReportedWithoutDeletingUnselectedData() throws Exception {
        var entry=key(true,'a');result(entry,"png");result(entry,"png");
        var limited=new MaintenanceOptions(converted,true,true,24,1,10000);var report=new CacheOrphanRepository().run(limited,now);
        assertThat(report.limitReached()).isTrue();assertThat(report.deleted()).isEqualTo(1);
        assertThat(new CacheOrphanRepository().run(options(false),now).items()).hasSize(1);
        var tiny=new CacheOrphanRepository().run(new MaintenanceOptions(converted,true,true,24,100,1),now);
        assertThat(tiny.limitReached()).isTrue();assertThat(tiny.scanned()).isEqualTo(1);assertThat(tiny.deleted()).isZero();
    }
    @Test void stateBecomingReadyAfterSelectionProtectsNewlyReferencedArtifact() throws Exception {
        var entry=key(true,'a');var orphan=result(entry,"png");
        var repository=new CacheOrphanRepository(()->{try{state(entry,true,"ready",orphan,null);}catch(Exception e){throw new RuntimeException(e);}});
        var report=repository.run(options(true),now);assertThat(report.deleted()).isZero();assertThat(report.items()).singleElement().satisfies(i->assertThat(i.outcome()).isEqualTo("SKIPPED_CHANGED_OR_IO"));
        assertThat(Files.exists(orphan)).isTrue();
    }
    @Test void replacedFileWithSameSizeAndTimestampIsPreservedByFileIdentity() throws Exception {
        var entry=key(true,'a');var orphan=result(entry,"png");var saved=directory.resolve("saved-original-object");
        var repository=new CacheOrphanRepository(()->{try{Files.move(orphan,saved);Files.write(orphan,new byte[]{9,9,9,9});Files.setLastModifiedTime(orphan,old);}catch(Exception e){throw new RuntimeException(e);}});
        var report=repository.run(options(true),now);assertThat(report.deleted()).isZero();assertThat(report.deletedBytes()).isZero();
        assertThat(Files.readAllBytes(orphan)).containsExactly(9,9,9,9);assertThat(Files.exists(saved)).isTrue();
    }
    @Test void newUnknownWorkChildAfterSelectionBlocksAllKnownLeaves() throws Exception {
        var entry=key(true,'a');var work=work(entry,false);
        var repository=new CacheOrphanRepository(()->{try{file(work,"unexpected.bin");Files.setLastModifiedTime(work,old);}catch(Exception e){throw new RuntimeException(e);}});
        var report=repository.run(options(true),now);assertThat(report.deleted()).isZero();assertThat(Files.exists(work.resolve("preview.png"))).isTrue();assertThat(Files.exists(work.resolve("unexpected.bin"))).isTrue();
    }
    @Test void missingLocksAndMissingRootAreNotCreatedByTheTool() throws Exception {
        Files.delete(preview.resolve("worker.lock"));
        assertThatThrownBy(()->new CacheOrphanRepository().run(options(false),now)).isInstanceOf(IOException.class);assertThat(Files.exists(preview.resolve("worker.lock"))).isFalse();
        Path missing=directory.resolve("missing");assertThatThrownBy(()->new CacheOrphanRepository().run(new MaintenanceOptions(missing,false,false,24,100,10000),now)).isInstanceOf(IOException.class);assertThat(Files.exists(missing)).isFalse();
    }
    void link(Path link,Path target) throws Exception {
        assertThat(target.toRealPath().startsWith(directory.toRealPath())).isTrue();assertThat(link.getParent().toRealPath().startsWith(directory.toRealPath())).isTrue();
        if(System.getProperty("os.name").startsWith("Windows")){
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).as("Junction fixture creation").isZero();
        }else Files.createSymbolicLink(link,target);
    }
    @Test void junctionWorkDoesNotTraverseOrDeleteOutsideTheCache() throws Exception {
        var entry=key(true,'a');var work=work(entry,false);var outside=Files.createDirectory(directory.resolve("outside"));var protectedFile=file(outside,"preview.png");var junction=work.resolve("native");
        link(junction,outside);try{Files.setLastModifiedTime(work,old);var report=new CacheOrphanRepository().run(options(true),now);
            assertThat(report.deleted()).isZero();assertThat(Files.readAllBytes(protectedFile)).containsExactly(1,2,3,4);assertThat(Files.exists(work.resolve("preview.png"))).isTrue();
        }finally{Files.delete(junction);}
    }
    @Test void junctionAsConvertedRootIsRejected() throws Exception {
        var alias=directory.resolve("alias");link(alias,converted);
        try{assertThatThrownBy(()->new CacheOrphanRepository().run(new MaintenanceOptions(alias,true,true,24,100,10000),now)).isInstanceOf(IOException.class);}
        finally{Files.delete(alias);}
    }
}
