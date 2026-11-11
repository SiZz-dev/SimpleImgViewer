package co.kr.satu.imagegate.media.controller;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest(properties={"spring.config.import=optional:classpath:/quota-test-absent.yml",
    "imagegate.upload.tenant-original-max-bytes=16","imagegate.upload.min-free-bytes=0",
    "imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false","imagegate.playback.cache.enabled=false",
    "imagegate.conversion.enabled=false","imagegate.audit.enabled=false"})
@AutoConfigureMockMvc
class OriginalQuotaApiTests {
    static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-quota-api-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());}
    @Autowired MockMvc mvc;
    org.springframework.test.web.servlet.request.RequestPostProcessor actor(String tenant,String owner){return authentication(UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal(tenant,owner),"unused",List.of()));}
    MockMultipartFile png(){return new MockMultipartFile("file","synthetic.png","image/png",new byte[]{(byte)137,80,78,71,13,10,26,10});}
    @Test void sameTenantAcrossOwnersSharesQuotaAndOtherTenantStillUploads() throws Exception {
        mvc.perform(multipart("/api/v1/media").file(png()).with(actor("quota","a")).with(csrf())).andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/media").file(png()).with(actor("quota","b")).with(csrf())).andExpect(status().isCreated());
        var failed=mvc.perform(multipart("/api/v1/media").file(png()).with(actor("quota","c")).with(csrf()))
            .andExpect(status().is(507)).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.code").value("TENANT_STORAGE_QUOTA_EXCEEDED")).andReturn();
        assertThat(failed.getResponse().getContentAsString()).doesNotContain(ROOT.toString(),"tenant-",".upload-");
        mvc.perform(multipart("/api/v1/media").file(png()).with(actor("separate","a")).with(csrf())).andExpect(status().isCreated());
    }
    @Test void quotaDoesNotReplaceAuthenticationOrCsrf() throws Exception {
        mvc.perform(multipart("/api/v1/media").file(png()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/media").file(png()).with(actor("security","a"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/media").file(png()).with(actor("security","a")).with(csrf())).andExpect(status().isCreated());
    }
}
