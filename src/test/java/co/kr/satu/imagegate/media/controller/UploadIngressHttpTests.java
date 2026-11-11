package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Real loopback parser tests. Synthetic credentials exist in test sources only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=optional:classpath:/imagegate-upload-test-absent.yml","server.address=127.0.0.1","server.ssl.enabled=false",
    "imagegate.upload.max-file-bytes=1024","imagegate.editing.max-stamp-upload-bytes=1024","imagegate.upload.multipart-overhead-bytes=1024",
    "imagegate.upload.multipart-max-concurrent=1","imagegate.upload.min-free-bytes=0","imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false",
    "imagegate.playback.enabled=false","imagegate.playback.cache.enabled=false","imagegate.conversion.enabled=false","imagegate.audit.enabled=false"})
@Import(UploadIngressHttpTests.Fixture.class)
@Timeout(value=60,unit=TimeUnit.SECONDS)
class UploadIngressHttpTests {
    static final String TOKEN=UUID.randomUUID().toString();
    static volatile java.util.concurrent.CountDownLatch RECEIVING=new java.util.concurrent.CountDownLatch(1);
    static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-http-ingress-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());}
    @TestConfiguration(proxyBeanMethods=false) static class Fixture {
        @Bean static BeanPostProcessor identity(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                if(name.equals("securityFilterChain")&&bean instanceof DefaultSecurityFilterChain chain){
                    int index=-1;for(int i=0;i<chain.getFilters().size();i++)if(chain.getFilters().get(i) instanceof SecurityContextHolderFilter)index=i;
                    if(index<0)throw new IllegalStateException("Missing production security context filter");
                    chain.getFilters().add(index+1,new OncePerRequestFilter(){
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next)throws ServletException,IOException {
                            if(("Bearer "+TOKEN).equals(request.getHeader("Authorization")))SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("tenant","owner"),"fixture",List.of()));
                            next.doFilter(request,response);
                        }
                    });
                    int ingress=-1;for(int i=0;i<chain.getFilters().size();i++)if(chain.getFilters().get(i) instanceof co.kr.satu.imagegate.media.config.MultipartIngressFilter)ingress=i;
                    if(ingress<0)throw new IllegalStateException("Missing production multipart admission filter");
                    chain.getFilters().add(ingress+1,new OncePerRequestFilter(){
                        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next)throws ServletException,IOException {
                            if("hold".equals(request.getHeader("X-Test-Receiving")))RECEIVING.countDown();next.doFilter(request,response);
                        }
                    });
                }return bean;
            }
        };}
    }
    @LocalServerPort int port;HttpClient client;String csrfHeader,csrfToken;
    HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+TOKEN);}
    @BeforeEach void connect()throws Exception {
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
            .cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).proxy(new ProxySelector(){public List<Proxy>select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();
        var response=client.send(request("/api/v1/viewer/bootstrap").GET().build(),HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isEqualTo(200);
        var csrf=JsonMapper.builder().build().readTree(response.body()).get("csrf");csrfHeader=csrf.get("headerName").asText();csrfToken=csrf.get("token").asText();
    }
    @AfterEach void close(){if(client!=null)client.close();}
    byte[] png()throws Exception{var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(12,10,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    HttpResponse<String> upload(byte[] file,boolean chunked,int extra,boolean csrf)throws Exception {
        String boundary="ImageGate"+UUID.randomUUID();var out=new ByteArrayOutputStream();
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(file);out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        if(extra>0){out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"extra\"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(new byte[extra]);out.write("\r\n".getBytes(StandardCharsets.US_ASCII));}
        out.write(("--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));byte[] body=out.toByteArray();
        var builder=request("/api/v1/media").header("Content-Type","multipart/form-data; boundary="+boundary);
        if(csrf)builder.header(csrfHeader,csrfToken);
        return client.send(builder.POST(chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(body)):HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    void assertCleanSpool()throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);long count;
        do{try(var paths=Files.list(ROOT.resolve("spool"))){count=paths.count();}if(count==0)return;Thread.sleep(20);}while(System.nanoTime()<until);
        assertThat(count).isZero();
    }
    void assert413(HttpResponse<String> response)throws Exception {
        assertThat(response.statusCode()).isEqualTo(413);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.body()).contains("FILE_TOO_LARGE");
        assertThat(JsonMapper.builder().build().readTree(response.body()).get("code").asText()).isEqualTo("FILE_TOO_LARGE");assertThat(response.body()).doesNotContain(ROOT.toString(),"synthetic.png");assertCleanSpool();
    }
    record Receiving(Socket socket,byte[] body,int sent) implements AutoCloseable {
        public void close()throws IOException{socket.close();}
    }
    Receiving beginReceiving()throws Exception {
        RECEIVING=new java.util.concurrent.CountDownLatch(1);String boundary="ImageGateReceiving";
        var body=new ByteArrayOutputStream();body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII));body.write(png());body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] bytes=body.toByteArray();var cookies=((CookieManager)client.cookieHandler().orElseThrow()).getCookieStore().getCookies();
        String cookie=cookies.stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));assertThat(cookie).isNotEmpty();
        String headers="POST /api/v1/media HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nAuthorization: Bearer "+TOKEN+"\r\nCookie: "+cookie+"\r\n"+csrfHeader+": "+csrfToken+"\r\nX-Test-Receiving: hold\r\nContent-Type: multipart/form-data; boundary="+boundary+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n";
        var socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(10000);
        try {
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(bytes,0,40);socket.getOutputStream().flush();
            assertThat(RECEIVING.await(5,TimeUnit.SECONDS)).isTrue();return new Receiving(socket,bytes,40);
        }catch(Exception|AssertionError e){socket.close();throw e;}
    }
    @Test void stalledReceptionRejectsAnotherMultipartButViewingContinues()throws Exception {
        try(var receiving=beginReceiving()) {
            var rejected=upload(png(),false,0,true);assertThat(rejected.statusCode()).isEqualTo(429);assertThat(rejected.body()).contains("UPLOAD_INGRESS_BUSY");assertThat(rejected.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
            assertThat(client.send(request("/api/v1/viewer/bootstrap").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            receiving.socket().getOutputStream().write(receiving.body(),receiving.sent(),receiving.body().length-receiving.sent());receiving.socket().getOutputStream().flush();
            String response=new String(receiving.socket().getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(response.split("\r\n",2)[0]).contains("201");
        }
        assertCleanSpool();assertThat(upload(png(),false,0,true).statusCode()).isEqualTo(201);
    }
    @Test void disconnectedSenderReleasesReceptionSlotAndTemporaryParts()throws Exception {
        try(var receiving=beginReceiving()){/* Close before sending the remainder. */}
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);HttpResponse<String> next;
        do{next=upload(png(),false,0,true);if(next.statusCode()!=429)break;Thread.sleep(20);}while(System.nanoTime()<until);
        assertThat(next.statusCode()).isEqualTo(201);assertCleanSpool();
    }

    @Test void realMultipartUploadSucceedsAndTempFilesAreRemoved()throws Exception {assertThat(upload(png(),false,0,true).statusCode()).isEqualTo(201);assertCleanSpool();}
    @Test void oversizedFileReturns413AndNextUploadWorks()throws Exception {assert413(upload(Arrays.copyOf(png(),1536),false,0,true));assertThat(upload(png(),false,0,true).statusCode()).isEqualTo(201);assertCleanSpool();}
    @Test void unknownLengthTotalRequestIsBoundedAndTempFilesAreRemoved()throws Exception {assert413(upload(png(),true,2200,true));assertThat(upload(png(),true,0,true).statusCode()).isEqualTo(201);assertCleanSpool();}
    @Test void productionCsrfAndAuthenticationRemainRequired()throws Exception {
        assertThat(upload(png(),false,0,false).statusCode()).isEqualTo(403);assertCleanSpool();
        var anonymous=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/viewer/bootstrap")).GET().build();assertThat(client.send(anonymous,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }
}
