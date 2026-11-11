package co.kr.satu.imagegate.viewer.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.preview.enabled=false"})
@AutoConfigureMockMvc
@Import(ViewerSessionApiTests.PolicyConfiguration.class)
class ViewerSessionApiTests {
    static final Path ROOT;
    static { try { ROOT=Files.createTempDirectory("imagegate-session-api-"); }
        catch(IOException e) { throw new ExceptionInInitializerError(e); } }
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true);
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) {
        r.add("imagegate.storage.root",ROOT::toString);
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class PolicyConfiguration {
        @Bean @Primary MediaAuthorizationPolicy policy() {
            return (a,m,action)->ALLOWED.get() && a.tenantId().equals(m.tenantId()) && a.userId().equals(m.ownerId());
        }
    }
    @Autowired MockMvc mvc;
    final JsonMapper json=JsonMapper.builder().build();
    final ViewerPrincipal owner=new ViewerPrincipal("tenant","user"+UUID.randomUUID().toString().substring(0,8));
    RequestPostProcessor actor(ViewerPrincipal who) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(who,"unused",List.of()));
    }
    @BeforeEach void allow() { ALLOWED.set(true); }
    @AfterEach void reset() { ALLOWED.set(true); }
    String upload(ViewerPrincipal who) throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",out);
        return upload(who,out.toByteArray(),"image.png");
    }
    String upload(ViewerPrincipal who,byte[] bytes,String name) throws Exception {
        var response=mvc.perform(multipart("/api/v1/media")
                .file(new MockMultipartFile("file",name,"application/octet-stream",bytes))
                .with(actor(who)).with(csrf())).andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).get("mediaId").asText();
    }
    JsonNode create(Object body) throws Exception {
        var response=mvc.perform(post("/api/v1/viewer/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)).with(actor(owner)).with(csrf()))
                .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        var node=json.readTree(response.getResponse().getContentAsString());
        assertThat(response.getResponse().getHeader("Location")).isEqualTo("/api/v1/viewer/sessions/"+node.get("sessionId").asText());
        assertThat(node.toString()).doesNotContain(ROOT.toString(),"tenantId","ownerId","storedFileName");
        return node;
    }

    @Test void createsOrderedSessionWithDefaultsAndSupportsGetAndDelete() throws Exception {
        String a=upload(owner),b=upload(owner);
        var session=create(Map.of("mediaIds",List.of(b,a)));
        String id=session.get("sessionId").asText();
        assertThat(session.get("selectedMediaId").asText()).isEqualTo(b);
        assertThat(session.get("media").get(0).get("mediaId").asText()).isEqualTo(b);
        assertThat(session.get("media").get(1).get("mediaId").asText()).isEqualTo(a);
        assertThat(session.get("theme").asText()).isEqualTo("dark");
        assertThat(session.get("initialPage").asInt()).isEqualTo(1);
        assertThat(session.get("leftPanelOpen").asBoolean()).isTrue();
        assertThat(session.get("positionState").asText()).isEqualTo("pending");
        mvc.perform(get("/api/v1/viewer/sessions/"+id).with(actor(owner)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(delete("/api/v1/viewer/sessions/"+id).with(actor(owner))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/viewer/sessions/"+id).with(actor(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/viewer/sessions/"+id).with(actor(owner))).andExpect(status().isNotFound());
    }

    @Test void honorsSelectedPageLightThemeAndPanelPreference() throws Exception {
        String a=upload(owner),b=upload(owner);
        var session=create(Map.of("mediaIds",List.of(a,b),"initialMediaId",b,"initialPage",2,
                "theme","light","leftPanelOpen",false));
        assertThat(session.get("theme").asText()).isEqualTo("light");
        assertThat(session.get("leftPanelOpen").asBoolean()).isFalse();
        assertThat(session.get("initialPage").asInt()).isEqualTo(2);
        assertThat(session.get("media").get(1).get("preview").get("page").asInt()).isEqualTo(2);
        assertThat(session.get("media").get(1).get("statusUrl").asText()).endsWith("?page=2");
    }

    @Test void checksSessionOwnershipAndRejectsForgedPrincipal() throws Exception {
        var session=create(Map.of("mediaIds",List.of(upload(owner))));
        String url="/api/v1/viewer/sessions/"+session.get("sessionId").asText();
        for(var stranger:List.of(new ViewerPrincipal("tenant","stranger"),new ViewerPrincipal("Tenant",owner.userId()))) {
            mvc.perform(get(url).with(actor(stranger))).andExpect(status().isNotFound());
            mvc.perform(delete(url).with(actor(stranger)).with(csrf())).andExpect(status().isNotFound());
        }
        mvc.perform(get(url).with(user(owner.userId())).header("X-Tenant-Id",owner.tenantId()))
                .andExpect(status().isForbidden());
    }

    @Test void rejectsMixedUnauthorizedSelectionAndRechecksAllPermissionsOnGet() throws Exception {
        String a=upload(owner),b=upload(new ViewerPrincipal("tenant","someone"));
        mvc.perform(post("/api/v1/viewer/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("mediaIds",List.of(a,b))))
                .with(actor(owner)).with(csrf())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND")).andExpect(header().doesNotExist("Location"));
        var session=create(Map.of("mediaIds",List.of(a)));
        String url="/api/v1/viewer/sessions/"+session.get("sessionId").asText();
        ALLOWED.set(false);
        mvc.perform(get(url).with(actor(owner))).andExpect(status().isNotFound());
        // The owner can release an unusable session after a file grant is revoked.
        mvc.perform(delete(url).with(actor(owner)).with(csrf())).andExpect(status().isNoContent());
    }

    @Test void rejectsUnknownFieldsDuplicatesInvalidPositionsAndSelection() throws Exception {
        String a=upload(owner);
        for(Object body:List.of(Map.of("mediaIds",List.of(a,a)),Map.of("mediaIds",List.of(a),"unknown",true),
                Map.of("mediaIds",List.of(a),"initialMediaId","missing"),Map.of("mediaIds",List.of(a),"theme","system"),
                Map.of("mediaIds",List.of(a),"initialPage",10001),
                Map.of("mediaIds",List.of(a),"initialTimeSeconds",0),Map.of("mediaIds",List.of("../secret")))) {
            mvc.perform(post("/api/v1/viewer/sessions").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)).with(actor(owner)).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void playbackPositionRemainsPendingUntilDurationIsVerified() throws Exception {
        byte[] header={0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0};
        String video=upload(owner,header,"candidate.mp4");
        var session=create(Map.of("mediaIds",List.of(video),"initialTimeSeconds",2.5));
        assertThat(session.get("initialPage").isNull()).isTrue();
        assertThat(session.get("initialTimeSeconds").asDouble()).isEqualTo(2.5);
        assertThat(session.get("positionState").asText()).isEqualTo("pending");
        assertThat(session.get("media").get(0).get("sources").size()).isZero();
        mvc.perform(post("/api/v1/viewer/sessions").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("mediaIds",List.of(video),"initialPage",1)))
                .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
    }

    @Test void anonymousAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(get("/api/v1/viewer/sessions/unknown")).andExpect(status().isUnauthorized());
        String body=json.writeValueAsString(Map.of("mediaIds",List.of(upload(owner))));
        mvc.perform(post("/api/v1/viewer/sessions").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(actor(owner))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/viewer/sessions").contentType(MediaType.APPLICATION_JSON).content(body)
                .with(csrf())).andExpect(status().isUnauthorized());
    }
}
