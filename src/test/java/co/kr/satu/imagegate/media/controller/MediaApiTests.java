package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties={"imagegate.preview.enabled=false","imagegate.conversion.enabled=false",
        "imagegate.features.image.annotations=false"})
@AutoConfigureMockMvc
class MediaApiTests {
    static final Path ROOT;
    static {
        try { ROOT=Files.createTempDirectory("imagegate-api-test-"); }
        catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) {
        registry.add("imagegate.storage.root",ROOT::toString);
    }
    @Autowired MockMvc mvc;

    @Test void ownerCanRegisterAndReadMetadataWithoutPathsOrReadySources() throws Exception {
        String id=register();
        var response=mvc.perform(get("/api/v1/media/"+id).with(actor("TenantA","alice")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.mediaId").value(id))
                .andExpect(jsonPath("$.originalMimeType").value("image/png"))
                .andExpect(jsonPath("$.mediaType").value("image"))
                .andExpect(jsonPath("$.fileName").value("sample.png"))
                .andExpect(jsonPath("$.status").value("processing"))
                .andExpect(jsonPath("$.permissions.view").value(true))
                .andExpect(jsonPath("$.permissions.annotate").value(false))
                .andExpect(jsonPath("$.sources").isEmpty()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(ROOT.toString(),"storedFileName","ownerId","tenantId");
    }

    @Test void otherOwnerAndOtherTenantCannotReadFile() throws Exception {
        String id=register();
        for(var actor:List.of(actor("TenantA","bob"),actor("TenantB","alice"),actor("tenanta","alice"))) {
            mvc.perform(get("/api/v1/media/"+id).with(actor))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
        }
        mvc.perform(get("/api/v1/media/00000000-0000-0000-0000-000000000000")
                .with(actor("TenantA","alice"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MEDIA_NOT_FOUND"));
    }

    @Test void requestHeadersAndOrdinaryUserCannotForgeTrustedActor() throws Exception {
        String id=register();
        mvc.perform(get("/api/v1/media/"+id).with(user("alice"))
                .header("X-Tenant-Id","TenantA").header("X-User-Id","alice"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/media/"+id).with(actor("TenantB","alice"))
                .header("X-Tenant-Id","TenantA")).andExpect(status().isNotFound());
    }

    @Test void anonymousAndMissingCsrfAreRejected() throws Exception {
        mvc.perform(get("/api/v1/media/example")).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/media").file(png()).with(actor("TenantA","alice")))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(multipart("/api/v1/media").file(png()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test void rejectsUnsupportedAndEmptyUploadsWithoutTrustingExtension() throws Exception {
        mvc.perform(multipart("/api/v1/media")
                .file(new MockMultipartFile("file","fake.jpg","image/jpeg","<script>bad</script>".getBytes()))
                .with(actor("TenantA","alice")).with(csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA"));
        mvc.perform(multipart("/api/v1/media")
                .file(new MockMultipartFile("file","empty.wav","audio/wav",new byte[0]))
                .with(actor("TenantA","alice")).with(csrf()))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("INVALID_MEDIA"));
    }

    @Test void contentIsNotExposedBeforeValidationAndSourcePublishing() throws Exception {
        String id=register();
        mvc.perform(get("/api/v1/media/"+id+"/content").with(actor("TenantA","alice")))
                .andExpect(status().isNotFound());
    }

    @Test void headerCandidateDoesNotClaimValidatedPlaybackOrImageEditing() throws Exception {
        byte[] candidate={0,0,0,24,102,116,121,112,105,115,111,109,0,0,0,0};
        mvc.perform(multipart("/api/v1/media")
                .file(new MockMultipartFile("file","candidate.mp4","video/mp4",candidate))
                .with(actor("TenantA","alice")).with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.playback.state").value("disabled"))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.permissions.stamp").value(false))
                .andExpect(jsonPath("$.permissions.cropAndSave").value(false));
    }

    private String register() throws Exception {
        var response=mvc.perform(multipart("/api/v1/media").file(png())
                .with(actor("TenantA","alice")).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String location=response.getHeader("Location");
        assertThat(location).startsWith("/api/v1/media/");
        return location.substring(location.lastIndexOf('/')+1);
    }

    private MockMultipartFile png() throws Exception {
        var output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",output);
        return new MockMultipartFile("file","sample.png","application/octet-stream",output.toByteArray());
    }

    private RequestPostProcessor actor(String tenant,String userId) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new ViewerPrincipal(tenant,userId),"unused",List.of()));
    }
}
