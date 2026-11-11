package co.kr.satu.imagegate.viewer.controller;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
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

/** Real converter, storage, security/CSRF, HTTP and browser; only trusted identity is test-specific. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=optional:classpath:/native-viewer-no-installation.yml","server.address=127.0.0.1","server.ssl.enabled=false",
    "imagegate.conversion.enabled=true","imagegate.tiles.enabled=true","imagegate.tiles.min-free-bytes=0",
    "imagegate.tiles.maintenance.enabled=false","imagegate.preview.enabled=true","imagegate.preview.cache.enabled=false",
    "imagegate.playback.enabled=false","imagegate.playback.cache.enabled=false","imagegate.audit.enabled=false",
    "imagegate.upload.min-free-bytes=0","imagegate.upload.tenant-original-max-bytes=0"})
@Import(NativeViewerIntegrationTests.Identity.class) @DirtiesContext
@Tag("img2tile") @Tag("native-viewer") @EnabledOnOs(OS.WINDOWS)
class NativeViewerIntegrationTests {
    static final String TOKEN=UUID.randomUUID().toString();static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-native-viewer-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());}
    @TestConfiguration(proxyBeanMethods=false) static class Identity {
        @Bean static BeanPostProcessor fixtureIdentity(){return new BeanPostProcessor(){public Object postProcessAfterInitialization(Object bean,String name){
            if(name.equals("securityFilterChain")&&bean instanceof DefaultSecurityFilterChain chain){
                int position=-1;for(int i=0;i<chain.getFilters().size();i++)if(chain.getFilters().get(i) instanceof SecurityContextHolderFilter)position=i;
                if(position<0)throw new IllegalStateException("Security boundary missing");
                chain.getFilters().add(position+1,new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next)throws ServletException,IOException{
                    if(("Bearer "+TOKEN).equals(request.getHeader("Authorization")))SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("synthetic","owner"),"test",List.of()));
                    next.doFilter(request,response);
                }});
            }return bean;
        }};}
    }
    @LocalServerPort int port;@Autowired MediaStore store;
    @Test @Timeout(150) void nativeTiffGeometryEnablesRotatedMouseEditingInChrome()throws Exception{
        var output=new ByteArrayOutputStream();var image=new BufferedImage(1026,514,BufferedImage.TYPE_INT_RGB);
        try{for(int y=0;y<514;y++)for(int x=0;x<1026;x++)image.setRGB(x,y,((x/64+y/64)%2==0)?0xffbedbff:0xffdbeafe);assertThat(ImageIO.write(image,"TIFF",output)).isTrue();}finally{image.flush();}
        var media=store.register("synthetic","owner","synthetic.tif",new ByteArrayInputStream(output.toByteArray()));
        String node=System.getenv("IMAGEGATE_NODE");assertThat(node).as("Set IMAGEGATE_NODE for opt-in browser integration").isNotBlank();
        assertThat(System.getenv("IMAGEGATE_NODE_MODULES")).isNotBlank();assertThat(System.getenv("IMAGEGATE_BROWSER")).isNotBlank();
        Path log=Path.of("build/native-viewer-tests/process.log");Files.createDirectories(log.getParent());
        var process=new ProcessBuilder(node,"scripts/test-native-viewer.mjs","http://127.0.0.1:"+port,TOKEN,media.mediaId()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertThat(process.waitFor(120,TimeUnit.SECONDS)).as("Browser deadline").isTrue();assertThat(process.exitValue()).as("%s",Files.readString(log)).isZero();}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
}
