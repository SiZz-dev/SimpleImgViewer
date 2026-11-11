package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.exception.OriginalOpenTimeoutException;
import co.kr.satu.imagegate.system.config.ImageGateProperties.OriginalOpenPolicy;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class OriginalOpenPolicyTests {
    final Path path=Path.of("synthetic");
    OriginalOpenAdmission gate(boolean enabled,int capacity,long millis){return new OriginalOpenAdmission(new OriginalOpenPolicy(enabled,capacity,Duration.ofMillis(millis)));}
    void await(CountDownLatch latch) throws IOException {
        try{if(!latch.await(5,TimeUnit.SECONDS))throw new IOException("Synthetic timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
    }
    Thread start(Runnable task){var thread=new Thread(task);thread.setDaemon(true);thread.start();return thread;}
    void awaitWaiting(Thread thread) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(thread.getState()!=Thread.State.TIMED_WAITING&&System.nanoTime()<deadline){Thread.sleep(1);}
        assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
    }
    @Test void invalidProgrammaticPoliciesFailEvenWhenDisabled(){
        for(var policy:new OriginalOpenPolicy[]{new OriginalOpenPolicy(false,0,Duration.ofSeconds(1)),new OriginalOpenPolicy(false,5,Duration.ofSeconds(1)),
                new OriginalOpenPolicy(false,1,null),new OriginalOpenPolicy(false,1,Duration.ZERO),new OriginalOpenPolicy(false,1,Duration.ofNanos(1)),new OriginalOpenPolicy(false,1,Duration.ofSeconds(61))})
            assertThatThrownBy(()->new OriginalOpenAdmission(policy)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void timeoutCannotEnterOrReturnSomeoneElsesPermitAndRecoverySucceeds() throws Exception {
        try(var gate=gate(true,1,50)){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new CompletableFuture<InputStream>();
            var worker=start(()->{try{first.complete(gate.open(path,p->{entered.countDown();await(release);return InputStream.nullInputStream();}));}catch(Throwable e){first.completeExceptionally(e);}});
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                for(int i=0;i<2;i++)assertThatThrownBy(()->gate.open(path,p->{throw new AssertionError("Exceeded capacity");})).isInstanceOf(OriginalOpenTimeoutException.class);
            }finally{release.countDown();}
            first.get(5,TimeUnit.SECONDS).close();worker.join(5000);
            var closed=new AtomicBoolean();
            var stream=gate.open(path,p->new ByteArrayInputStream(new byte[]{7}){public void close(){closed.set(true);}});
            try(var second=gate.open(path,p->InputStream.nullInputStream())){assertThat(closed).isFalse();assertThat(stream.read()).isEqualTo(7);}
            stream.close();assertThat(closed).isTrue();
            assertThatThrownBy(()->gate.open(path,p->{throw new IOException("Synthetic failure");})).hasMessage("Synthetic failure");
            gate.open(path,p->InputStream.nullInputStream()).close();
        }
    }
    @Test void disabledPolicyAndCapacityTwoAllowOverlappingOpens() throws Exception {
        for(boolean enabled:new boolean[]{false,true})try(var gate=gate(enabled,enabled?2:1,100)){
            var both=new CountDownLatch(2);var release=new CountDownLatch(1);var errors=new ConcurrentLinkedQueue<Throwable>();
            Runnable action=()->{try(var stream=gate.open(path,p->{both.countDown();await(release);return InputStream.nullInputStream();})){}catch(Throwable e){errors.add(e);}};
            var first=start(action);var second=start(action);
            try{assertThat(both.await(5,TimeUnit.SECONDS)).isTrue();}finally{release.countDown();}
            first.join(5000);second.join(5000);assertThat(errors).isEmpty();assertThat(first.isAlive()||second.isAlive()).isFalse();
        }
    }
    @Test void interruptedWaiterPreservesFlagAndDoesNotReleaseHeldSlot() throws Exception {
        try(var gate=gate(true,1,100)){
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var first=new CompletableFuture<InputStream>();
            var worker=start(()->{try{first.complete(gate.open(path,p->{entered.countDown();await(release);return InputStream.nullInputStream();}));}catch(Throwable e){first.completeExceptionally(e);}});
            try{
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                var waiting=new CountDownLatch(1);var result=new CompletableFuture<Boolean>();
                var waiter=start(()->{waiting.countDown();try{gate.open(path,p->{throw new AssertionError("Unexpected open");});result.complete(false);}catch(InterruptedIOException e){result.complete(Thread.currentThread().isInterrupted());}catch(Throwable e){result.completeExceptionally(e);}});
                assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();awaitWaiting(waiter);waiter.interrupt();assertThat(result.get(5,TimeUnit.SECONDS)).isTrue();waiter.join(5000);
                assertThatThrownBy(()->gate.open(path,p->{throw new AssertionError("Over-release");})).isInstanceOf(OriginalOpenTimeoutException.class);
            }finally{release.countDown();}
            first.get(5,TimeUnit.SECONDS).close();worker.join(5000);gate.open(path,p->InputStream.nullInputStream()).close();
        }
    }
    @Test void closeWakesWaiterAndClosesLateNativeStreamWithoutFreeingActiveSlotEarly() throws Exception {
        var gate=gate(true,1,10000);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var streamClosed=new AtomicBoolean();
        var first=new CompletableFuture<Throwable>();
        var worker=start(()->{try{gate.open(path,p->{entered.countDown();await(release);return new ByteArrayInputStream(new byte[0]){public void close(){streamClosed.set(true);}};});first.complete(new AssertionError("Late stream escaped"));}catch(Throwable e){first.complete(e);}});
        try{
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();var waiting=new CountDownLatch(1);var second=new CompletableFuture<Throwable>();
            var waiter=start(()->{waiting.countDown();try{gate.open(path,p->{throw new AssertionError("After close");});second.complete(new AssertionError("Unexpected stream"));}catch(Throwable e){second.complete(e);}});
            assertThat(waiting.await(5,TimeUnit.SECONDS)).isTrue();awaitWaiting(waiter);gate.close();gate.close();
            assertThat(second.get(1,TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);waiter.join(5000);
            assertThat(first.isDone()).isFalse();assertThat(streamClosed).isFalse();
            assertThatThrownBy(()->gate.open(path,p->{throw new AssertionError();})).isInstanceOf(InterruptedIOException.class);
        }finally{release.countDown();gate.close();}
        assertThat(first.get(5,TimeUnit.SECONDS)).isInstanceOf(InterruptedIOException.class);worker.join(5000);assertThat(streamClosed).isTrue();
    }
    @Test void lateCancellationReturnsSlotBeforeStreamCloseAndPreservesCloseFailure() throws Exception {
        try(var gate=gate(true,1,100)){
            var closing=new CountDownLatch(1);var release=new CountDownLatch(1);var outcome=new CompletableFuture<Throwable>();
            var worker=start(()->{
                try{gate.open(path,p->{Thread.currentThread().interrupt();return new ByteArrayInputStream(new byte[0]){
                    public void close() throws IOException {
                        closing.countDown();boolean interrupted=Thread.interrupted();
                        try{await(release);}finally{if(interrupted)Thread.currentThread().interrupt();}
                        throw new IOException("Synthetic close failure");
                    }
                };});outcome.complete(new AssertionError("Cancelled stream escaped"));}
                catch(Throwable e){outcome.complete(e);}
            });
            try{
                assertThat(closing.await(5,TimeUnit.SECONDS)).isTrue();
                gate.open(path,p->InputStream.nullInputStream()).close();
                assertThat(outcome.isDone()).isFalse();
            }finally{release.countDown();}
            var failure=outcome.get(5,TimeUnit.SECONDS);worker.join(5000);
            assertThat(failure).isInstanceOf(InterruptedIOException.class);
            assertThat(failure.getSuppressed()).hasSize(1);assertThat(failure.getSuppressed()[0]).hasMessage("Synthetic close failure");
        }
    }
    @Test void preCancelledNeverOpensAndLateCancellationClosesStream() throws Exception {
        for(boolean enabled:new boolean[]{false,true})try(var gate=gate(enabled,1,100)){
            Thread.currentThread().interrupt();
            try{assertThatThrownBy(()->gate.open(path,p->{throw new AssertionError();})).isInstanceOf(InterruptedIOException.class);assertThat(Thread.currentThread().isInterrupted()).isTrue();}
            finally{Thread.interrupted();}
            var closed=new AtomicBoolean();
            try{assertThatThrownBy(()->gate.open(path,p->{Thread.currentThread().interrupt();return new ByteArrayInputStream(new byte[0]){public void close(){closed.set(true);}};})).isInstanceOf(InterruptedIOException.class);assertThat(closed).isTrue();assertThat(Thread.currentThread().isInterrupted()).isTrue();}
            finally{Thread.interrupted();}
            gate.open(path,p->InputStream.nullInputStream()).close();
        }
    }
}
