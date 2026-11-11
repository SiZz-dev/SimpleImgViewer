package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.util.OpenStackSampler;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Tag("original-open-context")
class OriginalOpenContextTests {
    record Cell(int delayMs,int workers) {}
    record Source(MediaRecord media,long registered) {}
    final ViewerPrincipal actor=new ViewerPrincipal("synthetic","owner");
    @Test @Timeout(240)
    void controlsRegistrationAgeAndConcurrencyAndSamplesOnlyActiveOpens() throws Exception {
        var helper=new OriginalStreamComparisonTests();
        Path base=helper.directory("storageDirectory","build/open-context");
        if(Files.getFileStore(base).getUsableSpace()<1073741824L)throw new IOException("At least one GiB of workspace free space required");
        Path storage=Files.createTempDirectory(base,"run-");
        Path output=helper.directory("reportDirectory","build/reports/open-context").resolve("latest.json");
        var report=new LinkedHashMap<String,Object>();var rows=new ArrayList<Map<String,Object>>();
        report.put("schemaVersion",2);report.put("createdAt",Instant.now().toString());report.put("storage",storage.toString());
        report.put("scope","Synthetic first application read; balanced cohort of two newly registered files; not OS cold cache or production P95");
        report.put("javaVersion",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("heapLimitBytes",Runtime.getRuntime().maxMemory());
        report.put("sourceEdge",2048);report.put("method","FILES");report.put("cohortFiles",2);
        String sampling=System.getProperty("imagegate.openContext.sampleStacks","true");
        if(!Set.of("true","false").contains(sampling))throw new IllegalArgumentException("sampleStacks must be true or false");
        boolean sampleStacks=Boolean.parseBoolean(sampling);report.put("sampleStacksEnabled",sampleStacks);
        var sampler=new OpenStackSampler(sampleStacks);boolean passed=false;
        try(var store=new LocalMediaStore(storage.resolve("originals"),32L*1024*1024)){
            byte[] bytes=helper.fixture();var allSources=new ArrayList<Source>();
            var flags=new ImageGateProperties.Features(
                    new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                    new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
            var policy=new FeaturePolicy(new ImageGateProperties(null,flags,null,null,null,null,null,null));
            var cells=List.of(new Cell(0,1),new Cell(3000,2),new Cell(3000,1),new Cell(0,2));
            for(int block=0;block<2;block++){
                var order=new ArrayList<>(cells);if(block==1)Collections.reverse(order);
                for(int position=0;position<order.size();position++){
                    var cell=order.get(position);var sources=new ArrayList<Source>();
                    for(int slot=0;slot<2;slot++){
                        var media=store.register(actor.tenantId(),actor.userId(),"synthetic.png",new ByteArrayInputStream(bytes));
                        sources.add(new Source(media,System.nanoTime()));
                    }
                    allSources.addAll(sources);
                    var pool=(ThreadPoolExecutor)Executors.newFixedThreadPool(cell.workers(),r->{
                        var thread=new Thread(r,"original-context-worker");thread.setDaemon(true);return thread;
                    });
                    pool.prestartAllCoreThreads();
                    try{
                        var selected=cell.workers()==2?sources:List.of(sources.get(block%2));
                        long deadline=sources.stream().mapToLong(Source::registered).max().orElseThrow()+TimeUnit.MILLISECONDS.toNanos(cell.delayMs());
                        rows.addAll(wave(store,policy,pool,sampler,selected,block,position,cell,"first",deadline));
                        rows.addAll(wave(store,policy,pool,sampler,selected,block,position,cell,"repeat",0));
                    }finally{
                        pool.shutdownNow();
                        if(!pool.awaitTermination(5,TimeUnit.SECONDS))throw new AssertionError("Open workers did not stop");
                    }
                    assertThat(sampler.activeCount()).isZero();
                }
            }
            assertThat(rows).hasSize(24);assertThat(allSources).hasSize(16);
            for(var source:allSources){
                Path path=store.resolveOriginal(source.media());
                var checked=OriginalStreamComparisonTests.verify(path,OriginalStreamComparisonTests.Method.FILES,
                        source.media().sizeBytes(),source.media().sha256());
                assertThat(checked.bytes()).isEqualTo(source.media().sizeBytes());
            }
            report.put("originalsVerified",allSources.size());
            assertThat(sampler.report().get("samplingErrors")).isEqualTo(0);
            assertThat((Integer)sampler.report().get("peakActiveOpens")).isBetween(1,2);
            passed=true;
        }catch(Exception|AssertionError failure){report.put("failureType",failure.getClass().getSimpleName());throw failure;}
        finally{
            sampler.close();report.put("passed",passed);report.put("samples",rows);report.put("stackSampling",sampler.report());
            Files.writeString(output,JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("Original open context: "+output+"; passed="+passed);
        }
    }
    List<Map<String,Object>> wave(LocalMediaStore store,FeaturePolicy policy,ThreadPoolExecutor pool,OpenStackSampler sampler,
            List<Source> selected,int block,int position,Cell cell,String kind,long deadline) throws Exception {
        var ready=new CountDownLatch(selected.size());var release=new CountDownLatch(1);
        var releasedAt=new java.util.concurrent.atomic.AtomicLong();
        var futures=new ArrayList<Future<Map<String,Object>>>();int sequence=0;
        for(var source:selected){
            String label="b"+block+"-p"+position+"-s"+sequence+++"-"+kind;
            futures.add(pool.submit(()->{
                assertThat(policy.isEnabled(FeaturePolicy.Feature.IMAGE_VIEW)).isTrue();
                assertThat(source.media().tenantId()).isEqualTo(actor.tenantId());assertThat(source.media().ownerId()).isEqualTo(actor.userId());
                Path path=store.resolveOriginal(source.media());
                var trace=sampler.marker(label);var age=new AtomicReference<Double>();
                var openedAt=new java.util.concurrent.atomic.AtomicLong();
                var marker=new OpenStackSampler.Marker(){
                    public void entering(){openedAt.set(System.nanoTime());age.set(OriginalStreamComparisonTests.ms(openedAt.get()-source.registered()));trace.entering();}
                    public void exited(){trace.exited();}
                };
                ready.countDown();
                if(!release.await(20,TimeUnit.SECONDS))throw new IOException("Context barrier timeout");
                var result=OriginalStreamComparisonTests.verify(path,OriginalStreamComparisonTests.Method.FILES,
                        source.media().sizeBytes(),source.media().sha256(),marker);
                assertThat(result.reads()).isEqualTo(result.updates()+1);
                assertThat(age.get()).isGreaterThanOrEqualTo((double)cell.delayMs());
                var row=new LinkedHashMap<String,Object>();
                row.put("label",label);row.put("block",block+1);row.put("position",position+1);
                row.put("delayMinimumMs",cell.delayMs());row.put("workers",cell.workers());row.put("kind",kind);
                row.put("actualAgeAtOpenMs",age.get());row.put("openAfterReleaseMs",OriginalStreamComparisonTests.ms(openedAt.get()-releasedAt.get()));
                row.put("timings",result);return row;
            }));
        }
        try{
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            long remaining=deadline-System.nanoTime();
            while(remaining>0){
                TimeUnit.NANOSECONDS.sleep(Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)));
                remaining=deadline-System.nanoTime();
            }
        }finally{releasedAt.set(System.nanoTime());release.countDown();}
        var result=new ArrayList<Map<String,Object>>();
        for(var future:futures)result.add(future.get(40,TimeUnit.SECONDS));
        return result;
    }
}
