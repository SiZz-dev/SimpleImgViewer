package co.kr.satu.imagegate.playback.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.playback.domain.*;
import co.kr.satu.imagegate.playback.exception.PlaybackException;
import co.kr.satu.imagegate.playback.repository.PlaybackCache;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlaybackServiceTests {
    @TempDir Path root;
    final PlaybackEngine engine=mock(PlaybackEngine.class);
    final MediaService media=mock(MediaService.class);
    final MediaStore store=mock(MediaStore.class);
    final MediaAuthorizationPolicy policy=mock(MediaAuthorizationPolicy.class);
    final CurrentActor actors=mock(CurrentActor.class);
    final FeaturePolicy features=mock(FeaturePolicy.class);
    final ImageGateProperties properties=mock(ImageGateProperties.class);
    final Map<String,MediaRecord> records=new HashMap<>();
    MediaRecord record;
    PlaybackCache cache;
    PlaybackService service() throws Exception {
        var profile=new ImageGateProperties.PlaybackProcessing(true,"unused","unused","0".repeat(64),"0".repeat(64),
                Duration.ofSeconds(30),1,1,7200,8294400,100000,1,"v1");
        when(properties.playback()).thenReturn(profile);
        when(properties.audit()).thenReturn(new ImageGateProperties.Audit(false));
        when(engine.identity()).thenReturn("test-engine");
        var provider=mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(engine);
        cache=new PlaybackCache(root.resolve("cache"),100000);
        return new PlaybackService(media,store,policy,actors,features,properties,cache,provider);
    }
    void prepare() throws Exception {
        Path input=Files.write(root.resolve("original.wav"),new byte[512]);
        record=new MediaRecord(UUID.randomUUID().toString(),"tenant","alice",UUID.randomUUID().toString(),"audio.wav",
                MediaType.AUDIO,"audio/wav",512,HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(input))),"original.wav",Instant.EPOCH);
        records.put(record.mediaId(),record);
        when(media.require(anyString(),eq(MediaAction.VIEW))).thenAnswer(c->records.get(c.getArgument(0)));
        when(store.find(anyString(),anyString())).thenAnswer(c->Optional.ofNullable(records.get(c.getArgument(1))));
        when(store.resolveOriginal(any())).thenReturn(input);
        when(policy.permits(any(),any(),any())).thenReturn(true);
        when(features.isEnabled(any())).thenReturn(true);
        when(actors.require()).thenReturn(new ViewerPrincipal("tenant","alice"));
        when(engine.prepare(any(),any(),any())).thenAnswer(c->artifact(c.getArgument(2)));
    }
    PlaybackArtifact artifact(Path work) throws Exception {
        return new PlaybackArtifact(Files.write(work.resolve("playback.mp3"),new byte[1024]),3,"audio/mpeg",0,0,null,"mp3");
    }
    PlaybackInfo await(PlaybackService service,String id,String state) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end){var info=service.status(id);if(info.state().equals(state))return info;Thread.sleep(10);}
        throw new AssertionError("Timed out waiting for "+state);
    }
    @Test void warmCacheSurvivesRestartAndNoGetAdmitsAJob() throws Exception {
        prepare();String key;
        try(var service=service()){
            assertThat(service.status(record.mediaId()).state()).isEqualTo("not_requested");
            verify(engine,never()).prepare(any(),any(),any());
            service.request(record.mediaId(),false);key=await(service,record.mediaId(),"ready").sourceId();
        }
        try(var service=service()){
            assertThat(service.status(record.mediaId()).sourceId()).isEqualTo(key);
            assertThat(service.request(record.mediaId(),false).ready()).isTrue();
            service.content(record.mediaId(),key,c->assertThat(Files.size(c.path())).isEqualTo(1024));
        }
        verify(engine,times(1)).prepare(any(),any(),any());
    }
    @Test void deduplicatesAndBoundsQueuedWorkWhileAnotherJobIsRunning() throws Exception {
        prepare();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(c->{entered.countDown();release.await(5,TimeUnit.SECONDS);return artifact(c.getArgument(2));}).when(engine).prepare(any(),any(),any());
        try(var service=service()){
            var first=service.request(record.mediaId(),false);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(service.request(record.mediaId(),false).sourceId()).isEqualTo(first.sourceId());
            for(int i=0;i<2;i++){String id=UUID.randomUUID().toString();records.put(id,new MediaRecord(id,record.tenantId(),record.ownerId(),
                    record.mediaVersion(),"audio.wav",record.mediaType(),record.mimeType(),record.sizeBytes(),record.sha256(),record.storedFileName(),record.createdAt()));}
            var other=records.keySet().stream().filter(id->!id.equals(record.mediaId())).toList();
            service.request(other.get(0),false);
            assertThatThrownBy(()->service.request(other.get(1),false)).isInstanceOfSatisfying(PlaybackException.class,
                    e->assertThat(e.code()).isEqualTo(PlaybackException.Code.BUSY));
            release.countDown();await(service,record.mediaId(),"ready");await(service,other.get(0),"ready");
        } finally {release.countDown();}
        verify(engine,times(2)).prepare(any(),any(),any());
    }
    @Test void haltedPublisherRemainsInterruptedAndRetainsUnknownRemnantsAcrossRestart()throws Exception{
        prepare();byte[] original=Files.readAllBytes(root.resolve("original.wav"));String key;
        try(var before=service()){key=before.status(record.mediaId()).sourceId();}
        var crash=co.kr.satu.imagegate.conversion.util.PublicationCrashFixture.crash("playback",root.resolve("cache"),key);
        assertThat(crash.exitCode()).as("Child output: %s",crash.output()).isEqualTo(73);
        var remnants=new ArrayList<Path>();
        try(var files=Files.list(root.resolve("cache").resolve(key))){files.filter(p->p.getFileName().toString().startsWith("result-")).forEach(remnants::add);}
        assertThat(remnants).hasSize(1);
        try(var reopened=service()){
            assertThat(reopened.status(record.mediaId()).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(reopened.request(record.mediaId(),false).state()).isEqualTo("failed");
            verify(engine,never()).prepare(any(),any(),any());
            assertThatThrownBy(()->reopened.content(record.mediaId(),key,c->fail("No ready artifact"))).isInstanceOf(PlaybackException.class);
            reopened.request(record.mediaId(),true);await(reopened,record.mediaId(),"ready");
            verify(engine,times(1)).prepare(any(),any(),any());
        }
        try(var next=new PlaybackCache(root.resolve("cache"),100000)){
            var maintenance=next.maintain(new ImageGateProperties.PlaybackCachePolicy(true,Duration.ofHours(1),Duration.ofMinutes(1),1048576),
                    System.currentTimeMillis()+Duration.ofDays(8).toMillis());
            assertThat(maintenance.removed()).isZero();assertThat(maintenance.skipped()).isGreaterThanOrEqualTo(1);
            assertThat(next.read(key).ready()).isTrue();
            for(var remnant:remnants)assertThat(remnant).isRegularFile();
        }
        assertThat(Files.readAllBytes(root.resolve("original.wav"))).containsExactly(original);
    }

    @Test void failedProcessingRequiresExplicitRetryAndDoesNotPublishPartialMedia() throws Exception {
        prepare();
        doThrow(new PlaybackException(PlaybackException.Code.INVALID_MEDIA))
                .doAnswer(c->artifact(c.getArgument(2))).when(engine).prepare(any(),any(),any());
        try(var service=service()){
            service.request(record.mediaId(),false);await(service,record.mediaId(),"failed");
            assertThat(service.request(record.mediaId(),false).state()).isEqualTo("failed");
            verify(engine,times(1)).prepare(any(),any(),any());
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){service.request(record.mediaId(),true);if(service.status(record.mediaId()).ready())break;Thread.sleep(10);}
            assertThat(service.status(record.mediaId()).ready()).isTrue();
        }
    }
    @Test void streamAdmissionIsHeldUntilTheReaderCompletes() throws Exception {
        prepare();
        try(var service=service();var executor=Executors.newSingleThreadExecutor()){
            service.request(record.mediaId(),false);String key=await(service,record.mediaId(),"ready").sourceId();
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var first=executor.submit(()->{service.content(record.mediaId(),key,c->{entered.countDown();
                try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}});return null;});
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThat(cache.maintain(ImageGateProperties.PlaybackCachePolicy.defaults(),System.currentTimeMillis()+Duration.ofDays(8).toMillis()).removed()).isZero();
                assertThatThrownBy(()->service.content(record.mediaId(),key,c->fail("Capacity must remain held")))
                        .isInstanceOfSatisfying(PlaybackException.class,e->assertThat(e.code()).isEqualTo(PlaybackException.Code.BUSY));
            } finally {release.countDown();}
            first.get(5,TimeUnit.SECONDS);
            service.content(record.mediaId(),key,c->assertThat(c.info().ready()).isTrue());
        }
    }

    @Test void expiredCacheRebuildsOnlyOnExplicitRequestAndFailedTransfersReleaseTheLease() throws Exception {
        prepare();
        try(var service=service()){
            service.request(record.mediaId(),false);String key=await(service,record.mediaId(),"ready").sourceId();
            assertThatThrownBy(()->service.content(record.mediaId(),key,c->{throw new java.io.IOException("Disconnected");}))
                    .isInstanceOf(java.io.IOException.class);
            var result=cache.maintain(ImageGateProperties.PlaybackCachePolicy.defaults(),System.currentTimeMillis()+Duration.ofDays(8).toMillis());
            assertThat(result.removed()).isEqualTo(1);
            assertThat(service.status(record.mediaId()).state()).isEqualTo("not_requested");
            verify(engine,times(1)).prepare(any(),any(),any());
            service.request(record.mediaId(),false);await(service,record.mediaId(),"ready");
            service.content(record.mediaId(),key,c->assertThat(Files.size(c.path())).isEqualTo(1024));
            verify(engine,times(2)).prepare(any(),any(),any());
        }
    }

    @ParameterizedTest @ValueSource(strings={"permission","feature"})
    void finalPublicationCheckAfterArtifactMoveDeniesRevocationAndCleansPrivateResult(String change)throws Exception {
        prepare();var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);var enabled=new java.util.concurrent.atomic.AtomicBoolean(true);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(features.isEnabled(any())).thenAnswer(c->enabled.get());
        when(policy.permits(any(),any(),any())).thenAnswer(c->{
            boolean staged=false;
            if(Files.exists(root.resolve("cache")))try(var files=Files.walk(root.resolve("cache"))){
                staged=files.anyMatch(p->p.getFileName().toString().startsWith("result-"));
            }
            if(staged){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Playback gate timed out");}
            return allowed.get();
        });
        try(var service=service()) {
            service.request(record.mediaId(),false);
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();if(change.equals("permission"))allowed.set(false);else enabled.set(false);}
            finally{release.countDown();}
            assertThat(await(service,record.mediaId(),"failed").errorCode()).isEqualTo("PLAYBACK_FAILED");
        }finally{release.countDown();}
        try(var files=Files.walk(root.resolve("cache"))){assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("result-"))).isTrue();}
        when(policy.permits(any(),any(),any())).thenReturn(true);when(features.isEnabled(any())).thenReturn(true);
        try(var service=service()) {
            assertThat(service.request(record.mediaId(),false).state()).isEqualTo("failed");
            service.request(record.mediaId(),true);await(service,record.mediaId(),"ready");
        }
    }

    @Test void revocationDuringProcessingPreventsPublication() throws Exception {
        prepare();var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        when(policy.permits(any(),any(),any())).thenAnswer(c->allowed.get());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(c->{entered.countDown();release.await(5,TimeUnit.SECONDS);return artifact(c.getArgument(2));})
                .when(engine).prepare(any(),any(),any());
        try(var service=service()){
            service.request(record.mediaId(),false);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            allowed.set(false);release.countDown();
            var failed=await(service,record.mediaId(),"failed");assertThat(failed.errorCode()).isEqualTo("PLAYBACK_FAILED");
            try(var files=Files.walk(root.resolve("cache"))){assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("result-"))).isTrue();}
        } finally {release.countDown();}
    }

    @Test void timeoutAfterPartialOutputIsClassifiedAndCannotPublish() throws Exception {
        prepare();
        doAnswer(c->{
            artifact(c.getArgument(2));
            throw new co.kr.satu.imagegate.conversion.exception.ConversionException(
                    co.kr.satu.imagegate.conversion.exception.ConversionException.Code.TIMEOUT,"Playback preparation timed out");
        }).when(engine).prepare(any(),any(),any());
        try(var service=service()){
            service.request(record.mediaId(),false);
            assertThat(await(service,record.mediaId(),"failed").errorCode()).isEqualTo("PROCESS_TIMEOUT");
            verify(engine,times(1)).prepare(any(),any(),any());
        }
        try(var files=Files.walk(root.resolve("cache"))){
            assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("result-")
                    ||p.getFileName().toString().startsWith("work-"))).isTrue();
        }
    }

    @Test void interruptedEngineReturningAnArtifactCannotPublishAndWorkerCanRetry() throws Exception {
        prepare();var cancel=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(c->{
            var output=artifact(c.getArgument(2));
            if(cancel.getAndSet(false))Thread.currentThread().interrupt();
            return output;
        }).when(engine).prepare(any(),any(),any());
        try(var service=service()){
            service.request(record.mediaId(),false);
            assertThat(await(service,record.mediaId(),"failed").errorCode()).isEqualTo("INTERRUPTED");
            try(var files=Files.walk(root.resolve("cache"))){
                assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("result-"))).isTrue();
            }
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){
                service.request(record.mediaId(),true);
                if(service.status(record.mediaId()).ready())break;
                Thread.sleep(10);
            }
            assertThat(service.status(record.mediaId()).ready()).isTrue();
        }
    }

    MediaRecord anotherRecord() {
        String id=UUID.randomUUID().toString();
        var other=new MediaRecord(id,record.tenantId(),record.ownerId(),record.mediaVersion(),
                record.fileName(),record.mediaType(),record.mimeType(),record.sizeBytes(),
                record.sha256(),record.storedFileName(),record.createdAt());
        records.put(id,other);return other;
    }

    @Test void shutdownInterruptsRunningWorkDrainsQueuedJobAndRestartRequiresExplicitRetry() throws Exception {
        prepare();var other=anotherRecord();var entered=new CountDownLatch(1);
        doAnswer(c->{entered.countDown();new CountDownLatch(1).await(10,TimeUnit.SECONDS);
            return artifact(c.getArgument(2));}).when(engine).prepare(any(),any(),any());
        var service=service();String firstKey,queuedKey;
        try{
            firstKey=service.request(record.mediaId(),false).sourceId();
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            queuedKey=service.request(other.mediaId(),false).sourceId();
        }finally{service.close();}
        service.close();
        verify(engine,times(1)).prepare(any(),any(),any());
        assertThatThrownBy(()->service.request(record.mediaId(),true)).isInstanceOfSatisfying(PlaybackException.class,
                e->assertThat(e.code()).isEqualTo(PlaybackException.Code.BUSY));
        try(var reopened=service()){
            assertThat(cache.read(firstKey).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(cache.read(queuedKey).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(reopened.status(other.mediaId()).state()).isEqualTo("failed");
            reopened.request(other.mediaId(),false);
            verify(engine,times(1)).prepare(any(),any(),any());
            try(var files=Files.walk(root.resolve("cache"))){
                assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("work-")
                        ||p.getFileName().toString().startsWith("result-"))).isTrue();
            }
            doAnswer(c->artifact(c.getArgument(2))).when(engine).prepare(any(),any(),any());
            reopened.request(other.mediaId(),true);await(reopened,other.mediaId(),"ready");
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void lateWorkerRetainsWriterPastShutdownWaitOrCallerInterruptionAndEventuallyReleases(boolean interruptCaller) throws Exception {
        prepare();var other=anotherRecord();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(c->{
            var output=artifact(c.getArgument(2));entered.countDown();
            boolean interrupted=false;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(release.getCount()>0&&System.nanoTime()<end){
                try{release.await(1,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}
            }
            // Cover an engine that either preserves or consumes the worker interrupt.
            if(interrupted&&!interruptCaller)Thread.currentThread().interrupt();
            return output;
        }).when(engine).prepare(any(),any(),any());
        var service=service();String firstKey=null,queuedKey=null;
        try{
            firstKey=service.request(record.mediaId(),false).sourceId();
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            queuedKey=service.request(other.mediaId(),false).sourceId();
            if(interruptCaller)Thread.currentThread().interrupt();
            service.close();
            if(interruptCaller)assertThat(Thread.currentThread().isInterrupted()).isTrue();
            Thread.interrupted();
            assertWriterBusy();
            assertThat(cache.read(queuedKey).errorCode()).isEqualTo("INTERRUPTED");
            verify(engine,times(1)).prepare(any(),any(),any());
        }finally{Thread.interrupted();release.countDown();service.close();}
        // No second successful shutdown call is required to release the last worker's lock.
        try(var reopened=awaitReopen()){
            assertThat(reopened.read(firstKey).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(reopened.read(queuedKey).errorCode()).isEqualTo("INTERRUPTED");
            try(var files=Files.walk(root.resolve("cache"))){
                assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("work-")
                        ||p.getFileName().toString().startsWith("result-"))).isTrue();
            }
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void activeResponseRetainsWriterUntilCallbackCompletesEvenOnTransferFailure(boolean failTransfer) throws Exception {
        prepare();var service=service();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);String key=null;
        try(var executor=Executors.newSingleThreadExecutor()){
            service.request(record.mediaId(),false);key=await(service,record.mediaId(),"ready").sourceId();
            String sourceId=key;
            var response=executor.submit(()->{
                service.content(record.mediaId(),sourceId,c->{
                    entered.countDown();
                    try{if(!release.await(10,TimeUnit.SECONDS))throw new java.io.IOException("Test release timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException(e);}
                    assertThat(Files.size(c.path())).isEqualTo(1024);
                    if(failTransfer)throw new java.io.IOException("Synthetic disconnect");
                });return null;
            });
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();service.close();
                assertWriterBusy();
                assertThatThrownBy(()->service.content(record.mediaId(),sourceId,c->fail("New response after close")))
                        .isInstanceOfSatisfying(PlaybackException.class,e->assertThat(e.code()).isEqualTo(PlaybackException.Code.BUSY));
            }finally{release.countDown();}
            if(failTransfer)assertThatThrownBy(()->response.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            else response.get(5,TimeUnit.SECONDS);
        }finally{release.countDown();service.close();}
        try(var reopened=awaitReopen()){assertThat(reopened.read(key).ready()).isTrue();}
    }

    void assertWriterBusy(){
        assertThatThrownBy(()->{try(var unexpected=new PlaybackCache(root.resolve("cache"),100000)){}})
                .isInstanceOfAny(java.io.IOException.class,java.nio.channels.OverlappingFileLockException.class);
    }
    PlaybackCache awaitReopen() throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(true){
            try{return new PlaybackCache(root.resolve("cache"),100000);}
            catch(java.io.IOException|java.nio.channels.OverlappingFileLockException busy){
                if(System.nanoTime()>=end)throw busy;
                Thread.sleep(10);
            }
        }
    }
}
