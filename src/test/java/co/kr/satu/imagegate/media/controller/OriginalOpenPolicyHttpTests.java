package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.service.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual Tomcat, HTTP, CSRF, authorization, queue, cache and renderer; synthetic identity/open control only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=optional:classpath:/imagegate-open-policy-test-absent.yml","server.address=127.0.0.1","server.ssl.enabled=false",
    "imagegate.preview.enabled=true","imagegate.preview.metrics-enabled=true","imagegate.preview.workers=2","imagegate.preview.queue-capacity=4",
    "imagegate.preview.max-width=32","imagegate.preview.max-height=32","imagegate.preview.max-source-pixels=10000",
    "imagegate.preview.original-open.enabled=true","imagegate.preview.original-open.max-concurrent=1","imagegate.preview.original-open.wait-timeout=2s",
    "imagegate.preview.cache.enabled=false","imagegate.playback.enabled=false","imagegate.playback.cache.enabled=false",
    "imagegate.upload.min-free-bytes=0","imagegate.upload.tenant-original-max-bytes=0","imagegate.conversion.enabled=false","imagegate.audit.enabled=false"})
@Import(OriginalOpenPolicyHttpTests.Fixture.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(value=60,unit=TimeUnit.SECONDS)
class OriginalOpenPolicyHttpTests {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("imagegate-open-policy-http-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static final String TOKEN=UUID.randomUUID().toString();
    static final ViewerPrincipal OWNER=new ViewerPrincipal("tenant","owner");
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true);
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){
        p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());
    }
    @TestConfiguration(proxyBeanMethods=false) static class Fixture {
        @Bean ControlledOriginalOpenFixture control(){return new ControlledOriginalOpenFixture();}
        @Bean @Primary MediaAuthorizationPolicy policy(){return (a,m,action)->ALLOWED.get()&&a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId());}
        @Bean(destroyMethod="") @Primary PreviewRenderer controlledRenderer(ControlledOriginalOpenFixture control,MediaStore store,
                MediaAuthorizationPolicy authorization,FeaturePolicy features,ImageGateProperties properties,PreviewMetrics metrics){return control.renderer(store,authorization,features,properties,metrics);}
        @Bean static BeanPostProcessor identity(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                if(name.equals("securityFilterChain")&&bean instanceof DefaultSecurityFilterChain chain){
                    int index=-1;for(int i=0;i<chain.getFilters().size();i++)if(chain.getFilters().get(i) instanceof SecurityContextHolderFilter)index=i;
                    if(index<0)throw new IllegalStateException("Production security filter missing");
                    chain.getFilters().add(index+1,new OncePerRequestFilter(){
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next)throws ServletException,IOException {
                            String header=request.getHeader("Authorization");ViewerPrincipal actor=null;
                            if(("Bearer "+TOKEN).equals(header))actor=OWNER;
                            else if(("Bearer "+TOKEN+"-bob").equals(header))actor=new ViewerPrincipal("tenant","bob");
                            else if(("Bearer "+TOKEN+"-other").equals(header))actor=new ViewerPrincipal("other","owner");
                            if(actor!=null)SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,"fixture",List.of()));
                            next.doFilter(request,response);
                        }
                    });
                }return bean;
            }
        };}
    }
    @LocalServerPort int port;
    @Autowired ControlledOriginalOpenFixture control;
    @Autowired PreviewQueue queue;
    @Autowired MediaStore store;
    @Autowired ImageGateProperties properties;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    final JsonMapper json=JsonMapper.builder().build();
    HttpClient client;String csrfHeader,csrfToken;
    final List<String> registered=new ArrayList<>();
    HttpRequest.Builder request(String path,String identity){
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(identity!=null)builder.header("Authorization","Bearer "+TOKEN+identity);return builder;
    }
    HttpResponse<String> send(String path,String identity,String method,boolean csrf)throws Exception {
        var builder=request(path,identity);if(csrf)builder.header(csrfHeader,csrfToken);
        return client.send(builder.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> get(String path)throws Exception{return send(path,"","GET",false);}
    JsonNode body(HttpResponse<String> response){return json.readTree(response.body());}
    void privateResponse(HttpResponse<?> response,int status){
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        if(response.body() instanceof String text)assertThat(text).doesNotContain(ROOT.toString(),"private-native-detail","originals/","originals\\");
    }
    @BeforeEach void connect()throws Exception {
        ALLOWED.set(true);registered.clear();
        assertThat(properties.preview().originalOpen().enabled()).isTrue();assertThat(properties.preview().originalOpen().maxConcurrent()).isEqualTo(1);
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
        var bootstrap=get("/api/v1/viewer/bootstrap");privateResponse(bootstrap,200);
        var csrf=body(bootstrap).get("csrf");csrfHeader=csrf.get("headerName").asText();csrfToken=csrf.get("token").asText();
    }
    @AfterEach void reset()throws Exception {
        control.releaseAll();ALLOWED.set(true);
        try{for(String id:registered)await(id,null);assertClean();}finally{if(client!=null)client.close();}
    }
    String upload()throws Exception {
        var png=new ByteArrayOutputStream();var image=new BufferedImage(64,32,BufferedImage.TYPE_INT_RGB);
        try{ImageIO.write(image,"png",png);}finally{image.flush();}
        String boundary="ImageGate"+UUID.randomUUID();var bytes=new ByteArrayOutputStream();
        bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        bytes.write(png.toByteArray());bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var response=client.send(request("/api/v1/media","").header(csrfHeader,csrfToken).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        privateResponse(response,201);String id=body(response).get("mediaId").asText();registered.add(id);return id;
    }
    String statusPath(String id){return "/api/v1/media/"+id+"/status";}
    String retryPath(String id,boolean retry){return "/api/v1/media/"+id+"/previews?retry="+retry;}
    JsonNode await(String id,String expected)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        do{
            var response=get(statusPath(id));privateResponse(response,200);var node=body(response);String state=node.get("state").asText();
            if(expected==null?List.of("ready","failed").contains(state):state.equals(expected))return node;
            if(expected!=null&&List.of("ready","failed").contains(state))throw new AssertionError("Expected "+expected+", got "+node);
            Thread.sleep(10);
        }while(System.nanoTime()<end);throw new AssertionError("Preview did not settle");
    }
    void noSources(JsonNode state,String code){
        assertThat(state.get("state").asText()).isEqualTo("failed");assertThat(state.get("errorCode").asText()).isEqualTo(code);
        assertThat(state.get("source").isNull()).isTrue();assertThat(state.get("thumbnail").isNull()).isTrue();
    }
    String source(String id)throws IOException {var media=store.find(OWNER.tenantId(),id).orElseThrow();return "/api/v1/media/"+id+"/sources/"+queue.key(media,OWNER,1)+"/content?page=1";}
    void verifyReady(String id)throws Exception {
        var state=await(id,"ready");
        for(String role:List.of("source","thumbnail")){
            String url=state.get(role).get("url").asText();
            var response=client.send(request(url,"").GET().build(),HttpResponse.BodyHandlers.ofByteArray());privateResponse(response,200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("image/png");
            var image=ImageIO.read(new ByteArrayInputStream(response.body()));assertThat(image).isNotNull();
            try{assertThat(image.getWidth()).isEqualTo(32);assertThat(image.getHeight()).isEqualTo(16);}finally{image.flush();}
            var head=send(url,"","HEAD",false);privateResponse(head,200);assertThat(head.body()).isEmpty();
            assertThat(Long.parseLong(head.headers().firstValue("Content-Length").orElseThrow())).isEqualTo(response.body().length);
        }
        var media=store.find(OWNER.tenantId(),id).orElseThrow();assertThat(PreviewKeys.sha256(Files.readAllBytes(store.resolveOriginal(media)))).isEqualTo(media.sha256());
    }
    void assertClean()throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{boolean clean;try(var files=Files.walk(properties.storage().convertedPath())){clean=files.noneMatch(p->p.getFileName().toString().startsWith("work-")||p.getFileName().toString().endsWith(".tmp"));}
            if(clean){try(var files=Files.list(ROOT.resolve("spool"))){assertThat(files.count()).isZero();}return;}Thread.sleep(10);
        }while(System.nanoTime()<until);throw new AssertionError("Partial preview output remains");
    }
    @Test void metricsStayPrivateAndWarmReadsDoNotCreateWorkOrMeasurements()throws Exception {
        double before=meters.get("imagegate.preview.jobs.accepted").counter().count();String id=upload();verifyReady(id);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(meters.find("imagegate.preview.jobs.duration").timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum()<before+1&&System.nanoTime()<end)Thread.sleep(10);
        assertThat(meters.find("imagegate.preview.jobs.duration").timers().stream().mapToLong(io.micrometer.core.instrument.Timer::count).sum()).isEqualTo((long)before+1);
        assertThat(meters.get("imagegate.preview.jobs.accepted").counter().count()).isEqualTo(before+1);
        assertThat(meters.get("imagegate.preview.jobs.running").gauge().value()).isZero();
        assertThat(meters.get("imagegate.preview.original.active").gauge().value()).isZero();
        double counters=measurementCount();verifyReady(id);privateResponse(get("/api/v1/media/"+id+"/pages/1"),200);
        assertThat(measurementCount()).isEqualTo(counters);
        var own=meters.getMeters().stream().filter(m->m.getId().getName().startsWith("imagegate.preview.")).toList();
        assertThat(own).hasSize(34);
        for(var meter:own)for(var tag:meter.getId().getTags()){
            assertThat(tag.getKey()).isIn("outcome","result","reason");assertThat(tag.getValue()).doesNotContain(id,ROOT.toString(),"tenant","owner");
        }
        for(String path:List.of("/actuator/metrics","/actuator/metrics/imagegate.preview.jobs.accepted","/actuator/prometheus")){
            privateResponse(send(path,null,"GET",false),401);privateResponse(get(path),403);
        }
    }
    double measurementCount(){
        return meters.getMeters().stream().filter(m->m.getId().getName().startsWith("imagegate.preview."))
                .mapToDouble(m->m instanceof io.micrometer.core.instrument.Counter c?c.count():m instanceof io.micrometer.core.instrument.Timer t?t.count():0).sum();
    }

    @Test void timeoutIsPrivateAndStickyUntilAuthorizedExplicitRetry()throws Exception {
        try(var hold=control.arm(false)){
            String first=upload();hold.awaitEntered();int before=control.calls.get();String second=upload();
            noSources(await(second,"failed"),"ORIGINAL_OPEN_BUSY");assertThat(control.calls.get()).isEqualTo(before);
            var page=get("/api/v1/media/"+second+"/pages/1");privateResponse(page,200);noSources(body(page),"ORIGINAL_OPEN_BUSY");
            for(String method:List.of("GET","HEAD"))privateResponse(send(source(second),"",method,false),404);
            var unchanged=send(retryPath(second,false),"","POST",true);privateResponse(unchanged,200);noSources(body(unchanged),"ORIGINAL_OPEN_BUSY");
            assertThat(control.calls.get()).isEqualTo(before);hold.close();verifyReady(first);
            noSources(body(get(statusPath(second))),"ORIGINAL_OPEN_BUSY");
            var accepted=send(retryPath(second,true),"","POST",true);assertThat(accepted.statusCode()).isIn(200,202);privateResponse(accepted,accepted.statusCode());verifyReady(second);
        }
    }
    @Test void interruptedNativeOpenReleasesSlotAndLaterHttpJobsRecover()throws Exception {
        try(var hold=control.arm(false)){
            String first=upload();hold.awaitEntered();String second=upload();await(second,"running");hold.interrupt();
            noSources(await(first,"failed"),"INTERRUPTED");verifyReady(second);privateResponse(send(source(first),"","HEAD",false),404);
            var retry=send(retryPath(first,true),"","POST",true);assertThat(retry.statusCode()).isIn(200,202);verifyReady(first);
        }
    }
    @Test void authenticationCsrfOwnershipAndRevocationCannotBypassAdmissionOrPublish()throws Exception {
        try(var hold=control.arm(false)){
            String first=upload();hold.awaitEntered();String second=upload();noSources(await(second,"failed"),"ORIGINAL_OPEN_BUSY");int calls=control.calls.get();
            for(String who:Arrays.asList(null,"-bob","-other")){
                int expected=who==null?401:404;
                for(String endpoint:List.of(statusPath(second),"/api/v1/media/"+second+"/pages/1",source(second)))privateResponse(send(endpoint,who,"GET",false),expected);
                privateResponse(send(source(second),who,"HEAD",false),expected);
                privateResponse(send(retryPath(second,true),who,"POST",true),expected);
            }
            privateResponse(send(retryPath(second,true),"","POST",false),403);assertThat(control.calls.get()).isEqualTo(calls);
            ALLOWED.set(false);
            privateResponse(get(statusPath(first)),404);privateResponse(send(retryPath(first,true),"","POST",true),404);
            hold.close();var media=store.find(OWNER.tenantId(),first).orElseThrow();
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!queue.status(media,OWNER,1).state().equals("failed")&&System.nanoTime()<end)Thread.sleep(10);
            assertThat(queue.status(media,OWNER,1).state()).isEqualTo("failed");privateResponse(get(source(first)),404);
            ALLOWED.set(true);noSources(await(first,"failed"),"PREVIEW_FAILED");privateResponse(get(source(first)),404);
            var retry=send(retryPath(first,true),"","POST",true);assertThat(retry.statusCode()).isIn(200,202);verifyReady(first);
        }finally{ALLOWED.set(true);}
    }
    @Test void nativeOpenFailureDoesNotLeakDetailsOrPoisonNextRequest()throws Exception {
        try(var hold=control.arm(true)){
            String first=upload();hold.awaitEntered();hold.close();var failed=await(first,"failed");noSources(failed,"PREVIEW_FAILED");
            var page=get("/api/v1/media/"+first+"/pages/1");privateResponse(page,200);noSources(body(page),"PREVIEW_FAILED");
            verifyReady(upload());var retry=send(retryPath(first,true),"","POST",true);assertThat(retry.statusCode()).isIn(200,202);verifyReady(first);
        }
    }
}
