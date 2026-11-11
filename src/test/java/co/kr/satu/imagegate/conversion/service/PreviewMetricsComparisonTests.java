package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

@Tag("preview-metrics-comparison")
class PreviewMetricsComparisonTests {
    static final class Registry extends SimpleMeterRegistry implements AutoCloseable {}
    record Source(MediaRecord media,long registered) {}
    record Pixels(String preview,String thumbnail) {}
    final ViewerPrincipal actor=new ViewerPrincipal("synthetic","owner");
    Pixels expected;
    static double ms(long nanos){return nanos/1000000.0;}
    final class Cohort implements AutoCloseable {
        final boolean enabled;final Registry registry=new Registry();final PreviewMetrics metrics;
        final Preview profile;final FeaturePolicy policy;final List<Source> sources=new ArrayList<>();int batches;
        Cohort(boolean enabled,LocalMediaStore store,byte[] png)throws Exception {
            this.enabled=enabled;metrics=enabled?new PreviewMetrics(registry):PreviewMetrics.NONE;
            profile=new Preview(true,2,2,512,512,4194304,"metrics-v1",ImageGateProperties.PreviewCachePolicy.defaults(),true,256,
                    new ImageGateProperties.OriginalOpenPolicy(true,1,Duration.ofSeconds(10)),enabled);
            var flags=new ImageGateProperties.Features(new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                    new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
            policy=new FeaturePolicy(new ImageGateProperties(null,flags,null,profile,null,null,null,null));
            for(int slot=0;slot<2;slot++)sources.add(new Source(store.register(actor.tenantId(),actor.userId(),"synthetic.png",new ByteArrayInputStream(png)),System.nanoTime()));
        }
        Map<String,Object> meters(){
            if(!enabled){assertThat(registry.getMeters()).isEmpty();return Map.of("meters",0,"expectedJobs",batches*2);}
            assertThat(registry.getMeters()).hasSize(34);long jobs=batches*2L;
            assertThat(registry.get("imagegate.preview.jobs.accepted").counter().count()).isEqualTo(jobs);
            assertThat(registry.get("imagegate.preview.jobs.completed").tag("outcome","ready").counter().count()).isEqualTo(jobs);
            assertThat(registry.get("imagegate.preview.jobs.duration").tag("outcome","ready").timer().count()).isEqualTo(jobs);
            assertThat(registry.get("imagegate.preview.queue.wait").tag("result","started").timer().count()).isEqualTo(jobs);
            assertThat(registry.get("imagegate.preview.original.wait").tag("result","admitted").timer().count()).isEqualTo(jobs);
            assertThat(registry.get("imagegate.preview.original.open").tag("result","opened").timer().count()).isEqualTo(jobs);
            for(var meter:registry.getMeters()){
                for(var tag:meter.getId().getTags())assertThat(tag.getKey()).isIn("outcome","result","reason");
                if(meter instanceof Gauge g)assertThat(g.value()).isZero();
                if(meter instanceof Counter c&&!meter.getId().getName().equals("imagegate.preview.jobs.accepted")
                        &&!"ready".equals(meter.getId().getTag("outcome")))assertThat(c.count()).isZero();
                if(meter instanceof io.micrometer.core.instrument.Timer t&&!("ready".equals(meter.getId().getTag("outcome"))
                        ||List.of("started","admitted","opened").contains(meter.getId().getTag("result")==null?"":meter.getId().getTag("result"))))assertThat(t.count()).isZero();
            }
            return Map.of("meters",32,"expectedJobs",jobs,"completed",jobs,"gaugesZero",true,
                    "jobTotalMs",registry.get("imagegate.preview.jobs.duration").tag("outcome","ready").timer().totalTime(TimeUnit.MILLISECONDS),
                    "queueWaitMs",registry.get("imagegate.preview.queue.wait").tag("result","started").timer().totalTime(TimeUnit.MILLISECONDS),
                    "admissionWaitMs",registry.get("imagegate.preview.original.wait").tag("result","admitted").timer().totalTime(TimeUnit.MILLISECONDS),
                    "nativeOpenMs",registry.get("imagegate.preview.original.open").tag("result","opened").timer().totalTime(TimeUnit.MILLISECONDS));
        }
        @Override public void close(){registry.close();}
    }
    @Test @Timeout(1200)
    void alternatesMetricsWithIdenticalPixelsAndRepeatedConcurrentLifecycle()throws Exception {
        String first=System.getProperty("imagegate.metricsComparison.first","off");
        if(!List.of("off","on").contains(first))throw new IllegalArgumentException("first must be off/on");
        int rounds=Integer.parseInt(System.getProperty("imagegate.metricsComparison.rounds","20"));
        if(rounds<5||rounds>100)throw new IllegalArgumentException("rounds must be between five and one hundred");
        var helper=new OriginalStreamComparisonTests();Path base=helper.directory("storageDirectory","build/metrics-comparison");
        if(Files.getFileStore(base).getUsableSpace()<1073741824L+rounds*16777216L)throw new IOException("Insufficient synthetic benchmark space");
        Path storage=Files.createTempDirectory(base,"run-");Path output=helper.directory("reportDirectory","build/reports/metrics-comparison").resolve("latest.json");
        var report=new LinkedHashMap<String,Object>();var batches=new ArrayList<Map<String,Object>>();var originals=new ArrayList<Source>();
        long began=System.nanoTime();boolean passed=false;
        report.put("schemaVersion",2);report.put("createdAt",Instant.now().toString());report.put("first",first);report.put("roundsPerModePerBlock",rounds);
        report.put("storage",storage.toString());report.put("javaVersion",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));
        report.put("heapLimitBytes",Runtime.getRuntime().maxMemory());report.put("scope","Synthetic two-worker server readiness, metrics on/off, admission limit one; bounded repetition, not production soak/P95");
        var before=memory();report.put("memoryBefore",before);
        try(var store=new LocalMediaStore(storage.resolve("originals"),32L*1024*1024)){
            byte[] png=helper.fixture();
            for(int block=0;block<2;block++){
                boolean firstOn=(block==0)==first.equals("on");
                try(var a=new Cohort(firstOn,store,png);var b=new Cohort(!firstOn,store,png)){
                    originals.addAll(a.sources);originals.addAll(b.sources);
                    batches.add(batch(store,a,storage,block,0));batches.add(batch(store,b,storage,block,0));
                    for(int round=1;round<=rounds;round++){
                        var ordered=round%2==1?List.of(b,a):List.of(a,b);
                        for(var cohort:ordered)batches.add(batch(store,cohort,storage,block,round));
                        if(round%10==0)System.out.println("Metrics comparison block "+(block+1)+", round "+round+"/"+rounds);
                    }
                }
                System.out.println("Metrics comparison block "+(block+1)+" complete; batches="+batches.size());
            }
            for(var source:originals)OriginalStreamComparisonTests.verify(store.resolveOriginal(source.media()),OriginalStreamComparisonTests.Method.FILES,source.media().sizeBytes(),source.media().sha256());
            assertThat(originals).hasSize(8);assertThat(batches).hasSize(4*(rounds+1));report.put("originalsVerified",8);passed=true;
        }catch(Exception|AssertionError failure){report.put("failureType",failure.getClass().getSimpleName());throw failure;}
        finally{
            report.put("passed",passed);report.put("batches",batches);report.put("elapsedMs",ms(System.nanoTime()-began));report.put("memoryAfter",memory());
            report.put("pixels",expected);Files.writeString(output,JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("Metrics comparison: "+output+"; passed="+passed);
        }
    }
    Map<String,Object> batch(LocalMediaStore store,Cohort cohort,Path storage,int block,int round)throws Exception {
        if(Files.getFileStore(storage).getUsableSpace()<268435456L)throw new IOException("Synthetic free-space reserve");
        Path converted=storage.resolve("b"+block+"-"+(cohort.enabled?"on":"off")+"-r"+round);
        var entered=new CountDownLatch(2);var go=new CountDownLatch(1);
        var real=new ImagePreviewRenderer(cohort.metrics,store,(a,m,action)->a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId()),cohort.policy,cohort.profile,null);
        PreviewRenderer renderer=new PreviewRenderer(){
            public Result render(MediaRecord m,ViewerPrincipal a,int p,Path w)throws IOException {
                entered.countDown();try{if(!go.await(10,TimeUnit.SECONDS))throw new IOException("Barrier timeout");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
                return real.render(m,a,p,w);
            }
            public void close(){real.close();}
        };
        long begin=System.nanoTime(),released=0;double first=-1,all=-1;
        var ages=new ArrayList<Double>();for(var source:cohort.sources)ages.add(ms(begin-source.registered()));
        try(var queue=new PreviewQueue(cohort.profile,"metrics-comparison",new PreviewCache(converted),renderer,false,cohort.metrics)){
            try{for(var source:cohort.sources)queue.submit(source.media(),actor,1,false);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();}
            finally{released=System.nanoTime();go.countDown();}
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(System.nanoTime()<end){
                int ready=0;for(var source:cohort.sources){var state=queue.status(source.media(),actor,1);assertThat(state.state()).isNotEqualTo("failed");if(state.ready())ready++;}
                double elapsed=ms(System.nanoTime()-released);if(ready>0&&first<0)first=elapsed;if(ready==2){all=elapsed;break;}Thread.sleep(5);
            }
            assertThat(first).isNotNegative();assertThat(all).isGreaterThanOrEqualTo(first);
            for(var source:cohort.sources){
                String key=queue.status(source.media(),actor,1).sourceId();String[] hashes=new String[2];
                queue.content(source.media(),actor,1,key,path->hashes[0]=png(path,512));
                queue.content(source.media(),actor,1,key,true,path->hashes[1]=png(path,256));
                var pixels=new Pixels(hashes[0],hashes[1]);if(expected==null)expected=pixels;assertThat(pixels).isEqualTo(expected);
            }
        }finally{go.countDown();real.close();}
        cohort.batches++;var meters=cohort.meters();verifyCache(converted);
        var row=new LinkedHashMap<String,Object>(Map.of("block",block+1,"round",round,"mode",cohort.enabled?"on":"off","kind",round==0?"first":"repeat",
                "firstReadyMs",first,"allReadyMs",all,"withVerificationMs",ms(System.nanoTime()-begin),"sourceAgeMs",ages,"meters",meters,"heapUsedBytes",memory().get("heapUsedBytes")));
        row.put("admissionMs",ms(released-begin));
        row.put("firstFromAdmissionMs",ms(released-begin)+first);
        row.put("allFromAdmissionMs",ms(released-begin)+all);
        return row;
    }
    String png(Path path,int edge)throws IOException {
        var image=ImageIO.read(path.toFile());assertThat(image).isNotNull();try{assertThat(image.getWidth()).isEqualTo(edge);assertThat(image.getHeight()).isEqualTo(edge);}finally{image.flush();}
        return PreviewKeys.sha256(Files.readAllBytes(path));
    }
    void verifyCache(Path path)throws IOException {
        try(var cache=new PreviewCache(path)){
            cache.claimWriter();int entries=0,png=0;
            try(var directories=Files.newDirectoryStream(cache.root())){for(var entry:directories){
                if(entry.getFileName().toString().equals("worker.lock"))continue;assertThat(entry.getFileName().toString()).matches("[a-f0-9]{64}");entries++;
                assertThat(cache.read(entry.getFileName().toString(),1).ready()).isTrue();
                try(var files=Files.newDirectoryStream(entry)){for(var file:files){String name=file.getFileName().toString();if(name.matches("result-[a-f0-9-]{36}[.]png"))png++;else assertThat(name).isEqualTo("state.properties");}}
            }}assertThat(entries).isEqualTo(2);assertThat(png).isEqualTo(4);
        }
    }
    Map<String,Long> memory(){
        long collections=0,time=0;for(var gc:ManagementFactory.getGarbageCollectorMXBeans()){if(gc.getCollectionCount()>=0)collections+=gc.getCollectionCount();if(gc.getCollectionTime()>=0)time+=gc.getCollectionTime();}
        return Map.of("heapUsedBytes",ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),"gcCollections",collections,"gcTimeMs",time);
    }
}
