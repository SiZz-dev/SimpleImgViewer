package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.util.OpenPermitGate;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Tag("original-open-admission")
class OriginalOpenAdmissionTests {
    record Source(MediaRecord media,long registered,int slot) {}
    final ViewerPrincipal actor=new ViewerPrincipal("synthetic","owner");
    final Preview profile=new Preview(true,2,2,512,512,4194304,"admission-v1");
    final boolean runtime=Boolean.parseBoolean(System.getProperty("imagegate.openAdmission.runtime","false"));
    @Test @Timeout(240)
    void comparesOpenOnlyAdmissionWithTwoRealPreviewWorkers() throws Exception {
        int first=Integer.parseInt(System.getProperty("imagegate.openAdmission.firstLimit","2"));
        if(first!=1&&first!=2)throw new IllegalArgumentException("firstLimit must be 1 or 2");
        var helper=new OriginalStreamComparisonTests();
        Path base=helper.directory("storageDirectory","build/open-admission");
        if(Files.getFileStore(base).getUsableSpace()<1073741824L)throw new IOException("One GiB free space required");
        Path storage=Files.createTempDirectory(base,"run-");
        Path output=helper.directory("reportDirectory","build/reports/open-admission").resolve(runtime?"runtime-latest.json":"latest.json");
        var report=new LinkedHashMap<String,Object>();var batches=new ArrayList<Map<String,Object>>();
        report.put("schemaVersion",2);report.put("runtimeAdmission",runtime);report.put("createdAt",Instant.now().toString());report.put("storage",storage.toString());
        report.put("scope","Two-worker synthetic server preview readiness; gate wait included; no HTTP/browser/native stack sampling");
        report.put("javaVersion",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));
        report.put("heapLimitBytes",Runtime.getRuntime().maxMemory());report.put("workers",2);report.put("filesPerBatch",2);
        report.put("firstLimit",first);report.put("sourceEdge",2048);
        boolean passed=false;
        try(var store=new LocalMediaStore(storage.resolve("originals"),32L*1024*1024)){
            byte[] png=helper.fixture();var originals=new ArrayList<Source>();
            var flags=new ImageGateProperties.Features(
                    new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                    new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
            var policy=new FeaturePolicy(new ImageGateProperties(null,flags,null,profile,null,null,null,null));
            for(int block=0;block<2;block++){
                int[] limits=block==0?new int[]{first,3-first}:new int[]{3-first,first};
                for(int limit:limits){
                    var sources=new ArrayList<Source>();
                    for(int slot=0;slot<2;slot++){
                        var media=store.register(actor.tenantId(),actor.userId(),"synthetic.png",new ByteArrayInputStream(png));
                        sources.add(new Source(media,System.nanoTime(),slot));
                    }
                    originals.addAll(sources);
                    for(String kind:List.of("first","repeat")){
                        Path converted=storage.resolve("b"+block+"-limit"+limit+"-"+kind);
                        batches.add(batch(store,policy,sources,converted,block,limit,kind));
                    }
                }
            }
            for(var source:originals){
                var result=OriginalStreamComparisonTests.verify(store.resolveOriginal(source.media()),
                        OriginalStreamComparisonTests.Method.FILES,source.media().sizeBytes(),source.media().sha256());
                assertThat(result.bytes()).isEqualTo(source.media().sizeBytes());
            }
            assertThat(batches).hasSize(8);assertThat(originals).hasSize(8);
            report.put("originalsVerified",originals.size());passed=true;
        }catch(Exception|AssertionError failure){report.put("failureType",failure.getClass().getSimpleName());throw failure;}
        finally{
            report.put("passed",passed);report.put("batches",batches);
            Files.writeString(output,JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            System.out.println("Original open admission: "+output+"; passed="+passed);
        }
    }
    Map<String,Object> batch(LocalMediaStore store,FeaturePolicy policy,List<Source> sources,Path converted,int block,int limit,String kind) throws Exception {
        // In runtime mode this counter has more slots than workers and never limits the production policy.
        int counterCapacity=runtime?4:limit;
        var gate=new OpenPermitGate(counterCapacity);var entered=new CountDownLatch(2);var go=new CountDownLatch(1);
        var samples=Collections.synchronizedList(new ArrayList<Map<String,Object>>());
        var released=new AtomicLong();long admitted=System.nanoTime();double firstReady=-1,allReady=-1;
        var batchProfile=new Preview(true,2,2,512,512,4194304,"admission-v1",profile.cache(),true,256,
                new ImageGateProperties.OriginalOpenPolicy(runtime,limit,java.time.Duration.ofSeconds(10)));
        var hashOpen=new ThreadLocal<Long>();
        var renderer=new ImagePreviewRenderer(store,(a,m,action)->a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId()),
                policy,batchProfile,null,(stage,nanos)->{
                    if(stage==co.kr.satu.imagegate.conversion.util.PreviewTiming.Stage.HASH_OPEN)hashOpen.set(nanos);
                },gate::open);
        try(var queue=new PreviewQueue(profile,"open-admission",new PreviewCache(converted),(m,a,p,w)->{
            entered.countDown();
            try{if(!go.await(20,TimeUnit.SECONDS))throw new IOException("Admission barrier timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}
            long started=System.nanoTime();
            try{return renderer.render(m,a,p,w);}
            finally{
                long ended=System.nanoTime();var observation=gate.takeObservation();
                var source=sources.stream().filter(item->item.media().mediaId().equals(m.mediaId())).findFirst().orElseThrow();
                var row=new LinkedHashMap<String,Object>();row.put("slot",source.slot());
                row.put("renderMs",OriginalStreamComparisonTests.ms(ended-started));row.put("observedOpen",observation!=null);
                if(observation!=null){
                    row.put("testCounterWaitMs",OriginalStreamComparisonTests.ms(observation.waitNanos()));
                    if(hashOpen.get()!=null)row.put("admissionAndOpenOverheadMs",OriginalStreamComparisonTests.ms(Math.max(0,hashOpen.get()-observation.nativeOpenNanos())));
                    row.put("nativeOpenMs",OriginalStreamComparisonTests.ms(observation.nativeOpenNanos()));
                    row.put("actualAgeAtOpenMs",OriginalStreamComparisonTests.ms(observation.openStarted()-source.registered()));
                    row.put("permitAcquired",observation.acquired());row.put("openerReturned",observation.returned());
                }
                hashOpen.remove();samples.add(row);
            }
        })){
            try{
                for(var source:sources)queue.submit(source.media(),actor,1,false);
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            }finally{released.set(System.nanoTime());go.countDown();}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            while(System.nanoTime()<deadline){
                int ready=0;
                for(var source:sources){
                    var state=queue.status(source.media(),actor,1);
                    if(state.state().equals("failed"))throw new AssertionError("Preview failed: "+state.errorCode());
                    if(state.ready())ready++;
                }
                double elapsed=OriginalStreamComparisonTests.ms(System.nanoTime()-released.get());
                if(ready>0&&firstReady<0)firstReady=elapsed;
                if(ready==2){allReady=elapsed;break;}
                Thread.sleep(5);
            }
            assertThat(firstReady).isNotNegative();assertThat(allReady).isGreaterThanOrEqualTo(firstReady);
            for(var source:sources){
                String key=queue.status(source.media(),actor,1).sourceId();
                queue.content(source.media(),actor,1,key,path->verifyPng(path,512));
                queue.content(source.media(),actor,1,key,true,path->verifyPng(path,256));
            }
        }finally{go.countDown();renderer.close();}
        double batchWithVerification=OriginalStreamComparisonTests.ms(System.nanoTime()-admitted);
        assertThat(samples).hasSize(2);
        assertThat(gate.stats().get("active")).isZero();assertThat(gate.stats().get("waiting")).isZero();
        assertThat(gate.stats().get("available")).isEqualTo(counterCapacity);
        assertThat(gate.stats().get("peakActive")).isLessThanOrEqualTo(limit);
        assertThat(gate.stats().get("acquired")).isEqualTo(2);assertThat(gate.stats().get("released")).isEqualTo(2);
        try(var cache=new PreviewCache(converted)){
            cache.claimWriter();
            int entries=0,png=0;
            try(var directories=Files.newDirectoryStream(cache.root())){
                for(var entry:directories){
                    if(entry.getFileName().toString().equals("worker.lock"))continue;
                    assertThat(entry.getFileName().toString()).matches("[a-f0-9]{64}");entries++;
                    assertThat(cache.read(entry.getFileName().toString(),1).ready()).isTrue();
                    try(var files=Files.newDirectoryStream(entry)){
                        for(var file:files){
                            String name=file.getFileName().toString();
                            if(name.matches("result-[a-f0-9-]{36}[.]png"))png++;
                            else assertThat(name).isEqualTo("state.properties");
                        }
                    }
                }
            }
            assertThat(entries).isEqualTo(2);assertThat(png).isEqualTo(4);
        }
        return Map.of("block",block+1,"limit",limit,"kind",kind,"firstReadyMs",firstReady,"allReadyMs",allReady,
                "batchWithVerificationMs",batchWithVerification,"gate",gate.stats(),"samples",samples,
                "residues",0,"writerReacquired",true);
    }
    void verifyPng(Path path,int edge) throws IOException {
        var image=ImageIO.read(path.toFile());assertThat(image).isNotNull();
        try{assertThat(image.getWidth()).isEqualTo(edge);assertThat(image.getHeight()).isEqualTo(edge);}
        finally{image.flush();}
    }
}
