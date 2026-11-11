package co.kr.satu.imagegate.conversion.service;

import co.kr.satu.imagegate.conversion.exception.OriginalOpenTimeoutException;
import co.kr.satu.imagegate.conversion.util.PreviewCancellation;
import co.kr.satu.imagegate.system.config.ImageGateProperties.OriginalOpenPolicy;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/** Per-preview-queue admission around native open only; owns no returned stream or background threads. */
public final class OriginalOpenAdmission implements AutoCloseable {
    @FunctionalInterface interface Opener {InputStream open(Path path) throws IOException;}
    private final OriginalOpenPolicy policy;
    private final PreviewMetrics metrics;
    private final ReentrantLock lock=new ReentrantLock(true);
    private final java.util.concurrent.locks.Condition changed=lock.newCondition();
    private volatile boolean closed;
    private int active;

    public OriginalOpenAdmission(OriginalOpenPolicy policy){this(policy,PreviewMetrics.NONE);}
    public OriginalOpenAdmission(OriginalOpenPolicy policy,PreviewMetrics metrics){
        if(policy==null||policy.maxConcurrent()<1||policy.maxConcurrent()>4
                ||policy.waitTimeout()==null||!policy.isWaitBounded())throw new IllegalArgumentException("Invalid original open policy");
        this.policy=policy;this.metrics=java.util.Objects.requireNonNull(metrics);
    }
    public InputStream open(Path path) throws IOException {return open(path,Files::newInputStream);}
    InputStream open(Path path,Opener opener) throws IOException {
        boolean held=false,observedWait=false;
        long waitNanos=0,nativeNanos=0;
        PreviewMetrics.Wait waitResult=PreviewMetrics.Wait.FAILED;
        PreviewMetrics.Native nativeResult=null;
        InputStream stream;
        try{
            PreviewCancellation.check();
            if(policy.enabled()){
                long remaining=policy.waitTimeout().toNanos(),started=System.nanoTime();observedWait=true;metrics.waitStarted();
                try{
                    try{
                        if(!lock.tryLock(remaining,java.util.concurrent.TimeUnit.NANOSECONDS))throw new OriginalOpenTimeoutException();
                        try{
                            remaining-=System.nanoTime()-started;
                            while(!closed&&active>=policy.maxConcurrent()){
                                if(remaining<=0)throw new OriginalOpenTimeoutException();
                                remaining=changed.awaitNanos(remaining);
                            }
                            requireOpen();PreviewCancellation.check();
                            if(remaining<=0)throw new OriginalOpenTimeoutException();
                            active++;held=true;waitResult=PreviewMetrics.Wait.ADMITTED;
                        }finally{lock.unlock();}
                    }catch(InterruptedException interrupted){
                        Thread.currentThread().interrupt();throw new InterruptedIOException("Original open admission interrupted");
                    }
                }catch(OriginalOpenTimeoutException timeout){waitResult=PreviewMetrics.Wait.TIMEOUT;throw timeout;}
                catch(InterruptedIOException cancelled){
                    waitResult=Thread.currentThread().isInterrupted()?PreviewMetrics.Wait.INTERRUPTED:PreviewMetrics.Wait.CLOSED;throw cancelled;
                }finally{waitNanos=metrics.elapsed(started);metrics.waitLeft();}
            }
            requireOpen();PreviewCancellation.check();
            long started=metrics.nativeStarted();nativeResult=PreviewMetrics.Native.FAILED;
            try{stream=opener.open(path);nativeResult=PreviewMetrics.Native.OPENED;}
            catch(InterruptedIOException cancelled){nativeResult=PreviewMetrics.Native.INTERRUPTED;throw cancelled;}
            finally{nativeNanos=metrics.elapsed(started);}
        }finally{
            // Return the policy slot before any external meter callback or stream cleanup.
            if(held){lock.lock();try{active--;changed.signalAll();}finally{lock.unlock();}}
            if(nativeResult!=null)metrics.nativeFinished(nativeNanos,nativeResult);
            if(observedWait)metrics.waitFinished(waitNanos,waitResult);
        }
        try{requireOpen();PreviewCancellation.check();}
        catch(IOException cancelled){try{stream.close();}catch(IOException close){cancelled.addSuppressed(close);}throw cancelled;}
        return stream;
    }
    private void requireOpen() throws InterruptedIOException {
        if(closed)throw new InterruptedIOException("Original open admission closed");
    }
    /** Stops admission and wakes waiters; a native open in progress retains its slot until it returns. */
    @Override public void close(){lock.lock();try{closed=true;changed.signalAll();}finally{lock.unlock();}}
}
