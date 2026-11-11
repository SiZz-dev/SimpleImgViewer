package co.kr.satu.imagegate.system.config;

import static org.assertj.core.api.Assertions.assertThat;

import co.kr.satu.imagegate.system.service.FeaturePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.config.import=file:config/imagegate.example.yml",
        "imagegate.features.image.stamp-upload=false"
})
class ExternalYamlLoadingTests {
    @Autowired ImageGateProperties properties;
    @Autowired FeaturePolicy policy;

    @Test
    void springConfigDataLoadsExternalYamlAndHonorsHigherPriorityOverrides() {
        assertThat(properties.storage().convertedPath())
                .isEqualTo(java.nio.file.Path.of("data/converted").toAbsolutePath());
        assertThat(properties.conversion().enabled()).isFalse();
        assertThat(policy.isEnabled(FeaturePolicy.Feature.STAMPS)).isTrue();
        assertThat(policy.isEnabled(FeaturePolicy.Feature.STAMP_UPLOAD)).isFalse();
    }
}
