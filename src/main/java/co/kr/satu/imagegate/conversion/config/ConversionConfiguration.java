package co.kr.satu.imagegate.conversion.config;

import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.conversion.service.ImageConverter;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TileConverter;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import java.io.IOException;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ConversionConfiguration {
    @Bean
    @ConditionalOnProperty(name = "imagegate.conversion.enabled", havingValue = "true")
    Img2TileConverter imageConverter(ImageGateProperties properties) throws IOException {
        var configuration = properties.conversion();
        if (configuration.module() != ImageGateProperties.ImageModule.IMG2TILE) {
            throw new IllegalStateException("Selected conversion module is not implemented");
        }
        return new Img2TileConverter(Path.of(configuration.modules().img2tile().executable()),
                properties.storage().originalsPath(), properties.storage().convertedPath(),
                configuration.timeout(), new BoundedProcessExecutor(configuration.maxConcurrentJobs()));
    }
}
