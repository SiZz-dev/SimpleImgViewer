package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PreviewCachePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
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

@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.preview.max-width=1600","imagegate.preview.max-height=1600"})
@AutoConfigureMockMvc
@Import(ThumbnailApiTests.PolicyConfiguration.class)
class ThumbnailApiTests {
    static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-thumbnail-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true);
    @DynamicPropertySource static void storage(DynamicPropertyRegistry p){p.add("imagegate.storage.root",ROOT::toString);}
    @TestConfiguration(proxyBeanMethods=false) static class PolicyConfiguration {
        @Bean @Primary MediaAuthorizationPolicy thumbnailPolicy(){return (a,m,action)->ALLOWED.get()&&a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId());}
    }
    @Autowired MockMvc mvc;@Autowired PreviewCache cache;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach @AfterEach void allow(){ALLOWED.set(true);}
    RequestPostProcessor actor(String tenant,String user){return authentication(UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal(tenant,user),"unused",List.of()));}
    RequestPostProcessor owner(){return actor("tenant","alice");}
    String upload() throws Exception {
        var image=new BufferedImage(1024,768,BufferedImage.TYPE_INT_RGB);var random=new Random(71);int[] row=new int[1024];
        for(int y=0;y<768;y++){for(int x=0;x<1024;x++)row[x]=random.nextInt(0x1000000);image.setRGB(0,y,1024,1,row,0,1024);}
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);
        return json.readTree(mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","noise.png","image/png",out.toByteArray())).with(owner()).with(csrf()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("mediaId").asText();
    }
    JsonNode ready(String id) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end){var state=json.readTree(mvc.perform(get("/api/v1/media/"+id+"/status").with(owner())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if("ready".equals(state.get("state").asText()))return state;Thread.sleep(10);}
        throw new AssertionError("Thumbnail did not become ready");
    }
    @Test void smallPngSourceUsesFullCurrentAuthorizationForGetAndHead() throws Exception {
        String id=upload();var state=ready(id);var small=state.get("thumbnail");String url=small.get("url").asText();
        assertThat(small.get("role").asText()).isEqualTo("thumbnail");assertThat(small.get("width").asInt()).isEqualTo(256);assertThat(small.get("height").asInt()).isEqualTo(192);
        byte[] bytes=mvc.perform(get(url).with(owner())).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        var image=ImageIO.read(new ByteArrayInputStream(bytes));assertThat(image.getWidth()).isEqualTo(256);assertThat(image.getHeight()).isEqualTo(192);
        byte[] large=mvc.perform(get(state.get("source").get("url").asText()).with(owner())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes.length).isLessThan(large.length/4);
        mvc.perform(head(url).with(owner())).andExpect(status().isOk()).andExpect(header().string("Content-Length",""+bytes.length)).andExpect(content().bytes(new byte[0]));
        for(var other:List.of(actor("tenant","bob"),actor("other","alice"))){mvc.perform(get(url).with(other)).andExpect(status().isNotFound());mvc.perform(head(url).with(other)).andExpect(status().isNotFound());}
        mvc.perform(get(url)).andExpect(status().isUnauthorized());mvc.perform(get(url.replace("page=1","page=2")).with(owner())).andExpect(status().isNotFound());
        mvc.perform(get(url.replace(state.get("source").get("sourceId").asText(),"0".repeat(64))).with(owner())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(owner())).andExpect(status().isOk()).andExpect(jsonPath("$.thumbnail.role").value("thumbnail"));
        mvc.perform(get("/api/v1/media/"+id).with(owner())).andExpect(status().isOk()).andExpect(jsonPath("$.sources[1].role").value("thumbnail"));
        ALLOWED.set(false);mvc.perform(get(url).with(owner())).andExpect(status().isNotFound());
    }
    @Test void cleanupAccountsForBothArtifactsAndWaitsForTheirSharedLease() throws Exception {
        String id=upload();var state=ready(id);String key=state.get("source").get("sourceId").asText(),url=state.get("thumbnail").get("url").asText();
        long future=System.currentTimeMillis()+Duration.ofDays(8).toMillis();
        try(var lease=cache.lease(key)){cache.maintain(PreviewCachePolicy.defaults(),future);assertThat(cache.thumbnail(key,1)).exists();}
        long end=System.nanoTime()+Duration.ofSeconds(5).toNanos();
        while(System.nanoTime()<end&&cache.read(key,1).ready()){cache.maintain(PreviewCachePolicy.defaults(),future);Thread.sleep(10);}
        assertThat(cache.read(key,1).state()).isEqualTo("not_requested");
        assertThat(ROOT.resolve("derived/previews-v1").resolve(key)).doesNotExist();
        mvc.perform(get(url).with(owner())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner()).with(csrf())).andExpect(status().isAccepted());
        assertThat(ready(id).get("thumbnail").get("url").asText()).isEqualTo(url);
    }
}
