package co.kr.satu.imagegate.conversion.service;

import io.micrometer.core.instrument.*;
import java.util.EnumMap;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-cardinality process-local metrics. No media, actor, path or exception values are accepted. */
public final class PreviewMetrics {
    public enum Outcome {READY,FAILED,INTERRUPTED,OPEN_BUSY,DECODER_UNAVAILABLE,CONVERSION_BUSY,CONVERSION_FAILED,QUEUE_FULL}
    public enum Rejection {CLOSING,CAPACITY,EXECUTOR}
    public enum Wait {ADMITTED,TIMEOUT,INTERRUPTED,CLOSED,FAILED}
    public enum Native {OPENED,FAILED,INTERRUPTED}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PreviewMetrics.class);
    private static final JobSample NO_JOB=new JobSample(null,0);
    public static final PreviewMetrics NONE=new PreviewMetrics();
    private final boolean enabled;
    private final EnumMap<Outcome,Counter> completed=new EnumMap<>(Outcome.class);
    private final EnumMap<Outcome,Timer> duration=new EnumMap<>(Outcome.class);
    private final EnumMap<Rejection,Counter> rejected=new EnumMap<>(Rejection.class);
    private final EnumMap<Wait,Timer> wait=new EnumMap<>(Wait.class);
    private final EnumMap<Native,Timer> nativeOpen=new EnumMap<>(Native.class);
    private final AtomicInteger queued=new AtomicInteger(),running=new AtomicInteger(),opening=new AtomicInteger(),waitingForOpen=new AtomicInteger();
    private final AtomicBoolean warned=new AtomicBoolean();
    private Counter accepted;
    private Timer queueStarted,queueCancelled;
    private PreviewMetrics(){enabled=false;}
    public PreviewMetrics(MeterRegistry registry){
        java.util.Objects.requireNonNull(registry);enabled=true;
        accepted=registry.counter("imagegate.preview.jobs.accepted");
        for(var outcome:Outcome.values()){
            completed.put(outcome,registry.counter("imagegate.preview.jobs.completed","outcome",label(outcome)));
            duration.put(outcome,registry.timer("imagegate.preview.jobs.duration","outcome",label(outcome)));
        }
        for(var reason:Rejection.values())rejected.put(reason,registry.counter("imagegate.preview.jobs.rejected","reason",label(reason)));
        for(var result:Wait.values())wait.put(result,registry.timer("imagegate.preview.original.wait","result",label(result)));
        for(var result:Native.values())nativeOpen.put(result,registry.timer("imagegate.preview.original.open","result",label(result)));
        queueStarted=registry.timer("imagegate.preview.queue.wait","result","started");
        queueCancelled=registry.timer("imagegate.preview.queue.wait","result","cancelled");
        registry.gauge("imagegate.preview.queue.waiting",queued);
        registry.gauge("imagegate.preview.jobs.running",running);
        registry.gauge("imagegate.preview.original.active",opening);
        registry.gauge("imagegate.preview.original.waiting",waitingForOpen);
    }
    private static String label(Enum<?> value){return value.name().toLowerCase(Locale.ROOT);}
    private void record(Runnable action){
        try{action.run();}catch(RuntimeException failure){
            if(warned.compareAndSet(false,true))LOG.warn("Preview metrics recording failed; inspect the private metrics configuration");
        }
    }
    public JobSample accepted(){
        if(!enabled)return NO_JOB;
        long start=System.nanoTime();queued.incrementAndGet();record(accepted::increment);return new JobSample(this,start);
    }
    public void rejected(Rejection reason){if(enabled)record(()->rejected.get(reason).increment());}
    long clock(){return enabled?System.nanoTime():0;}
    long elapsed(long start){return enabled?Math.max(0,System.nanoTime()-start):0;}
    void waitStarted(){if(enabled)waitingForOpen.incrementAndGet();}
    void waitLeft(){if(enabled)waitingForOpen.decrementAndGet();}
    long nativeStarted(){if(!enabled)return 0;opening.incrementAndGet();return System.nanoTime();}
    void nativeFinished(long nanos,Native result){
        if(!enabled)return;opening.decrementAndGet();record(()->nativeOpen.get(result).record(nanos,TimeUnit.NANOSECONDS));
    }
    void waitFinished(long nanos,Wait result){if(enabled)record(()->wait.get(result).record(nanos,TimeUnit.NANOSECONDS));}
    public static Outcome outcome(String code){
        return switch(code){
            case "INTERRUPTED"->Outcome.INTERRUPTED;
            case "ORIGINAL_OPEN_BUSY"->Outcome.OPEN_BUSY;
            case "CONVERSION_BUSY"->Outcome.CONVERSION_BUSY;
            case "DECODER_UNAVAILABLE"->Outcome.DECODER_UNAVAILABLE;
            case "QUEUE_FULL"->Outcome.QUEUE_FULL;
            default->code.startsWith("CONVERSION_")?Outcome.CONVERSION_FAILED:Outcome.FAILED;
        };
    }
    /** Owned by a queue Job's existing single-claim lifecycle, never by HTTP request state. */
    public static final class JobSample {
        private final PreviewMetrics owner;private final long acceptedAt;
        private boolean started;
        private JobSample(PreviewMetrics owner,long acceptedAt){this.owner=owner;this.acceptedAt=acceptedAt;}
        public void start(){
            if(owner==null)return;started=true;long nanos=owner.elapsed(acceptedAt);
            owner.queued.decrementAndGet();owner.running.incrementAndGet();
            owner.record(()->owner.queueStarted.record(nanos,TimeUnit.NANOSECONDS));
        }
        public void finish(Outcome outcome){
            if(owner==null)return;long nanos=owner.elapsed(acceptedAt);
            if(started)owner.running.decrementAndGet();else{
                owner.queued.decrementAndGet();owner.record(()->owner.queueCancelled.record(nanos,TimeUnit.NANOSECONDS));
            }
            owner.record(()->owner.completed.get(outcome).increment());
            owner.record(()->owner.duration.get(outcome).record(nanos,TimeUnit.NANOSECONDS));
        }
    }
}
