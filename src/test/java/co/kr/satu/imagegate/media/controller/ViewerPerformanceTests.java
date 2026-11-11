package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Development only: real filters/controllers/workers/files, in-process mock HTTP transport. */
@Tag("performance")
@SpringBootTest(properties={"imagegate.preview.enabled=true","imagegate.playback.enabled=false",
        "imagegate.preview.workers=2","imagegate.preview.queue-capacity=32",
        "imagegate.preview.max-width=1600","imagegate.preview.max-height=1600",
        "imagegate.preview.max-source-pixels=16000000","imagegate.preview.thumbnail-enabled=true","imagegate.preview.thumbnail-max-edge=256"})
@AutoConfigureMockMvc
@Import(ViewerPerformanceTests.PolicyConfiguration.class)
class ViewerPerformanceTests {
    static final Path STORAGE=storage();
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true);
    static Path storage(){try{return Files.createTempDirectory(Path.of(System.getProperty("imagegate.performance.storageDirectory","build/performance")).toAbsolutePath(),"run-");}
        catch(IOException first){try{var parent=Path.of(System.getProperty("imagegate.performance.storageDirectory","build/performance")).toAbsolutePath();Files.createDirectories(parent);return Files.createTempDirectory(parent,"run-");}
        catch(IOException failure){throw new ExceptionInInitializerError(failure);}}}
    static boolean nativeEnabled(){String value=System.getProperty("imagegate.performance.native","false");
        if(!Set.of("true","false").contains(value))throw new IllegalArgumentException("performanceImg2tile must be true or false");return Boolean.parseBoolean(value);}
    @DynamicPropertySource static void settings(DynamicPropertyRegistry p){
        p.add("imagegate.storage.root",STORAGE::toString);
        p.add("imagegate.conversion.enabled",ViewerPerformanceTests::nativeEnabled);
    }
    @TestConfiguration(proxyBeanMethods=false) static class PolicyConfiguration {
        @Bean @Primary MediaAuthorizationPolicy benchmarkPolicy(){return (actor,media,action)->ALLOWED.get()
                &&actor.tenantId().equals(media.tenantId())&&actor.userId().equals(media.ownerId());}
    }
    @Autowired MockMvc mvc;
    final JsonMapper json=JsonMapper.builder().build();
    final List<Map<String,Object>> rows=new ArrayList<>();
    final List<Map<String,Object>> fixtures=new ArrayList<>();
    final List<Double> warmP95=new ArrayList<>();
    RequestPostProcessor owner(){return actor("benchmark","owner");}
    RequestPostProcessor actor(String tenant,String name){return authentication(UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal(tenant,name),"unused",List.of()));}
    int setting(String name,int fallback,int min,int max){int value=Integer.parseInt(System.getProperty("imagegate.performance."+name,""+fallback));
        if(value<min||value>max)throw new IllegalArgumentException(name+" outside supported bounds");return value;}
    record Fixture(String label,String format,int width,int height,byte[] bytes) {}
    record Source(String id,String url,int width,int height,String thumbnailUrl,int thumbnailWidth,int thumbnailHeight) {}
    Fixture fixture(String format,int width,int height) throws Exception {
        var raster=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var random=new Random(83021);
        int[] line=new int[width];for(int y=0;y<height;y++){for(int x=0;x<width;x++)line[x]=random.nextInt(0x1000000);raster.setRGB(0,y,width,1,line,0,width);}
        var output=new ByteArrayOutputStream();if(!ImageIO.write(raster,format,output))throw new IOException("Fixture encoder missing");raster.flush();
        return new Fixture(format+"-"+width+"x"+height,format,width,height,output.toByteArray());
    }
    Source prepare(Fixture f) throws Exception {
        var result=mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","synthetic."+f.format(),"application/octet-stream",f.bytes()))
                .with(owner()).with(csrf())).andExpect(status().isCreated()).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).get("mediaId").asText();
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(System.nanoTime()<end){
            var response=mvc.perform(get("/api/v1/media/"+id+"/status").with(owner())).andExpect(status().isOk()).andReturn();
            JsonNode state=json.readTree(response.getResponse().getContentAsString());String value=state.get("state").asText();
            if("ready".equals(value)){var source=state.get("source");var thumbnail=state.get("thumbnail");return new Source(id,source.get("url").asText(),source.get("width").asInt(),source.get("height").asInt(),thumbnail.get("url").asText(),thumbnail.get("width").asInt(),thumbnail.get("height").asInt());}
            if("failed".equals(value))throw new AssertionError("Synthetic preview failed: "+state.get("errorCode"));
            Thread.sleep(10);
        }
        throw new AssertionError("Synthetic preview timed out");
    }
    byte[] download(Source source) throws Exception {return download(source,false);}
    byte[] download(Source source,boolean thumbnail) throws Exception {
        var response=mvc.perform(get(thumbnail?source.thumbnailUrl():source.url()).with(owner())).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        byte[] bytes=response.getContentAsByteArray();assertThat(bytes.length).isPositive();
        assertThat(response.getHeader("Content-Length")).isEqualTo(""+bytes.length);return bytes;
    }
    void verifyHead(Source source,long expected) throws Exception {
        mvc.perform(head(source.url()).with(owner())).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("Content-Length",""+expected))
                .andExpect(content().bytes(new byte[0]));
    }
    void validatePng(Source source,byte[] bytes) throws Exception {
        var image=ImageIO.read(new ByteArrayInputStream(bytes));assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(source.width());assertThat(image.getHeight()).isEqualTo(source.height());image.flush();
    }
    void security(Source source) throws Exception {
        for(String url:List.of(source.url(),source.thumbnailUrl())){
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
            mvc.perform(get(url).with(actor("benchmark","other"))).andExpect(status().isNotFound());
            mvc.perform(get(url).with(actor("other","owner"))).andExpect(status().isNotFound());
            ALLOWED.set(false);try{mvc.perform(get(url).with(owner())).andExpect(status().isNotFound());}finally{ALLOWED.set(true);}
        }
    }
    Map<String,Object> phase(String label,String phase,int concurrency,long[] durations,long wall,HeapSample memory){
        var sorted=durations.clone();Arrays.sort(sorted);var row=new LinkedHashMap<String,Object>();
        row.put("fixture",label);row.put("phase",phase);row.put("concurrency",concurrency);row.put("requests",durations.length);
        row.put("p50Ms",sorted[(int)Math.ceil(sorted.length*.50)-1]/1e6);row.put("p95Ms",sorted[(int)Math.ceil(sorted.length*.95)-1]/1e6);
        row.put("maxMs",sorted[sorted.length-1]/1e6);row.put("requestsPerSecond",durations.length/(wall/1e9));
        row.put("heapBeforeBytes",memory.before);row.put("sampledPeakHeapBytes",memory.peak.get());
        row.put("gcCountDelta",gcCount()-memory.gcBefore);row.put("gcTimeMsDelta",gcTime()-memory.gcTimeBefore);
        row.put("latenciesMs",Arrays.stream(durations).mapToDouble(n->n/1e6).toArray());rows.add(row);
        System.out.printf(Locale.ROOT,"PERF %s %s concurrency=%d count=%d p50=%.2fms p95=%.2fms%n",label,phase,concurrency,durations.length,row.get("p50Ms"),row.get("p95Ms"));return row;
    }
    long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionCount())).sum();}
    long gcTime(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b->Math.max(0,b.getCollectionTime())).sum();}
    final class HeapSample implements AutoCloseable {
        final long before=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),gcBefore=gcCount(),gcTimeBefore=gcTime();
        final AtomicLong peak=new AtomicLong(before);
        final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"benchmark-heap-sampler");t.setDaemon(true);return t;});
        HeapSample(){sampler.scheduleAtFixedRate(()->peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max),0,20,TimeUnit.MILLISECONDS);}
        @Override public void close(){peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);sampler.shutdownNow();}
    }
    @Test @Timeout(value=10,unit=TimeUnit.MINUTES)
    void coldWarmAndConcurrentPreviewBaseline() throws Exception {
        int cold=setting("coldSamples",5,3,30),warm=setting("warmRequests",24,24,1000);
        double budget=Double.parseDouble(System.getProperty("imagegate.performance.maxWarmP95Ms","0"));
        if(!Double.isFinite(budget)||budget<0)throw new IllegalArgumentException("Invalid performanceMaxWarmP95Ms");
        boolean passed=false;
        try{
            // Compile/JIT/security initialization is outside the measured phases. OS page cache is not flushed.
            var preheat=fixture("jpeg",640,480);Source preheated=prepare(preheat);for(int i=0;i<5;i++)download(preheated);
            for(String format:List.of("jpeg","png","tiff")){
                int w=format.equals("jpeg")?3200:2400,h=format.equals("jpeg")?2400:1600;
                Fixture f=fixture(format,w,h);var meta=new LinkedHashMap<String,Object>();
                meta.put("label",f.label());meta.put("format",format);meta.put("width",w);meta.put("height",h);meta.put("inputBytes",f.bytes().length);
                meta.put("engine",nativeEnabled()&&format.equals("tiff")?"img2tile":"JDK ImageIO");fixtures.add(meta);
                long[] times=new long[cold];Source source=null;byte[] sample=null;long begin=System.nanoTime();
                try(var memory=new HeapSample()){
                    for(int i=0;i<cold;i++){long start=System.nanoTime();source=prepare(f);sample=download(source);times[i]=System.nanoTime()-start;}
                    memory.close();phase(f.label(),"cold-register-to-png",1,times,System.nanoTime()-begin,memory);
                }
                validatePng(source,sample);meta.put("outputBytes",sample.length);meta.put("outputWidth",source.width());meta.put("outputHeight",source.height());
                byte[] thumbnailBytes=download(source,true);var decodedThumbnail=ImageIO.read(new ByteArrayInputStream(thumbnailBytes));
                assertThat(decodedThumbnail.getWidth()).isEqualTo(source.thumbnailWidth());assertThat(decodedThumbnail.getHeight()).isEqualTo(source.thumbnailHeight());decodedThumbnail.flush();
                assertThat(thumbnailBytes.length).isLessThan(sample.length);meta.put("thumbnailBytes",thumbnailBytes.length);meta.put("thumbnailWidth",source.thumbnailWidth());meta.put("thumbnailHeight",source.thumbnailHeight());
                security(source);for(int i=0;i<5;i++)download(source);
                for(String operation:List.of("warm-head","warm-png","warm-thumbnail"))for(int concurrency:List.of(1,4,8)){
                    final Source selected=source;final long expected=operation.equals("warm-thumbnail")?thumbnailBytes.length:sample.length;
                    var next=new AtomicInteger();var ready=new CountDownLatch(concurrency);var startGate=new CountDownLatch(1);long[] warmTimes=new long[warm];
                    try(var clients=Executors.newFixedThreadPool(concurrency);var memory=new HeapSample()){
                        var jobs=new ArrayList<Future<?>>();
                        for(int i=0;i<concurrency;i++)jobs.add(clients.submit(()->{ready.countDown();try{startGate.await();
                            for(int index;(index=next.getAndIncrement())<warm;){long start=System.nanoTime();
                                if(operation.equals("warm-head"))verifyHead(selected,expected);else{byte[] bytes=download(selected,operation.equals("warm-thumbnail"));assertThat(bytes.length).isEqualTo(expected);}
                                warmTimes[index]=System.nanoTime()-start;}
                        }catch(Exception failure){throw new CompletionException(failure);}}));
                        boolean allReady=ready.await(10,TimeUnit.SECONDS);long start=System.nanoTime();startGate.countDown();assertThat(allReady).isTrue();
                        for(var job:jobs)job.get(2,TimeUnit.MINUTES);memory.close();
                        var row=phase(f.label(),operation,concurrency,warmTimes,System.nanoTime()-start,memory);warmP95.add((Double)row.get("p95Ms"));
                    }finally{startGate.countDown();}
                }
            }
            if(budget>0)assertThat(warmP95).allSatisfy(value->assertThat(value).as("warm P95 budget in milliseconds").isLessThanOrEqualTo(budget));
            passed=true;
        }finally{ALLOWED.set(true);writeReport(passed,cold,warm,budget);}
    }
    void writeReport(boolean passed,int cold,int warm,double budget) throws Exception {
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);report.put("generatedAt",Instant.now().toString());report.put("passed",passed);
        report.put("measurement","MockMvc security/API/real worker/files; excludes browser, network, SSO, JVM startup and fixture generation; OS page cache retained");
        report.put("javaVersion",System.getProperty("java.version"));report.put("osName",System.getProperty("os.name"));report.put("osVersion",System.getProperty("os.version"));
        report.put("architecture",System.getProperty("os.arch"));report.put("processors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("coldSamples",cold);report.put("warmRequestsPerPhase",warm);report.put("maxWarmP95Ms",budget);report.put("nativeImg2tile",nativeEnabled());
        report.put("mockTransportCaveat","Spring MockHttpServletResponse stream performs per-byte writes/flush; GET body timing is dominated partly by mock transport. Compare warm-head and use real HTTP/browser before production conclusions.");
        report.put("previewWorkers",2);report.put("previewMaxEdge",1600);report.put("memoryMeaning","20ms sampled JVM heap, including MockMvc response/client buffers and fixtures; not RSS or native process memory");
        report.put("fixtures",fixtures);report.put("phases",rows);
        Path directory=Path.of(System.getProperty("imagegate.performance.reportDirectory","build/reports/performance")).toAbsolutePath();Files.createDirectories(directory);
        String name="viewer-"+Instant.now().toEpochMilli();Files.writeString(directory.resolve(name+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        var md=new StringBuilder("# Viewer API performance baseline\n\n");
        md.append("Passed: ").append(passed).append(". In-process synthetic API benchmark; excludes browser, network and real SSO. OS page cache retained.\n\n");
        md.append("| Fixture | Phase | Concurrency | Samples | P50 ms | P95 ms | Max ms | req/s | Sampled heap MiB |\n| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for(var row:rows)md.append(String.format(Locale.ROOT,"| %s | %s | %s | %s | %.2f | %.2f | %.2f | %.2f | %.1f |%n",row.get("fixture"),row.get("phase"),row.get("concurrency"),row.get("requests"),row.get("p50Ms"),row.get("p95Ms"),row.get("maxMs"),row.get("requestsPerSecond"),((Long)row.get("sampledPeakHeapBytes"))/1048576.0));
        md.append("\nGET body times include per-byte MockMvc output buffering; compare HEAD and validate with real HTTP before production conclusions.\n");
        md.append("\nHeap includes fixture/client/mock-response buffers. No production SLA is asserted by default. Full environment and raw samples are in the adjacent JSON file.\n");
        Files.writeString(directory.resolve(name+".md"),md.toString());
    }
}
