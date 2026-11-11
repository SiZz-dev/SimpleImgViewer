package co.kr.satu.imagegate.media.controller;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;

/** Runs the actual optional backend YAML against embedded Tomcat; no proxy binary is simulated. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=file:./config/proxy/imagegate.backend.example.yml","server.port=0","server.ssl.enabled=false",
    "imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false","imagegate.playback.enabled=false",
    "imagegate.playback.cache.enabled=false","imagegate.conversion.enabled=false","imagegate.audit.enabled=false"})
@Import(UploadIngressHttpTests.Fixture.class)
@Timeout(value=30,unit=TimeUnit.SECONDS)
class ProxyForwardingHttpTests {
    static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-proxy-headers-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());}
    @LocalServerPort int port;
    record Result(int status,boolean secureCookie){}
    Result request(String peer,String proto,boolean auth,boolean forwarded)throws Exception{
        try(var socket=new Socket()){
            socket.bind(new InetSocketAddress(peer,0));socket.connect(new InetSocketAddress("127.0.0.1",port),5000);socket.setSoTimeout(10000);
            String headers="GET /api/v1/viewer/bootstrap HTTP/1.1\r\nHost: imagegate.example.invalid\r\nX-Forwarded-Proto: "+proto+"\r\nX-Forwarded-For: 203.0.113.10\r\n"+(auth?"Authorization: Bearer "+UploadIngressHttpTests.TOKEN+"\r\n":"X-Authenticated-User: owner\r\nX-Tenant-Id: tenant\r\n")+(forwarded?"Forwarded: for=203.0.113.10;proto=https;host=untrusted.invalid\r\n":"")+"Connection: close\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().flush();
            String response=new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8);String head=response.split("\r\n\r\n",2)[0];
            int status=Integer.parseInt(head.split("\r\n",2)[0].split(" ")[1]);
            boolean secure=head.lines().map(l->l.toLowerCase(Locale.ROOT)).filter(l->l.startsWith("set-cookie:")).anyMatch(l->l.contains("; secure"));return new Result(status,secure);
        }
    }
    @Test void trustedLoopbackProxyHttpsProducesSecureSessionCookie()throws Exception{var r=request("127.0.0.1","https",true,false);assertThat(r.status()).isEqualTo(200);assertThat(r.secureCookie()).isTrue();}
    @Test void untrustedLoopbackPeerCannotForgeHttpsForwarding()throws Exception{var r=request("127.0.0.2","https",true,false);assertThat(r.status()).isEqualTo(200);assertThat(r.secureCookie()).isFalse();}
    @Test void rfcForwardedHeaderCannotOverrideSelectedNativeProtocolHeader()throws Exception{var r=request("127.0.0.1","http",true,true);assertThat(r.status()).isEqualTo(200);assertThat(r.secureCookie()).isFalse();}
    @Test void forwardingAndIdentityHeadersNeverCreateAuthentication()throws Exception{assertThat(request("127.0.0.1","https",false,true).status()).isEqualTo(401);}
}
