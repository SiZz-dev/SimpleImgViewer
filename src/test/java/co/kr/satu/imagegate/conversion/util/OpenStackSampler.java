package co.kr.satu.imagegate.conversion.util;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded test-only sampling of explicitly registered worker threads during original open. */
public final class OpenStackSampler implements AutoCloseable {
    public interface Marker {void entering();void exited();}
    private record Active(Thread thread,String label) {}
    private final boolean enabled;
    private final ConcurrentMap<Long,Active> active=new ConcurrentHashMap<>();
    private final Set<String> labels=new HashSet<>();
    private final Map<String,Map<String,Integer>> counts=new LinkedHashMap<>();
    private final AtomicInteger dropped=new AtomicInteger(),errors=new AtomicInteger(),peakActive=new AtomicInteger();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor(task->{
        var thread=new Thread(task,"original-open-sampler");thread.setDaemon(true);return thread;
    });
    public OpenStackSampler(){this(true);}
    public OpenStackSampler(boolean enabled){
        this.enabled=enabled;
        if(enabled)worker.scheduleWithFixedDelay(this::sample,20,20,TimeUnit.MILLISECONDS);
    }
    public synchronized Marker marker(String label){
        if(label==null||!label.matches("[a-z0-9-]{1,64}")||labels.size()>=64||!labels.add(label))
            throw new IllegalArgumentException("Invalid or duplicate sample label");
        return new Marker(){
            private Active registration;
            public void entering(){
                if(registration!=null)throw new IllegalStateException("Marker already used");
                var thread=Thread.currentThread();registration=new Active(thread,label);
                if(active.putIfAbsent(thread.threadId(),registration)!=null)throw new IllegalStateException("Nested open marker");
                peakActive.accumulateAndGet(active.size(),Math::max);
            }
            public void exited(){if(registration!=null)active.remove(registration.thread().threadId(),registration);}
        };
    }
    private void sample(){
        try{sampleActive();}catch(RuntimeException failure){errors.incrementAndGet();}
    }
    private void sampleActive(){
        for(var registration:active.values()){
            if(active.get(registration.thread().threadId())!=registration)continue;
            var trace=registration.thread().getStackTrace();
            var state=registration.thread().getState();
            if(active.get(registration.thread().threadId())!=registration)continue;
            var frames=Arrays.stream(trace).limit(12).map(frame->frame.getClassName()+"."+frame.getMethodName()
                    +(frame.isNativeMethod()?" [native]":"")).toList();
            record(registration.label(),state.name(),frames);
        }
    }
    // Package-visible to test the bounded aggregation without depending on a machine's native I/O speed.
    synchronized void record(String label,String state,List<String> frames){
        if(!labels.contains(label))throw new IllegalArgumentException("Unregistered sample label");
        frames=frames.stream().limit(12).toList();
        var bucket=counts.computeIfAbsent(label,key->new LinkedHashMap<>());
        String signature=state+"|"+String.join(" > ",frames);
        if(!bucket.containsKey(signature)&&bucket.size()>=16){dropped.incrementAndGet();return;}
        bucket.merge(signature,1,Integer::sum);
    }
    public int activeCount(){return active.size();}
    public synchronized Map<String,Object> report(){
        var snapshots=new LinkedHashMap<String,Object>();
        for(var label:labels){
            var bucket=counts.getOrDefault(label,Map.of());var rows=new ArrayList<Map<String,Object>>();
            for(var entry:bucket.entrySet()){
                int separator=entry.getKey().indexOf('|');
                rows.add(Map.of("state",entry.getKey().substring(0,separator),"frames",
                        List.of(entry.getKey().substring(separator+1).split(" > ")),"samples",entry.getValue()));
            }
            rows.sort(Comparator.comparingInt(row->-((Integer)row.get("samples"))));
            snapshots.put(label,rows);
        }
        return Map.of("enabled",enabled,"intervalMs",20,"maxFrames",12,"maxStackKindsPerLabel",16,"droppedSamples",dropped.get(),
                "activeAtReport",activeCount(),"peakActiveOpens",peakActive.get(),"samplingErrors",errors.get(),"labels",snapshots);
    }
    @Override public void close() throws InterruptedException {
        worker.shutdownNow();
        if(!worker.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Stack sampler did not stop");
    }
}
