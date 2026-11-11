package co.kr.satu.imagegate.media.config;

import co.kr.satu.imagegate.system.config.ImageGateProperties;
import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** One bounded Servlet parser for original and stamp uploads. Stream-specific limits remain in services. */
@Configuration(proxyBeanMethods=false)
public class UploadIngressConfiguration {
    @Bean
    co.kr.satu.imagegate.media.service.MultipartAdmission multipartAdmission(ImageGateProperties properties){return new co.kr.satu.imagegate.media.service.MultipartAdmission(properties.upload().multipartMaxConcurrent());}
    @Bean
    MultipartIngressFilter multipartIngressFilter(co.kr.satu.imagegate.media.service.MultipartAdmission admission){return new MultipartIngressFilter(admission);}
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<MultipartIngressFilter> multipartIngressRegistration(MultipartIngressFilter filter){
        var registration=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);registration.setEnabled(false);return registration;
    }
    @Bean
    MultipartConfigElement multipartConfigElement(ImageGateProperties properties,Environment environment) throws IOException {
        for(String key:new String[]{"max-file-size","max-request-size","file-size-threshold","location","enabled","resolve-lazily"})
            if(environment.containsProperty("spring.servlet.multipart."+key))
                throw new IllegalStateException("Use imagegate.upload settings for multipart ingress");
        long maxFile=Math.max(properties.upload().maxFileBytes(),properties.editing().maxStampUploadBytes());
        Path directory=properties.upload().multipartDirectory().toAbsolutePath().normalize();
        Path prospective=canonicalFuture(directory);
        if(!prospective.equals(directory))throw new IOException("Unsafe multipart directory");
        var storage=properties.storage();
        for(Path stored:new Path[]{storage.originalsPath(),storage.convertedPath(),storage.annotationsPath(),storage.stampsPath()}) {
            Path actual=canonicalFuture(stored);
            if(prospective.startsWith(actual)||actual.startsWith(prospective))throw new IOException("Multipart and persistent storage must not overlap");
        }
        Files.createDirectories(directory);
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)||!directory.toRealPath().equals(directory)||!Files.isWritable(directory))
            throw new IOException("Multipart directory unavailable");
        return new MultipartConfigElement(directory.toString(),maxFile,Math.addExact(maxFile,properties.upload().multipartOverheadBytes()),0);
    }
    private static Path canonicalFuture(Path path) throws IOException {
        Path ancestor=path;
        while(ancestor!=null&&!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS))ancestor=ancestor.getParent();
        if(ancestor==null||!Files.isDirectory(ancestor))throw new IOException("Invalid storage ancestor");
        return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize();
    }
}
