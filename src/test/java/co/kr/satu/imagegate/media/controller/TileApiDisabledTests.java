package co.kr.satu.imagegate.media.controller;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import co.kr.satu.imagegate.conversion.service.TileQueue;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
@SpringBootTest(properties={"spring.config.import=optional:classpath:/tile-disabled-no-installation.yml","imagegate.tiles.enabled=false","imagegate.conversion.enabled=false",
        "imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false","imagegate.playback.enabled=false","imagegate.playback.cache.enabled=false","imagegate.audit.enabled=false","imagegate.upload.min-free-bytes=0","imagegate.upload.tenant-original-max-bytes=0"})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TileApiDisabledTests {
    static final Path ROOT;static{try{ROOT=Files.createTempDirectory("imagegate-tile-disabled-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());}
    @Autowired MockMvc mvc;@Autowired MediaStore store;@Autowired ApplicationContext context;
    @Test void disabledStatusAndPrepareNeverCreateQueueOrStorageAndContentStaysHidden()throws Exception{
        var image=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB);var bytes=new ByteArrayOutputStream();try{ImageIO.write(image,"TIFF",bytes);}finally{image.flush();}
        var media=store.register("tenant","owner","x.tif",new ByteArrayInputStream(bytes.toByteArray()));String path="/api/v1/media/"+media.mediaId()+"/pages/1";
        var actor=UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("tenant","owner"),"fixture",List.of());
        mvc.perform(get(path+"/pyramid").with(authentication(actor))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("disabled")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(path+"/pyramids").with(authentication(actor)).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("disabled"));
        mvc.perform(get(path+"/pyramids/"+UUID.randomUUID()+"/tiles/0/0/0").with(authentication(actor))).andExpect(status().isNotFound());
        assertThat(context.getBeansOfType(TileQueue.class)).isEmpty();assertThat(ROOT.resolve("data/derived/tiles-v1")).doesNotExist();
    }
}
