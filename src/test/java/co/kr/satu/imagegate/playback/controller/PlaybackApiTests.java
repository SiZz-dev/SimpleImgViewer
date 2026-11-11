package co.kr.satu.imagegate.playback.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.playback.domain.*;
import co.kr.satu.imagegate.playback.service.PlaybackEngine;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.preview.enabled=false",
        "imagegate.playback.enabled=true",
        "imagegate.playback.ffmpeg-sha256=0000000000000000000000000000000000000000000000000000000000000000",
        "imagegate.playback.ffprobe-sha256=0000000000000000000000000000000000000000000000000000000000000000"})
@AutoConfigureMockMvc
@Import(PlaybackApiTests.Policy.class)
class PlaybackApiTests {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("imagegate-playback-api-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true);
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r){r.add("imagegate.storage.root",ROOT::toString);}
    @TestConfiguration(proxyBeanMethods=false) static class Policy {
        @Bean @Primary MediaAuthorizationPolicy playbackPolicy(){return (a,m,action)->ALLOWED.get()
                &&a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId());}
    }
    @MockitoBean PlaybackEngine engine;
    @Autowired MockMvc mvc;
    final JsonMapper json=JsonMapper.builder().build();
    final ViewerPrincipal owner=new ViewerPrincipal("tenant","u"+UUID.randomUUID().toString().substring(0,8));
    final byte[] bytes=new byte[1024];
    RequestPostProcessor actor(ViewerPrincipal p){return authentication(UsernamePasswordAuthenticationToken.authenticated(p,"unused",List.of()));}
    @BeforeEach void setup() throws Exception {
        ALLOWED.set(true);for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        when(engine.identity()).thenReturn("mock-playback-v1");
        when(engine.prepare(any(),any(),any())).thenAnswer(call->{
            Path work=call.getArgument(2);Path output=Files.write(work.resolve("playback.mp3"),bytes);
            return new PlaybackArtifact(output,3,"audio/mpeg",0,0,null,"mp3");
        });
    }
    @AfterEach void reset(){ALLOWED.set(true);}
    JsonNode body(MvcResult result) throws Exception{return json.readTree(result.getResponse().getContentAsString());}
    String register() throws Exception {
        byte[] wav=new byte[512];System.arraycopy("RIFF".getBytes(),0,wav,0,4);System.arraycopy("WAVE".getBytes(),0,wav,8,4);
        String id=body(mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","record.wav","audio/wav",wav))
                .with(actor(owner)).with(csrf())).andExpect(status().isCreated()).andReturn()).get("mediaId").asText();
        mvc.perform(post("/api/v1/media/"+id+"/playback").with(actor(owner)).with(csrf())).andExpect(status().isAccepted());return id;
    }
    JsonNode ready(String id) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<until) {
            var info=body(mvc.perform(get("/api/v1/media/"+id+"/playback").with(actor(owner))).andExpect(status().isOk()).andReturn());
            if(info.get("state").asText().equals("ready"))return info;
            if(info.get("state").asText().equals("failed"))throw new AssertionError(info.toString());
            Thread.sleep(10);
        }throw new AssertionError("Playback not ready");
    }
    @Test void servesAuthenticatedFullRangesHeadAndIfRangeWithoutLeakingPaths() throws Exception {
        String id=register();var info=ready(id);String url=info.get("url").asText(),etag="\""+info.get("sourceId").asText()+"\"";
        assertThat(info.toString()).doesNotContain(ROOT.toString(),owner.userId(),"tenantId");
        mvc.perform(get(url).with(actor(owner))).andExpect(status().isOk()).andExpect(content().bytes(bytes))
                .andExpect(header().string("Accept-Ranges","bytes")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get(url).header("Range","bytes=10-19").with(actor(owner))).andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(bytes,10,20))).andExpect(header().string("Content-Range","bytes 10-19/1024"));
        mvc.perform(get(url).header("Range","bytes=-4").with(actor(owner))).andExpect(status().isPartialContent())
                .andExpect(content().bytes(Arrays.copyOfRange(bytes,1020,1024)));
        mvc.perform(head(url).header("Range","bytes=100-").with(actor(owner))).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Length","924")).andExpect(content().bytes(new byte[0]));
        mvc.perform(get(url).header("Range","bytes=1-2").header("If-Range","\"old\"").with(actor(owner)))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        mvc.perform(get(url).header("Range","bytes=1-2").header("If-Range",etag).with(actor(owner)))
                .andExpect(status().isPartialContent()).andExpect(content().bytes(Arrays.copyOfRange(bytes,1,3)));
    }
    @Test void rejectsUnsatisfiableMalformedAndMultipleRanges() throws Exception {
        String url=ready(register()).get("url").asText();
        for(String range:List.of("bytes=1024-","bytes=10-9","bytes=-0","bytes=0-1,4-5","bytes=999999999999999999999999-"))
            mvc.perform(get(url).header("Range",range).with(actor(owner))).andExpect(status().is(416))
                    .andExpect(header().string("Content-Range","bytes */1024")).andExpect(content().bytes(new byte[0]));
    }
    @Test void everyWarmRangeRechecksTenantOwnerAndLatestPermission() throws Exception {
        String id=register(),url=ready(id).get("url").asText();
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        for(var other:List.of(new ViewerPrincipal("tenant","other"),new ViewerPrincipal("Tenant",owner.userId())))
            mvc.perform(get(url).header("Range","bytes=0-9").with(actor(other))).andExpect(status().isNotFound());
        String wrong=url.replaceAll("[a-f0-9]{64}","f".repeat(64));
        mvc.perform(get(wrong).with(actor(owner))).andExpect(status().isNotFound());
        ALLOWED.set(false);
        mvc.perform(get(url).header("Range","bytes=0-9").with(actor(owner))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/"+id+"/playback").with(actor(owner))).andExpect(status().isNotFound());
    }
    @Test void csrfIsRequiredAndWarmRequestsReuseThePreparedArtifact() throws Exception {
        String id=register();ready(id);
        mvc.perform(post("/api/v1/media/"+id+"/playback").with(actor(owner))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/media/"+id+"/playback").with(actor(owner)).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.durationSeconds").value(3));
        verify(engine,times(1)).prepare(any(),any(),any());
    }
    @Test void durationIsValidatedWhenThePendingViewerSessionBecomesReady() throws Exception {
        String id=register();ready(id);
        String valid="{\"mediaIds\":[\""+id+"\"],\"initialTimeSeconds\":1.5}";
        mvc.perform(post("/api/v1/viewer/sessions").contentType("application/json").content(valid).with(actor(owner)).with(csrf()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.positionState").value("validated"));
        String invalid=valid.replace("1.5","3.0");
        mvc.perform(post("/api/v1/viewer/sessions").contentType("application/json").content(invalid).with(actor(owner)).with(csrf()))
                .andExpect(status().isBadRequest());
    }

    @Test void registrationAndGetOnlyClassifyUntilExplicitPreparation() throws Exception {
        byte[] wav=new byte[512];System.arraycopy("RIFF".getBytes(),0,wav,0,4);System.arraycopy("WAVE".getBytes(),0,wav,8,4);
        var registered=mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","new.wav","audio/wav",wav))
                .with(actor(owner)).with(csrf())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.playback.state").value("not_requested")).andExpect(jsonPath("$.status").value("processing")).andReturn();
        String id=body(registered).get("mediaId").asText();
        mvc.perform(get("/api/v1/media/"+id+"/playback").with(actor(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("not_requested"));
        verify(engine,never()).prepare(any(),any(),any());
        mvc.perform(post("/api/v1/media/"+id+"/playback").with(actor(owner)).with(csrf())).andExpect(status().isAccepted());ready(id);
    }
}
