package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

/** Loopback-only test fixture. No authentication bean/controller is added to src/main or the application JAR. */
@Tag("http-performance")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=optional:classpath:/imagegate-http-benchmark-absent.yml",
        "server.address=127.0.0.1","server.port=0","server.ssl.enabled=false","server.forward-headers-strategy=none",
        "imagegate.preview.enabled=true","imagegate.playback.enabled=false","imagegate.audit.enabled=true",
        "imagegate.preview.workers=2","imagegate.preview.queue-capacity=32",
        "imagegate.preview.max-width=1600","imagegate.preview.max-height=1600",
        "imagegate.preview.max-source-pixels=16000000","imagegate.preview.thumbnail-enabled=true","imagegate.preview.thumbnail-max-edge=256"})
@Import({ViewerPerformanceTests.PolicyConfiguration.class,HttpViewerPerformanceTests.HttpFixtureConfiguration.class})
class HttpViewerPerformanceTests extends ViewerPerformanceTests {
    private static final String OWNER=UUID.randomUUID().toString(),OTHER=UUID.randomUUID().toString(),TENANT=UUID.randomUUID().toString();
    private static final Map<String,ViewerPrincipal> IDENTITIES=Map.of(
        "Bearer "+OWNER,new ViewerPrincipal("benchmark","owner"),"Bearer "+OTHER,new ViewerPrincipal("benchmark","other"),
        "Bearer "+TENANT,new ViewerPrincipal("other","owner"));
    @TestConfiguration(proxyBeanMethods=false) static class HttpFixtureConfiguration {
        @Bean static BeanPostProcessor benchmarkIdentityFixture(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                if(name.equals("securityFilterChain")&&bean instanceof DefaultSecurityFilterChain chain){
                    int position=-1;
                    for(int i=0;i<chain.getFilters().size();i++)if(chain.getFilters().get(i) instanceof SecurityContextHolderFilter)position=i;
                    if(position<0)throw new IllegalStateException("Production security context boundary unavailable");
                    chain.getFilters().add(position+1,new OncePerRequestFilter(){
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next) throws ServletException,IOException {
                            String credential=request.getHeader("Authorization");
                            var actor=credential==null?null:IDENTITIES.get(credential);
                            if(actor!=null)SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,"fixture",List.of()));
                            next.doFilter(request,response);
                        }
                    });
                }
                return bean;
            }
        };}
    }
    @LocalServerPort int port;
    final HttpClient ownerHttp=client(),otherHttp=client(),tenantHttp=client(),anonymousHttp=client();
    String csrfHeader,csrfToken;
    HttpClient client(){return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER))
        .proxy(new ProxySelector(){public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI uri,SocketAddress address,IOException e){}}).build();}
    HttpRequest.Builder request(String path,String token){
        if(!path.startsWith("/api/v1/")||path.indexOf('\r')>=0||path.indexOf('\n')>=0)throw new IllegalArgumentException("Expected local API path");
        URI origin=URI.create("http://127.0.0.1:"+port),uri=origin.resolve(path);
        if(!uri.getHost().equals("127.0.0.1")||uri.getPort()!=port||!uri.getScheme().equals("http"))throw new IllegalArgumentException("Foreign benchmark destination");
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120));
        if(token!=null)builder.header("Authorization","Bearer "+token);return builder;
    }
    HttpResponse<byte[]> get(String path,HttpClient client,String token) throws Exception {
        return client.send(request(path,token).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    void boot() throws Exception {
        if(csrfToken!=null)return;
        assertThat(InetAddress.getByName("127.0.0.1").isLoopbackAddress()).isTrue();
        var response=get("/api/v1/viewer/bootstrap",ownerHttp,OWNER);assertThat(response.statusCode()).isEqualTo(200);
        var csrf=json.readTree(response.body()).get("csrf");csrfHeader=csrf.get("headerName").asText();csrfToken=csrf.get("token").asText();
        assertThat(csrfHeader).matches("[A-Za-z0-9-]{1,80}");assertThat(csrfToken).isNotBlank();
        // Exercise actual CSRF rejection before any measured upload. No fake CSRF request postprocessor.
        var denied=ownerHttp.send(request("/api/v1/media",OWNER).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(denied.body()).get("code").asText()).isEqualTo("ACCESS_DENIED");
    }
    @Override Source prepare(Fixture f) throws Exception {
        boot();String boundary="imagegate-"+UUID.randomUUID();
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic."+f.format()+"\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] suffix=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII);
        var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),HttpRequest.BodyPublishers.ofByteArray(f.bytes()),HttpRequest.BodyPublishers.ofByteArray(suffix));
        var response=ownerHttp.send(request("/api/v1/media",OWNER).header(csrfHeader,csrfToken).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(201);assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        String id=json.readTree(response.body()).get("mediaId").asText();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(System.nanoTime()<deadline){
            var stateResponse=get("/api/v1/media/"+id+"/status",ownerHttp,OWNER);assertThat(stateResponse.statusCode()).isEqualTo(200);
            var state=json.readTree(stateResponse.body());String value=state.get("state").asText();
            if(value.equals("ready")){var source=state.get("source");var thumb=state.get("thumbnail");return new Source(id,source.get("url").asText(),source.get("width").asInt(),source.get("height").asInt(),thumb.get("url").asText(),thumb.get("width").asInt(),thumb.get("height").asInt());}
            if(value.equals("failed"))throw new AssertionError("HTTP synthetic preview failed: "+state.get("errorCode"));
            Thread.sleep(10);
        }
        throw new AssertionError("HTTP synthetic preview deadline exceeded");
    }
    @Override byte[] download(Source source) throws Exception {return download(source,false);}
    @Override byte[] download(Source source,boolean thumbnail) throws Exception {
        var response=get(thumbnail?source.thumbnailUrl():source.url(),ownerHttp,OWNER);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);assertThat(response.headers().firstValue("Content-Type")).contains("image/png");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.body().length).isPositive();assertThat(response.headers().firstValueAsLong("Content-Length").orElseThrow()).isEqualTo(response.body().length);return response.body();
    }
    @Override void verifyHead(Source source,long expected) throws Exception {
        var response=ownerHttp.send(request(source.url(),OWNER).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.body()).isEmpty();
        assertThat(response.headers().firstValue("Content-Type")).contains("image/png");assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValueAsLong("Content-Length").orElseThrow()).isEqualTo(expected);
    }
    @Override void security(Source source) throws Exception {
        for(String path:List.of(source.url(),source.thumbnailUrl())){
            assertThat(get(path,anonymousHttp,null).statusCode()).isEqualTo(401);
            assertThat(get(path,otherHttp,OTHER).statusCode()).isEqualTo(404);
            assertThat(get(path,tenantHttp,TENANT).statusCode()).isEqualTo(404);
            ALLOWED.set(false);try{assertThat(get(path,ownerHttp,OWNER).statusCode()).isEqualTo(404);}finally{ALLOWED.set(true);}
        }
    }
    @Override @Test @Timeout(value=10,unit=TimeUnit.MINUTES)
    void coldWarmAndConcurrentPreviewBaseline() throws Exception {super.coldWarmAndConcurrentPreviewBaseline();}
    @AfterEach void closeClients(){ownerHttp.close();otherHttp.close();tenantHttp.close();anonymousHttp.close();}
    @Override void writeReport(boolean passed,int cold,int warm,double budget) throws Exception {
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion",1);report.put("generatedAt",Instant.now().toString());report.put("passed",passed);
        report.put("measurement","Real loopback Tomcat HTTP/1.1 with production security chain and test-only identity fixture; excludes SSO, TLS, browser and server startup; OS page cache retained");
        report.put("javaVersion",System.getProperty("java.version"));report.put("osName",System.getProperty("os.name"));report.put("osVersion",System.getProperty("os.version"));
        report.put("architecture",System.getProperty("os.arch"));report.put("processors",Runtime.getRuntime().availableProcessors());report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());
        report.put("transport","JDK HttpClient, HTTP/1.1, direct loopback, redirects disabled, reusable client connections");
        report.put("authentication","random ephemeral test-only fixture principals; actual production CSRF and per-file ACL enforced; no SSO timing");
        report.put("coldSamples",cold);report.put("warmRequestsPerPhase",warm);report.put("maxWarmP95Ms",budget);report.put("nativeImg2tile",nativeEnabled());
        report.put("previewWorkers",2);report.put("previewMaxEdge",1600);report.put("thumbnailMaxEdge",256);
        report.put("memoryMeaning","20ms sampled JVM heap includes server, synthetic fixtures and full JDK HTTP client response buffers; not RSS, native memory or separate server-only memory");
        report.put("fixtures",fixtures);report.put("phases",rows);
        Path directory=Path.of(System.getProperty("imagegate.performance.reportDirectory","build/reports/http-performance")).toAbsolutePath();Files.createDirectories(directory);
        String name="http-viewer-"+Instant.now().toEpochMilli();Files.writeString(directory.resolve(name+".json"),json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        var md=new StringBuilder("# Loopback HTTP viewer performance\n\nPassed: "+passed+". Real Tomcat + JDK HTTP/1.1 client; synthetic principals and images; excludes SSO/TLS/browser/startup. OS page cache retained.\n\n");
        md.append("| Fixture | Phase | Clients | Samples | P50 ms | P95 ms | Max ms | completions/s | Sampled heap MiB |\n| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for(var row:rows)md.append(String.format(Locale.ROOT,"| %s | %s | %s | %s | %.2f | %.2f | %.2f | %.2f | %.1f |%n",row.get("fixture"),row.get("phase"),row.get("concurrency"),row.get("requests"),row.get("p50Ms"),row.get("p95Ms"),row.get("maxMs"),row.get("requestsPerSecond"),((Long)row.get("sampledPeakHeapBytes"))/1048576.0));
        md.append("\nCold rate counts whole register/poll/download workflows; warm rate counts completed HTTP requests. Small synthetic samples are not a production SLA. Client buffers share the server JVM.\n");Files.writeString(directory.resolve(name+".md"),md.toString());
    }
}
