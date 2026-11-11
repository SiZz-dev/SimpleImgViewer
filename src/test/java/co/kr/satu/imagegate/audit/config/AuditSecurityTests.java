package co.kr.satu.imagegate.audit.config;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.util.List;
import java.nio.file.*;
import java.io.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false","imagegate.playback.enabled=false","imagegate.playback.cache.enabled=false"})
@AutoConfigureMockMvc
class AuditSecurityTests {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("imagegate-audit-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r){r.add("imagegate.storage.root",ROOT::toString);}
    @Autowired MockMvc mvc;
    final Logger logger=(Logger)LoggerFactory.getLogger("imagegate.audit");ListAppender<ILoggingEvent> appender;
    @BeforeEach void setup(){appender=new ListAppender<>();appender.start();logger.addAppender(appender);}
    @AfterEach void close(){logger.detachAppender(appender);appender.stop();}
    @Test void csrfDenialIsRecordedOnceBeforeControllerWithTrustedActor() throws Exception {
        mvc.perform(post("/api/v1/media").with(authentication(UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("t","u"),"unused",List.of()))))
            .andExpect(status().isForbidden());
        assertThat(appender.list).hasSize(1);assertThat(appender.list.getFirst().getFormattedMessage()).contains("action=MEDIA_REGISTER tenant=t actor=u","status=403");
    }
    @Test void anonymousDenialNeverTrustsIdentityHeaders() throws Exception {
        mvc.perform(post("/api/v1/media").with(csrf()).header("X-User","forged").header("X-Tenant","forged-tenant")).andExpect(status().isUnauthorized());
        assertThat(appender.list).hasSize(1);assertThat(appender.list.getFirst().getFormattedMessage()).contains("tenant=- actor=-","status=401").doesNotContain("forged");
    }
    @Test void successfulRegistrationCapturesServerIdOnlyAndPrivateReadsAreNotAudited() throws Exception {
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"png",out);
        var auth=authentication(UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("t","u"),"unused",List.of()));
        var response=mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","private-filename.png","image/png",out.toByteArray())).with(auth).with(csrf()))
            .andExpect(status().isCreated()).andReturn().getResponse();
        String id=response.getHeader("Location").substring(14);
        mvc.perform(get("/api/v1/media/"+id).with(auth)).andExpect(status().isOk());
        assertThat(appender.list).hasSize(1);assertThat(appender.list.getFirst().getFormattedMessage()).contains("media="+id,"status=201 outcome=HTTP_RESPONSE").doesNotContain("private-filename");
    }
}
