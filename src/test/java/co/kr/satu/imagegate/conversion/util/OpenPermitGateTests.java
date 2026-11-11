package co.kr.satu.imagegate.conversion.util;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Timeout(15)
class OpenPermitGateTests {
    final Path path=Path.of("synthetic-unused");
    @Test void rejectsUnboundedCapacity(){
        for(int n:new int[]{0,-1,5})assertThatIllegalArgumentException().isThrownBy(()->new OpenPermitGate(n));
    }
    @Test void permitIsReturnedBeforeCallerReadsOrClosesStream() throws Exception {
        var gate=new OpenPermitGate(1);
        try(var first=gate.open(path,p->new ByteArrayInputStream(new byte[]{1}))){
            assertThat(gate.stats().get("available")).isEqualTo(1);
            try(var second=gate.open(path,p->new ByteArrayInputStream(new byte[]{2}))){
                assertThat(first.read()).isEqualTo(1);assertThat(second.read()).isEqualTo(2);
            }
        }
        assertThat(gate.stats().get("acquired")).isEqualTo(2);assertThat(gate.stats().get("released")).isEqualTo(2);
    }
    @Test void failingOpenReturnsPermitAndRecordsFailure() throws Exception {
        var gate=new OpenPermitGate(1);
        assertThatThrownBy(()->gate.open(path,p->{throw new IOException("Synthetic open failure");})).isInstanceOf(IOException.class);
        var observation=gate.takeObservation();assertThat(observation.acquired()).isTrue();assertThat(observation.returned()).isFalse();
        assertThat(gate.stats().get("available")).isEqualTo(1);assertThat(gate.stats().get("active")).isZero();
        try(var stream=gate.open(path,p->new ByteArrayInputStream(new byte[0]))){}
    }
    @Test void alreadyInterruptedCallerNeverInvokesOpenerOrAcquiresPermit(){
        var gate=new OpenPermitGate(1);var calls=new AtomicInteger();
        try{
            Thread.currentThread().interrupt();
            assertThatThrownBy(()->gate.open(path,p->{calls.incrementAndGet();return new ByteArrayInputStream(new byte[0]);}))
                    .isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();assertThat(calls).hasValue(0);
            assertThat(gate.stats().get("acquired")).isZero();assertThat(gate.stats().get("available")).isEqualTo(1);
        }finally{Thread.interrupted();}
    }
    @Test void interruptedWaiterDoesNotInvokeOpenerOrReturnAnotherCallsPermit() throws Exception {
        var gate=new OpenPermitGate(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();var cancelledFlag=new AtomicBoolean();var extra=new AtomicInteger();
        var holder=new Thread(()->{
            try(var stream=gate.open(path,p->{
                entered.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Release timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
                return new ByteArrayInputStream(new byte[0]);
            })){}catch(Throwable e){failure.compareAndSet(null,e);}
        });
        var waiter=new Thread(()->{
            try(var stream=gate.open(path,p->{extra.incrementAndGet();return new ByteArrayInputStream(new byte[0]);})){
                failure.compareAndSet(null,new AssertionError("Waiter unexpectedly opened"));
            }catch(InterruptedIOException expected){cancelledFlag.set(Thread.currentThread().isInterrupted());}
            catch(Throwable e){failure.compareAndSet(null,e);}
        });
        holder.setDaemon(true);waiter.setDaemon(true);holder.start();
        try{
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();waiter.start();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(gate.waiting()==0&&System.nanoTime()<end)Thread.sleep(5);
            assertThat(gate.waiting()).isEqualTo(1);waiter.interrupt();waiter.join(2000);
            assertThat(waiter.isAlive()).isFalse();assertThat(cancelledFlag).isTrue();assertThat(extra).hasValue(0);
            assertThat(gate.stats().get("available")).isZero();assertThat(gate.stats().get("active")).isEqualTo(1);
        }finally{release.countDown();holder.join(2000);waiter.interrupt();waiter.join(2000);}
        assertThat(holder.isAlive()).isFalse();assertThat(failure.get()).isNull();
        assertThat(gate.stats().get("available")).isEqualTo(1);assertThat(gate.stats().get("waiting")).isZero();
        assertThat(gate.stats().get("acquired")).isEqualTo(gate.stats().get("released"));
    }
}
