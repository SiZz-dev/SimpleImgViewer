package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.playback.domain.PlaybackArtifact;
import co.kr.satu.imagegate.playback.service.PlaybackEngine;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.tiles.enabled=false",
        "imagegate.playback.enabled=true","imagegate.preview.enabled=true","imagegate.features.image.enabled=true",
        "imagegate.features.image.annotations=true","imagegate.features.image.stamps=true","imagegate.features.image.stamp-upload=true",
        "imagegate.features.image.crop-and-save=true","imagegate.features.image.export-edited=true",
        "imagegate.features.video.enabled=true","imagegate.features.audio.enabled=true",
        "imagegate.playback.ffmpeg-sha256=0000000000000000000000000000000000000000000000000000000000000000",
        "imagegate.playback.ffprobe-sha256=0000000000000000000000000000000000000000000000000000000000000000",
        "imagegate.preview.max-width=32","imagegate.preview.max-height=32","imagegate.preview.max-source-pixels=100000"})
@AutoConfigureMockMvc
@Import(LinkedPermissionFixtures.Policy.class)
class LinkedActionPermissionTests extends LinkedPermissionFixtures {
    static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-linked-actions-");}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r){r.add("imagegate.storage.root",ROOT::toString);}
    @MockitoBean PlaybackEngine engine;
    final byte[] playback=new byte[1024];
    @BeforeEach void engineFixture()throws Exception{
        for(int i=0;i<playback.length;i++)playback[i]=(byte)i;
        when(engine.identity()).thenReturn("linked-permission-playback-fixture-v1");
        when(engine.prepare(any(),any(),any())).thenAnswer(call->{
            co.kr.satu.imagegate.media.domain.MediaRecord record=call.getArgument(0);
            boolean video=record.mediaType()==MediaType.VIDEO;Path work=call.getArgument(2);
            var output=Files.write(work.resolve(video?"playback.webm":"playback.mp3"),playback);
            return new PlaybackArtifact(output,3,video?"video/webm":"audio/mpeg",video?320:0,video?180:0,video?"vp8":null,video?null:"mp3");
        });
    }
    JsonNode body(MvcResult result)throws Exception{return json.readTree(result.getResponse().getContentAsString());}
    String endpoint(JsonNode file,String suffix){return "/api/v1/media/"+file.get("mediaId").asText()+"/pages/1/"+suffix;}
    String region(JsonNode file,JsonNode page,boolean export,boolean partial)throws Exception{
        var request=new java.util.LinkedHashMap<String,Object>();
        request.put("mediaVersion",file.get("mediaVersion").asText());request.put("coordinateSpaceId",page.get("coordinateSpaceId").asText());
        request.put("x",0);request.put("y",0);request.put("width",partial?8:16);request.put("height",partial?8:16);
        if(export){request.put("expectedRevision",0);request.put("includeAnnotations",false);request.put("brightness",100);request.put("rotation",0);}
        return json.writeValueAsString(request);
    }
    void pngResponse(MvcResult result,int width)throws Exception{
        var image=ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        assertThat(image).isNotNull();try{assertThat(image.getWidth()).isEqualTo(width);assertThat(image.getHeight()).isEqualTo(width);}finally{image.flush();}
    }
    @Test void cropGrantIsSeparateFromExportAndRevocationKeepsOnlyView()throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW,MediaAction.CROP_AND_SAVE));var page=prepare(file);
        String crop=endpoint(file,"crop"),request=region(file,page,false,true);
        mvc.perform(post(crop).with(shared()).contentType("application/json").content(request)).andExpect(status().isForbidden());
        pngResponse(mvc.perform(post(crop).with(shared()).with(csrf()).contentType("application/json").content(request))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn(),8);
        mvc.perform(post(endpoint(file,"export")).with(shared()).with(csrf()).contentType("application/json").content(region(file,page,true,false)))
                .andExpect(status().isNotFound());
        grant(file,Set.of(MediaAction.VIEW));
        mvc.perform(post(crop).with(shared()).with(csrf()).contentType("application/json").content(request)).andExpect(status().isNotFound());
        mvc.perform(get(page.get("source").get("url").asText()).with(shared())).andExpect(status().isOk());
    }
    @Test void fullExportGrantDoesNotPermitPartialExportWithoutCropAndIsRechecked()throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW,MediaAction.EXPORT_EDITED));var page=prepare(file);
        String url=endpoint(file,"export"),full=region(file,page,true,false),partial=region(file,page,true,true);
        mvc.perform(post(url).with(shared()).contentType("application/json").content(full)).andExpect(status().isForbidden());
        pngResponse(mvc.perform(post(url).with(shared()).with(csrf()).contentType("application/json").content(full))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn(),16);
        mvc.perform(post(url).with(shared()).with(csrf()).contentType("application/json").content(partial)).andExpect(status().isNotFound());
        grant(file,Set.of(MediaAction.VIEW,MediaAction.EXPORT_EDITED,MediaAction.CROP_AND_SAVE));
        pngResponse(mvc.perform(post(url).with(shared()).with(csrf()).contentType("application/json").content(partial)).andExpect(status().isOk()).andReturn(),8);
        grant(file,Set.of(MediaAction.VIEW,MediaAction.CROP_AND_SAVE));
        mvc.perform(post(url).with(shared()).with(csrf()).contentType("application/json").content(full)).andExpect(status().isNotFound());
    }
    MockMultipartFile stampFile()throws Exception{
        var bytes=new java.io.ByteArrayOutputStream();
        ImageIO.write(new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_ARGB),"png",bytes);
        return new MockMultipartFile("file","synthetic-stamp.png","image/png",bytes.toByteArray());
    }
    @Test void stampGrantAndPersonalLibraryDoNotOverrideFileOrNotePermissions()throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW));var page=prepare(file);
        String upload="/api/v1/media/"+file.get("mediaId").asText()+"/stamps",annotations=endpoint(file,"annotations");
        mvc.perform(multipart(upload).file(stampFile()).with(shared()).with(csrf())).andExpect(status().isNotFound());
        grant(file,Set.of(MediaAction.VIEW,MediaAction.STAMP));
        mvc.perform(multipart(upload).file(stampFile()).with(shared())).andExpect(status().isForbidden());
        var stamp=body(mvc.perform(multipart(upload).file(stampFile()).with(shared()).with(csrf())).andExpect(status().isCreated()).andReturn());
        String personal=stamp.get("contentUrl").asText();
        mvc.perform(get(personal).with(shared())).andExpect(status().isOk());
        mvc.perform(get(personal).with(owner())).andExpect(status().isNotFound());
        String change=json.writeValueAsString(Map.of("mediaVersion",file.get("mediaVersion").asText(),"coordinateSpaceId",page.get("coordinateSpaceId").asText(),
                "expectedRevision",0,"type","stamp","x",1,"y",1,"width",4,"height",4,"stampId",stamp.get("stampId").asText()));
        var document=body(mvc.perform(post(annotations).with(shared()).with(csrf()).contentType("application/json").content(change))
                .andExpect(status().isCreated()).andReturn());
        String placed=document.get("items").get(0).get("stampUrl").asText();
        mvc.perform(post(annotations).with(shared()).with(csrf()).contentType("application/json").content(note(file,page,1))).andExpect(status().isNotFound());
        grant(file,Set.of(MediaAction.VIEW));
        mvc.perform(get(placed).with(shared())).andExpect(status().isOk());
        mvc.perform(get(placed).with(owner())).andExpect(status().isOk());
        mvc.perform(multipart(upload).file(stampFile()).with(shared()).with(csrf())).andExpect(status().isNotFound());
        GRANT.set(null);
        mvc.perform(get(placed).with(shared())).andExpect(status().isNotFound());
        mvc.perform(get(personal).with(shared())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/stamps").with(shared())).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }
    @ParameterizedTest @EnumSource(value=MediaAction.class,names={"STAMP","CROP_AND_SAVE","EXPORT_EDITED"})
    void actionGrantWithoutViewCannotReusePreviouslyPreparedPage(MediaAction action)throws Exception{
        var file=register();grant(file,Set.of(MediaAction.VIEW,action));var page=prepare(file);grant(file,Set.of(action));
        if(action==MediaAction.STAMP) {
            mvc.perform(multipart("/api/v1/media/"+file.get("mediaId").asText()+"/stamps").file(stampFile()).with(shared()).with(csrf()))
                    .andExpect(status().isNotFound());
        }else{
            boolean export=action==MediaAction.EXPORT_EDITED;
            mvc.perform(post(endpoint(file,export?"export":"crop")).with(shared()).with(csrf()).contentType("application/json")
                    .content(region(file,page,export,false))).andExpect(status().isNotFound());
        }
    }
    @ParameterizedTest @ValueSource(strings={"wav","mp4"})
    void warmPlaybackRangesHeadAndRetryRequireCurrentLinkedViewGrant(String format)throws Exception{
        byte[] input=new byte[512];
        if(format.equals("wav")){System.arraycopy("RIFF".getBytes(),0,input,0,4);System.arraycopy("WAVE".getBytes(),0,input,8,4);}
        else System.arraycopy(new byte[]{0,0,0,24,102,116,121,112,105,115,111,109},0,input,0,12);
        var file=body(mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","synthetic."+format,"application/octet-stream",input))
                .with(owner()).with(csrf())).andExpect(status().isCreated()).andReturn());
        grant(file,Set.of(MediaAction.VIEW));String path="/api/v1/media/"+file.get("mediaId").asText()+"/playback";
        mvc.perform(post(path).with(shared())).andExpect(status().isForbidden());
        mvc.perform(post(path).with(shared()).with(csrf())).andExpect(status().is2xxSuccessful());
        JsonNode ready=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<deadline){var current=body(mvc.perform(get(path).with(shared())).andExpect(status().isOk()).andReturn());
            if(current.path("state").asText().equals("ready")){ready=current;break;}
            assertThat(current.path("state").asText()).isNotEqualTo("failed");Thread.sleep(10);}
        assertThat(ready).isNotNull();String url=ready.get("url").asText();
        mvc.perform(get(url).with(shared()).header("Range","bytes=10-19")).andExpect(status().isPartialContent())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(content().bytes(Arrays.copyOfRange(playback,10,20)));
        mvc.perform(head(url).with(shared()).header("Range","bytes=10-19")).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Length","10")).andExpect(content().bytes(new byte[0]));
        mvc.perform(get(url).with(actor("other","bob"))).andExpect(status().isNotFound());
        mvc.perform(get(url).with(actor("tenant","mallory"))).andExpect(status().isNotFound());
        NOW.set(GRANT.get().expires());
        mvc.perform(get(url).with(shared())).andExpect(status().isNotFound());
        mvc.perform(get(url).with(shared()).header("Range","bytes=999999999-")).andExpect(status().isNotFound());
        mvc.perform(head(url).with(shared())).andExpect(status().isNotFound());
        mvc.perform(get(path).with(shared())).andExpect(status().isNotFound());
        mvc.perform(post(path).with(shared()).with(csrf()).param("retry","true")).andExpect(status().isNotFound());
        verify(engine,times(1)).prepare(any(),any(),any());
    }
}
