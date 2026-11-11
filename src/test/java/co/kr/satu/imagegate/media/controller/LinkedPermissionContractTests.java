package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Test-only verified grant fixture. No login, request-header trust, grant transport or production store. */
@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.tiles.enabled=false",
        "imagegate.playback.enabled=false","imagegate.preview.enabled=true","imagegate.features.image.enabled=true",
        "imagegate.features.image.annotations=true","imagegate.features.image.crop-and-save=false",
        "imagegate.preview.max-width=32","imagegate.preview.max-height=32","imagegate.preview.max-source-pixels=100000"})
@AutoConfigureMockMvc
@Import(LinkedPermissionFixtures.Policy.class)
class LinkedPermissionContractTests extends LinkedPermissionFixtures {
    static final Path ROOT;
    static {try {ROOT=Files.createTempDirectory("imagegate-linked-permission-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r){r.add("imagegate.storage.root",ROOT::toString);}
    @Test void viewGrantDoesNotInheritOwnerEditingRightsOrClientPermissionClaims()throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW));String id=file.get("mediaId").asText();
        mvc.perform(get("/api/v1/media/"+id).with(shared()).header("X-Permissions","ANNOTATE,EXPORT_EDITED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.permissions.view").value(true))
                .andExpect(jsonPath("$.permissions.annotate").value(false)).andExpect(jsonPath("$.permissions.exportEdited").value(false));
        var page=prepare(file);
        mvc.perform(post("/api/v1/media/"+id+"/pages/1/annotations").with(shared()).with(csrf())
                .contentType("application/json").content(note(file,page,0))).andExpect(status().isNotFound());
    }
    @Test void annotationGrantCanBeRevokedWithoutRevokingView()throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW,MediaAction.ANNOTATE));var page=prepare(file);
        String url="/api/v1/media/"+file.get("mediaId").asText()+"/pages/1/annotations";
        mvc.perform(post(url).with(shared()).contentType("application/json").content(note(file,page,0))).andExpect(status().isForbidden());
        mvc.perform(post(url).with(shared()).with(csrf()).contentType("application/json").content(note(file,page,0)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revision").value(1));
        grant(file,Set.of(MediaAction.VIEW));
        mvc.perform(get(url).with(shared())).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].text").value("Shared note"));
        mvc.perform(post(url).with(shared()).with(csrf()).contentType("application/json").content(note(file,page,1))).andExpect(status().isNotFound());
    }
    @Test void expiryInvalidatesPreparedContentAndExistingViewerSession()throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW));var page=prepare(file);String id=file.get("mediaId").asText();
        var session=mvc.perform(post("/api/v1/viewer/sessions").with(shared()).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("mediaIds",List.of(id))))).andExpect(status().isCreated()).andReturn();
        String sid=json.readTree(session.getResponse().getContentAsString()).get("sessionId").asText();
        NOW.set(GRANT.get().expires());
        mvc.perform(get(page.get("source").get("url").asText()).with(shared())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/"+id+"/status").with(shared())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/viewer/sessions/"+sid).with(shared())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(shared()).with(csrf())).andExpect(status().isNotFound());
    }
    @ParameterizedTest @ValueSource(strings={"tenant","user","media","version","view"})
    void grantBindingAndViewPrerequisiteAreRequired(String field)throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW,MediaAction.ANNOTATE));var g=GRANT.get();
        GRANT.set(new Grant(field.equals("tenant")?"other":g.tenant(),field.equals("user")?"other":g.user(),
                field.equals("media")?"other":g.media(),field.equals("version")?"other":g.version(),g.expires(),
                field.equals("view")?Set.of(MediaAction.ANNOTATE):g.actions()));
        mvc.perform(get("/api/v1/media/"+file.get("mediaId").asText()).with(shared())
                .header("X-Tenant-Id","tenant").header("X-User-Id","bob")).andExpect(status().isNotFound());
    }
    @Test void installationPolicyStillRestrictsGrantedAction()throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW,MediaAction.CROP_AND_SAVE));String id=file.get("mediaId").asText();
        mvc.perform(get("/api/v1/media/"+id).with(shared())).andExpect(status().isOk())
                .andExpect(jsonPath("$.permissions.cropAndSave").value(false));
        var page=prepare(file);
        mvc.perform(post("/api/v1/media/"+id+"/pages/1/crop").with(shared()).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("mediaVersion",file.get("mediaVersion").asText(),
                        "coordinateSpaceId",page.get("coordinateSpaceId").asText(),"x",0,"y",0,"width",8,"height",8))))
                .andExpect(status().isForbidden());
    }
    @Test void customPolicyDenialDoesNotFallBackToOwnerAccess()throws Exception{
        var file=register();OWNERS.set(false);
        mvc.perform(get("/api/v1/media/"+file.get("mediaId").asText()).with(owner())).andExpect(status().isNotFound());
    }
}
