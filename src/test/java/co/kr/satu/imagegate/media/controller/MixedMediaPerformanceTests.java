package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in real-module server measurement. All media, principals and storage are synthetic and isolated. */
@Tag("mixed-media-performance") @EnabledOnOs(OS.WINDOWS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=optional:classpath:/mixed-media-no-installation.yml","server.address=127.0.0.1","server.ssl.enabled=false",
    "imagegate.conversion.enabled=true","imagegate.conversion.timeout=120s",
    "imagegate.tiles.enabled=true","imagegate.tiles.workers=1","imagegate.tiles.min-free-bytes=0","imagegate.tiles.maintenance.enabled=false",
    "imagegate.preview.enabled=true","imagegate.preview.workers=2","imagegate.preview.cache.enabled=false",
    "imagegate.playback.enabled=true","imagegate.playback.workers=1","imagegate.playback.cache.enabled=false",
    "imagegate.upload.min-free-bytes=0","imagegate.upload.tenant-original-max-bytes=0","imagegate.audit.enabled=false"})
@Import(MixedMediaPerformanceTests.Identity.class) @DirtiesContext
class MixedMediaPerformanceTests {
    static final Path ROOT;static final String TOKEN=UUID.randomUUID().toString();
    static{try{Path parent=Files.createDirectories(Path.of("build/mixed-media-work").toAbsolutePath());ROOT=Files.createTempDirectory(parent,"run-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static final Path FFMPEG=Path.of(System.getenv().getOrDefault("FFMPEG_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffmpeg.exe"));
    static final Path FFPROBE=Path.of(System.getenv().getOrDefault("FFPROBE_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffprobe.exe"));
    static String hash(Path file){try(var input=Files.newInputStream(file)){var digest=co.kr.satu.imagegate.conversion.util.PreviewKeys.digest();byte[] block=new byte[32768];int n;while((n=input.read(block))!=-1)digest.update(block,0,n);return HexFormat.of().formatHex(digest.digest());}catch(IOException e){throw new IllegalStateException("Installed benchmark module unavailable",e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){
        p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());
        p.add("imagegate.conversion.max-concurrent-jobs",()->{int slots=Integer.parseInt(System.getProperty("imagegate.mixed.nativeSlots","2"));if(slots<1||slots>2)throw new IllegalArgumentException("Benchmark native slots must be 1 or 2");return slots;});
        // Test-only pinning of installed tools; production still requires operator-approved configuration.
        p.add("imagegate.playback.ffmpeg",()->FFMPEG.toString());p.add("imagegate.playback.ffprobe",()->FFPROBE.toString());
        p.add("imagegate.playback.ffmpeg-sha256",()->hash(FFMPEG));p.add("imagegate.playback.ffprobe-sha256",()->hash(FFPROBE));
    }
    @TestConfiguration(proxyBeanMethods=false) static class Identity {
        @Bean static BeanPostProcessor identity(){return new BeanPostProcessor(){public Object postProcessAfterInitialization(Object bean,String name){
            if(name.equals("securityFilterChain")&&bean instanceof DefaultSecurityFilterChain chain){
                int position=-1;for(int i=0;i<chain.getFilters().size();i++)if(chain.getFilters().get(i) instanceof SecurityContextHolderFilter)position=i;
                if(position<0)throw new IllegalStateException("Missing security context boundary");
                chain.getFilters().add(position+1,new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next)throws ServletException,IOException{
                    var credential=request.getHeader("Authorization");
                    if(("Bearer "+TOKEN).equals(credential)||("Bearer "+TOKEN+"-other").equals(credential))SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("benchmark",credential.endsWith("-other")?"other":"owner"),"fixture",List.of()));
                    next.doFilter(request,response);
                }});
            }return bean;
        }};}
    }
    @Autowired MediaStore store;@Autowired ImageGateProperties properties;@LocalServerPort int port;
    final JsonMapper json=JsonMapper.builder().build();final List<Map<String,Object>> rows=new ArrayList<>(),preparations=new CopyOnWriteArrayList<>();
    String csrfHeader,csrfToken;HttpClient client;Resources overall;int securityChecks;final List<MediaRecord> originals=new ArrayList<>();
    record Target(String kind,String path,String mime,boolean range){}
    record Sample(String kind,long nanos,int status,long bytes,String failure){}
    HttpRequest.Builder request(String path,String credential){
        var origin=URI.create("http://127.0.0.1:"+port);var uri=origin.resolve(path);
        if(!path.startsWith("/api/v1/")||!uri.getHost().equals("127.0.0.1")||uri.getPort()!=port||!uri.getScheme().equals("http"))throw new IllegalArgumentException("Local API only");
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));if(credential!=null)request.header("Authorization","Bearer "+credential);return request;
    }
    HttpResponse<byte[]> call(String path,boolean post)throws Exception{
        var r=request(path,TOKEN);if(post)r.header(csrfHeader,csrfToken).POST(HttpRequest.BodyPublishers.noBody());
        var response=client.send(r.build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isIn(200,202);return response;
    }
    JsonNode node(String path,boolean post)throws Exception{return json.readTree(call(path,post).body());}
    JsonNode await(String path)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
        do{var reply=node(path,false);var state=reply.path("state").asText();if(state.equals("ready"))return reply;
            assertThat(state).as("Native preparation state: %s",reply).isNotEqualTo("failed");Thread.sleep(50);
        }while(System.nanoTime()<deadline);throw new AssertionError("Native preparation deadline");
    }
    MediaRecord register(Path file)throws Exception{try(var input=Files.newInputStream(file)){var record=store.register("benchmark","owner",file.getFileName().toString(),input);originals.add(record);return record;}}
    Target prepare(MediaRecord media,String kind)throws Exception{return prepare(media,kind,"initial");}
    Target prepare(MediaRecord media,String kind,String phase)throws Exception{
        long started=System.nanoTime();String base="/api/v1/media/"+media.mediaId();Target target;
        if(kind.equals("preview")){node(base+"/previews?page=1",true);var result=await(base+"/pages/1");target=new Target(kind,result.path("source").path("url").asText(),"image/png",false);}
        else if(kind.equals("tile")){node(base+"/pages/1/pyramids",true);var p=await(base+"/pages/1/pyramid").path("pyramid");
            target=new Target(kind,p.path("tileUrlTemplate").asText().replace("{level}",p.path("maxLevel").asText()).replace("{column}","11").replace("{row}","7"),"image/png",false);}
        else{node(base+"/playback",true);var result=await(base+"/playback");target=new Target(kind,result.path("url").asText(),kind.equals("video")?"video/webm":"audio/mpeg",true);}
        var sample=read(target);assertThat(sample.failure()).isNull();preparations.add(Map.of("kind",kind,"phase",phase,"readyAndFirstContentMs",(System.nanoTime()-started)/1e6));return target;
    }
    Sample read(Target target){
        long start=System.nanoTime();int status=0;long bytes=0;String failure=null;
        try{var request=request(target.path(),TOKEN);if(target.range())request.header("Range","bytes=0-4095");
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());status=response.statusCode();bytes=response.body().length;
            if(status!=(target.range()?206:200)||!response.headers().firstValue("Content-Type").orElse("").startsWith(target.mime())
                ||!response.headers().firstValue("Cache-Control").orElse("").contains("no-store")
                ||!response.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff")
                ||(!target.kind().equals("page")&&response.headers().firstValueAsLong("Content-Length").orElse(-1)!=bytes)||bytes<1)failure="HTTP_CONTRACT";
            if(target.range()&&(bytes!=4096||!response.headers().firstValue("Content-Range").orElse("").startsWith("bytes 0-4095/")))failure="RANGE_CONTRACT";
            if(target.mime().equals("image/png")&&(bytes<8||response.body()[0]!=(byte)0x89||response.body()[1]!='P'))failure="PNG_SIGNATURE";
            if(target.kind().equals("page")&&!json.readTree(response.body()).path("originalCoordinatesAvailable").asBoolean())failure="GEOMETRY_CONTRACT";
        }catch(Exception e){failure=e instanceof InterruptedException?"INTERRUPTED":"REQUEST_FAILED";if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
        return new Sample(target.kind(),System.nanoTime()-start,status,bytes,failure);
    }
    static long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g->Math.max(0,g.getCollectionCount())).sum();}
    static long gcTime(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g->Math.max(0,g.getCollectionTime())).sum();}
    static long cpu(){var os=ManagementFactory.getOperatingSystemMXBean();return os instanceof com.sun.management.OperatingSystemMXBean bean?bean.getProcessCpuTime():-1;}
    final class Resources implements AutoCloseable {
        final long gcBefore=gcCount(),gcTimeBefore=gcTime(),cpuBefore=cpu();final AtomicLong peak=new AtomicLong();final AtomicInteger children=new AtomicInteger();
        final AtomicInteger nativeChildren=new AtomicInteger();int ticks;
        final Set<String> nativeCommands=Set.of(command(properties.conversion().modules().img2tile().executable()),command(properties.playback().ffmpeg()),command(properties.playback().ffprobe()));
        final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"mixed-media-resource-sample");t.setDaemon(true);return t;});
        String command(String path){return Path.of(path).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);}
        Resources(){sampler.scheduleAtFixedRate(this::sample,0,20,TimeUnit.MILLISECONDS);}
        void sample(){peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
            if(ticks++%10==0)try(var descendants=ProcessHandle.current().descendants()){
                var observed=descendants.limit(64).toList();children.accumulateAndGet(observed.size(),Math::max);
                nativeChildren.accumulateAndGet((int)observed.stream().filter(p->p.info().command().map(path->nativeCommands.contains(command(path))).orElse(false)).count(),Math::max);
            }}
        public void close(){sampler.shutdownNow();try{if(!sampler.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Resource sampler did not stop");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}sample();}
        Map<String,Object> values(){return Map.of("sampledPeakJvmHeapBytes",peak.get(),"sampledPeakDescendantProcesses",children.get(),"sampledPeakApprovedNativeProcesses",nativeChildren.get(),"gcCountDelta",gcCount()-gcBefore,"gcTimeMsDelta",gcTime()-gcTimeBefore,"jvmCpuTimeMsDelta",cpuBefore<0?-1:(cpu()-cpuBefore)/1e6);}
    }
    void phase(String name,int concurrency,int count,List<Target> targets,CountDownLatch started)throws Exception{
        var samples=new ConcurrentLinkedQueue<Sample>();var next=new AtomicInteger();long begin=System.nanoTime();Resources resources=new Resources();
        try(var executor=Executors.newFixedThreadPool(concurrency)){
            var jobs=new ArrayList<Future<?>>();for(int c=0;c<concurrency;c++)jobs.add(executor.submit(()->{
                if(started!=null)started.countDown();int index;while((index=next.getAndIncrement())<count)samples.add(read(targets.get(index%targets.size())));
            }));for(var job:jobs)job.get(90,TimeUnit.SECONDS);
        }finally{resources.close();}
        double seconds=(System.nanoTime()-begin)/1e9;var measuredResources=resources.values();
        for(var target:targets){var group=samples.stream().filter(s->s.kind().equals(target.kind())).toList();var times=group.stream().mapToLong(Sample::nanos).sorted().toArray();
            var row=new LinkedHashMap<String,Object>();row.put("phase",name);row.put("concurrency",concurrency);row.put("kind",target.kind());row.put("samples",times.length);
            row.put("errors",group.stream().filter(s->s.failure()!=null).count());row.put("statuses",group.stream().collect(java.util.stream.Collectors.groupingBy(Sample::status,java.util.stream.Collectors.counting())));
            row.put("p50Ms",times[(int)Math.ceil(times.length*.50)-1]/1e6);row.put("p95Ms",times[(int)Math.ceil(times.length*.95)-1]/1e6);row.put("maxMs",times[times.length-1]/1e6);
            row.put("bytes",group.stream().mapToLong(Sample::bytes).sum());row.put("phaseDurationSeconds",seconds);row.put("phaseRequestsPerSecond",count/seconds);row.put("resources",measuredResources);
            row.put("latenciesMs",Arrays.stream(times).mapToDouble(n->n/1e6).toArray());rows.add(row);
            System.out.printf(Locale.ROOT,"MIXED %s c=%d %s n=%d p95=%.2fms errors=%s%n",name,concurrency,target.kind(),times.length,row.get("p95Ms"),row.get("errors"));
        }
        assertThat(samples).hasSize(count);assertThat(samples).allMatch(s->s.failure()==null);
        if(name.equals("during-native-preparation"))assertThat(resources.nativeChildren.get()).as("Measured requests must overlap approved img2tile/FFmpeg/FFprobe executables").isPositive();
    }
    void security(List<Target> targets)throws Exception{
        for(var target:targets)for(String who:Arrays.asList(null,TOKEN+"-other")){
            var response=client.send(request(target.path(),who).build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(response.statusCode()).isEqualTo(who==null?401:404);
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            securityChecks++;
        }
        var denied=client.send(request(targets.getFirst().path().split("/sources/")[0]+"/previews?page=1",TOKEN).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());assertThat(denied.statusCode()).isEqualTo(403);securityChecks++;
    }
    Path tiff()throws Exception{
        var image=new BufferedImage(6000,4000,BufferedImage.TYPE_INT_RGB);Path path=ROOT.resolve("synthetic-large.tif");
        try{int[] row=new int[6000];for(int y=0;y<4000;y++){for(int x=0;x<6000;x++)row[x]=((x/64+y/64)%2==0)?0xffbedbff:0xffdbeafe;image.setRGB(0,y,6000,1,row,0,6000);}assertThat(ImageIO.write(image,"TIFF",path.toFile())).isTrue();}finally{image.flush();}return path;
    }
    Path av(boolean video)throws Exception{
        Path output=ROOT.resolve(video?"synthetic.mp4":"synthetic.wav");var args=new ArrayList<>(List.of(properties.playback().ffmpeg(),"-hide_banner","-loglevel","error","-nostdin","-y"));
        if(video)args.addAll(List.of("-f","lavfi","-i","testsrc=size=640x360:rate=25"));args.addAll(List.of("-f","lavfi","-i","sine=frequency=440:sample_rate=48000","-t","4"));
        args.addAll(video?List.of("-c:v","mpeg4","-q:v","5","-c:a","aac","-movflags","+faststart"):List.of("-c:a","pcm_s16le"));args.add(output.toString());
        assertThat(new BoundedProcessExecutor(1).execute(args,ROOT,Duration.ofSeconds(30)).exitCode()).isZero();return output;
    }
    @Test @Timeout(value=8,unit=TimeUnit.MINUTES) void largeTiffAndPlaybackUnderConcurrentNativePreparation()throws Exception{
        int count=Integer.parseInt(System.getProperty("imagegate.mixed.requests","200"));assertThat(count).isBetween(100,1000);assertThat(count%5).isZero();
        double budget=Double.parseDouble(System.getProperty("imagegate.mixed.maxP95Ms","0"));assertThat(Double.isFinite(budget)&&budget>=0).isTrue();boolean passed=false;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
        overall=new Resources();try{
            var csrf=node("/api/v1/viewer/bootstrap",false).path("csrf");csrfHeader=csrf.path("headerName").asText();csrfToken=csrf.path("token").asText();
            Path image=tiff(),video=av(true),audio=av(false);var source=register(image);var v=register(video);var a=register(audio);
            var targets=new ArrayList<Target>();targets.add(prepare(source,"preview"));targets.add(prepare(source,"tile"));
            targets.add(new Target("page","/api/v1/media/"+source.mediaId()+"/pages/1","application/json",false));targets.add(prepare(v,"video"));targets.add(prepare(a,"audio"));
            security(targets);for(int i=0;i<3;i++)for(var target:targets){var sample=read(target);assertThat(sample.failure()).as("%s status=%d",sample.kind(),sample.status()).isNull();}
            phase("cached-baseline",1,count,targets,null);
            phase("cached-baseline",4,count,targets,null);
            var nextImage=register(image);var nextVideo=register(video);var nextAudio=register(audio);
            // Start real uncached work at the same barrier as HTTP consumers, not before measuring them.
            var gate=new CountDownLatch(1);try(var background=Executors.newFixedThreadPool(4)){
                var jobs=new ArrayList<Future<Target>>();for(String kind:List.of("preview","tile","video","audio"))jobs.add(background.submit(()->{
                    if(!gate.await(10,TimeUnit.SECONDS))throw new AssertionError("Load start barrier");return prepare(kind.equals("video")?nextVideo:kind.equals("audio")?nextAudio:nextImage,kind,"background");
                }));
                phase("during-native-preparation",4,count,targets,gate);for(var job:jobs)job.get(180,TimeUnit.SECONDS);
            }
            phase("cached-concurrent",8,count,targets,null);security(targets);
            for(var original:originals)assertThat(hash(store.resolveOriginal(original))).isEqualTo(original.sha256());
            if(budget>0)assertThat(rows).allMatch(row->((Number)row.get("p95Ms")).doubleValue()<=budget);passed=true;
        }finally{overall.close();try{report(passed,count,budget);}finally{client.close();}}
    }
    void report(boolean passed,int requests,double budget)throws Exception{
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);report.put("passed",passed);report.put("generatedAt",Instant.now().toString());report.put("java",System.getProperty("java.version"));report.put("os",System.getProperty("os.name"));
        report.put("processors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());report.put("sourceWidth",6000);report.put("sourceHeight",4000);report.put("requestsPerPhase",requests);report.put("maxP95Ms",budget);
        report.put("nativeSlots",properties.conversion().maxConcurrentJobs());report.put("previewWorkers",properties.preview().workers());report.put("tileWorkers",properties.tiles().workers());report.put("playbackWorkers",properties.playback().workers());
        report.put("scope","Real loopback HTTP/1.1 + img2tile + FFmpeg; synthetic TIFF/MP4/WAV, test-only identity, actual CSRF/ACL. Excludes registration, fixtures, startup, browser decoding, SSO, TLS and OS cold-cache effects. Audit and maintenance disabled.");
        report.put("resourceMeaning","20ms sampled JVM heap and 200ms descendant inventory; JVM GC/CPU includes server, HTTP clients and fixture memory. Approved native count matches configured executable paths; sampling can miss short processes. Does not measure RSS, native heap, native CPU, disk IOPS or hard isolation.");
        report.put("preparations",preparations);report.put("phases",rows);
        report.put("overallResources",overall.values());report.put("securityChecks",securityChecks);report.put("originalIntegrityVerified",passed&&originals.size()==6);
        long[] disk={0,0};try(var paths=Files.walk(ROOT)){var iterator=paths.iterator();while(iterator.hasNext()){var path=iterator.next();if(++disk[1]>10000)throw new IOException("Benchmark inventory limit");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))disk[0]+=Files.size(path);}}
        report.put("testWorkspaceBytes",disk[0]);report.put("testWorkspaceEntries",disk[1]);
        Path directory=Path.of(System.getProperty("imagegate.mixed.reportDirectory","build/reports/mixed-media-performance"));Files.createDirectories(directory);json.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("latest.json").toFile(),report);
        var md=new StringBuilder("# Mixed media HTTP performance\n\nPassed: "+passed+". Synthetic 6000×4000 TIFF and 4s MP4/WAV, real img2tile/FFmpeg, loopback only. No production SLA.\n\n| Phase | Clients | Kind | Samples | Errors | P50 ms | P95 ms | Max ms |\n| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: |\n");
        for(var row:rows)md.append(String.format(Locale.ROOT,"| %s | %s | %s | %s | %s | %.2f | %.2f | %.2f |%n",row.get("phase"),row.get("concurrency"),row.get("kind"),row.get("samples"),row.get("errors"),row.get("p50Ms"),row.get("p95Ms"),row.get("maxMs")));
        Files.writeString(directory.resolve("latest.md"),md.toString());
    }
}
