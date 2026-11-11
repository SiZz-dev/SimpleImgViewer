package co.kr.satu.imagegate.media.config;

import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class MediaConfiguration {
    @Bean @ConditionalOnMissingBean(MediaStore.class)
    MediaStore mediaStore(ImageGateProperties properties) throws java.io.IOException {
        return new LocalMediaStore(properties.storage().originalsPath(),properties.upload().maxFileBytes(),properties.upload().minFreeBytes(),properties.upload().tenantOriginalMaxBytes());
    }

    @Bean @ConditionalOnMissingBean(MediaAuthorizationPolicy.class)
    MediaAuthorizationPolicy ownerOnlyPolicy() {
        return (actor,media,action) -> actor.tenantId().equals(media.tenantId())
                && actor.userId().equals(media.ownerId());
    }
}
