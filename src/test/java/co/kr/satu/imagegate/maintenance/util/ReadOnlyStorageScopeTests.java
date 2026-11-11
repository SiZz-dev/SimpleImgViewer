package co.kr.satu.imagegate.maintenance.util;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.repository.*;
import co.kr.satu.imagegate.editing.repository.AnnotationStore;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ReadOnlyStorageScopeTests {
    @TempDir Path temp;
    @Test void validBorrowedScopeSupportsBothInspectorsButClosedScopeCannotBypassChecks() throws Exception {
        var f=new BackupManifestFixture(temp);var scope=new ReadOnlyStorageScope(f.stores.originals,f.stores.annotations,f.stores.stamps,true);
        try(scope){
            assertThat(new BackupManifestRepository().inspectWithinScope(f.options(),Instant.EPOCH,scope).issues()).isZero();
            assertThat(new EditingReferenceRepository().inspectWithinScope(f.stores.options(),Instant.EPOCH,scope).issues()).isZero();
        }
        assertThatThrownBy(()->new BackupManifestRepository().inspectWithinScope(f.options(),Instant.EPOCH,scope)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->new EditingReferenceRepository().inspectWithinScope(f.stores.options(),Instant.EPOCH,scope)).isInstanceOf(IOException.class);
        scope.close();
        try(var writer=new AnnotationStore(f.stores.annotations)){assertThat(writer).isNotNull();}
    }
    @Test void wrongRootAndThreadCannotReuseLiveScope() throws Exception {
        var f=new BackupManifestFixture(temp);
        try(var scope=new ReadOnlyStorageScope(f.stores.originals,f.stores.annotations,f.stores.stamps,true)){
            var m=f.options();var different=new BackupManifestOptions(temp.resolve("other"),m.annotationsRoot(),m.stampsRoot(),m.manifest(),m.manifestSha256(),true,100,1000);
            assertThatThrownBy(()->new BackupManifestRepository().inspectWithinScope(different,Instant.EPOCH,scope)).isInstanceOf(IOException.class);
            try(var executor=Executors.newSingleThreadExecutor()){
                var result=executor.submit(()->{try{scope.verify();return false;}catch(IOException expected){return true;}});
                assertThat(result.get(10,TimeUnit.SECONDS)).isTrue();
            }scope.verify();
        }
    }
    @Test void newMarkerOrChangedRootInvalidatesWholeScope() throws Exception {
        var f=new BackupManifestFixture(temp);
        try(var scope=new ReadOnlyStorageScope(f.stores.originals,f.stores.annotations,f.stores.stamps,true)){
            Files.createFile(f.stores.originals.resolve(".quota-writer.lock"));assertThatThrownBy(scope::verify).isInstanceOf(IOException.class);
        }
        try(var scope=new ReadOnlyStorageScope(f.stores.originals,f.stores.annotations,f.stores.stamps,true)){
            Files.createDirectory(f.stores.stamps.resolve("unexpected"));assertThatThrownBy(scope::verify).isInstanceOf(IOException.class);
        }
    }
    @Test void stoppedAcknowledgementAndIndependentRootsAreRequiredBeforeLocks() throws Exception {
        var f=new BackupManifestFixture(temp);
        assertThatThrownBy(()->new ReadOnlyStorageScope(f.stores.originals,f.stores.annotations,f.stores.stamps,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new ReadOnlyStorageScope(f.stores.originals,f.stores.originals.resolve("nested"),f.stores.stamps,true)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.exists(f.stores.originals.resolve("nested"))).isFalse();
    }

    @Test void originalInspectorReusesOnlyLiveScopeBoundToItsOriginalsRoot() throws Exception {
        var f=new BackupManifestFixture(temp);var o=new OriginalIntegrityOptions(f.stores.originals,true,100,10000,10485760);
        var scope=new ReadOnlyStorageScope(f.stores.originals,f.stores.annotations,f.stores.stamps,true);
        try(scope){
            assertThat(new OriginalIntegrityRepository().inspectWithinScope(o,Instant.EPOCH,scope).verified()).isEqualTo(1);
            var wrong=new OriginalIntegrityOptions(temp.resolve("different"),true,100,10000,10485760);
            assertThatThrownBy(()->new OriginalIntegrityRepository().inspectWithinScope(wrong,Instant.EPOCH,scope)).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(()->new OriginalIntegrityRepository().inspectWithinScope(o,Instant.EPOCH,scope)).isInstanceOf(IOException.class);
    }
}
