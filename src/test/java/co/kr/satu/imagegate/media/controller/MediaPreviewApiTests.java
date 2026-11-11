package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.*;
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

@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.preview.enabled=true","imagegate.preview.metrics-enabled=false",
        "imagegate.preview.max-width=32","imagegate.preview.max-height=32",
        "imagegate.preview.max-source-pixels=100000"})
@AutoConfigureMockMvc
@Import(MediaPreviewApiTests.PolicyConfiguration.class)
class MediaPreviewApiTests {
    static final Path ROOT;
    static { try { ROOT=Files.createTempDirectory("imagegate-preview-api-"); }
        catch(IOException e) { throw new ExceptionInInitializerError(e); } }
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true);
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) {
        r.add("imagegate.storage.root",ROOT::toString);
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class PolicyConfiguration {
        @Bean @Primary MediaAuthorizationPolicy testPolicy() {
            return (a,m,action)-> ALLOWED.get() && a.tenantId().equals(m.tenantId()) && a.userId().equals(m.ownerId());
        }
    }
    @Autowired MockMvc mvc;
    @Autowired co.kr.satu.imagegate.conversion.service.PreviewMetrics metrics;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;
    @Autowired co.kr.satu.imagegate.conversion.repository.PreviewCache cache;
    final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void allow() { ALLOWED.set(true); }
    @AfterEach void reset() { ALLOWED.set(true); }
    RequestPostProcessor actor(String tenant,String name) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(
                new ViewerPrincipal(tenant,name),"unused",List.of()));
    }
    RequestPostProcessor owner() { return actor("tenant","alice"); }
    byte[] png(int width,int height) throws Exception {
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();
    }
    String upload(byte[] bytes,String name) throws Exception {
        var result=mvc.perform(multipart("/api/v1/media")
                .file(new MockMultipartFile("file",name,"application/octet-stream",bytes))
                .with(owner()).with(csrf())).andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("mediaId").asText();
    }
    JsonNode await(String id,int page) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        do {
            var response=mvc.perform(get("/api/v1/media/"+id+"/status").param("page",""+page).with(owner()))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
            var body=json.readTree(response.getResponse().getContentAsString());
            if(List.of("ready","failed").contains(body.get("state").asText())) return body;
            Thread.sleep(20);
        } while(System.nanoTime()<end);
        throw new AssertionError("Processing timed out");
    }

    @Test void persistedUnsupportedProfileResponseKeepsContentAndEditingBlockedAndChecksCurrentRights() throws Exception {
        String id=upload(png(16,16),"source.png");
        var ready=await(id,1);String key=ready.get("source").get("sourceId").asText();
        String url=ready.get("source").get("url").asText();
        cache.state(key,1,"failed","CONVERSION_UNSUPPORTED_PROFILE");
        var result=await(id,1);
        assertThat(result.get("errorCode").asText()).isEqualTo("CONVERSION_UNSUPPORTED_PROFILE");
        assertThat(result.get("source").isNull()).isTrue();assertThat(result.get("thumbnail").isNull()).isTrue();
        assertThat(result.toString()).doesNotContain(ROOT.toString(),"private-command");
        mvc.perform(get(url).with(owner())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.errorCode").value("CONVERSION_UNSUPPORTED_PROFILE"))
                .andExpect(jsonPath("$.editingReady").value(false)).andExpect(jsonPath("$.source").isEmpty());
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner()).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("failed"));
        mvc.perform(get("/api/v1/media/"+id+"/status").with(actor("other","alice"))).andExpect(status().isNotFound());
        ALLOWED.set(false);
        mvc.perform(get("/api/v1/media/"+id+"/status").with(owner())).andExpect(status().isNotFound());
    }

    @Test void disabledMetricsServePreviewsWithoutRegisteringProcessingMeters() throws Exception {
        assertThat(metrics).isSameAs(co.kr.satu.imagegate.conversion.service.PreviewMetrics.NONE);
        String id=upload(png(64,32),"synthetic.png");assertThat(await(id,1).get("state").asText()).isEqualTo("ready");
        assertThat(meters.getMeters().stream().filter(m->m.getId().getName().startsWith("imagegate.preview.")).toList()).isEmpty();
    }

    @Test void validatesResizesPublishesAndReusesProtectedPng() throws Exception {
        String id=upload(png(160,80),"source.png");
        var state=await(id,1);
        assertThat(state.get("state").asText()).isEqualTo("ready");
        String url=state.get("source").get("url").asText();
        var result=mvc.perform(get(url).with(owner())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("X-Content-Type-Options","nosniff")).andReturn();
        var decoded=ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        assertThat(decoded.getWidth()).isEqualTo(32);assertThat(decoded.getHeight()).isEqualTo(16);
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source.url").value(url));
        mvc.perform(get("/api/v1/media/"+id).with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready")).andExpect(jsonPath("$.sources[0].url").value(url));
        mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(owner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("ready"))
                .andExpect(jsonPath("$.displayWidth").value(32)).andExpect(jsonPath("$.displayHeight").value(16))
                .andExpect(jsonPath("$.coordinateSpaceId").value(org.hamcrest.Matchers.startsWith("original:")))
                .andExpect(jsonPath("$.coordinateWidth").value(160)).andExpect(jsonPath("$.coordinateHeight").value(80))
                .andExpect(jsonPath("$.previewToOriginal.a").value(5.0))
                .andExpect(jsonPath("$.previewToOriginal.d").value(5.0))
                .andExpect(jsonPath("$.originalCoordinatesAvailable").value(true))
                .andExpect(jsonPath("$.editingReady").value(true));
        mvc.perform(get("/api/v1/media/"+id+"/pages/2").with(owner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("not_requested"))
                .andExpect(jsonPath("$.source").isEmpty());
        assertThat(state.toString()).doesNotContain(ROOT.toString(),"storedFileName");
    }

    @Test void cacheHitsStatusAndContentRecheckOwnerTenantAndRevocation() throws Exception {
        String id=upload(png(16,16),"source.png");
        String url=await(id,1).get("source").get("url").asText();
        for(var other:List.of(actor("tenant","bob"),actor("other","alice"))) {
            mvc.perform(get("/api/v1/media/"+id+"/status").with(other)).andExpect(status().isNotFound());
            mvc.perform(get(url).with(other)).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(other)).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/media/"+id+"/previews").with(other).with(csrf())).andExpect(status().isNotFound());
        }
        ALLOWED.set(false);
        mvc.perform(get(url).with(owner())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/"+id+"/status").with(owner())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner()).with(csrf())).andExpect(status().isNotFound());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={
            "524946460400000057454250", "00000018667479706865696300000000686569636d696631",
            "0000000c6a5020200d0a870a", "ff4fff51"})
    void recognizedCandidateWithoutConfiguredDecoderNeverExposesReadySource(String hex) throws Exception {
        // Synthetic signatures exercise classification/failure only, not actual format compatibility.
        String id=upload(java.util.HexFormat.of().parseHex(hex),"claimed-image.png");
        var result=await(id,1);
        assertThat(result.get("state").asText()).isEqualTo("failed");
        assertThat(result.get("errorCode").asText()).isEqualTo("DECODER_UNAVAILABLE");
        assertThat(result.get("source").isNull()).isTrue();
        assertThat(result.get("thumbnail").isNull()).isTrue();
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("failed"))
                .andExpect(jsonPath("$.errorCode").value("DECODER_UNAVAILABLE"));
        mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(owner()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.editingReady").value(false))
                .andExpect(jsonPath("$.originalCoordinatesAvailable").value(false))
                .andExpect(jsonPath("$.source").isEmpty());
        mvc.perform(get("/api/v1/media/"+id+"/sources/"+"0".repeat(64)+"/content").with(owner()))
                .andExpect(status().isNotFound());
        assertThat(result.toString()).doesNotContain(ROOT.toString(),"storedFileName");
    }

    @Test void rejectsFakeHeaderAndOversizedPixelInputWithoutPublishing() throws Exception {
        for(var bytes:List.of(new byte[]{(byte)137,80,78,71,13,10,26,10},png(400,400))) {
            String id=upload(bytes,"fake.png");
            var result=await(id,1);
            assertThat(result.get("state").asText()).isEqualTo("failed");
            assertThat(result.get("source").isNull()).isTrue();
            mvc.perform(get("/api/v1/media/"+id+"/sources/"+"0".repeat(64)+"/content").with(owner()))
                    .andExpect(status().isNotFound());
        }
    }

    @Test void validatesPageAndCsrfAndDoesNotTreatSourceIdAsAuthorization() throws Exception {
        String id=upload(png(16,16),"source.png");await(id,1);
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/media/"+id+"/status").param("page","0").with(owner())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/media/"+id+"/pages/0").with(owner())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/media/"+id+"/sources/unknown/content").with(owner())).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/media/"+id+"/status")).andExpect(status().isUnauthorized());
    }

    @Test void rendersRequestedTiffPageWithoutInventingPageCount() throws Exception {
        var out=new ByteArrayOutputStream();
        var writer=ImageIO.getImageWritersByFormatName("TIFF").next();
        try(var stream=ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);writer.prepareWriteSequence(null);
            for(var color:List.of(Color.RED,Color.BLUE)) {
                var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics();g.setColor(color);g.fillRect(0,0,16,16);g.dispose();
                writer.writeToSequence(new IIOImage(image,null,null),writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        String id=upload(out.toByteArray(),"two.tif");await(id,1);
        mvc.perform(post("/api/v1/media/"+id+"/previews").param("page","2").with(owner()).with(csrf()))
                .andExpect(status().isAccepted());
        String url=await(id,2).get("source").get("url").asText();
        byte[] bytes=mvc.perform(get(url).with(owner())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(bytes)).getRGB(0,0)&255).isEqualTo(255);
        mvc.perform(post("/api/v1/media/"+id+"/previews").param("page","3").with(owner()).with(csrf()))
                .andExpect(status().isAccepted());
        assertThat(await(id,3).get("state").asText()).isEqualTo("failed");
    }

    @Test void viewerSessionTracksValidatedAndFailedImagePositions() throws Exception {
        String id=upload(png(16,16),"image.png");await(id,1);
        mvc.perform(post("/api/v1/viewer/sessions").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("mediaIds",List.of(id))))
                .with(owner()).with(csrf())).andExpect(status().isCreated())
                .andExpect(jsonPath("$.positionState").value("validated"));
        var response=mvc.perform(post("/api/v1/viewer/sessions").contentType("application/json")
                .content(json.writeValueAsString(java.util.Map.of("mediaIds",List.of(id),"initialPage",2)))
                .with(owner()).with(csrf())).andExpect(status().isCreated()).andReturn();
        String session=json.readTree(response.getResponse().getContentAsString()).get("sessionId").asText();
        assertThat(await(id,2).get("state").asText()).isEqualTo("failed");
        mvc.perform(get("/api/v1/viewer/sessions/"+session).with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.positionState").value("failed"))
                .andExpect(jsonPath("$.media[0].sources").isEmpty());
    }

    @Test void exposesUprightOriginalCoordinatesAndInverseForExifRotatedJpeg() throws Exception {
        String id=upload(co.kr.satu.imagegate.conversion.util.OrientationFixtures.jpeg(6),"rotated.jpg");
        assertThat(await(id,1).get("state").asText()).isEqualTo("ready");
        var response=mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(owner()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.encodedWidth").value(60))
                .andExpect(jsonPath("$.encodedHeight").value(40)).andExpect(jsonPath("$.exifOrientation").value(6))
                .andExpect(jsonPath("$.coordinateWidth").value(40)).andExpect(jsonPath("$.coordinateHeight").value(60))
                .andExpect(jsonPath("$.displayWidth").value(21)).andExpect(jsonPath("$.displayHeight").value(32))
                .andExpect(jsonPath("$.originalToEncoded.a").value(0.0))
                .andExpect(jsonPath("$.originalToEncoded.c").value(1.0))
                .andExpect(jsonPath("$.originalToEncoded.b").value(-1.0))
                .andExpect(jsonPath("$.originalToEncoded.f").value(40.0)).andReturn();
        var page=json.readTree(response.getResponse().getContentAsString());
        assertThat(page.get("previewToOriginal").get("a").asDouble()).isCloseTo(40.0/21.0,within(1e-10));
        assertThat(page.get("previewToOriginal").get("d").asDouble()).isEqualTo(60.0/32.0);
    }
    @Test void evictionAndRebuildPreserveNotesStampsGeometryAndProtectedHead() throws Exception {
        String id=upload(png(160,80),"original.png");String source=await(id,1).get("source").get("url").asText();
        mvc.perform(head(source).with(owner())).andExpect(status().isOk()).andExpect(content().bytes(new byte[0]))
                .andExpect(header().exists("Content-Length")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(head(source)).andExpect(status().isUnauthorized());
        mvc.perform(head(source).with(actor("tenant","bob"))).andExpect(status().isNotFound());
        String annotations="/api/v1/media/"+id+"/pages/1/annotations";
        var initial=json.readTree(mvc.perform(get(annotations).with(owner())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var note=new java.util.LinkedHashMap<String,Object>();note.put("mediaVersion",initial.get("mediaVersion").asText());
        note.put("coordinateSpaceId",initial.get("coordinateSpaceId").asText());note.put("expectedRevision",0);note.put("type","note");
        note.put("x",4);note.put("y",5);note.put("width",20);note.put("height",10);note.put("text","Keep this note");
        mvc.perform(post(annotations).with(owner()).with(csrf()).contentType("application/json").content(json.writeValueAsString(note)))
                .andExpect(status().isCreated());
        var stampResponse=mvc.perform(multipart("/api/v1/media/"+id+"/stamps")
                .file(new MockMultipartFile("file","stamp.png","image/png",png(8,8))).with(owner()).with(csrf()))
                .andExpect(status().isCreated()).andReturn();
        String stamp=json.readTree(stampResponse.getResponse().getContentAsString()).get("stampId").asText();
        note.put("expectedRevision",1);note.put("type","stamp");note.remove("text");note.put("stampId",stamp);
        var before=json.readTree(mvc.perform(post(annotations).with(owner()).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(note))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var policy=co.kr.satu.imagegate.system.config.ImageGateProperties.PreviewCachePolicy.defaults();
        long removed=0,end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<end&&removed==0){removed+=cache.maintain(policy,System.currentTimeMillis()+java.time.Duration.ofDays(8).toMillis()).removed();Thread.sleep(10);}
        assertThat(removed).isGreaterThan(0);
        mvc.perform(get("/api/v1/media/"+id+"/status").with(owner())).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("not_requested"));
        mvc.perform(get(source).with(owner())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(owner()).with(csrf())).andExpect(status().isAccepted());
        assertThat(await(id,1).get("source").get("url").asText()).isEqualTo(source);
        var after=json.readTree(mvc.perform(get(annotations).with(owner())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(after).isEqualTo(before);
        mvc.perform(get(after.get("items").get(1).get("stampUrl").asText()).with(owner()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(owner())).andExpect(status().isOk())
                .andExpect(jsonPath("$.coordinateWidth").value(160)).andExpect(jsonPath("$.coordinateHeight").value(80));
    }
}
