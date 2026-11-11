package co.kr.satu.imagegate.media.config;

import co.kr.satu.imagegate.system.config.ImageGateProperties;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

/** The pinned Tomcat HTTP/1.1 connector uses an idle read timeout, never a total transfer deadline. */
@Configuration(proxyBeanMethods=false)
public class UploadReadTimeoutConfiguration {
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<UploadTimeoutErrorFilter> uploadTimeoutErrorRegistration(){
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new UploadTimeoutErrorFilter());
        registration.setDispatcherTypes(jakarta.servlet.DispatcherType.ERROR);
        registration.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);return registration;
    }
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> uploadReadTimeoutCustomizer(ImageGateProperties properties,Environment environment){
        if(environment.getProperty("server.http2.enabled",Boolean.class,false))
            throw new IllegalStateException("Upload idle timeout requires an HTTP/1.1 backend connector");
        int timeout=Math.toIntExact(properties.upload().bodyReadIdleTimeout().toMillis());
        return factory->factory.addConnectorCustomizers(connector->{
            if(!(connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> http))
                throw new IllegalStateException("Unsupported upload timeout connector");
            http.setDisableUploadTimeout(false);
            http.setConnectionUploadTimeout(timeout);
        });
    }
}
