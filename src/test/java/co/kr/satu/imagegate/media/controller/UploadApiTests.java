package co.kr.satu.imagegate.media.controller;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import co.kr.satu.imagegate.media.service.UploadAdmission;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
@SpringBootTest(properties={"imagegate.upload.max-file-bytes=128","imagegate.upload.max-concurrent=1","imagegate.upload.max-concurrent-per-user=1","imagegate.upload.min-free-bytes=0","imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false","imagegate.playback.cache.enabled=false","imagegate.conversion.enabled=false"})
@AutoConfigureMockMvc
class UploadApiTests {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("imagegate-upload-api-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r){r.add("imagegate.storage.root",ROOT::toString);}
    @Autowired MockMvc mvc;@Autowired UploadAdmission admission;
    final ViewerPrincipal actor=new ViewerPrincipal("tenant","owner");
    org.springframework.test.web.servlet.request.RequestPostProcessor auth(){return authentication(UsernamePasswordAuthenticationToken.authenticated(actor,"unused",List.of()));}
    byte[] png() throws Exception {var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}
    @Test void bootstrapAdvertisesConfiguredUploadLimit() throws Exception {
        mvc.perform(get("/api/v1/viewer/bootstrap").with(auth())).andExpect(status().isOk())
            .andExpect(jsonPath("$.limits.maxUploadBytes").value(128));
    }
    @Test void busyReturns429WithoutPathsAndAuthenticationStillRunsFirst() throws Exception {
        var file=new MockMultipartFile("file","synthetic.png","image/png",png());
        try(var held=admission.acquire(actor)){
            mvc.perform(multipart("/api/v1/media").file(file).with(auth()).with(csrf())).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.code").value("UPLOAD_BUSY"));
            mvc.perform(multipart("/api/v1/media").file(file).with(csrf())).andExpect(status().isUnauthorized());
        }
        mvc.perform(multipart("/api/v1/media").file(file).with(auth()).with(csrf())).andExpect(status().isCreated());
    }
    @Test void configuredSmallerSizeLimitRejectsStreamAndNextUploadStillWorks() throws Exception {
        byte[] oversized=Arrays.copyOf(png(),256);
        var result=mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","large.png","image/png",oversized)).with(auth()).with(csrf()))
            .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE")).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(ROOT.toString());
        mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","small.png","image/png",png())).with(auth()).with(csrf())).andExpect(status().isCreated());
        try(var entries=Files.walk(ROOT)){assertThat(entries.filter(p->p.getFileName().toString().startsWith(".upload-")).toList()).isEmpty();}
    }
}
