package co.kr.satu.imagegate.editing.controller;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import co.kr.satu.imagegate.editing.util.PrivateFiles;
import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"imagegate.conversion.enabled=false","imagegate.preview.enabled=true",
        "imagegate.preview.max-width=128","imagegate.preview.max-height=128",
        "imagegate.editing.max-annotations-per-page=3","imagegate.editing.max-note-length=40",
        "imagegate.crop.max-output-pixels=10000",
        "imagegate.editing.max-stamps-per-user=2","imagegate.editing.max-stamp-upload-bytes=1024"})
@AutoConfigureMockMvc
@Import(EditingApiTests.Policy.class)
class EditingApiTests {
    static final Path ROOT;
    static {try{ROOT=Files.createTempDirectory("imagegate-editing-api-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true),NOTE=new AtomicBoolean(true),
            STAMP=new AtomicBoolean(true),CROP=new AtomicBoolean(true),EXPORT=new AtomicBoolean(true),SHARED=new AtomicBoolean(false),SHARED_EXPORT=new AtomicBoolean(false),SHARED_EDIT=new AtomicBoolean(false);
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r){r.add("imagegate.storage.root",ROOT::toString);}
    @TestConfiguration(proxyBeanMethods=false) static class Policy {
        @Bean @Primary MediaAuthorizationPolicy policy(){return (a,m,action)->a.tenantId().equals(m.tenantId())&&ALLOWED.get()
                && ((a.userId().equals(m.ownerId())&&(action!=MediaAction.ANNOTATE||NOTE.get())&&(action!=MediaAction.STAMP||STAMP.get())&&(action!=MediaAction.CROP_AND_SAVE||CROP.get())&&(action!=MediaAction.EXPORT_EDITED||EXPORT.get()))
                ||(SHARED.get()&&(action==MediaAction.VIEW||(SHARED_EDIT.get()&&(action==MediaAction.ANNOTATE||action==MediaAction.STAMP))||(SHARED_EXPORT.get()
                        &&(action==MediaAction.EXPORT_EDITED||action==MediaAction.CROP_AND_SAVE)))));}
    }
    @Autowired MockMvc mvc;@Autowired MediaStore mediaStore;
    final JsonMapper json=JsonMapper.builder().build();
    final ViewerPrincipal owner=new ViewerPrincipal("tenant","u"+UUID.randomUUID().toString().substring(0,8));
    RequestPostProcessor actor(ViewerPrincipal a){return authentication(UsernamePasswordAuthenticationToken.authenticated(a,"unused",List.of()));}
    @BeforeEach void flags(){ALLOWED.set(true);NOTE.set(true);STAMP.set(true);CROP.set(true);EXPORT.set(true);SHARED.set(false);SHARED_EXPORT.set(false);SHARED_EDIT.set(false);}
    @AfterEach void reset(){flags();}
    byte[] png(int width,int height) throws IOException {
        var out=new ByteArrayOutputStream();var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();g.setColor(new Color(210,40,40,180));g.fillRect(5,5,Math.max(1,width-10),Math.max(1,height-10));g.dispose();
        ImageIO.write(image,"png",out);return out.toByteArray();
    }
    JsonNode body(MvcResult result) throws Exception{return json.readTree(result.getResponse().getContentAsString());}
    String media() throws Exception {
        var response=mvc.perform(multipart("/api/v1/media").file(new MockMultipartFile("file","image.png","image/png",png(256,160)))
                .with(actor(owner)).with(csrf())).andExpect(status().isCreated()).andReturn();
        String id=body(response).get("mediaId").asText();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {
            var status=body(mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(actor(owner))).andReturn());
            if(status.get("state").asText().equals("ready"))return id;
            Thread.sleep(10);
        }
        throw new AssertionError("Image not ready");
    }
    String base(String id){return "/api/v1/media/"+id+"/pages/1/annotations";}
    Map<String,Object> change(String id,int revision,String type,String stampId) throws Exception {
        var record=mediaStore.find(owner.tenantId(),id).orElseThrow();
        var values=new LinkedHashMap<String,Object>();
        values.put("mediaVersion",record.mediaVersion());values.put("coordinateSpaceId","original:"+record.mediaVersion()+":1:upright-v1");
        values.put("expectedRevision",revision);values.put("type",type);values.put("x",30);values.put("y",20);
        values.put("width",100);values.put("height",50);
        if(type.equals("note"))values.put("text","검토 메모");else values.put("stampId",stampId);
        return values;
    }
    JsonNode create(String id,Map<String,Object> data) throws Exception {
        return body(mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(data))
                .with(actor(owner)).with(csrf())).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control","no-store")).andReturn());
    }
    JsonNode stamp(String id) throws Exception {
        return body(mvc.perform(multipart("/api/v1/media/"+id+"/stamps")
                .file(new MockMultipartFile("file","seal.png","image/png",png(64,32))).with(actor(owner)).with(csrf()))
                .andExpect(status().isCreated()).andReturn());
    }

    @Test void noteCrudPreservesOriginalAndDoesNotExposePrivateIdentity() throws Exception {
        String id=media();var record=mediaStore.find(owner.tenantId(),id).orElseThrow();
        byte[] original=Files.readAllBytes(mediaStore.resolveOriginal(record));
        var data=change(id,0,"note",null);data.put("text","<img src=x onerror=alert(1)>");
        var doc=create(id,data);String item=doc.get("items").get(0).get("id").asText();
        assertThat(doc.get("revision").asInt()).isEqualTo(1);
        assertThat(doc.toString()).doesNotContain(ROOT.toString(),"stampOwner",owner.userId(),"tenantId");
        var replacement=change(id,1,"note",null);replacement.put("x",70);replacement.put("text","수정한 메모");
        mvc.perform(put(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(replacement))
                .with(actor(owner)).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.items[0].x").value(70.0));
        var delete=Map.of("mediaVersion",record.mediaVersion(),"coordinateSpaceId",data.get("coordinateSpaceId"),"expectedRevision",2);
        mvc.perform(delete(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(delete))
                .with(actor(owner)).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(3)).andExpect(jsonPath("$.items").isEmpty());
        assertThat(Files.readAllBytes(mediaStore.resolveOriginal(record))).isEqualTo(original);
    }

    @Test void staleRevisionVersionAndCoordinateSpaceCannotOverwriteEdits() throws Exception {
        String id=media();var data=change(id,0,"note",null);create(id,data);
        mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(data))
                .with(actor(owner)).with(csrf())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        for(String field:List.of("mediaVersion","coordinateSpaceId")) {
            var invalid=change(id,1,"note",null);invalid.put(field,field.equals("mediaVersion")?UUID.randomUUID().toString():"preview:wrong");
            mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(invalid))
                    .with(actor(owner)).with(csrf())).andExpect(status().isConflict());
        }
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test void rejectsMissingNonFiniteOutOfBoundsAndOverlongNoteInput() throws Exception {
        String id=media();
        for(String field:List.of("x","width","text","expectedRevision","y")) {
            var invalid=change(id,0,"note",null);
            if(field.equals("x"))invalid.put(field,-1);
            else if(field.equals("width"))invalid.put(field,9999);
            else if(field.equals("text"))invalid.put(field,"x".repeat(41));
            else invalid.remove(field);
            mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(invalid))
                    .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        }
        String infinity=json.writeValueAsString(change(id,0,"note",null)).replace("\"x\":30","\"x\":1e309");
        mvc.perform(post(base(id)).contentType("application/json").content(infinity).with(actor(owner)).with(csrf()))
                .andExpect(status().isBadRequest());
        var oversized=change(id,0,"note",null);oversized.put("text","x".repeat(20000));
        mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(oversized))
                .with(actor(owner)).with(csrf())).andExpect(status().is(413)).andExpect(jsonPath("$.code").value("EDIT_REQUEST_TOO_LARGE"));
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(jsonPath("$.revision").value(0));
    }

    @Test void boundsAnnotationsAndDoesNotAcceptUnpreparedPage() throws Exception {
        String id=media();
        for(int i=0;i<3;i++)create(id,change(id,i,"note",null));
        mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(change(id,3,"note",null)))
                .with(actor(owner)).with(csrf())).andExpect(status().isTooManyRequests());
        mvc.perform(get("/api/v1/media/"+id+"/pages/2/annotations").with(actor(owner)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_NOT_READY"));
    }

    @Test void stampUploadSanitizesAlphaAndRejectsActiveOrOversizedInputs() throws Exception {
        String id=media();var stamp=stamp(id);
        String url=stamp.get("contentUrl").asText();
        byte[] bytes=mvc.perform(get(url).with(actor(owner))).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andReturn().getResponse().getContentAsByteArray();
        var decoded=ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(decoded.getWidth()).isEqualTo(64);assertThat(decoded.getRGB(0,0)>>>24).isZero();
        mvc.perform(multipart("/api/v1/media/"+id+"/stamps").file(new MockMultipartFile("file","bad.png","image/png","<svg onload='alert(1)'/>".getBytes()))
                .with(actor(owner)).with(csrf())).andExpect(status().isUnsupportedMediaType());
        for(byte[] invalid:List.of(new byte[1025],png(4096,1)))
            mvc.perform(multipart("/api/v1/media/"+id+"/stamps").file(new MockMultipartFile("file","bad.png","image/png",invalid))
                    .with(actor(owner)).with(csrf())).andExpect(status().is(413));
        stamp(id);
        mvc.perform(multipart("/api/v1/media/"+id+"/stamps").file(new MockMultipartFile("file","third.png","image/png",png(10,10)))
                .with(actor(owner)).with(csrf())).andExpect(status().isTooManyRequests());
    }

    @Test void publishedStampIsVisibleByFileAclWhilePrivateLibraryRemainsOwnerOnly() throws Exception {
        String id=media();var stamp=stamp(id);String stampId=stamp.get("stampId").asText();
        var doc=create(id,change(id,0,"stamp",stampId));
        String url=doc.get("items").get(0).get("stampUrl").asText();
        var bob=new ViewerPrincipal("tenant","bob");SHARED.set(true);
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(actor(bob)).with(csrf()))
                .andExpect(status().isAccepted());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end) {
            var page=body(mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(actor(bob))).andReturn());
            if(page.get("state").asText().equals("ready"))break;Thread.sleep(10);
        }
        mvc.perform(get(base(id)).with(actor(bob))).andExpect(status().isOk());
        mvc.perform(get(url).with(actor(bob))).andExpect(status().isOk());
        mvc.perform(get(stamp.get("contentUrl").asText()).with(actor(bob))).andExpect(status().isNotFound());
        SHARED_EXPORT.set(true);
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(edited(id,1,true)))
                .with(actor(bob)).with(csrf())).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/v1/stamps").with(actor(bob))).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(url).with(actor(new ViewerPrincipal("Tenant","bob")))).andExpect(status().isNotFound());
        ALLOWED.set(false);
        mvc.perform(get(url).with(actor(owner))).andExpect(status().isNotFound());
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(status().isNotFound());
    }

    @Test void rechecksActionPermissionAndDoesNotAllowTypeConfusionOrForeignStampReference() throws Exception {
        String id=media();var stamp=stamp(id);var doc=create(id,change(id,0,"stamp",stamp.get("stampId").asText()));
        String item=doc.get("items").get(0).get("id").asText();
        var note=change(id,1,"note",null);
        mvc.perform(put(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(note))
                .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        STAMP.set(false);
        var delete=Map.of("mediaVersion",doc.get("mediaVersion").asText(),"coordinateSpaceId",doc.get("coordinateSpaceId").asText(),"expectedRevision",1);
        mvc.perform(delete(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(delete))
                .with(actor(owner)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(status().isOk());
        STAMP.set(true);
        mvc.perform(post(base(id)).contentType("application/json")
                .content(json.writeValueAsString(change(id,1,"stamp",UUID.randomUUID().toString()))).with(actor(owner)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test void anonymousMissingCsrfAndOtherOwnersCannotModifyOrReadEdits() throws Exception {
        String id=media();var data=change(id,0,"note",null);
        mvc.perform(get(base(id))).andExpect(status().isUnauthorized());
        mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(data))
                .with(actor(owner))).andExpect(status().isForbidden());
        mvc.perform(get(base(id)).with(actor(new ViewerPrincipal("tenant","other")))).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/v1/media/"+id+"/stamps").file(new MockMultipartFile("file","seal.png","image/png",png(32,32)))
                .with(actor(new ViewerPrincipal("tenant","other"))).with(csrf())).andExpect(status().isNotFound());
    }

    Map<String,Object> crop(String id) throws Exception {
        var data=new LinkedHashMap<String,Object>(change(id,0,"note",null));
        data.remove("expectedRevision");data.remove("type");data.remove("text");
        data.put("x",20);data.put("y",30);data.put("width",100);data.put("height",60);return data;
    }
    String cropUrl(String id){return "/api/v1/media/"+id+"/pages/1/crop";}

    @Test void cropDownloadsOriginalPixelsAndLeavesNoNewMediaOrTemporaryFiles() throws Exception {
        String id=media();var record=mediaStore.find(owner.tenantId(),id).orElseThrow();
        byte[] before=Files.readAllBytes(mediaStore.resolveOriginal(record));
        var result=mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(crop(id)))
                .with(actor(owner)).with(csrf())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"imagegate-crop-p1.png\""))
                .andExpect(header().string("X-Image-Width","100")).andExpect(header().string("X-Image-Height","60")).andReturn();
        var actual=ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
        var source=ImageIO.read(new ByteArrayInputStream(before));
        assertThat(actual.getWidth()).isEqualTo(100);assertThat(actual.getHeight()).isEqualTo(60);
        for(int y=0;y<60;y++)for(int x=0;x<100;x++)assertThat(actual.getRGB(x,y)).isEqualTo(source.getRGB(x+20,y+30));
        assertThat(Files.readAllBytes(mediaStore.resolveOriginal(record))).isEqualTo(before);
        try(var paths=Files.walk(ROOT)){assertThat(paths.filter(p->p.getFileName().toString().startsWith("crop-")).toList())
                .allMatch(p->p.getFileName().toString().equals("crop-work-v1"));}
    }
    @Test void cropRejectsFractionalMissingNonFiniteAndOutOfBoundsCoordinates() throws Exception {
        String id=media();
        for(Object value:List.of(-1,0.5,99999)) {
            var invalid=crop(id);invalid.put("x",value);
            mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(invalid))
                    .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        }
        var missing=crop(id);missing.remove("width");
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(missing))
                .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        var large=crop(id);large.put("x",0);large.put("y",0);large.put("width",256);large.put("height",160);
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(large))
                .with(actor(owner)).with(csrf())).andExpect(status().is(413));
        var unknown=crop(id);unknown.put("sourcePath","D:/private.png");
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(unknown))
                .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        String tooLarge="{\"text\":\""+"a".repeat(20000)+"\"}";
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(tooLarge).with(actor(owner)).with(csrf()))
                .andExpect(status().is(413));
    }
    @Test void cropRequiresTrustedActorCsrfAndCurrentSavePermission() throws Exception {
        String id=media();String body=json.writeValueAsString(crop(id));
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(body)).andExpect(status().is4xxClientError());
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(body).with(actor(owner))).andExpect(status().isForbidden());
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(body)
                .with(actor(new ViewerPrincipal("tenant","other"))).with(csrf())).andExpect(status().isNotFound());
        CROP.set(false);
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(status().isOk());
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(body).with(actor(owner)).with(csrf()))
                .andExpect(status().isNotFound());
    }
    @Test void cropRejectsStaleVersionCoordinateSpaceAndChangedSource() throws Exception {
        String id=media();
        for(String field:List.of("mediaVersion","coordinateSpaceId")) {
            var stale=crop(id);stale.put(field,field.equals("mediaVersion")?UUID.randomUUID().toString():"wrong");
            mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(stale))
                    .with(actor(owner)).with(csrf())).andExpect(status().isConflict());
        }
        var record=mediaStore.find(owner.tenantId(),id).orElseThrow();
        Path original=mediaStore.resolveOriginal(record);byte[] changed=Files.readAllBytes(original);changed[changed.length-1]^=1;Files.write(original,changed);
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(crop(id)))
                .with(actor(owner)).with(csrf())).andExpect(status().isConflict());
    }

    Map<String,Object> edited(String id,int revision,boolean include) throws Exception {
        var data=new LinkedHashMap<String,Object>(crop(id));data.put("expectedRevision",revision);
        data.put("includeAnnotations",include);data.put("brightness",100);data.put("rotation",0);return data;
    }
    String exportUrl(String id){return "/api/v1/media/"+id+"/pages/1/export";}
    byte[] exported(String id,Map<String,Object> data) throws Exception {
        return mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(data))
                .with(actor(owner)).with(csrf())).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Content-Disposition","attachment; filename=\"imagegate-edited-p1.png\""))
                .andReturn().getResponse().getContentAsByteArray();
    }
    @Test void editedExportCompositesSavedNotesAndStampsThenRotatesWithoutChangingTheOriginal() throws Exception {
        String id=media();var record=mediaStore.find(owner.tenantId(),id).orElseThrow();
        byte[] original=Files.readAllBytes(mediaStore.resolveOriginal(record));
        create(id,change(id,0,"note",null));
        var stamp=stamp(id);var placement=change(id,1,"stamp",stamp.get("stampId").asText());
        placement.put("x",90);placement.put("y",50);placement.put("width",20);placement.put("height",20);create(id,placement);
        var data=edited(id,2,true);data.put("brightness",150);data.put("rotation",90);
        var result=ImageIO.read(new ByteArrayInputStream(exported(id,data)));
        assertThat(result.getWidth()).isEqualTo(60);assertThat(result.getHeight()).isEqualTo(100);
        var plain=edited(id,0,false);plain.put("brightness",150);plain.put("rotation",90);
        var base=ImageIO.read(new ByteArrayInputStream(exported(id,plain)));
        int changed=0;
        for(int y=0;y<100;y++)for(int x=0;x<60;x++)if(result.getRGB(x,y)!=base.getRGB(x,y))changed++;
        assertThat(changed).isGreaterThan(100);
        assertThat(Files.readAllBytes(mediaStore.resolveOriginal(record))).isEqualTo(original);
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(jsonPath("$.revision").value(2));
    }
    @Test void editedExportRequiresExactSavedRevisionAndIgnoresNoBrowserAnnotationPayload() throws Exception {
        String id=media();create(id,change(id,0,"note",null));
        var stale=edited(id,0,true);
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(stale))
                .with(actor(owner)).with(csrf())).andExpect(status().isConflict());
        var injected=edited(id,1,true);injected.put("items",List.of(Map.of("stampUrl","https://example.invalid")));
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(injected))
                .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        var wrongVersion=edited(id,1,true);wrongVersion.put("mediaVersion",UUID.randomUUID().toString());
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(wrongVersion))
                .with(actor(owner)).with(csrf())).andExpect(status().isConflict());
    }
    @Test void exportAndCropPermissionsAreEnforcedSeparatelyAndCsrfCannotBeBypassed() throws Exception {
        String id=media();var data=edited(id,0,false);String body=json.writeValueAsString(data);
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(body).with(actor(owner)))
                .andExpect(status().isForbidden());
        EXPORT.set(false);
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(body).with(actor(owner)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(cropUrl(id)).contentType("application/json").content(json.writeValueAsString(crop(id)))
                .with(actor(owner)).with(csrf())).andExpect(status().isOk());
        EXPORT.set(true);CROP.set(false);
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(body).with(actor(owner)).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(body)
                .with(actor(new ViewerPrincipal("Tenant","other"))).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void exportRejectsInvalidAdjustmentsMissingFlagsAndOversizedJson() throws Exception {
        String id=media();
        for(String field:List.of("includeAnnotations","expectedRevision","brightness","rotation")) {
            var data=edited(id,0,false);data.remove(field);
            mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(data))
                    .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        }
        for(int rotation:List.of(-90,45,360)){
            var data=edited(id,0,false);data.put("rotation",rotation);
            mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(data))
                    .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        }
        for(String field:List.of("expectedRevision","brightness","rotation")){
            var fractional=edited(id,0,false);fractional.put(field,field.equals("brightness")?100.5:0.5);
            mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(fractional))
                    .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        }
        var data=edited(id,0,false);data.put("brightness",201);
        mvc.perform(post(exportUrl(id)).contentType("application/json").content(json.writeValueAsString(data))
                .with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(exportUrl(id)).contentType("application/json").content("{\"text\":\""+"a".repeat(20000)+"\"}")
                .with(actor(owner)).with(csrf())).andExpect(status().is(413));
    }
    Map<String,Object> historyBody(String id,int revision) throws Exception {
        var change=change(id,revision,"note",null);return Map.of("mediaVersion",change.get("mediaVersion"),"coordinateSpaceId",change.get("coordinateSpaceId"),"expectedRevision",revision);
    }
    JsonNode history(String id,int revision,boolean redo) throws Exception {
        return body(mvc.perform(post(base(id)+"/history/"+(redo?"redo":"undo")).contentType("application/json").content(json.writeValueAsString(historyBody(id,revision)))
                .with(actor(owner)).with(csrf())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn());
    }
    @Test void undoRedoPreservesIdsOrderingAndMovesRevisionForward() throws Exception {
        String id=media();var first=create(id,change(id,0,"note",null));String item=first.get("items").get(0).get("id").asText();
        var changed=change(id,1,"note",null);changed.put("x",70);changed.put("text","updated");
        mvc.perform(put(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(changed)).with(actor(owner)).with(csrf())).andExpect(status().isOk());
        mvc.perform(delete(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(historyBody(id,2))).with(actor(owner)).with(csrf())).andExpect(status().isOk());
        var restored=history(id,3,false);assertThat(restored.get("revision").asInt()).isEqualTo(4);assertThat(restored.get("items").get(0).get("id").asText()).isEqualTo(item);
        assertThat(restored.get("items").get(0).get("x").asDouble()).isEqualTo(70);
        assertThat(history(id,4,false).get("items")).isEqualTo(first.get("items"));assertThat(history(id,5,false).get("items").size()).isZero();
        assertThat(history(id,6,true).get("items")).isEqualTo(first.get("items"));assertThat(history(id,7,true).get("items").get(0).get("text").asText()).isEqualTo("updated");
        assertThat(history(id,8,true).get("items").size()).isZero();
        mvc.perform(post(base(id)+"/history/redo").contentType("application/json").content(json.writeValueAsString(historyBody(id,9))).with(actor(owner)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_HISTORY_UNAVAILABLE"));
    }
    @Test void newEditDropsRedoAndConcurrentDuplicateUndoMutatesOnce() throws Exception {
        String id=media();create(id,change(id,0,"note",null));String payload=json.writeValueAsString(historyBody(id,1));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var start=new java.util.concurrent.CountDownLatch(1);var jobs=new ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<2;i++)jobs.add(executor.submit(()->{start.await();return mvc.perform(post(base(id)+"/history/undo").contentType("application/json").content(payload).with(actor(owner)).with(csrf())).andReturn().getResponse().getStatus();}));
            start.countDown();var codes=new ArrayList<Integer>();for(var job:jobs)codes.add(job.get(10,TimeUnit.SECONDS));assertThat(codes).containsExactlyInAnyOrder(200,409);
        }
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(jsonPath("$.revision").value(2)).andExpect(jsonPath("$.items").isEmpty()).andExpect(jsonPath("$.history.canRedo").value(true));
        create(id,change(id,2,"note",null));
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(jsonPath("$.history.canRedo").value(false));
        assertThat(history(id,3,false).get("items").size()).isZero();
    }
    @Test void historyIsActorScopedAndExternalChangesCannotBeOverwritten() throws Exception {
        String id=media();create(id,change(id,0,"note",null));SHARED.set(true);SHARED_EDIT.set(true);var bob=new ViewerPrincipal("tenant","bob");
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(actor(bob)).with(csrf())).andExpect(status().isAccepted());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){var page=body(mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(actor(bob))).andReturn());if(page.get("state").asText().equals("ready"))break;Thread.sleep(10);}
        mvc.perform(post(base(id)+"/history/undo").contentType("application/json").content(json.writeValueAsString(historyBody(id,1))).with(actor(bob)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_HISTORY_UNAVAILABLE"));
        mvc.perform(post(base(id)).contentType("application/json").content(json.writeValueAsString(change(id,1,"note",null))).with(actor(bob)).with(csrf())).andExpect(status().isCreated());
        mvc.perform(post(base(id)+"/history/undo").contentType("application/json").content(json.writeValueAsString(historyBody(id,1))).with(actor(owner)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_CONFLICT"));
        mvc.perform(post(base(id)+"/history/undo").contentType("application/json").content(json.writeValueAsString(historyBody(id,2))).with(actor(owner)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EDIT_HISTORY_UNAVAILABLE"));
        create(id,change(id,2,"note",null));var result=history(id,3,false);assertThat(result.get("items").size()).isEqualTo(2);
        assertThat(result.get("history").get("canUndo").asBoolean()).isFalse();
    }
    @Test void restoredStampUsesPrivateServerIdentityAndCurrentStampPermission() throws Exception {
        String id=media();var stamp=stamp(id);var doc=create(id,change(id,0,"stamp",stamp.get("stampId").asText()));String item=doc.get("items").get(0).get("id").asText();
        mvc.perform(delete(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(historyBody(id,1))).with(actor(owner)).with(csrf())).andExpect(status().isOk());
        STAMP.set(false);mvc.perform(get(base(id)).with(actor(owner))).andExpect(jsonPath("$.history.canUndo").value(false));
        mvc.perform(post(base(id)+"/history/undo").contentType("application/json").content(json.writeValueAsString(historyBody(id,2))).with(actor(owner)).with(csrf())).andExpect(status().isNotFound());
        STAMP.set(true);var restored=history(id,2,false);assertThat(restored.get("items")).isEqualTo(doc.get("items"));assertThat(restored.toString()).doesNotContain("stampOwner",owner.userId(),ROOT.toString());
        mvc.perform(get(restored.get("items").get(0).get("stampUrl").asText()).with(actor(owner))).andExpect(status().isOk());
        ALLOWED.set(false);mvc.perform(post(base(id)+"/history/redo").contentType("application/json").content(json.writeValueAsString(historyBody(id,3))).with(actor(owner)).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void historyRequiresCsrfCurrentIdentityAndBoundedStrictInput() throws Exception {
        String id=media();create(id,change(id,0,"note",null));String url=base(id)+"/history/undo",payload=json.writeValueAsString(historyBody(id,1));
        mvc.perform(post(url).contentType("application/json").content(payload).with(actor(owner))).andExpect(status().isForbidden());
        mvc.perform(post(url).contentType("application/json").content(payload).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType("application/json").content(payload).with(actor(new ViewerPrincipal("other",owner.userId()))).with(csrf())).andExpect(status().isNotFound());
        var wrong=new LinkedHashMap<String,Object>(historyBody(id,1));wrong.put("mediaVersion",UUID.randomUUID().toString());
        mvc.perform(post(url).contentType("application/json").content(json.writeValueAsString(wrong)).with(actor(owner)).with(csrf())).andExpect(status().isConflict());
        wrong=new LinkedHashMap<>(historyBody(id,1));wrong.put("items",List.of());
        mvc.perform(post(url).contentType("application/json").content(json.writeValueAsString(wrong)).with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType("application/json").content(" ".repeat(17000)).with(actor(owner)).with(csrf())).andExpect(status().is(413));
        mvc.perform(post(base(id)+"/history/invalid").contentType("application/json").content(" ".repeat(17000)).with(actor(owner)).with(csrf())).andExpect(status().is(413));
        mvc.perform(post(base(id)+"/history/invalid").contentType("application/json").content(payload).with(actor(owner)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(get(base(id)).with(actor(owner))).andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.history.canUndo").value(true));
    }
    @Test void undoRestoresOriginalLayerOrderAndRedoReappliesIt() throws Exception {
        String id=media();String first=create(id,change(id,0,"note",null)).get("items").get(0).get("id").asText();
        var two=create(id,change(id,1,"note",null));String second=two.get("items").get(1).get("id").asText();
        var move=change(id,2,"note",null);move.put("x",80);
        mvc.perform(put(base(id)+"/"+first).contentType("application/json").content(json.writeValueAsString(move)).with(actor(owner)).with(csrf())).andExpect(status().isOk());
        var undo=history(id,3,false);assertThat(undo.get("items").get(0).get("id").asText()).isEqualTo(first);assertThat(undo.get("items").get(1).get("id").asText()).isEqualTo(second);
        var redo=history(id,4,true);assertThat(redo.get("items").get(0).get("id").asText()).isEqualTo(second);assertThat(redo.get("items").get(1).get("id").asText()).isEqualTo(first);
    }
    @Test void sharedEditorCanRestorePublishedStampWithoutAccessToPrivateLibrary() throws Exception {
        String id=media();var stamp=stamp(id);var original=create(id,change(id,0,"stamp",stamp.get("stampId").asText()));String item=original.get("items").get(0).get("id").asText();
        SHARED.set(true);SHARED_EDIT.set(true);var bob=new ViewerPrincipal("tenant","bob");
        mvc.perform(post("/api/v1/media/"+id+"/previews").with(actor(bob)).with(csrf())).andExpect(status().isAccepted());
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(System.nanoTime()<end){var page=body(mvc.perform(get("/api/v1/media/"+id+"/pages/1").with(actor(bob))).andReturn());if(page.get("state").asText().equals("ready"))break;Thread.sleep(10);}
        mvc.perform(delete(base(id)+"/"+item).contentType("application/json").content(json.writeValueAsString(historyBody(id,1))).with(actor(bob)).with(csrf())).andExpect(status().isOk());
        var restored=body(mvc.perform(post(base(id)+"/history/undo").contentType("application/json").content(json.writeValueAsString(historyBody(id,2))).with(actor(bob)).with(csrf()))
                .andExpect(status().isOk()).andReturn());
        assertThat(restored.get("items")).isEqualTo(original.get("items"));assertThat(restored.toString()).doesNotContain("stampOwner",owner.userId());
        mvc.perform(get(restored.get("items").get(0).get("stampUrl").asText()).with(actor(bob))).andExpect(status().isOk());
        mvc.perform(get(stamp.get("contentUrl").asText()).with(actor(bob))).andExpect(status().isNotFound());
    }
}
