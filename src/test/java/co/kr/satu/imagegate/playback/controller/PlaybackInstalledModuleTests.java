package co.kr.satu.imagegate.playback.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

@Tag("ffmpeg")
@SpringBootTest(properties={"imagegate.playback.enabled=true","imagegate.preview.enabled=false","imagegate.conversion.enabled=false"})
@AutoConfigureMockMvc
class PlaybackInstalledModuleTests {
    static final Path ROOT,FFMPEG=Path.of(System.getenv().getOrDefault("FFMPEG_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffmpeg.exe")).toAbsolutePath(),
            FFPROBE=Path.of(System.getenv().getOrDefault("FFPROBE_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffprobe.exe")).toAbsolutePath();
    static {try{ROOT=Files.createTempDirectory("imagegate-real-playback-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static String hash(Path path) {
        try(var in=Files.newInputStream(path)){
            var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] block=new byte[32768];int n;
            while((n=in.read(block))!=-1)digest.update(block,0,n);return HexFormat.of().formatHex(digest.digest());
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("imagegate.storage.root",ROOT::toString);
        r.add("imagegate.playback.ffmpeg",FFMPEG::toString);r.add("imagegate.playback.ffprobe",FFPROBE::toString);
        r.add("imagegate.playback.ffmpeg-sha256",()->hash(FFMPEG));r.add("imagegate.playback.ffprobe-sha256",()->hash(FFPROBE));
    }
    @Autowired MockMvc mvc;
    final JsonMapper json=JsonMapper.builder().build();
    RequestPostProcessor actor(){return authentication(UsernamePasswordAuthenticationToken.authenticated(
            new ViewerPrincipal("tenant","alice"),"unused",List.of()));}
    @Test void realSpringQueueProbeNormalizationAndProtectedRangeWorkTogether() throws Exception {
        byte[] pcm=new byte[48000*2];
        for(int i=0;i<48000;i++){short sample=(short)(Math.sin(i*2*Math.PI*440/48000)*8000);pcm[i*2]=(byte)sample;pcm[i*2+1]=(byte)(sample>>8);}
        var wav=new ByteArrayOutputStream();
        try(var audio=new AudioInputStream(new ByteArrayInputStream(pcm),new AudioFormat(48000,16,1,true,false),48000)){
            AudioSystem.write(audio,AudioFileFormat.Type.WAVE,wav);
        }
        var registered=mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","synthetic.wav","audio/wav",wav.toByteArray()))
                .with(actor()).with(csrf())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.playback.state").value("not_requested")).andReturn();
        String id=json.readTree(registered.getResponse().getContentAsString()).get("mediaId").asText();
        mvc.perform(post("/api/v1/media/"+id+"/playback").with(actor()).with(csrf())).andExpect(status().isAccepted());
        String url=null;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){
            var response=mvc.perform(get("/api/v1/media/"+id+"/playback").with(actor())).andExpect(status().isOk()).andReturn();
            var info=json.readTree(response.getResponse().getContentAsString());
            if(info.get("state").asText().equals("failed"))throw new AssertionError(info.toString());
            if(info.get("state").asText().equals("ready")){url=info.get("url").asText();break;}Thread.sleep(20);
        }
        assertThat(url).isNotNull();
        mvc.perform(get(url).header("Range","bytes=100-199").with(actor())).andExpect(status().isPartialContent())
                .andExpect(content().contentType("audio/mpeg")).andExpect(header().string("Content-Length","100"));
        mvc.perform(get("/api/v1/media/"+id).with(actor())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready")).andExpect(jsonPath("$.playback.audioCodec").value("mp3"));
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
    }
}
