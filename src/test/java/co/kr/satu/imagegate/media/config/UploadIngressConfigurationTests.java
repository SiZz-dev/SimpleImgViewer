package co.kr.satu.imagegate.media.config;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import jakarta.servlet.MultipartConfigElement;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
class UploadIngressConfigurationTests {
    @TempDir Path temp;
    ApplicationContextRunner runner(){return new ApplicationContextRunner().withInitializer(context->{
        try{for(var source:new YamlPropertySourceLoader().load("defaults",new ClassPathResource("application.yml")))context.getEnvironment().getPropertySources().addLast(source);}
        catch(java.io.IOException e){throw new IllegalStateException(e);}
    }).withUserConfiguration(Config.class).withPropertyValues("imagegate.storage.root="+temp.resolve("data"),"imagegate.upload.multipart-directory="+temp.resolve("spool"));}
    @Configuration(proxyBeanMethods=false) @EnableConfigurationProperties(ImageGateProperties.class) @Import({UploadIngressConfiguration.class,UploadReadTimeoutConfiguration.class}) static class Config {}
    @Test @SuppressWarnings("unchecked") void appliesSeparateBodyIdleTimeoutWithoutChangingHeaderTimeout(){
        runner().withPropertyValues("imagegate.upload.body-read-idle-timeout=2s").run(c->{
            assertThat(c).hasNotFailed();var factory=new org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory();
            ((org.springframework.boot.web.server.WebServerFactoryCustomizer<org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory>)c.getBean("uploadReadTimeoutCustomizer")).customize(factory);
            var connector=new org.apache.catalina.connector.Connector();var protocol=(org.apache.coyote.http11.AbstractHttp11Protocol<?>)connector.getProtocolHandler();protocol.setConnectionTimeout(12345);
            factory.getConnectorCustomizers().forEach(customizer->customizer.customize(connector));
            assertThat(protocol.getDisableUploadTimeout()).isFalse();assertThat(protocol.getConnectionUploadTimeout()).isEqualTo(2000);assertThat(protocol.getConnectionTimeout()).isEqualTo(12345);
        });
    }
    @Test void unsupportedHttp2BackendFailsInsteadOfSilentlyIgnoringIdlePolicy(){runner().withPropertyValues("server.http2.enabled=true").run(c->assertThat(c).hasFailed());}

    @Test void parserUsesLargestFileLimitAndBoundedOverheadWithDiskThresholdZero(){
        for(long[] caps:new long[][]{{1024,2048},{4096,1024}})runner().withPropertyValues("imagegate.upload.max-file-bytes="+caps[0],"imagegate.editing.max-stamp-upload-bytes="+caps[1],"imagegate.upload.multipart-overhead-bytes=1024").run(c->{
            assertThat(c).hasNotFailed();var config=c.getBean(MultipartConfigElement.class);
            assertThat(config.getMaxFileSize()).isEqualTo(Math.max(caps[0],caps[1]));assertThat(config.getMaxRequestSize()).isEqualTo(Math.max(caps[0],caps[1])+1024);
            assertThat(config.getFileSizeThreshold()).isZero();assertThat(config.getLocation()).isEqualTo(temp.resolve("spool").toAbsolutePath().normalize().toString());
        });
    }
    @Test void rejectsOverlapWithEveryPersistentStoreBeforeCreatingDirectories(){
        for(String path:new String[]{"originals","derived","annotations","stamps"})runner().withPropertyValues("imagegate.upload.multipart-directory="+temp.resolve("data").resolve(path)).run(c->assertThat(c).hasFailed());
        runner().withPropertyValues("imagegate.upload.multipart-directory="+temp.resolve("data")).run(c->assertThat(c).hasFailed());assertThat(Files.exists(temp.resolve("data"))).isFalse();
    }
    @Test void rejectsCompetingSpringMultipartConfiguration(){
        for(String key:new String[]{"max-file-size","max-request-size","file-size-threshold","location","enabled","resolve-lazily"})runner().withPropertyValues("spring.servlet.multipart."+key+"=1").run(c->assertThat(c).hasFailed());
    }
    @Test void invalidOverheadOrUnknownUploadKeyFailsBinding(){
        for(String property:new String[]{"imagegate.upload.multipart-overhead-bytes=1023","imagegate.upload.multipart-overhead-bytes=1048577","imagegate.upload.multipart-overhead-byte=1024","imagegate.upload.multipart-max-concurrent=0","imagegate.upload.multipart-max-concurrent=17","imagegate.upload.body-read-idle-timeout=0s","imagegate.upload.body-read-idle-timeout=500ms","imagegate.upload.body-read-idle-timeout=301s","imagegate.upload.body-read-idle-timeout=1000000001ns"})runner().withPropertyValues(property).run(c->assertThat(c).hasFailed());
    }
    @Test void symlinkOrJunctionSpoolIsRejectedBeforeCreatingChildren() throws Exception {
        Path outside=Files.createDirectory(temp.resolve("outside")),link=temp.resolve("link");
        if(System.getProperty("os.name").startsWith("Windows")) {
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }else Files.createSymbolicLink(link,outside);
        try{runner().withPropertyValues("imagegate.upload.multipart-directory="+link.resolve("child")).run(c->assertThat(c).hasFailed());assertThat(Files.exists(outside.resolve("child"))).isFalse();}
        finally{Files.delete(link);}
    }
}
