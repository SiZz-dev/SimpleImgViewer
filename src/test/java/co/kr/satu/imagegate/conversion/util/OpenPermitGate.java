package co.kr.satu.imagegate.conversion.util;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only interruptible admission around open(), never around reads/decoding or returned stream lifetime. */
public final class OpenPermitGate {
    @FunctionalInterface public interface Opener {InputStream open(Path path) throws IOException;}
    public record Observation(long waitNanos,long nativeOpenNanos,long openStarted,boolean acquired,boolean returned) {}
    private final int capacity;
    private final Semaphore permits;
    private final AtomicInteger waiting=new AtomicInteger(),active=new AtomicInteger(),peak=new AtomicInteger();
    private final AtomicInteger acquired=new AtomicInteger(),released=new AtomicInteger();
    private final ThreadLocal<Observation> last=new ThreadLocal<>();
    public OpenPermitGate(int capacity){
        if(capacity<1||capacity>4)throw new IllegalArgumentException("Capacity must be between one and four");
        this.capacity=capacity;permits=new Semaphore(capacity,true);
    }
    public InputStream open(Path path) throws IOException{return open(path,p->Files.newInputStream(p,StandardOpenOption.READ));}
    public InputStream open(Path path,Opener opener) throws IOException {
        last.remove();PreviewCancellation.check();
        long started=System.nanoTime(),openStarted=0,wait=0;boolean held=false,inside=false,returned=false;
        try{
            waiting.incrementAndGet();
            try{permits.acquire();}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("Original open admission interrupted");}
            finally{waiting.decrementAndGet();}
            held=true;acquired.incrementAndGet();wait=System.nanoTime()-started;
            PreviewCancellation.check();
            peak.accumulateAndGet(active.incrementAndGet(),Math::max);inside=true;openStarted=System.nanoTime();
            var stream=opener.open(path);returned=true;return stream;
        }finally{
            long end=System.nanoTime();
            if(inside)active.decrementAndGet();
            if(held){released.incrementAndGet();permits.release();}
            last.set(new Observation(held?wait:end-started,openStarted==0?0:end-openStarted,openStarted,held,returned));
        }
    }
    public Observation takeObservation(){var value=last.get();last.remove();return value;}
    public int waiting(){return waiting.get();}
    public Map<String,Integer> stats(){
        return Map.of("capacity",capacity,"available",permits.availablePermits(),"waiting",waiting.get(),"active",active.get(),
                "peakActive",peak.get(),"acquired",acquired.get(),"released",released.get());
    }
}
