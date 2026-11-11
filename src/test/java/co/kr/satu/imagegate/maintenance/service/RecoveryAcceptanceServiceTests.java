package co.kr.satu.imagegate.maintenance.service;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.util.*;
import co.kr.satu.imagegate.editing.repository.AnnotationStore;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class RecoveryAcceptanceServiceTests {
    @TempDir Path temp;
    final Clock clock=Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC);
    RecoveryAcceptanceOptions options(BackupManifestFixture f) throws Exception {return new RecoveryAcceptanceOptions(f.options(),100,100);}
    RecoveryAcceptanceReport run(BackupManifestFixture f) throws Exception {return new RecoveryAcceptanceService(clock).run(options(f));}
    Map<String,String> inventory() throws Exception {
        var map=new TreeMap<String,String>();try(var paths=Files.walk(temp)){
            for(Path p:paths.toList())map.put(temp.relativize(p).toString(),Files.isDirectory(p)?"D":BackupManifestFixture.hash(Files.readAllBytes(p)));
        }return map;
    }
    @Test void allFourPhasesPassWithSharedStampOwnerAndNoByteOrEntryChanges() throws Exception {
        var f=new BackupManifestFixture(temp);var before=inventory();var report=run(f);
        assertThat(report.offlineChecksPassed()).isTrue();assertThat(report.generatedAt()).isEqualTo(clock.instant());
        assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::name).containsExactly("MANIFEST_BEFORE","ORIGINALS","EDITING_REFERENCES","MANIFEST_AFTER");
        assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::status).containsOnly("PASSED");
        assertThat(report.stages().get(1).passedItems()).isEqualTo(1);assertThat(report.stages().get(2).passedItems()).isEqualTo(3);
        assertThat(inventory()).isEqualTo(before);
    }
    @Test void missingEmptySnapshotFailsManifestAndLaterPhasesAreNotRun() throws Exception {
        var f=new BackupManifestFixture(temp);Files.delete(f.stores.page.resolveSibling("page-2.properties"));var before=inventory();
        var report=run(f);assertThat(report.offlineChecksPassed()).isFalse();
        assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::status).containsExactly("FINDINGS","NOT_RUN","NOT_RUN","NOT_RUN");
        assertThat(inventory()).isEqualTo(before);
    }
    @Test void matchingManifestCannotHideAnInvalidOriginalCatalog() throws Exception {
        var f=new BackupManifestFixture(temp);f.stores.property(f.stores.originalEntry.resolve("metadata.properties"),"sha256","0".repeat(64));
        f.refreshManifest();var report=run(f);assertThat(report.offlineChecksPassed()).isFalse();
        assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::status).containsExactly("PASSED","FINDINGS","NOT_RUN","NOT_RUN");
    }
    @Test void matchingManifestAndOriginalCannotHideInvalidEditingReferences() throws Exception {
        var f=new BackupManifestFixture(temp);f.stores.property(f.stores.page,"space","invalid");f.refreshManifest();
        var report=run(f);assertThat(report.offlineChecksPassed()).isFalse();
        assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::status).containsExactly("PASSED","PASSED","FINDINGS","NOT_RUN");
    }
    @Test void manifestAndEditingBudgetsNeverProduceAnAcceptancePass() throws Exception {
        var f=new BackupManifestFixture(temp);
        for(var limited:List.of(new RecoveryAcceptanceOptions(f.options(1,10485760),100,100),
                new RecoveryAcceptanceOptions(f.options(10000,1),100,100),new RecoveryAcceptanceOptions(f.options(),100,1))){
            var report=new RecoveryAcceptanceService(clock).run(limited);assertThat(report.offlineChecksPassed()).isFalse();
            assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::status).contains("PARTIAL","NOT_RUN");
        }
    }
    @Test void allExistingMarkersStayHeldBetweenPhasesAndReleaseAfterReturn() throws Exception {
        var f=new BackupManifestFixture(temp);Files.createFile(f.stores.originals.resolve(".quota-writer.lock"));f.refreshManifest();
        var markers=List.of(f.stores.originals.resolve(".quota-writer.lock"),f.stores.annotations.resolve("writer.lock"),f.stores.stamps.resolve("writer.lock"));
        var count=new AtomicInteger();var report=new RecoveryAcceptanceService(clock,stage->{
            for(Path marker:markers){
                try(var channel=FileChannel.open(marker,StandardOpenOption.WRITE)){
                    assertThatThrownBy(()->channel.tryLock()).isInstanceOf(OverlappingFileLockException.class);
                }catch(IOException failure){throw new UncheckedIOException(failure);}
            }count.incrementAndGet();
        }).run(options(f));
        assertThat(report.offlineChecksPassed()).isTrue();assertThat(count.get()).isEqualTo(3);
        for(Path marker:markers)try(var channel=FileChannel.open(marker,StandardOpenOption.WRITE);var lock=channel.tryLock()){assertThat(lock).isNotNull();}
    }
    @Test void finalManifestDetectsStampTamperAfterSemanticChecks() throws Exception {
        var f=new BackupManifestFixture(temp);var report=new RecoveryAcceptanceService(clock,stage->{
            if(stage.equals("EDITING_REFERENCES"))try{
                Path p=f.stores.stampEntry.resolve("stamp.png");byte[] bytes=Files.readAllBytes(p);bytes[bytes.length-1]^=1;Files.write(p,bytes);
            }catch(IOException failure){throw new UncheckedIOException(failure);}
        }).run(options(f));
        assertThat(report.offlineChecksPassed()).isFalse();
        assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::status).containsExactly("PASSED","PASSED","PASSED","FINDINGS");
    }
    @Test void manifestMutationBetweenPhasesAbortsAndAllLocksAreReleased() throws Exception {
        var f=new BackupManifestFixture(temp);
        assertThatThrownBy(()->new RecoveryAcceptanceService(clock,stage->{
            if(stage.equals("EDITING_REFERENCES"))try{Files.writeString(f.manifest,"changed");}catch(IOException failure){throw new UncheckedIOException(failure);}
        }).run(options(f))).isInstanceOf(IOException.class);
        try(var writer=new AnnotationStore(f.stores.annotations)){assertThat(writer).isNotNull();}
    }
    @Test void liveWriterBlocksWholeOperationAndUnavailableRootsAreNotCreated() throws Exception {
        var f=new BackupManifestFixture(temp);
        try(var writer=new AnnotationStore(f.stores.annotations)){
            assertThatThrownBy(()->run(f)).isInstanceOf(ReadOnlyStorageLock.StorageInUseException.class);
        }
        assertThat(run(f).offlineChecksPassed()).isTrue();var m=f.options();Path missing=temp.resolve("missing");
        var bad=new BackupManifestOptions(missing,m.annotationsRoot(),m.stampsRoot(),m.manifest(),m.manifestSha256(),true,100,1000);
        assertThatThrownBy(()->new RecoveryAcceptanceService(clock).run(new RecoveryAcceptanceOptions(bad,100,100))).isInstanceOf(IOException.class);
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test void originalItemBudgetIsPartialEvenWhenManifestIsComplete() throws Exception {
        var f=new BackupManifestFixture(temp);
        try(var originals=new co.kr.satu.imagegate.media.repository.LocalMediaStore(f.stores.originals,1048576)){
            originals.register(EditingReferenceFixture.TENANT,EditingReferenceFixture.OWNER,"second.png",
                    new ByteArrayInputStream(Files.readAllBytes(f.stores.originalEntry.resolve("original.png"))));
        }
        f.refreshManifest();var report=new RecoveryAcceptanceService(clock).run(new RecoveryAcceptanceOptions(f.options(),1,100));
        assertThat(report.offlineChecksPassed()).isFalse();
        assertThat(report.stages()).extracting(RecoveryAcceptanceReport.Stage::status).containsExactly("PASSED","PARTIAL","NOT_RUN","NOT_RUN");
    }

    @Test void relocatedCopyUsesExternalManifestAndLeavesSourceBackupAndRestoreUnchanged() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source"));var f=new BackupManifestFixture(source);
        Path restored=Files.createDirectory(temp.resolve("relocated"));
        for(String area:List.of("originals","annotations","stamps")){
            Path from=source.resolve(area),to=restored.resolve(area);
            try(var paths=Files.walk(from)){
                for(Path path:paths.toList()){
                    assertThat(Files.isSymbolicLink(path)).isFalse();Path target=to.resolve(from.relativize(path));
                    if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(target);else Files.copy(path,target);
                }
            }
        }
        var before=inventory();var original=f.options();
        var relocated=new BackupManifestOptions(restored.resolve("originals"),restored.resolve("annotations"),restored.resolve("stamps"),
                original.manifest(),original.manifestSha256(),true,10000,10485760);
        assertThat(new RecoveryAcceptanceService(clock).run(new RecoveryAcceptanceOptions(relocated,100,100)).offlineChecksPassed()).isTrue();
        assertThat(inventory()).isEqualTo(before);
    }
}
