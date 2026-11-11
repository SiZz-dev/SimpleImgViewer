package co.kr.satu.imagegate.maintenance.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.util.*;
import co.kr.satu.imagegate.editing.repository.*;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class BackupManifestRepositoryTests {
    @TempDir Path temp;
    BackupManifestReport inspect(BackupManifestFixture f) throws Exception {return new BackupManifestRepository().inspect(f.options(),Instant.EPOCH);}
    Map<String,String> inventory() throws Exception {
        var result=new TreeMap<String,String>();try(var paths=Files.walk(temp)){
            for(Path p:paths.toList())result.put(temp.relativize(p).toString(),Files.isDirectory(p)?"D":BackupManifestFixture.hash(Files.readAllBytes(p)));
        }return result;
    }
    @Test void realStoppedStoresMatchAndEveryByteAndEntryRemainsUnchanged() throws Exception {
        var f=new BackupManifestFixture(temp);var before=inventory();var report=inspect(f);
        assertThat(report.issues()).isZero();assertThat(report.limitReached()).isFalse();
        assertThat(report.matched()).isEqualTo(report.scanned());assertThat(report.hashedBytes()).isGreaterThan(0);
        assertThat(report.generatedAt()).isEqualTo(Instant.EPOCH);assertThat(inventory()).isEqualTo(before);
    }
    @Test void missingEmptyPageAndSameSizeOriginalAndStampTamperAreDetectedTogether() throws Exception {
        var f=new BackupManifestFixture(temp);Files.delete(f.stores.page.resolveSibling("page-2.properties"));
        Path stamp=f.stores.stampEntry.resolve("stamp.png");byte[] bytes=Files.readAllBytes(stamp);bytes[bytes.length-1]^=1;Files.write(stamp,bytes);
        Path original=f.stores.originalEntry.resolve("original.png");byte[] originalBytes=Files.readAllBytes(original);
        originalBytes[originalBytes.length-1]^=1;Files.write(original,originalBytes);
        var before=inventory();var report=inspect(f);
        assertThat(report.items()).extracting(BackupManifestReport.Item::outcome).contains("MISSING_ENTRY","CHECKSUM_MISMATCH");
        assertThat(report.issues()).isEqualTo(3);assertThat(inventory()).isEqualTo(before);
    }
    @Test void missingEntireMediaDirectoryIsDetectedWithoutRelyingOnCatalogDiscovery() throws Exception {
        var f=new BackupManifestFixture(temp);Files.delete(f.stores.originalEntry.resolve("original.png"));
        Files.delete(f.stores.originalEntry.resolve("metadata.properties"));Files.delete(f.stores.originalEntry);
        assertThat(inspect(f).items()).extracting(BackupManifestReport.Item::outcome).contains("MISSING_ENTRY");
    }
    @Test void unexpectedPrivateNameSizeAndTypeMismatchHaveNoRawNamesInReport() throws Exception {
        var f=new BackupManifestFixture(temp);Files.writeString(f.stores.originals.resolve("private-secret.txt"),"private content");
        Files.writeString(f.stores.page,"changed length");Files.delete(f.stores.stampEntry.resolve("preview.png"));
        Files.createDirectory(f.stores.stampEntry.resolve("preview.png"));
        var report=inspect(f);assertThat(report.items()).extracting(BackupManifestReport.Item::outcome)
                .contains("UNEXPECTED_ENTRY","SIZE_MISMATCH","TYPE_MISMATCH");
        assertThat(report.toString()).doesNotContain("private-secret","private content",temp.toString());
    }
    @Test void callerDigestMustMatchAndManifestMustStayOutsideStores() throws Exception {
        var f=new BackupManifestFixture(temp);var options=f.options();Files.writeString(f.manifest,"altered");
        assertThatThrownBy(()->new BackupManifestRepository().inspect(options,Instant.EPOCH)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->new BackupManifestOptions(f.stores.originals,f.stores.annotations,f.stores.stamps,
                f.stores.originals.resolve("manifest.tsv"),"a".repeat(64),true,100,1000)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void unsafeDuplicateIncompleteMalformedAndOversizedManifestInputsAreRejected() throws Exception {
        var f=new BackupManifestFixture(temp);String valid=Files.readString(f.manifest);
        String root="D\toriginals\t.\t0\t-\n";
        for(String bad:List.of(valid+root,valid.replace(root,""),valid+"D\toriginals\t../outside\t0\t-\n",
                valid+"D\toriginals\tCON.txt\t0\t-\n",valid+"D\toriginals\tUpper\t0\t-\n",
                valid+"D\toriginals\tmissing/child\t0\t-\n",valid+"D\toriginals\ta.\t0\t-\n",
                valid+"D\toriginals\ta:b\t0\t-\n",valid+"D\toriginals\ta\\b\t0\t-\n",
                valid.replace("IMAGEGATE-MANIFEST\t1","IMAGEGATE-MANIFEST\t2"),valid+"x".repeat(4194305))){
            Files.writeString(f.manifest,bad);
            assertThatThrownBy(()->inspect(f)).isInstanceOf(IOException.class);
        }
        Files.write(f.manifest,new byte[]{(byte)0xc3,(byte)0x28});assertThatThrownBy(()->inspect(f)).isInstanceOf(IOException.class);
    }
    @Test void entryAndByteBudgetsReturnPartialInsteadOfCleanSuccess() throws Exception {
        var f=new BackupManifestFixture(temp);var before=inventory();
        for(var options:List.of(f.options(1,10485760),f.options(10000,1))){
            var report=new BackupManifestRepository().inspect(options,Instant.EPOCH);
            assertThat(report.limitReached()).isTrue();assertThat(report.scanned()).isLessThanOrEqualTo(options.maxEntries());
            assertThat(report.hashedBytes()).isLessThanOrEqualTo(options.maxBytes());
        }assertThat(inventory()).isEqualTo(before);
    }
    @Test void allThreeRuntimeWritersBlockAndPreviouslyAcquiredLocksAreReleased() throws Exception {
        var f=new BackupManifestFixture(temp);
        try(var writer=new AnnotationStore(f.stores.annotations)){assertThatThrownBy(()->inspect(f)).isInstanceOf(ReadOnlyStorageLock.StorageInUseException.class);}
        try(var writer=new StampStore(f.stores.stamps,EditingReferenceFixture.LIMITS)){assertThatThrownBy(()->inspect(f)).isInstanceOf(ReadOnlyStorageLock.StorageInUseException.class);}
        try(var writer=new LocalMediaStore(f.stores.originals,1048576,0,10485760)){
            assertThatThrownBy(()->inspect(f)).isInstanceOf(ReadOnlyStorageLock.StorageInUseException.class);
        }
        // The quota writer introduced a marker absent from the recorded set. It must be reported, not ignored.
        assertThat(inspect(f).items()).extracting(BackupManifestReport.Item::outcome).contains("UNEXPECTED_ENTRY");
    }
    @Test void changesToFilesAndManifestDuringInspectionFailWithoutRepair() throws Exception {
        var f=new BackupManifestFixture(temp);var options=f.options();
        assertThatThrownBy(()->new BackupManifestRepository(()->{
            try{Files.writeString(f.stores.page,"changed");}catch(IOException e){throw new RuntimeException(e);}
        }).inspect(options,Instant.EPOCH)).isInstanceOf(IOException.class);
        var finalOptions=f.options();
        assertThatThrownBy(()->new BackupManifestRepository(()->{
            try{Files.writeString(f.manifest,"changed");}catch(IOException e){throw new RuntimeException(e);}
        }).inspect(finalOptions,Instant.EPOCH)).isInstanceOf(IOException.class);
    }
    @Test void missingRootsAreNeverCreated() throws Exception {
        var f=new BackupManifestFixture(temp);Path absent=temp.resolve("absent");var o=f.options();
        var options=new BackupManifestOptions(absent,o.annotationsRoot(),o.stampsRoot(),o.manifest(),o.manifestSha256(),true,100,1000);
        assertThatThrownBy(()->new BackupManifestRepository().inspect(options,Instant.EPOCH)).isInstanceOf(IOException.class);
        assertThat(Files.exists(absent)).isFalse();
    }

    @Test void windowsJunctionIsNeitherTraversedNorAcceptedAsAnExplicitRoot() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var f=new BackupManifestFixture(temp);Path outside=Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"),"private outside");
        Path junction=f.stores.originals.resolve("junction");
        String prefix=""+((char)92)+((char)92)+"?"+((char)92);
        var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",prefix+junction,prefix+outside)
                .redirectErrorStream(true).start();process.getInputStream().readAllBytes();
        assertThat(process.waitFor()).isZero();
        try{
            var report=inspect(f);assertThat(report.items()).extracting(BackupManifestReport.Item::outcome).contains("UNSAFE_OR_UNREADABLE");
            var options=f.options();var aliased=new BackupManifestOptions(junction,options.annotationsRoot(),options.stampsRoot(),
                    options.manifest(),options.manifestSha256(),true,100,1000);
            assertThatThrownBy(()->new BackupManifestRepository().inspect(aliased,Instant.EPOCH)).isInstanceOf(IOException.class);
            assertThat(Files.readString(outside.resolve("secret.txt"))).isEqualTo("private outside");
        }finally{Files.delete(junction);}
    }
}
