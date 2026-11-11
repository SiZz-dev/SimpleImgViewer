package co.kr.satu.imagegate.viewer.config;

import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.viewer.repository.ViewerSessionStore;
import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
public class ViewerConfiguration {
    @Bean ViewerSessionStore viewerSessionStore(ImageGateProperties properties) {
        return new ViewerSessionStore(properties.viewer(),Clock.systemUTC());
    }
}
