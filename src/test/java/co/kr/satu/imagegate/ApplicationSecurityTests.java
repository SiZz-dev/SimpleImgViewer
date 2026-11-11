package co.kr.satu.imagegate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "spring.config.import=optional:classpath:/imagegate-security-test-absent.yml",
        "imagegate.conversion.enabled=false",
        "imagegate.playback.enabled=false",
        "imagegate.audit.enabled=false"
})
@AutoConfigureMockMvc
class ApplicationSecurityTests {

    private static final Path ROOT = temporaryRoot();

    @DynamicPropertySource
    static void isolatedStorage(DynamicPropertyRegistry properties) {
        properties.add("imagegate.storage.root", () -> ROOT.resolve("data").toString());
        properties.add("imagegate.upload.multipart-directory", () -> ROOT.resolve("spool").toString());
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("imagegate-security-test-");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot create isolated test storage", failure);
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void applicationStartsAndExposesHealthWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void anonymousMediaAccessIsBlockedAndSecurityHeadersArePresent() throws Exception {
        mvc.perform(get("/api/v1/media/example/content"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void stateChangingRequestsRequireCsrfProtection() throws Exception {
        mvc.perform(post("/api/v1/viewer/sessions"))
                .andExpect(status().isForbidden());
    }
}
