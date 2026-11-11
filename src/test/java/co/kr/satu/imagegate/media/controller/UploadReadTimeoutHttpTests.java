package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;

/** Real HTTP/1.1 sockets, isolated files, test-only identity; never uses business data. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=optional:classpath:/imagegate-upload-timeout-absent.yml","server.address=127.0.0.1","server.ssl.enabled=false",
    "imagegate.upload.max-file-bytes=8192","imagegate.editing.max-stamp-upload-bytes=1024","imagegate.upload.multipart-overhead-bytes=1024",
    "imagegate.upload.body-read-idle-timeout=2s","imagegate.upload.multipart-max-concurrent=1","imagegate.upload.min-free-bytes=0",
    "imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false","imagegate.playback.enabled=false","imagegate.playback.cache.enabled=false",
    "imagegate.conversion.enabled=false","imagegate.audit.enabled=false"})
@Import(UploadIngressHttpTests.Fixture.class)
@Timeout(value=30,unit=TimeUnit.SECONDS)
class UploadReadTimeoutHttpTests {
    static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-http-idle-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());}
    @LocalServerPort int port;
    @Autowired ServletWebServerApplicationContext context;
    UploadIngressHttpTests http;
    @BeforeEach void connect()throws Exception{
        http=new UploadIngressHttpTests();http.port=port;http.connect();
        var protocol=(org.apache.coyote.http11.AbstractHttp11Protocol<?>)((TomcatWebServer)context.getWebServer()).getTomcat().getConnector().getProtocolHandler();
        assertThat(protocol.getDisableUploadTimeout()).isFalse();assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(2000);
    }
    @AfterEach void close(){if(http!=null)http.close();}
    record Receiving(Socket socket,byte[] body,int sent,boolean chunked) implements AutoCloseable{
        public void close()throws IOException{socket.close();}
        void send(byte[] bytes,int offset,int count)throws IOException{
            var output=socket.getOutputStream();if(chunked)output.write((Integer.toHexString(count)+"\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(bytes,offset,count);if(chunked)output.write("\r\n".getBytes(StandardCharsets.US_ASCII));output.flush();
        }
    }
    Receiving begin(boolean chunked)throws Exception{
        UploadIngressHttpTests.RECEIVING=new java.util.concurrent.CountDownLatch(1);String boundary="ImageGateIdle";
        byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
        var data=new ByteArrayOutputStream();data.write(prefix);data.write(Arrays.copyOf(http.png(),4096));data.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));byte[] body=data.toByteArray();
        String cookie=((CookieManager)http.client.cookieHandler().orElseThrow()).getCookieStore().getCookies().stream().map(c->c.getName()+"="+c.getValue()).collect(java.util.stream.Collectors.joining("; "));
        String headers="POST /api/v1/media HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nAuthorization: Bearer "+UploadIngressHttpTests.TOKEN+"\r\nCookie: "+cookie+"\r\n"+http.csrfHeader+": "+http.csrfToken+"\r\nX-Test-Receiving: hold\r\nContent-Type: multipart/form-data; boundary="+boundary+"\r\n"+(chunked?"Transfer-Encoding: chunked":"Content-Length: "+body.length)+"\r\nConnection: close\r\n\r\n";
        var socket=new Socket();socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(10000);
        try{
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));var receiving=new Receiving(socket,body,prefix.length+2048,chunked);receiving.send(body,0,receiving.sent());
            assertThat(UploadIngressHttpTests.RECEIVING.await(5,TimeUnit.SECONDS)).isTrue();return receiving;
        }catch(Exception|AssertionError e){socket.close();throw e;}
    }
    long spoolCount()throws IOException{try(var files=Files.list(ROOT.resolve("spool"))){return files.count();}}
    void awaitPartialFile()throws Exception{
        long until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1500);
        do{if(spoolCount()>0)return;Thread.sleep(10);}while(System.nanoTime()<until);
        assertThat(spoolCount()).as("Parser must have created a partial temporary file").isPositive();
    }
    void assertClean()throws Exception{
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);do{if(spoolCount()==0)return;Thread.sleep(20);}while(System.nanoTime()<until);assertThat(spoolCount()).isZero();
    }
    void stoppedSender(boolean chunked)throws Exception{
        try(var receiving=begin(chunked)){
            awaitPartialFile();assertThat(http.upload(http.png(),false,0,true).statusCode()).isEqualTo(429);
            assertThat(http.client.send(http.request("/api/v1/viewer/bootstrap").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            String response=new String(receiving.socket().getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            assertThat(response.split("\r\n",2)[0]).contains("408");assertThat(response).contains("UPLOAD_READ_TIMEOUT","no-store").doesNotContain(ROOT.toString(),"SocketTimeoutException");
        }
        assertClean();assertThat(http.upload(http.png(),false,0,true).statusCode()).isEqualTo(201);assertClean();
    }
    @Test void stoppedFixedLengthBodyTimesOutAndReleasesPartsAndSlot()throws Exception{stoppedSender(false);}
    @Test void stoppedChunkedBodyTimesOutAndReleasesPartsAndSlot()throws Exception{stoppedSender(true);}
    @Test void continuingProgressMayExceedIdleTimeoutInTotal()throws Exception{
        long start=System.nanoTime();
        try(var receiving=begin(false)){
            awaitPartialFile();int offset=receiving.sent();
            while(offset<receiving.body().length){Thread.sleep(600);int count=Math.min(400,receiving.body().length-offset);receiving.send(receiving.body(),offset,count);offset+=count;}
            String response=new String(receiving.socket().getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(response.split("\r\n",2)[0]).contains("201");
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isGreaterThan(2000);assertClean();
    }
}
