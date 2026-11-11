package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.media.exception.MediaException;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

class PreviewQueueShutdownTests {
    @TempDir Path root;
    final PreviewQueueTests f=new PreviewQueueTests();
    @BeforeEach void setup(){f.directory=root;}
    PreviewQueue queue(PreviewCache cache,PreviewRenderer renderer) throws IOException {
        return new PreviewQueue(f.profile("v1"),"engine",cache,renderer);
    }
    @Test void shutdownDrainsQueuedWorkPersistsInterruptedStateAndRestartRequiresExplicitRetry() throws Exception {
        var calls=new AtomicInteger();var entered=new CountDownLatch(1);
        var cache=new PreviewCache(root);
        var queue=queue(cache,(m,a,p,w)->{
            calls.incrementAndGet();entered.countDown();
            try{new CountDownLatch(1).await(10,TimeUnit.SECONDS);}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
            return f.image(w);
        });
        String first=null,second=null;
        try{
            first=queue.submit(f.media,f.actor,1,false).sourceId();
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            second=queue.submit(f.media,f.actor,2,false).sourceId();
        }finally{queue.close();}
        queue.close();assertThat(calls).hasValue(1);
        assertThatThrownBy(()->queue.submit(f.media,f.actor,1,true)).isInstanceOfSatisfying(MediaException.class,
                e->assertThat(e.code()).isEqualTo(MediaException.Code.PREVIEW_QUEUE_FULL));
        assertNoPartialOutput();
        try(var nextCache=new PreviewCache(root);var next=queue(nextCache,(m,a,p,w)->{calls.incrementAndGet();return f.image(w);})){
            assertThat(nextCache.read(first,1).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(nextCache.read(second,2).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(next.submit(f.media,f.actor,1,false).state()).isEqualTo("failed");
            assertThat(calls).hasValue(1);
            next.submit(f.media,f.actor,1,true);assertThat(f.await(next).ready()).isTrue();
            assertThat(calls).hasValue(2);
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lateRendererRetainsWriterAndCannotPublishEvenIfItConsumesTheInterrupt(boolean interruptCaller) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var cache=new PreviewCache(root);
        var queue=queue(cache,(m,a,p,w)->{
            calls.incrementAndGet();var result=f.image(w);entered.countDown();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean interrupted=false;
            while(release.getCount()>0&&System.nanoTime()<end){
                try{release.await(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}
            }
            if(interrupted&&!interruptCaller)Thread.currentThread().interrupt();
            return result;
        });
        String first=null,second=null;
        try{
            first=queue.submit(f.media,f.actor,1,false).sourceId();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            second=queue.submit(f.media,f.actor,2,false).sourceId();
            if(interruptCaller)Thread.currentThread().interrupt();
            queue.close();
            if(interruptCaller)assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted();
            assertBusy();assertThat(cache.read(second,2).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(calls).hasValue(1);
        }finally{Thread.interrupted();release.countDown();queue.close();}
        try(var next=awaitReopen()){
            assertThat(next.read(first,1).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(next.read(second,2).errorCode()).isEqualTo("INTERRUPTED");
            assertNoPartialOutput();
        }
    }

    @ParameterizedTest @CsvSource({"false,false","false,true","true,false","true,true"})
    void previewAndThumbnailResponsesRetainWriterUntilSuccessOrFailure(boolean thumbnail,boolean failure) throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var queue=queue(new PreviewCache(root),(m,a,p,w)->{
            var result=f.image(w);var small=Files.copy(result.png(),w.resolve("thumbnail.png"));
            return new PreviewRenderer.Result(result.png(),16,16,null,small,8,8);
        });
        String key=null;
        try(var executor=Executors.newSingleThreadExecutor()){
            key=queue.submit(f.media,f.actor,1,false).sourceId();assertThat(f.await(queue).ready()).isTrue();
            String source=key;
            var response=executor.submit(()->{
                queue.content(f.media,f.actor,1,source,thumbnail,path->{
                    entered.countDown();
                    try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Test release timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                    assertThat(path).isRegularFile();
                    if(failure)throw new IOException("Synthetic disconnect");
                });return null;
            });
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();queue.close();assertBusy();
                assertThatThrownBy(()->queue.content(f.media,f.actor,1,source,thumbnail,path->fail("New response after close")))
                        .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.PREVIEW_QUEUE_FULL));
            }finally{release.countDown();}
            if(failure)assertThatThrownBy(()->response.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            else response.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();queue.close();}
        try(var next=awaitReopen()){assertThat(next.read(key,1).ready()).isTrue();}
    }

    @Test void interruptedIOExceptionWithoutASetFlagIsClassifiedAndCanBeRetried() throws Exception {
        var calls=new AtomicInteger();
        try(var cache=new PreviewCache(root);var queue=queue(cache,(m,a,p,w)->{
            if(calls.incrementAndGet()==1)throw new java.io.InterruptedIOException("Synthetic decoder cancellation");
            return f.image(w);
        })){
            queue.submit(f.media,f.actor,1,false);
            assertThat(f.await(queue).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(queue.submit(f.media,f.actor,1,false).state()).isEqualTo("failed");
            // failed is persisted before work cleanup and active-key release; retry must wait for admission.
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){
                queue.submit(f.media,f.actor,1,true);
                if(queue.status(f.media,f.actor,1).ready())break;
                Thread.sleep(10);
            }
            assertThat(queue.status(f.media,f.actor,1).ready()).isTrue();
            assertThat(calls).hasValue(2);
        }
    }

    void assertBusy(){
        assertThatThrownBy(()->{try(var next=new PreviewCache(root)){next.claimWriter();}})
                .isInstanceOfAny(IOException.class,java.nio.channels.OverlappingFileLockException.class);
    }
    PreviewCache awaitReopen() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(true){
            var next=new PreviewCache(root);
            try{next.claimWriter();return next;}
            catch(IOException|java.nio.channels.OverlappingFileLockException busy){
                next.close();if(System.nanoTime()>=end)throw busy;Thread.sleep(10);
            }
        }
    }
    void assertNoPartialOutput() throws IOException {
        try(var files=Files.walk(root.resolve("previews-v1"))){
            assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("work-")
                    ||p.getFileName().toString().startsWith("result-"))).isTrue();
        }
    }
}
