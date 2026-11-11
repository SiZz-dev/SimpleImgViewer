package co.kr.satu.imagegate.conversion.config;

import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.service.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class PreviewConfiguration {
    @Bean PreviewCache previewCache(ImageGateProperties properties){return new PreviewCache(properties.storage().convertedPath());}

    @Bean PreviewMetrics previewMetrics(ImageGateProperties properties,ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registries){
        return properties.preview().metricsEnabled()?new PreviewMetrics(registries.getObject()):PreviewMetrics.NONE;
    }

    // The queue owns renderer shutdown, including admission waiters.
    @Bean(destroyMethod="")
    PreviewRenderer previewRenderer(ImageGateProperties properties,MediaStore store,
            MediaAuthorizationPolicy authorization,FeaturePolicy features,ObjectProvider<ImageConverter> converters,PreviewMetrics metrics){
        return new ImagePreviewRenderer(metrics,store,authorization,features,properties.preview(),converters.getIfAvailable());
    }

    @Bean(destroyMethod="close")
    PreviewQueue previewQueue(ImageGateProperties properties,PreviewRenderer renderer,
            ObjectProvider<ImageConverter> converters,PreviewCache cache,PreviewMetrics metrics)
            throws IOException {
        var converter=converters.getIfAvailable();
        String engine=converter==null ? "jdk-imageio-v1" : "jdk-imageio-v1/"+converter.cacheIdentity();
        return new PreviewQueue(properties.preview(),engine,cache,renderer,properties.audit().enabled(),metrics);
    }
}
