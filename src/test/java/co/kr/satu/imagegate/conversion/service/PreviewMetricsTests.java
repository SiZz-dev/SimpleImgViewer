package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.exception.OriginalOpenTimeoutException;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.system.config.ImageGateProperties.OriginalOpenPolicy;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(20)
class PreviewMetricsTests {
    static final class Registry extends SimpleMeterRegistry implements AutoCloseable {}
    @TempDir Path root;
    final PreviewQueueTests fixture=new PreviewQueueTests();
    @BeforeEach void setup(){fixture.directory=root;}
    Thread start(Runnable task){var t=new Thread(task);t.setDaemon(true);t.start();return t;}
    void await(CountDownLatch latch)throws IOException {
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("Synthetic timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
    }
    void waiting(Thread t)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(t.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<end)Thread.sleep(1);
        assertThat(t.getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }
    void finished(SimpleMeterRegistry registry,long count)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(registry.find("imagegate.preview.jobs.duration").timers().stream().mapToLong(Timer::count).sum()<count&&System.nanoTime()<end)Thread.sleep(1);
        assertThat(registry.find("imagegate.preview.jobs.duration").timers().stream().mapToLong(Timer::count).sum()).isEqualTo(count);
    }
    @Test void disabledMetricsCreateNoMetersAndKeepLegacyConstructors()throws Exception {
        try(var registry=new Registry();var queue=new PreviewQueue(fixture.profile("v1"),"engine",new PreviewCache(root),
                (m,a,p,w)->fixture.image(w),false,PreviewMetrics.NONE)){
            queue.submit(fixture.media,fixture.actor,1,false);assertThat(fixture.await(queue).ready()).isTrue();assertThat(registry.getMeters()).isEmpty();
            assertThat(PreviewMetrics.NONE.clock()).isZero();assertThat(PreviewMetrics.NONE.elapsed(0)).isZero();
        }
    }
    @Test void terminalStatesRejectionsAndShutdownAreCountedOnce()throws Exception {
        try(var registry=new Registry()){
            var metrics=new PreviewMetrics(registry);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var queue=new PreviewQueue(fixture.profile("v1"),"engine",new PreviewCache(root),(m,a,p,w)->{
                entered.countDown();await(release);return fixture.image(w);
            },false,metrics);
            try{
                queue.submit(fixture.media,fixture.actor,1,false);assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                queue.submit(fixture.media,fixture.actor,2,false);queue.submit(fixture.media,fixture.actor,1,false);
                assertThatThrownBy(()->queue.submit(fixture.media,fixture.actor,3,false)).isInstanceOf(MediaException.class);
                assertThat(registry.get("imagegate.preview.jobs.accepted").counter().count()).isEqualTo(2);
                assertThat(registry.get("imagegate.preview.queue.waiting").gauge().value()).isEqualTo(1);
                assertThat(registry.get("imagegate.preview.jobs.running").gauge().value()).isEqualTo(1);
            }finally{queue.close();release.countDown();}
            queue.close();finished(registry,2);
            assertThat(registry.get("imagegate.preview.jobs.completed").tag("outcome","interrupted").counter().count()).isEqualTo(2);
            assertThat(registry.get("imagegate.preview.queue.wait").tag("result","started").timer().count()).isEqualTo(1);
            assertThat(registry.get("imagegate.preview.queue.wait").tag("result","cancelled").timer().count()).isEqualTo(1);
            assertThat(registry.get("imagegate.preview.jobs.rejected").tag("reason","capacity").counter().count()).isEqualTo(1);
            assertThatThrownBy(()->queue.submit(fixture.media,fixture.actor,1,true)).isInstanceOf(MediaException.class);
            assertThat(registry.get("imagegate.preview.jobs.rejected").tag("reason","closing").counter().count()).isEqualTo(1);
            assertThat(registry.get("imagegate.preview.queue.waiting").gauge().value()).isZero();
            assertThat(registry.get("imagegate.preview.jobs.running").gauge().value()).isZero();
            assertThat(registry.getMeters()).hasSize(34);
        }
    }
    @ParameterizedTest @ValueSource(strings={"ready","failed","open_busy","interrupted","decoder_unavailable","conversion_busy"})
    void actualQueueMapsOnlyFixedTerminalOutcomesAndCacheReadsStayUnmeasured(String outcome)throws Exception {
        try(var registry=new Registry();var queue=new PreviewQueue(fixture.profile("v1"),"engine",new PreviewCache(root),(m,a,p,w)->{
            return switch(outcome){
                case "ready"->fixture.image(w);
                case "open_busy"->throw new OriginalOpenTimeoutException();
                case "conversion_busy"->throw new co.kr.satu.imagegate.conversion.exception.ConversionException(co.kr.satu.imagegate.conversion.exception.ConversionException.Code.BUSY,"private detail");
                case "interrupted"->throw new InterruptedIOException();
                case "decoder_unavailable"->throw new UnsupportedOperationException("private detail");
                default->throw new IOException("private detail");
            };
        },false,new PreviewMetrics(registry))){
            queue.submit(fixture.media,fixture.actor,1,false);fixture.await(queue);finished(registry,1);
            assertThat(registry.get("imagegate.preview.jobs.completed").tag("outcome",outcome).counter().count()).isEqualTo(1);
            assertThat(registry.get("imagegate.preview.jobs.duration").tag("outcome",outcome).timer().count()).isEqualTo(1);
            queue.status(fixture.media,fixture.actor,1);queue.submit(fixture.media,fixture.actor,1,false);
            assertThat(registry.get("imagegate.preview.jobs.accepted").counter().count()).isEqualTo(1);
            assertThat(PreviewMetrics.outcome("CONVERSION_private-detail")).isEqualTo(PreviewMetrics.Outcome.CONVERSION_FAILED);
            for(var meter:registry.getMeters())for(var tag:meter.getId().getTags())assertThat(tag.getValue()).doesNotContain("private",fixture.media.mediaId());
        }
    }
    @Test void gateTimesAdmissionTimeoutAndNativeFailureAndReturnsActiveGaugeToZero()throws Exception {
        try(var registry=new Registry();var gate=new OriginalOpenAdmission(new OriginalOpenPolicy(true,1,Duration.ofMillis(100)),new PreviewMetrics(registry))){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var result=new CompletableFuture<InputStream>();
            var worker=start(()->{try{result.complete(gate.open(root,p->{entered.countDown();await(release);return InputStream.nullInputStream();}));}catch(Throwable e){result.completeExceptionally(e);}});
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();assertThat(registry.get("imagegate.preview.original.active").gauge().value()).isEqualTo(1);
                assertThatThrownBy(()->gate.open(root,p->{throw new AssertionError("No permit");})).isInstanceOf(OriginalOpenTimeoutException.class);
                assertThat(registry.get("imagegate.preview.original.wait").tag("result","timeout").timer().count()).isEqualTo(1);
            }finally{release.countDown();}
            result.get(5,TimeUnit.SECONDS).close();worker.join(5000);
            assertThatThrownBy(()->gate.open(root,p->{throw new IOException("private detail");})).isInstanceOf(IOException.class);
            assertThat(registry.get("imagegate.preview.original.wait").tag("result","admitted").timer().count()).isEqualTo(2);
            assertThat(registry.get("imagegate.preview.original.open").tag("result","opened").timer().count()).isEqualTo(1);
            assertThat(registry.get("imagegate.preview.original.open").tag("result","failed").timer().count()).isEqualTo(1);
            assertThat(registry.get("imagegate.preview.original.active").gauge().value()).isZero();
        }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancelledWaitHasNoNativeSampleAndLateNativeReturnStillResetsGauge(boolean shutdown)throws Exception {
        try(var registry=new Registry();var gate=new OriginalOpenAdmission(new OriginalOpenPolicy(true,1,Duration.ofSeconds(10)),new PreviewMetrics(registry))){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new CompletableFuture<Boolean>();
            var worker=start(()->{try(var stream=gate.open(root,p->{entered.countDown();await(release);return InputStream.nullInputStream();})){first.complete(true);}catch(InterruptedIOException e){first.complete(false);}catch(Throwable e){first.completeExceptionally(e);}});
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();var second=new CompletableFuture<Boolean>();
                var waiter=start(()->{try{gate.open(root,p->{throw new AssertionError("No native call expected");});second.complete(false);}catch(InterruptedIOException e){second.complete(shutdown||Thread.currentThread().isInterrupted());}catch(Throwable e){second.completeExceptionally(e);}});
                waiting(waiter);assertThat(registry.get("imagegate.preview.original.waiting").gauge().value()).isEqualTo(1);
                if(shutdown)gate.close();else waiter.interrupt();
                assertThat(second.get(5,TimeUnit.SECONDS)).isTrue();waiter.join(5000);
                assertThat(registry.get("imagegate.preview.original.waiting").gauge().value()).isZero();
                assertThat(registry.get("imagegate.preview.original.wait").tag("result",shutdown?"closed":"interrupted").timer().count()).isEqualTo(1);
                assertThat(registry.get("imagegate.preview.original.active").gauge().value()).isEqualTo(1);
            }finally{release.countDown();}
            assertThat(first.get(5,TimeUnit.SECONDS)).isEqualTo(!shutdown);worker.join(5000);
            assertThat(registry.get("imagegate.preview.original.active").gauge().value()).isZero();
            assertThat(registry.get("imagegate.preview.original.open").tag("result","opened").timer().count()).isEqualTo(1);
        }
    }
    @Test void failingMetricsCannotFailJobsOrRetainAnOpenPermit()throws Exception {
        var registry=mock(MeterRegistry.class,RETURNS_DEEP_STUBS);var metrics=new PreviewMetrics(registry);
        var acceptedCounter=registry.counter("imagegate.preview.jobs.accepted");
        var waitTimer=registry.timer("imagegate.preview.original.wait","result","admitted");
        doThrow(new IllegalStateException("Synthetic meter failure")).when(acceptedCounter).increment();
        doThrow(new IllegalStateException("Synthetic meter failure")).when(waitTimer).record(anyLong(),eq(TimeUnit.NANOSECONDS));
        try(var gate=new OriginalOpenAdmission(new OriginalOpenPolicy(true,1,Duration.ofMillis(100)),metrics)){
            for(int i=0;i<2;i++)gate.open(root,p->InputStream.nullInputStream()).close();
        }
        try(var queue=new PreviewQueue(fixture.profile("v1"),"engine",new PreviewCache(root),(m,a,p,w)->fixture.image(w),false,metrics)){
            queue.submit(fixture.media,fixture.actor,1,false);assertThat(fixture.await(queue).ready()).isTrue();
        }
    }
    @Test void slowMeterRunsAfterPermitRelease()throws Exception {
        var registry=mock(MeterRegistry.class,RETURNS_DEEP_STUBS);var metrics=new PreviewMetrics(registry);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var waitTimer=registry.timer("imagegate.preview.original.wait","result","admitted");
        doAnswer(invocation->{if(calls.incrementAndGet()==1){entered.countDown();await(release);}return null;})
                .when(waitTimer).record(anyLong(),eq(TimeUnit.NANOSECONDS));
        try(var gate=new OriginalOpenAdmission(new OriginalOpenPolicy(true,1,Duration.ofMillis(100)),metrics)){
            var first=new CompletableFuture<InputStream>();var worker=start(()->{try{first.complete(gate.open(root,p->InputStream.nullInputStream()));}catch(Throwable e){first.completeExceptionally(e);}});
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();gate.open(root,p->InputStream.nullInputStream()).close();}
            finally{release.countDown();}
            first.get(5,TimeUnit.SECONDS).close();worker.join(5000);
        }
    }
}
