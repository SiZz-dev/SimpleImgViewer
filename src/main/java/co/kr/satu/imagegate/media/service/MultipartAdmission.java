package co.kr.satu.imagegate.media.service;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
/** Single-JVM admission before multipart parsing, without a queue or retained user identities. */
public final class MultipartAdmission {
    private final Semaphore slots;
    public MultipartAdmission(int maximum){if(maximum<1||maximum>16)throw new IllegalArgumentException("Invalid ingress capacity");slots=new Semaphore(maximum);}
    public Lease tryAcquire(){return slots.tryAcquire()?new Lease():null;}
    public final class Lease implements AutoCloseable {
        private final AtomicBoolean closed=new AtomicBoolean();
        private Lease(){}
        @Override public void close(){if(closed.compareAndSet(false,true))slots.release();}
    }
}
