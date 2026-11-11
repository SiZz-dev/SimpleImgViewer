package co.kr.satu.imagegate.media.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OriginalQuotaTests {
    @TempDir Path root;
    static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    static ByteArrayInputStream png(){return new ByteArrayInputStream(PNG);}
    static void quotaFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable task){assertThatThrownBy(task).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.TENANT_STORAGE_QUOTA_EXCEEDED));}
    @Test void exactLimitAcrossOwnersAndRestartPreservesOriginalsAndOtherTenants() throws Exception {
        String id;
        try(var store=new LocalMediaStore(root,100,0,16)){
            id=store.register("tenant","a","x.png",png()).mediaId();
            store.register("tenant","b","x.png",png());
            quotaFailure(()->store.register("tenant","c","x.png",png()));
            store.register("other","c","x.png",png());
            assertThat(store.find("tenant",id)).isPresent();
        }
        try(var store=new LocalMediaStore(root,100,0,16)){
            quotaFailure(()->store.register("tenant","a","x.png",png()));
            assertThat(store.find("tenant",id)).isPresent();
        }
        try(var store=new LocalMediaStore(root,100,0,7)){
            quotaFailure(()->store.register("tenant","a","x.png",png()));
            assertThat(store.find("tenant",id)).isPresent();
        }
    }
    @Test void unsupportedOversizedAndBrokenInputReleaseReservationAndStaging() throws Exception {
        try(var store=new LocalMediaStore(root,10,0,10)){
            assertThatThrownBy(()->store.register("t","u","x",new ByteArrayInputStream(new byte[8]))).isInstanceOf(MediaException.class);
            assertThatThrownBy(()->store.register("t","u","x",new ByteArrayInputStream(new byte[11]))).isInstanceOf(MediaException.class);
            InputStream broken=new InputStream(){int n;public int read()throws IOException{if(n++>=4)throw new IOException("synthetic failure");return 1;}};
            assertThatThrownBy(()->store.register("t","u","x",broken)).isInstanceOf(IOException.class);
            store.register("t","u","x.png",png());
            try(var paths=Files.walk(root)){assertThat(paths.filter(p->p.getFileName().toString().startsWith(".upload-")).toList()).isEmpty();}
        }
    }
    @Test void concurrentUploadReservesBeforePublishWhileReadsAndOtherTenantsContinue() throws Exception {
        var reserved=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try(var store=new LocalMediaStore(root,100,0,16);var pool=Executors.newVirtualThreadPerTaskExecutor()){
            InputStream slow=new ByteArrayInputStream(PNG){@Override public synchronized int read(byte[] b,int o,int l){int n=super.read(b,o,l);if(n<0){reserved.countDown();try{if(!finish.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}}return n;}};
            var upload=pool.submit(()->store.register("t","u","x.png",slow));
            try{
                assertThat(reserved.await(10,TimeUnit.SECONDS)).isTrue();
                var committed=store.register("t","v","x.png",png());
                quotaFailure(()->store.register("t","w","x.png",png()));
                assertThat(store.find("t",committed.mediaId())).isPresent();
                store.register("other","u","x.png",png());
            }finally{finish.countDown();}
            assertThat(upload.get(10,TimeUnit.SECONDS).sizeBytes()).isEqualTo(8);
            quotaFailure(()->store.register("t","w","x.png",png()));
        }
    }
    @Test void writerLockRefusesSecondWriterAndCanBeReacquiredAfterClose() throws Exception {
        try(var store=new LocalMediaStore(root,100,0,8)){
            assertThatThrownBy(()->new LocalMediaStore(root,100,0,8)).isInstanceOf(IOException.class);
        }
        try(var store=new LocalMediaStore(root,100,0,8)){store.register("t","u","x.png",png());}
    }
    @Test void restartCountsAbandonedStagingWithoutDeletingIt() throws Exception {
        Path tenant;
        try(var store=new LocalMediaStore(root,100,0,16)){
            var record=store.register("t","u","x.png",png());tenant=store.resolveOriginal(record).getParent().getParent();
        }
        Path abandoned=Files.createDirectory(tenant.resolve(".upload-"+UUID.randomUUID()));
        Files.write(abandoned.resolve("pending"),PNG);
        try(var store=new LocalMediaStore(root,100,0,16)){
            quotaFailure(()->store.register("t","u","x.png",png()));
            assertThat(Files.readAllBytes(abandoned.resolve("pending"))).isEqualTo(PNG);
            store.register("other","u","x.png",png());
        }
    }
    @Test void junctionOrSymlinkInTenantIsRejectedAndOutsideBytesPreserved() throws Exception {
        try(var store=new LocalMediaStore(root.resolve("originals"),100,0,100)){
            var record=store.register("t","u","x.png",png());var tenant=store.resolveOriginal(record).getParent().getParent();
            Path outside=Files.createDirectory(root.resolve("outside"));Path sentinel=Files.writeString(outside.resolve("sentinel"),"unchanged");
            Path link=tenant.resolve(UUID.randomUUID().toString());
            if(System.getProperty("os.name").startsWith("Windows")){
                var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
                assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
            }else Files.createSymbolicLink(link,outside);
            try{
                assertThatThrownBy(()->store.register("t","u","x.png",png())).isInstanceOf(IOException.class);
                assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
            }finally{Files.delete(link);}
        }
    }
    @Test void unknownFileInsideCommittedEntryRefusesAnotherUpload() throws Exception {
        try(var store=new LocalMediaStore(root,100,0,100)){
            var record=store.register("t","u","x.png",png());
            Path unknown=Files.writeString(store.resolveOriginal(record).resolveSibling("unknown"),"keep");
            assertThatThrownBy(()->store.register("t","u","x.png",png())).isInstanceOf(IOException.class);
            assertThat(Files.readString(unknown)).isEqualTo("keep");
        }
    }
    @Test void malformedCatalogOrUnknownEntryRefusesWritesWithoutPreventingOtherTenants() throws Exception {
        try(var store=new LocalMediaStore(root,100,0,100)){
            var record=store.register("t","u","x.png",png());var entry=store.resolveOriginal(record).getParent();
            for(String invalid:new String[]{"size=0","tenantId="+((char)92)+"uXXXX"}){
                Files.writeString(entry.resolve("metadata.properties"),invalid);
                assertThatThrownBy(()->store.register("t","u","x.png",png())).isInstanceOf(IOException.class);
            }
            store.register("other","u","x.png",png());
        }
    }
}
