package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewQueueTests {
    static final class Registry extends io.micrometer.core.instrument.simple.SimpleMeterRegistry implements AutoCloseable {}
    @TempDir Path directory;
    final ViewerPrincipal actor=new ViewerPrincipal("tenant","alice");
    final MediaRecord media=new MediaRecord("id","tenant","alice","version","file.png",MediaType.IMAGE,
            "image/png",10,"digest","original.png",Instant.EPOCH);
    Preview profile(String revision) { return new Preview(true,1,1,32,32,100000,revision); }
    PreviewRenderer.Result image(Path work) throws IOException {
        Files.createDirectory(work);
        Path png=work.resolve("preview.png");
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",png.toFile());
        return new PreviewRenderer.Result(png,16,16);
    }
    PreviewStatus await(PreviewQueue queue) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        PreviewStatus status;
        do {
            status=queue.status(media,actor,1);
            if(!"queued".equals(status.state()) && !"running".equals(status.state())) return status;
            Thread.sleep(10);
        } while(System.nanoTime()<end);
        throw new AssertionError("Preview did not finish");
    }
    @Test void conversionBusyRequiresExplicitRetryAndKeepsFixedPublicCode()throws Exception{
        var calls=new AtomicInteger();try(var registry=new Registry();var queue=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)->{
            if(calls.incrementAndGet()==1)throw new co.kr.satu.imagegate.conversion.exception.ConversionException(
                co.kr.satu.imagegate.conversion.exception.ConversionException.Code.BUSY,"private-native-command");return image(w);
        },false,new PreviewMetrics(registry))){
            queue.submit(media,actor,1,false);assertThat(await(queue).errorCode()).isEqualTo("CONVERSION_BUSY");
            for(int i=0;i<3;i++)assertThat(queue.submit(media,actor,1,false).errorCode()).isEqualTo("CONVERSION_BUSY");
            assertThat(calls).hasValue(1);
            // Failure can be visible before private work cleanup/lease release has completed.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);var done=registry.get("imagegate.preview.jobs.completed").tag("outcome","conversion_busy").counter();
            while(done.count()<1&&System.nanoTime()<deadline)Thread.sleep(10);assertThat(done.count()).isEqualTo(1);
            queue.submit(media,actor,1,true);assertThat(await(queue).ready()).isTrue();assertThat(calls).hasValue(2);
        }
    }

    @Test void duplicateRequestsAndRestartReuseValidatedDiskCache() throws Exception {
        var calls=new AtomicInteger();
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        PreviewRenderer renderer=(m,a,p,w)-> {
            calls.incrementAndGet();started.countDown();
            try { if(!release.await(5,TimeUnit.SECONDS)) throw new IOException("timeout"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IOException(e); }
            return image(w);
        };
        var cache=new PreviewCache(directory);
        String key;
        try(var queue=new PreviewQueue(profile("v1"),"engine",cache,renderer)) {
            key=queue.submit(media,actor,1,false).sourceId();
            assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            for(int i=0;i<10;i++) assertThat(queue.submit(media,actor,1,false).sourceId()).isEqualTo(key);
            release.countDown();
            assertThat(await(queue).ready()).isTrue();
            assertThat(queue.submit(media,actor,1,false).ready()).isTrue();
            assertThat(calls).hasValue(1);
        }
        try(var restarted=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),renderer)) {
            assertThat(restarted.submit(media,actor,1,false).ready()).isTrue();
            restarted.content(media,actor,1,key,path->assertThat(path).exists());
            assertThat(calls).hasValue(1);
        }
    }

    @Test void queueCapacityRejectsExcessAndStatusReadsNeverEnqueue() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var queue=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)-> {
            started.countDown();
            try { release.await(); } catch(InterruptedException e) { throw new IOException(e); }
            return image(w);
        })) {
            queue.submit(media,actor,1,false);assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
            queue.submit(media,actor,2,false);
            assertThatThrownBy(()->queue.submit(media,actor,3,false))
                    .isInstanceOfSatisfying(MediaException.class,e->
                            assertThat(e.code()).isEqualTo(MediaException.Code.PREVIEW_QUEUE_FULL));
            assertThat(queue.status(media,actor,3).state()).isEqualTo("not_requested");
            release.countDown();
        }
    }

    @Test void openAdmissionTimeoutRequiresExplicitRetryAndQueueOwnsRendererClose() throws Exception {
        var calls=new AtomicInteger();var closes=new AtomicInteger();
        var renderer=new PreviewRenderer(){
            public Result render(MediaRecord m,ViewerPrincipal a,int p,Path w) throws IOException {
                if(calls.incrementAndGet()==1)throw new co.kr.satu.imagegate.conversion.exception.OriginalOpenTimeoutException();
                return image(w);
            }
            public void close(){closes.incrementAndGet();}
        };
        var queue=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),renderer);
        try{
            String key=queue.submit(media,actor,1,false).sourceId();
            assertThat(await(queue).errorCode()).isEqualTo("ORIGINAL_OPEN_BUSY");
            assertThat(queue.submit(media,actor,1,false).state()).isEqualTo("failed");assertThat(calls).hasValue(1);
            assertThatThrownBy(()->queue.content(media,actor,1,key,p->fail("Unavailable"))).isInstanceOf(MediaException.class);
            queue.submit(media,actor,1,true);assertThat(await(queue).ready()).isTrue();
        }finally{queue.close();queue.close();}
        assertThat(closes).hasValue(1);
    }

    @Test void unsupportedProfilePersistsFixedCodeWithoutPublishingOrAutomaticRetry() throws Exception {
        var calls=new AtomicInteger();String key;
        try(var queue=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)->{
            calls.incrementAndGet();throw new co.kr.satu.imagegate.conversion.exception.ConversionException(
                    co.kr.satu.imagegate.conversion.exception.ConversionException.Code.UNSUPPORTED_PROFILE,"private-command-and-path");
        })) {
            key=queue.submit(media,actor,1,false).sourceId();
            var result=await(queue);
            assertThat(result.errorCode()).isEqualTo("CONVERSION_UNSUPPORTED_PROFILE");
            assertThat(result.toString()).doesNotContain("private-command-and-path");
            assertThat(result.ready()).isFalse();
            assertThatThrownBy(()->queue.content(media,actor,1,key,p->fail("Must not publish"))).isInstanceOf(MediaException.class);
            assertThat(queue.submit(media,actor,1,false).errorCode()).isEqualTo(result.errorCode());
            assertThat(calls).hasValue(1);
        }
        try(var reopened=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)->{
            throw new AssertionError("Status and non-retry submission must not render");
        })){
            assertThat(reopened.status(media,actor,1).errorCode()).isEqualTo("CONVERSION_UNSUPPORTED_PROFILE");
            assertThat(reopened.submit(media,actor,1,false).errorCode()).isEqualTo("CONVERSION_UNSUPPORTED_PROFILE");
        }
    }

    @Test void failedDecodeRequiresExplicitRetryAndDoesNotExposeContent() throws Exception {
        var calls=new AtomicInteger();
        try(var queue=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)-> {
            if(calls.incrementAndGet()==1) throw new IOException("private path and native diagnostics");
            return image(w);
        })) {
            String key=queue.submit(media,actor,1,false).sourceId();
            assertThat(await(queue).errorCode()).isEqualTo("PREVIEW_FAILED");
            assertThat(queue.submit(media,actor,1,false).state()).isEqualTo("failed");
            assertThat(calls).hasValue(1);
            assertThatThrownBy(()->queue.content(media,actor,1,key,path->fail("Unavailable content"))).isInstanceOf(MediaException.class);
            queue.submit(media,actor,1,true);
            assertThat(await(queue).ready()).isTrue();
            assertThat(calls).hasValue(2);
        }
    }

    @Test void interruptedStateIsRecoverableByAuthorizedExplicitRetry() throws Exception {
        var cache=new PreviewCache(directory);
        try(var queue=new PreviewQueue(profile("v1"),"engine",cache,(m,a,p,w)->image(w))) {
            String key=queue.key(media,actor,1);
            cache.state(key,1,"running",null); // state left by a terminated JVM
            assertThat(queue.status(media,actor,1).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(queue.submit(media,actor,1,false).state()).isEqualTo("failed");
            queue.submit(media,actor,1,true);
            assertThat(await(queue).ready()).isTrue();
        }
    }

    @Test void missingArtifactCannotBeServedAndCanBeRegenerated() throws Exception {
        try(var queue=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)->image(w))) {
            String key=queue.submit(media,actor,1,false).sourceId();
            assertThat(await(queue).ready()).isTrue();
            queue.content(media,actor,1,key,Files::delete);
            assertThat(queue.status(media,actor,1).errorCode()).isEqualTo("CACHE_INVALID");
            assertThatThrownBy(()->queue.content(media,actor,1,key,path->fail("Unavailable content"))).isInstanceOf(MediaException.class);
            queue.submit(media,actor,1,true);
            assertThat(await(queue).ready()).isTrue();
        }
    }

    @Test void identitiesVersionsPagesAndProfilesCannotShareSourceKeys() throws Exception {
        try(var queue=new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)->image(w))) {
            String key=queue.key(media,actor,1);
            assertThat(queue.key(media,new ViewerPrincipal("Tenant","alice"),1)).isNotEqualTo(key);
            assertThat(queue.key(media,new ViewerPrincipal("tenant","bob"),1)).isNotEqualTo(key);
            assertThat(queue.key(media,actor,2)).isNotEqualTo(key);
            var changed=new MediaRecord(media.mediaId(),media.tenantId(),media.ownerId(),"new-version",
                    media.fileName(),media.mediaType(),media.mimeType(),media.sizeBytes(),media.sha256(),
                    media.storedFileName(),media.createdAt());
            assertThat(queue.key(changed,actor,1)).isNotEqualTo(key);
            try(var other=new PreviewQueue(profile("v2"),"engine",new PreviewCache(directory.resolve("other")),(m,a,p,w)->image(w))) {
                assertThat(other.key(media,actor,1)).isNotEqualTo(key);
            }
            assertThatThrownBy(()->new PreviewQueue(profile("v1"),"engine",new PreviewCache(directory),(m,a,p,w)->image(w)))
                    .isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
        }
    }
    @Test void contentLeaseSurvivesConcurrentCleanupAndExceptionsReleaseIt() throws Exception {
        var cache=new PreviewCache(directory);var calls=new AtomicInteger();
        try(var queue=new PreviewQueue(profile("v1"),"engine",cache,(m,a,p,w)->{calls.incrementAndGet();return image(w);});
                var executor=Executors.newSingleThreadExecutor()){
            String key=queue.submit(media,actor,1,false).sourceId();assertThat(await(queue).ready()).isTrue();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var future=executor.submit(()->{queue.content(media,actor,1,key,path->{entered.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}
                assertThat(path).exists();});return null;});
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(cache.maintain(profile("v1").cache(),System.currentTimeMillis()+java.time.Duration.ofDays(8).toMillis()).removed()).isZero();
                assertThat(queue.status(media,actor,1).ready()).isTrue();
            }finally{release.countDown();}
            future.get(5,TimeUnit.SECONDS);
            assertThatThrownBy(()->queue.content(media,actor,1,key,path->{throw new IOException("Disconnected");})).isInstanceOf(IOException.class);
            // Rendering can publish before its final work directory cleanup. Wait for that cleanup as well.
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);long removed=0;
            while(System.nanoTime()<end&&removed==0){removed=cache.maintain(profile("v1").cache(),System.currentTimeMillis()+java.time.Duration.ofDays(8).toMillis()).removed();Thread.sleep(10);}
            assertThat(removed).isEqualTo(1);assertThat(queue.status(media,actor,1).state()).isEqualTo("not_requested");assertThat(calls).hasValue(1);
            queue.submit(media,actor,1,false);assertThat(await(queue).ready()).isTrue();assertThat(calls).hasValue(2);
        }
    }
}
