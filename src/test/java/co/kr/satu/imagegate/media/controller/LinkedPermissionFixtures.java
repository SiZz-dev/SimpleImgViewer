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

/** Shared server-only synthetic grant fixture; never registered in production. */
abstract class LinkedPermissionFixtures {
    record Grant(String tenant,String user,String media,String version,Instant expires,Set<MediaAction> actions){}
    static final AtomicReference<Grant> GRANT=new AtomicReference<>();
    static final AtomicReference<Instant> NOW=new AtomicReference<>();
    static final AtomicBoolean OWNERS=new AtomicBoolean(true);
    @TestConfiguration(proxyBeanMethods=false)
    static class Policy {
        @Bean @Primary MediaAuthorizationPolicy linkedPolicy(){return (actor,media,action)->{
            if(!actor.tenantId().equals(media.tenantId()))return false;
            if(actor.userId().equals(media.ownerId()))return OWNERS.get();
            var grant=GRANT.get();
            return grant!=null&&actor.tenantId().equals(grant.tenant())&&actor.userId().equals(grant.user())
                    &&media.mediaId().equals(grant.media())&&media.mediaVersion().equals(grant.version())
                    &&NOW.get().isBefore(grant.expires())&&grant.actions().contains(action);
        };}
    }
    @Autowired MockMvc mvc;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void reset(){GRANT.set(null);OWNERS.set(true);NOW.set(Instant.parse("2030-01-01T00:00:00Z"));}
    @AfterEach void clear(){GRANT.set(null);OWNERS.set(true);}
    RequestPostProcessor actor(String tenant,String user){return authentication(
            UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal(tenant,user),"unused",List.of()));}
    RequestPostProcessor owner(){return actor("tenant","alice");}
    RequestPostProcessor shared(){return actor("tenant","bob");}
    JsonNode register()throws Exception{
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var response=mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","synthetic.png","image/png",bytes.toByteArray()))
                .with(owner()).with(csrf())).andExpect(status().isCreated()).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }
    void grant(JsonNode file,Set<MediaAction> actions){GRANT.set(new Grant("tenant","bob",file.get("mediaId").asText(),
            file.get("mediaVersion").asText(),NOW.get().plusSeconds(60),Set.copyOf(actions)));}
    JsonNode prepare(JsonNode file)throws Exception{
        String id=file.get("mediaId").asText();
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(shared()).with(csrf())).andExpect(status().is2xxSuccessful());
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){
            var response=mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(shared())).andExpect(status().isOk()).andReturn();
            var page=json.readTree(response.getResponse().getContentAsString());
            if(page.path("state").asText().equals("ready"))return page;
            assertThat(page.path("state").asText()).isNotEqualTo("failed");Thread.sleep(10);
        }
        throw new AssertionError("Shared preview did not complete");
    }
    String note(JsonNode file,JsonNode page,int revision)throws Exception{
        return json.writeValueAsString(java.util.Map.of("mediaVersion",file.get("mediaVersion").asText(),
                "coordinateSpaceId",page.get("coordinateSpaceId").asText(),"expectedRevision",revision,
                "type","note","x",1,"y",1,"width",8,"height",8,"text","Shared note"));
    }
}
