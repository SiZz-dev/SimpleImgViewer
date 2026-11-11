package co.kr.satu.imagegate.editing.config;

import co.kr.satu.imagegate.editing.repository.*;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import java.io.IOException;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class EditingConfiguration {
    @Bean AnnotationHistoryStore annotationHistoryStore(ImageGateProperties properties){
        return new AnnotationHistoryStore(properties.editing().history(),java.time.Clock.systemUTC());
    }
    @Bean(destroyMethod="close") AnnotationStore annotationStore(ImageGateProperties properties) throws IOException {
        return new AnnotationStore(properties.storage().annotationsPath());
    }
    @Bean(destroyMethod="close") StampStore stampStore(ImageGateProperties properties) throws IOException {
        return new StampStore(properties.storage().stampsPath(),properties.editing());
    }
}
