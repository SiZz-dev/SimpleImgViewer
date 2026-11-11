package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.PreviewTiming;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

/** Test-only synthetic service workload: no HTTP/SSO bypass, native tools, real media or forced GC. */
@Tag("preview-stress")
class PreviewStressTests {
    final ViewerPrincipal actor=new ViewerPrincipal("synthetic","owner");
    final Preview profile=new Preview(true,2,2,512,512,4194304,"stress-v1");
    final List<Double> cancelTimes=new ArrayList<>(),retryTimes=new ArrayList<>();
    final List<Map<String,Object>> rows=new ArrayList<>();
    final Map<String,Object> report=new LinkedHashMap<>();
    final ThreadLocal<EnumMap<PreviewTiming.Stage,Long>> stageSamples=new ThreadLocal<>();
    final ThreadLocal<EnumMap<PreviewTiming.Stage,Integer>> stageCounts=new ThreadLocal<>();
    int setting(String key,int fallback,int min,int max){
        int value=Integer.parseInt(System.getProperty("imagegate.previewStress."+key,""+fallback));
        if(value<min||value>max)throw new IllegalArgumentException("Unsupported preview stress "+key);
        return value;
    }
    Path directory(String key,String fallback) throws IOException {
        Path build=Path.of("build").toAbsolutePath().normalize();
        Path path=Path.of(System.getProperty("imagegate.previewStress."+key,fallback)).toAbsolutePath().normalize();
        if(!path.startsWith(build)||path.equals(build))throw new IOException("Stress artifacts must stay below build");
        Files.createDirectories(path);
        if(!path.toRealPath().equals(path))throw new IOException("Canonical stress directory required");
        return path;
    }
    @Test @Timeout(240)
    void repeatedCancellationRetryAndConcurrentRendering() throws Exception {
        int rounds=setting("rounds",4,2,12),edge=setting("sourceEdge",1024,256,2048);
        Path storage=Files.createTempDirectory(directory("storageDirectory","build/preview-stress"),"run-");
        Path output=directory("reportDirectory","build/reports/preview-stress").resolve("latest.json");
        report.put("schemaVersion",3);report.put("createdAt",Instant.now().toString());
        report.put("scope","Synthetic JDK PNG service/queue workload; not HTTP, production P95, RSS or native memory");
        report.put("storage",storage.toString());report.put("rounds",rounds);report.put("sourceEdge",edge);
        report.put("javaVersion",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("stageTimingEnabled",true);report.put("hashReadBlockBytes",32768);
        report.put("workers",2);report.put("queueCapacity",2);report.put("itemsPerRound",4);
        report.put("heapLimitBytes",Runtime.getRuntime().maxMemory());report.put("forcedGc",false);
        report.put("memorySampleIntervalMs",20);report.put("samplingIncludesFixtureCreation",true);
        var monitor=new Monitor();boolean passed=false;
        try(var store=new LocalMediaStore(storage.resolve("originals"),32L*1024*1024)){
            var media=sources(store,edge);
            var flags=new ImageGateProperties.Features(
                    new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                    new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
            // Only the immutable feature flags are consumed by this real policy; no server/configuration is started.
            var features=new FeaturePolicy(new ImageGateProperties(null,flags,null,profile,null,null,null,null));
            var renderer=new ImagePreviewRenderer(store,(a,m,action)->a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId()),
                    features,profile,null,(stage,nanos)->{
                        var sample=stageSamples.get();
                        if(sample!=null){sample.merge(stage,nanos,Long::sum);stageCounts.get().merge(stage,1,Integer::sum);}
                    });
            report.put("workloadInitialHeapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            Path converted=storage.resolve("converted");
            for(int round=0;round<rounds;round++){
                String engine="stress-round-"+round;
                boolean before=(round%2==0);
                var cancelled=new Phase(renderer,before?1:2);
                long started=System.nanoTime();
                try(var queue=new PreviewQueue(profile,engine,new PreviewCache(converted),cancelled)){
                    admit(queue,media,cancelled);
                    await(queue,media.subList(0,4),"failed");
                    for(var item:media.subList(0,4))assertThat(queue.status(item,actor,1).errorCode()).isEqualTo("INTERRUPTED");
                }finally{cancelled.release.countDown();}
                double cancelBatch=millis(System.nanoTime()-started);
                assertThat(cancelled.calls).hasValue(4);assertThat(cancelled.active).hasValue(0);
                assertThat(cancelled.maxActive).hasValue(2);assertThat(cancelled.preservedFlags).hasValue(4);
                var cancelledAudit=audit(converted,round*4,4);
                cancelTimes.addAll(cancelled.times);
                var retry=new Phase(renderer,0);started=System.nanoTime();
                try(var queue=new PreviewQueue(profile,engine,new PreviewCache(converted),retry)){
                    for(var item:media.subList(0,4))assertThat(queue.submit(item,actor,1,false).state()).isEqualTo("failed");
                    assertThat(retry.calls).hasValue(0);
                    admit(queue,media,retry);
                    await(queue,media.subList(0,4),"ready");
                    for(var item:media.subList(0,4)){
                        String key=queue.status(item,actor,1).sourceId();
                        queue.content(item,actor,1,key,path->verifyPng(path,Math.min(edge,512)));
                        queue.content(item,actor,1,key,true,path->verifyPng(path,Math.min(edge,256)));
                    }
                }finally{retry.release.countDown();}
                double retryBatch=millis(System.nanoTime()-started);
                assertThat(retry.calls).hasValue(4);assertThat(retry.active).hasValue(0);assertThat(retry.maxActive).hasValue(2);
                retryTimes.addAll(retry.times);
                var finished=audit(converted,(round+1)*4,0);
                rows.add(Map.of("round",round+1,"cancellationPoint",before?"before-render":"after-render-before-publication",
                        "cancelBatchMs",cancelBatch,"retryBatchMs",retryBatch,
                        "cancelRendererMs",summary(cancelled.times),"retryRendererMs",summary(retry.times),
                        "cancelledCache",cancelledAudit,"finishedCache",finished,
                        "quiescentHeapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
                var row=new LinkedHashMap<>(rows.removeLast());
                row.put("cancelStageSamples",cancelled.details);row.put("retryStageSamples",retry.details);rows.add(row);
            }
            for(var item:media)try(var in=Files.newInputStream(store.resolveOriginal(item))){
                var digest=PreviewKeys.digest();byte[] block=new byte[32768];int n;
                while((n=in.read(block))!=-1)digest.update(block,0,n);
                assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(item.sha256());
            }
            report.put("originalsUnchanged",true);passed=true;
        }catch(Exception|AssertionError failure){report.put("failureType",failure.getClass().getSimpleName());throw failure;}
        finally{
            monitor.close();report.put("passed",passed);report.put("roundResults",rows);
            report.put("cancelRendererMs",summary(cancelTimes));report.put("retryRendererMs",summary(retryTimes));
            report.put("memory",monitor.result());
            Files.writeString(output,JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("Preview stress report: "+output+"; passed="+passed);
        }
    }
    List<MediaRecord> sources(LocalMediaStore store,int edge) throws Exception {
        var image=new BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB);var bytes=new ByteArrayOutputStream();
        try{
            var random=new Random(20261026);int[] row=new int[edge];
            for(int y=0;y<edge;y++){
                for(int x=0;x<edge;x++)row[x]=0x80000000|random.nextInt(0x1000000);
                image.setRGB(0,y,edge,1,row,0,edge);
            }
            assertThat(ImageIO.write(image,"png",bytes)).isTrue();
        }finally{image.flush();}
        byte[] png=bytes.toByteArray();var media=new ArrayList<MediaRecord>();
        for(int i=0;i<5;i++)media.add(store.register(actor.tenantId(),actor.userId(),"synthetic-"+i+".png",new ByteArrayInputStream(png)));
        return media;
    }
    final class Phase implements PreviewRenderer {
        final PreviewRenderer delegate;final int cancellation;
        final CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);
        final AtomicInteger calls=new AtomicInteger(),active=new AtomicInteger(),maxActive=new AtomicInteger(),preservedFlags=new AtomicInteger();
        final List<Double> times=Collections.synchronizedList(new ArrayList<>());
        final List<Map<String,Object>> details=Collections.synchronizedList(new ArrayList<>());
        Phase(PreviewRenderer delegate,int cancellation){this.delegate=delegate;this.cancellation=cancellation;}
        @Override public Result render(MediaRecord media,ViewerPrincipal actor,int page,Path work) throws IOException {
            calls.incrementAndGet();maxActive.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();
            long started=0,cpuStarted=-1;
            try{
                try{if(!release.await(20,TimeUnit.SECONDS))throw new IOException("Stress admission barrier timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Stress barrier interrupted");}
                stageSamples.set(new EnumMap<>(PreviewTiming.Stage.class));stageCounts.set(new EnumMap<>(PreviewTiming.Stage.class));cpuStarted=cpuTime();
                started=System.nanoTime();
                if(cancellation==1)Thread.currentThread().interrupt();
                var result=delegate.render(media,actor,page,work);
                if(cancellation==2)Thread.currentThread().interrupt();
                return result;
            }finally{
                if(started!=0){
                    double elapsed=millis(System.nanoTime()-started);long cpuEnd=cpuTime();times.add(elapsed);
                    var stages=new LinkedHashMap<String,Double>();var hash=new LinkedHashMap<String,Double>();
                    var counts=new LinkedHashMap<String,Integer>();long total=0,hashTotal=0;
                    for(var entry:stageSamples.get().entrySet()){
                        if(entry.getKey().hashDetail()){
                            hash.put(entry.getKey().name(),millis(entry.getValue()));hashTotal+=entry.getValue();
                            counts.put(entry.getKey().name(),stageCounts.get().get(entry.getKey()));
                        }else{stages.put(entry.getKey().name(),millis(entry.getValue()));total+=entry.getValue();}
                    }
                    var detail=new LinkedHashMap<String,Object>();detail.put("elapsedMs",elapsed);detail.put("stagesMs",stages);
                    detail.put("unattributedMs",Math.max(0,elapsed-millis(total)));
                    detail.put("hashDetailsMs",hash);detail.put("hashDetailCounts",counts);
                    detail.put("hashUnattributedMs",Math.max(0,stages.getOrDefault("ORIGINAL_HASH",0.0)-millis(hashTotal)));
                    detail.put("threadCpuMs",cpuStarted<0||cpuEnd<0?-1:millis(cpuEnd-cpuStarted));
                    detail.put("interrupted",Thread.currentThread().isInterrupted());details.add(detail);
                }
                stageSamples.remove();stageCounts.remove();
                if(Thread.currentThread().isInterrupted())preservedFlags.incrementAndGet();
                active.decrementAndGet();
            }
        }
    }
    void admit(PreviewQueue queue,List<MediaRecord> items,Phase phase) throws Exception {
        try{
            for(var item:items.subList(0,4))queue.submit(item,actor,1,true);
            assertThat(phase.entered.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(phase.calls).hasValue(2);
            queue.submit(items.getFirst(),actor,1,true);
            assertThat(phase.calls).hasValue(2);
            assertThatThrownBy(()->queue.submit(items.get(4),actor,1,false)).isInstanceOfSatisfying(MediaException.class,
                    e->assertThat(e.code()).isEqualTo(MediaException.Code.PREVIEW_QUEUE_FULL));
            assertThat(queue.status(items.get(4),actor,1).state()).isEqualTo("not_requested");
        }finally{phase.release.countDown();}
    }
    void await(PreviewQueue queue,List<MediaRecord> items,String expected) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while(System.nanoTime()<end){
            boolean done=true;
            for(var item:items){
                var status=queue.status(item,actor,1);
                if(expected.equals("ready")&&status.state().equals("failed"))throw new AssertionError("Retry failed: "+status.errorCode());
                done&=status.state().equals(expected);
            }
            if(done)return;Thread.sleep(5);
        }
        throw new AssertionError("Stress jobs did not reach "+expected);
    }
    Map<String,Object> audit(Path converted,int expectedReady,int expectedFailed) throws Exception {
        int entries=0,ready=0,failed=0,png=0,work=0,temp=0,unexpected=0;long bytes=0;
        try(var cache=new PreviewCache(converted)){
            cache.claimWriter(); // Reacquisition proves every phase relinquished its writer lock.
            try(var directories=Files.newDirectoryStream(cache.root())){
                for(var entry:directories){
                    if(entry.getFileName().toString().equals("worker.lock"))continue;
                    assertThat(entry.getFileName().toString()).matches("[a-f0-9]{64}");
                    assertThat(Files.isDirectory(entry,LinkOption.NOFOLLOW_LINKS)).isTrue();entries++;
                    var state=cache.read(entry.getFileName().toString(),1);
                    if(state.ready())ready++;else if(state.state().equals("failed"))failed++;else unexpected++;
                    try(var files=Files.newDirectoryStream(entry)){
                        for(var file:files){
                            String name=file.getFileName().toString();
                            if(name.startsWith("work-"))work++;
                            else if(name.endsWith(".tmp"))temp++;
                            else if(name.matches("result-[a-f0-9-]{36}[.]png")){
                                assertThat(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)).isTrue();png++;bytes+=Files.size(file);
                            }else if(!name.equals("state.properties"))unexpected++;
                        }
                    }
                }
            }
        }
        assertThat(entries).isEqualTo(expectedReady+expectedFailed);assertThat(ready).isEqualTo(expectedReady);
        assertThat(failed).isEqualTo(expectedFailed);assertThat(png).isEqualTo(expectedReady*2);
        assertThat(work).isZero();assertThat(temp).isZero();assertThat(unexpected).isZero();
        return Map.of("ready",ready,"failed",failed,"pngArtifacts",png,"artifactBytes",bytes,
                "workDirectories",work,"temporaryFiles",temp,"unexpected",unexpected,"writerReacquired",true);
    }
    void verifyPng(Path path,int edge) throws IOException {
        var image=ImageIO.read(path.toFile());assertThat(image).isNotNull();
        try{assertThat(image.getWidth()).isEqualTo(edge);assertThat(image.getHeight()).isEqualTo(edge);}
        finally{image.flush();}
    }
    static long cpuTime(){
        var bean=ManagementFactory.getThreadMXBean();
        return bean.isCurrentThreadCpuTimeSupported()&&bean.isThreadCpuTimeEnabled()?bean.getCurrentThreadCpuTime():-1;
    }
    static double millis(long nanos){return nanos/1_000_000.0;}
    static Map<String,Object> summary(List<Double> samples){
        if(samples.isEmpty())return Map.of("count",0);
        var sorted=new ArrayList<>(samples);Collections.sort(sorted);
        return Map.of("count",sorted.size(),"min",sorted.getFirst(),"p50",sorted.get((int)Math.ceil(sorted.size()*.5)-1),
                "p95",sorted.get((int)Math.ceil(sorted.size()*.95)-1),"max",sorted.getLast());
    }
    static final class Monitor implements AutoCloseable {
        final MemoryMXBean memory=ManagementFactory.getMemoryMXBean();
        final AtomicLong heapPeak=new AtomicLong(),nonHeapPeak=new AtomicLong();
        final AtomicInteger samples=new AtomicInteger();
        final long initialGcCount=gc(false),initialGcMillis=gc(true);
        final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{
            var thread=new Thread(r,"preview-stress-memory");thread.setDaemon(true);return thread;});
        Monitor(){sample();sampler.scheduleAtFixedRate(this::sample,20,20,TimeUnit.MILLISECONDS);}
        void sample(){
            heapPeak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max);
            nonHeapPeak.accumulateAndGet(memory.getNonHeapMemoryUsage().getUsed(),Math::max);samples.incrementAndGet();
        }
        @Override public void close() throws InterruptedException {
            sampler.shutdown();if(!sampler.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Memory sampler did not stop");sample();
        }
        Map<String,Object> result(){return Map.of("samples",samples.get(),"sampledHeapPeakBytes",heapPeak.get(),
                "sampledJvmNonHeapPeakBytes",nonHeapPeak.get(),"finalHeapUsedBytes",memory.getHeapMemoryUsage().getUsed(),
                "gcCountDelta",gc(false)-initialGcCount,"gcTimeMillisDelta",gc(true)-initialGcMillis,
                "interpretation","Sampled JVM metrics without forced GC; not a leak proof, RSS or OS/native memory measurement");}
        static long gc(boolean time){return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean->time?bean.getCollectionTime():bean.getCollectionCount()).filter(value->value>=0).sum();}
    }
}
