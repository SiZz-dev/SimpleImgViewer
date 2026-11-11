package co.kr.satu.imagegate.viewer.controller;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.preview.enabled=false",
        "imagegate.features.image.zoom=false","imagegate.features.image.brightness=false",
        "imagegate.features.image.crop-and-save=false","imagegate.features.image.export-edited=false"})
@AutoConfigureMockMvc
class ViewerUiApiTests {
    static final Path ROOT;
    static { try { ROOT=Files.createTempDirectory("imagegate-ui-api-"); }
        catch(IOException e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) {
        r.add("imagegate.storage.root",ROOT::toString);
    }
    @Autowired MockMvc mvc;
    final JsonMapper json=JsonMapper.builder().build();
    RequestPostProcessor owner() {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new ViewerPrincipal("tenant","alice"),"unused",List.of()));
    }

    @Test void publicShellAndExactStaticAssetsContainNoPrivateDataOrInlineScripts() throws Exception {
        var response=mvc.perform(get("/viewer")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Security-Policy",containsString("img-src 'self' blob:")))
                .andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn();
        assertThat(response.getResponse().getContentAsString())
                .contains("/viewer/viewer.js","file-list","brightness")
                .doesNotContain("<footer","onclick=","<script>","tenantId","csrfToken");
        for(String asset:List.of("viewer.css","viewer.js","viewer-core.js","viewer-tiles.js","viewer-editing.js","viewer-crop.js","viewer-export.js","viewer-player.js"))
            mvc.perform(get("/viewer/"+asset)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/media/unknown")).andExpect(status().isUnauthorized());
    }

    @Test void bootstrapRequiresTrustedAuthenticatedPrincipal() throws Exception {
        mvc.perform(get("/api/v1/viewer/bootstrap")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/viewer/bootstrap").with(user("alice"))
                .header("X-Tenant-Id","tenant")).andExpect(status().isForbidden());
    }

    @Test void exposesOnlyImplementedToolsAndRespectsInstallationSwitches() throws Exception {
        mvc.perform(get("/api/v1/viewer/bootstrap").with(owner())).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.features.zoom").value(false))
                .andExpect(jsonPath("$.features.tiles").value(false))
                .andExpect(jsonPath("$.features.brightness").value(false))
                .andExpect(jsonPath("$.features.rotation").value(true))
                .andExpect(jsonPath("$.features.annotations").value(true))
                .andExpect(jsonPath("$.features.stamps").value(true))
                .andExpect(jsonPath("$.features.cropAndSave").value(false))
                .andExpect(jsonPath("$.features.exportEdited").value(false))
                .andExpect(jsonPath("$.csrf.headerName").value("X-CSRF-TOKEN"))
                .andExpect(jsonPath("$.csrf.token").isNotEmpty())
                .andExpect(jsonPath("$.limits.maxFiles").value(100));
    }

    @Test void browserBootstrapTokenWorksForCsrfProtectedMultipartRegistration() throws Exception {
        var bootstrap=mvc.perform(get("/api/v1/viewer/bootstrap").with(owner()))
                .andExpect(status().isOk()).andReturn();
        var token=json.readTree(bootstrap.getResponse().getContentAsString()).get("csrf");
        var session=(MockHttpSession)bootstrap.getRequest().getSession(false);
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",bytes);
        mvc.perform(multipart("/api/v1/media")
                .file(new MockMultipartFile("file","image.png","image/png",bytes.toByteArray()))
                .session(session).with(owner())
                .header(token.get("headerName").asText(),token.get("token").asText()))
                .andExpect(status().isCreated());
    }

    @Test void installationDisabledCropCannotBeInvokedDirectlyByTheOwner() throws Exception {
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var uploaded=mvc.perform(multipart("/api/v1/media")
                .file(new MockMultipartFile("file","image.png","image/png",bytes.toByteArray())).with(owner()).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        String id=json.readTree(uploaded.getResponse().getContentAsString()).get("mediaId").asText();
        String body="{\"mediaVersion\":\"10000000-0000-4000-8000-000000000001\",\"coordinateSpaceId\":\"original:unknown\",\"x\":0,\"y\":0,\"width\":1,\"height\":1}";
        mvc.perform(post("/api/v1/media/"+id+"/pages/1/crop").contentType("application/json").content(body)
                .with(owner()).with(csrf())).andExpect(status().isForbidden());
        String edited=body.substring(0,body.length()-1)+",\"expectedRevision\":0,\"includeAnnotations\":false,\"brightness\":100,\"rotation\":0}";
        mvc.perform(post("/api/v1/media/"+id+"/pages/1/export").contentType("application/json").content(edited)
                .with(owner()).with(csrf())).andExpect(status().isForbidden());
    }
}
