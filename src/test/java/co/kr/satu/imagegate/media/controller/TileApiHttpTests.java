package co.kr.satu.imagegate.media.controller;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.TilePyramidLayout;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.service.*;
import co.kr.satu.imagegate.conversion.service.img2tile.*;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.*;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP/security/service/queue/cache. Only native raster generation and trusted identity are fixtures. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.config.import=optional:classpath:/tile-http-no-installation.yml","server.address=127.0.0.1","server.ssl.enabled=false",
    "imagegate.conversion.enabled=true","imagegate.tiles.enabled=true","imagegate.tiles.workers=1","imagegate.tiles.queue-capacity=1",
    "imagegate.tiles.max-tracked-jobs=64","imagegate.tiles.max-page-bytes=1048576","imagegate.tiles.max-source-pixels=1000000",
    "imagegate.tiles.maintenance.enabled=false","imagegate.tiles.min-free-bytes=0","imagegate.tiles.timeout=30s",
    "imagegate.preview.enabled=false","imagegate.preview.cache.enabled=false","imagegate.playback.enabled=false","imagegate.playback.cache.enabled=false",
    "imagegate.upload.min-free-bytes=0","imagegate.upload.tenant-original-max-bytes=0","imagegate.audit.enabled=false"})
@Import(TileApiHttpTests.Fixture.class) @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class TileApiHttpTests {
    static final Path ROOT;
    static{try{ROOT=Files.createTempDirectory("imagegate-tile-http-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    static final String TOKEN=UUID.randomUUID().toString();static final ViewerPrincipal OWNER=new ViewerPrincipal("tenant","owner");
    static final AtomicBoolean ALLOWED=new AtomicBoolean(true);
    @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("imagegate.storage.root",()->ROOT.resolve("data").toString());p.add("imagegate.upload.multipart-directory",()->ROOT.resolve("spool").toString());}
    // Prevent native installation access in ordinary HTTP tests; real module integration runs separately.
    @MockitoBean(name="imageConverter") Img2TileConverter unusedPreviewConverter;
    static final class VersionStore implements MediaStore,AutoCloseable {
        final LocalMediaStore delegate;final Map<String,String> versions=new ConcurrentHashMap<>();
        VersionStore(Path path){delegate=new LocalMediaStore(path,104857600);}
        public MediaRecord register(String t,String u,String n,InputStream input)throws IOException{return delegate.register(t,u,n,input);}
        public Path resolveOriginal(MediaRecord media)throws IOException{return delegate.resolveOriginal(media);}
        public Optional<MediaRecord> find(String tenant,String id)throws IOException{return delegate.find(tenant,id).map(m->new MediaRecord(m.mediaId(),m.tenantId(),m.ownerId(),versions.getOrDefault(id,m.mediaVersion()),m.fileName(),m.mediaType(),m.mimeType(),m.sizeBytes(),m.sha256(),m.storedFileName(),m.createdAt()));}
        public void close()throws IOException{delegate.close();}
    }
    static final class Control implements TileNativeConverter {
        final AtomicInteger calls=new AtomicInteger();volatile String mode="ok";volatile CountDownLatch entered=new CountDownLatch(0),release=new CountDownLatch(0);
        void block(){mode="block";entered=new CountDownLatch(1);release=new CountDownLatch(1);}
        void reset(){release.countDown();mode="ok";}
        public String tileIdentity(){return Img2TilePyramidValidator.MODULE;}
        public Path generateTiles(Path input,int page,Path work)throws IOException{
            calls.incrementAndGet();String selected=mode;
            if(selected.equals("block")){entered.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new IOException("fixture deadline");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException();}}
            if(selected.equals("fail"))throw new IOException("private-native-detail");
            if(selected.equals("busy"))throw new co.kr.satu.imagegate.conversion.exception.ConversionException(co.kr.satu.imagegate.conversion.exception.ConversionException.Code.BUSY,"private-native-detail");
            var original=ImageIO.read(input.toFile());int width,height,color;
            try{width=original.getWidth();height=original.getHeight();color=original.getRGB(0,0);}finally{original.flush();}
            Files.createDirectory(work);Path output=Files.createDirectory(work.resolve("output"));var layout=new TilePyramidLayout(width,height,256,0);
            for(int z=0;z<=layout.maxLevel();z++){
                Path directory=Files.createDirectories(output.resolve("mipmap_files/"+z));var grid=layout.level(z);
                for(int c=0;c<grid.columns();c++)for(int r=0;r<grid.rows();r++){
                    var bounds=layout.tile(z,c,r).stored();var image=new BufferedImage(bounds.width(),bounds.height(),BufferedImage.TYPE_INT_RGB);
                    try{for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,color);ImageIO.write(image,"PNG",directory.resolve(c+"_"+r+".png").toFile());}finally{image.flush();}
                }
            }
            Files.writeString(output.resolve("mipmap.dzi"),"<Image xmlns=\"http://schemas.microsoft.com/deepzoom/2008\" Format=\"png\" Overlap=\"1\" TileSize=\"256\"><Size Width=\""+width+"\" Height=\""+height+"\"/></Image>");return output;
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Fixture {
        @Bean @Primary VersionStore versionStore(ImageGateProperties p){return new VersionStore(p.storage().originalsPath());}
        @Bean @Primary Control fixtureNative(){return new Control();}
        @Bean @Primary MediaAuthorizationPolicy fixturePolicy(){return (a,m,action)->ALLOWED.get()&&a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId());}
        @Bean static BeanPostProcessor identity(){return new BeanPostProcessor(){public Object postProcessAfterInitialization(Object bean,String name){
            if(name.equals("securityFilterChain")&&bean instanceof DefaultSecurityFilterChain chain){
                int position=-1;for(int i=0;i<chain.getFilters().size();i++)if(chain.getFilters().get(i) instanceof SecurityContextHolderFilter)position=i;
                if(position<0)throw new IllegalStateException("Security boundary missing");
                chain.getFilters().add(position+1,new OncePerRequestFilter(){protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain next)throws ServletException,IOException{
                    String token=request.getHeader("Authorization");ViewerPrincipal actor=null;
                    if(("Bearer "+TOKEN).equals(token))actor=OWNER;else if(("Bearer "+TOKEN+"-bob").equals(token))actor=new ViewerPrincipal("tenant","bob");else if(("Bearer "+TOKEN+"-other").equals(token))actor=new ViewerPrincipal("other","owner");
                    if(actor!=null)SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,"fixture",List.of()));next.doFilter(request,response);
                }});
            }return bean;
        }};}
    }
    @LocalServerPort int port;
    @Autowired VersionStore store;@Autowired Control control;@Autowired TileQueue queue;@Autowired TileCache cache;@Autowired TileJobStore states;
    final JsonMapper json=JsonMapper.builder().build();HttpClient client;String csrfHeader,csrfToken;
    HttpRequest.Builder request(String path,String identity){var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));if(identity!=null)r.header("Authorization","Bearer "+TOKEN+identity);return r;}
    HttpResponse<String> send(String path,String identity,String method,boolean csrf)throws Exception{var r=request(path,identity);if(csrf)r.header(csrfHeader,csrfToken);return client.send(r.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> get(String path)throws Exception{return send(path,"","GET",false);}
    JsonNode body(HttpResponse<String> response){return json.readTree(response.body());}
    void privateResponse(HttpResponse<?> response,int status){assertThat(response.statusCode()).isEqualTo(status);assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");assertThat(response.headers().firstValue("X-Content-Type-Options").orElse("")).isEqualTo("nosniff");
        if(response.body() instanceof String text)assertThat(text).doesNotContain(ROOT.toString(),"private-native-detail","sourceManifestSha256","moduleIdentity","originals/","originals\\");}
    String base(MediaRecord media){return "/api/v1/media/"+media.mediaId()+"/pages/1";}
    MediaRecord register(String format)throws Exception{
        var output=new ByteArrayOutputStream();var image=new BufferedImage(257,65,BufferedImage.TYPE_INT_RGB);
        try{for(int y=0;y<65;y++)for(int x=0;x<257;x++)image.setRGB(x,y,0xff123456);ImageIO.write(image,format,output);}finally{image.flush();}
        return store.register("tenant","owner","private-file."+format,new ByteArrayInputStream(output.toByteArray()));
    }
    JsonNode await(MediaRecord media,String expected)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);do{var response=get(base(media)+"/pyramid");privateResponse(response,200);var node=body(response);if(node.get("state").asText().equals(expected))return node;Thread.sleep(20);}while(System.nanoTime()<end);throw new AssertionError("Expected "+expected);}
    JsonNode ready(MediaRecord media)throws Exception{privateResponse(send(base(media)+"/pyramids","","POST",true),202);return await(media,"ready");}
    String tile(MediaRecord media,JsonNode ready){var p=ready.get("pyramid");return p.get("tileUrlTemplate").asText().replace("{level}",p.get("maxLevel").asText()).replace("{column}","0").replace("{row}","0");}
    @BeforeEach void connect()throws Exception{ALLOWED.set(true);control.reset();store.versions.clear();client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER).cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ORIGINAL_SERVER)).proxy(new ProxySelector(){public List<Proxy> select(URI u){return List.of(Proxy.NO_PROXY);}public void connectFailed(URI u,SocketAddress a,IOException e){}}).build();var bootstrap=get("/api/v1/viewer/bootstrap");privateResponse(bootstrap,200);var csrf=body(bootstrap).get("csrf");csrfHeader=csrf.get("headerName").asText();csrfToken=csrf.get("token").asText();}
    @AfterEach void reset()throws Exception{control.reset();ALLOWED.set(true);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end&&states.snapshots().stream().anyMatch(s->Set.of("queued","running").contains(s.state().state())))Thread.sleep(20);if(client!=null)client.close();}
    @Test void readsNeverGenerateAndPreparationRequiresAuthenticationCsrfAndOwner()throws Exception{
        var media=register("TIFF");int calls=control.calls.get();String status=base(media)+"/pyramid";
        privateResponse(send(status,null,"GET",false),401);privateResponse(send(status,"-bob","GET",false),404);privateResponse(send(status,"-other","GET",false),404);
        privateResponse(send(base(media)+"/pyramids","","POST",false),403);
        privateResponse(send(base(media)+"/pyramids","-bob","POST",true),404);
        assertThat(body(get(status)).get("state").asText()).isEqualTo("not_requested");
        privateResponse(get(base(media)+"/pyramids/"+UUID.randomUUID()+"/tiles/0/0/0"),404);assertThat(control.calls).hasValue(calls);
    }
    @Test void servesNormalizedManifestPngAndHeadWithoutAdditionalGeneration()throws Exception{
        var media=register("TIFF");var ready=ready(media);var p=ready.get("pyramid");
        assertThat(p.get("rasterWidth").asInt()).isEqualTo(257);assertThat(p.get("tileOverlap").asInt()).isZero();assertThat(p.get("coordinateSpaceId").asText()).isEqualTo("original:"+media.mediaVersion()+":1:upright-v1");
        assertThat(ready.toString()).doesNotContain(queue.key(media,OWNER,1),media.sha256(),media.fileName(),"profile","totalBytes");int calls=control.calls.get();String path=tile(media,ready);
        var png=client.send(request(path,"").GET().build(),HttpResponse.BodyHandlers.ofByteArray());privateResponse(png,200);assertThat(png.headers().firstValue("Content-Type")).contains("image/png");assertThat(png.headers().firstValue("Content-Length").orElseThrow()).isEqualTo(""+png.body().length);
        var image=ImageIO.read(new ByteArrayInputStream(png.body()));try{assertThat(image.getWidth()).isEqualTo(256);assertThat(image.getRGB(0,0)).isEqualTo(0xff123456);}finally{image.flush();}
        var head=client.send(request(path,"").method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());privateResponse(head,200);assertThat(head.body()).isEmpty();assertThat(head.headers().firstValue("Content-Length")).isEqualTo(png.headers().firstValue("Content-Length"));
        var conditional=client.send(request(path,"").header("If-None-Match","*").GET().build(),HttpResponse.BodyHandlers.ofByteArray());privateResponse(conditional,200);
        privateResponse(send(base(media)+"/pyramids","","POST",true),200);assertThat(control.calls).hasValue(calls);
    }
    @Test void hidesOtherActorsRevokedPermissionStaleGenerationAndWrongCoordinates()throws Exception{
        var media=register("TIFF");var ready=ready(media);String path=tile(media,ready);int calls=control.calls.get();
        privateResponse(send(path,"-bob","GET",false),404);privateResponse(send(path,"-other","HEAD",false),404);
        privateResponse(get(path.replace(ready.get("pyramid").get("tileSetId").asText(),UUID.randomUUID().toString())),404);
        var other=register("TIFF");var otherReady=ready(other);
        privateResponse(get(tile(other,otherReady).replace(otherReady.get("pyramid").get("tileSetId").asText(),ready.get("pyramid").get("tileSetId").asText())),404);
        calls=control.calls.get();
        privateResponse(get(path.substring(0,path.lastIndexOf('/'))+"/100"),404);privateResponse(get(path.replace("/tiles/9/","/tiles/99/")),404);
        ALLOWED.set(false);privateResponse(get(path),404);privateResponse(get(base(media)+"/pyramid"),404);ALLOWED.set(true);
        store.versions.put(media.mediaId(),"new-version");privateResponse(get(path),404);assertThat(body(get(base(media)+"/pyramid")).get("state").asText()).isEqualTo("not_requested");assertThat(control.calls).hasValue(calls);
    }
    @Test void queueSaturationDoesNotCreateJobsAndFailureRequiresExplicitRetry()throws Exception{
        var first=register("TIFF");var second=register("TIFF");var third=register("TIFF");control.block();
        privateResponse(send(base(first)+"/pyramids","","POST",true),202);assertThat(control.entered.await(10,TimeUnit.SECONDS)).isTrue();privateResponse(send(base(second)+"/pyramids","","POST",true),202);
        var rejected=send(base(third)+"/pyramids","","POST",true);privateResponse(rejected,429);assertThat(body(rejected).get("code").asText()).isEqualTo("TILE_QUEUE_FULL");assertThat(body(get(base(third)+"/pyramid")).get("state").asText()).isEqualTo("not_requested");
        control.reset();await(first,"ready");await(second,"ready");control.mode="fail";
        privateResponse(send(base(third)+"/pyramids","","POST",true),202);assertThat(await(third,"failed").get("errorCode").asText()).isEqualTo("TILE_FAILED");int calls=control.calls.get();
        privateResponse(send(base(third)+"/pyramids","","POST",true),200);privateResponse(get(base(third)+"/pyramid"),200);assertThat(control.calls).hasValue(calls);
        control.reset();privateResponse(send(base(third)+"/pyramids?retry=true","","POST",true),202);await(third,"ready");assertThat(control.calls).hasValue(calls+1);
    }
    @Test void acceptedNativeBusyIsNotQueue429AndRetryStillRequiresCurrentAccessAndCsrf()throws Exception{
        var media=register("TIFF");control.mode="busy";int calls=control.calls.get();
        privateResponse(send(base(media)+"/pyramids","","POST",true),202);var failed=await(media,"failed");assertThat(failed.get("errorCode").asText()).isEqualTo("CONVERSION_BUSY");assertThat(failed.get("pyramid").isNull()).isTrue();
        for(int i=0;i<3;i++){privateResponse(get(base(media)+"/pyramid"),200);privateResponse(send(base(media)+"/pyramids","","POST",true),200);}assertThat(control.calls).hasValue(calls+1);
        privateResponse(send(base(media)+"/pyramids?retry=true","","POST",false),403);privateResponse(send(base(media)+"/pyramids?retry=true","-bob","POST",true),404);
        ALLOWED.set(false);privateResponse(send(base(media)+"/pyramids?retry=true","","POST",true),404);assertThat(control.calls).hasValue(calls+1);ALLOWED.set(true);
        control.reset();privateResponse(send(base(media)+"/pyramids?retry=true","","POST",true),202);await(media,"ready");assertThat(control.calls).hasValue(calls+2);
    }
    @Test void rejectsRangeMalformedInputsAndUnsupportedMimeWithoutPreparation()throws Exception{
        var media=register("TIFF");var ready=ready(media);String path=tile(media,ready);int calls=control.calls.get();
        var range=client.send(request(path,"").header("Range","bytes=0-1").GET().build(),HttpResponse.BodyHandlers.ofString());privateResponse(range,400);assertThat(body(range).get("code").asText()).isEqualTo("INVALID_REQUEST");
        privateResponse(send(base(media)+"/pyramids?retry=private-malformed","","POST",true),400);privateResponse(get(base(media).replace("/pages/1","/pages/0")+"/pyramid"),400);
        privateResponse(get(path.replace("/tiles/9/","/tiles/not-an-int/")),400);
        var png=register("PNG");assertThat(body(send(base(png)+"/pyramids","","POST",true)).get("state").asText()).isEqualTo("unsupported");assertThat(control.calls).hasValue(calls);
    }
    @Test void evictedCorruptAndPrunedHistoryResultsFailClosedWithoutRegeneration()throws Exception{
        var media=register("TIFF");var ready=ready(media);String path=tile(media,ready),key=queue.key(media,OWNER,1);int calls=control.calls.get();
        var snapshot=states.snapshots().stream().filter(s->s.state().key().equals(key)).findFirst().orElseThrow();assertThat(states.remove(snapshot)).isTrue();assertThat(body(get(base(media)+"/pyramid")).get("state").asText()).isEqualTo("ready");
        Path file=ROOT.resolve("data/derived/tiles-v1/"+key+"/9_0_0.png");byte[] bytes=Files.readAllBytes(file);bytes[30]^=1;Files.write(file,bytes);
        var corrupt=get(path);privateResponse(corrupt,503);assertThat(body(corrupt).get("code").asText()).isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(cache.evict(key)).isTrue();privateResponse(get(path),404);assertThat(body(get(base(media)+"/pyramid")).get("state").asText()).isEqualTo("not_requested");assertThat(control.calls).hasValue(calls);
    }
    @Test void revocationDuringPreparationPreventsPublicationUntilAuthorizedRetry()throws Exception{
        var media=register("TIFF");String key=queue.key(media,OWNER,1);control.block();privateResponse(send(base(media)+"/pyramids","","POST",true),202);
        assertThat(control.entered.await(10,TimeUnit.SECONDS)).isTrue();ALLOWED.set(false);control.reset();privateResponse(get(base(media)+"/pyramid"),404);
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end&&!states.read(key,1).state().equals("failed"))Thread.sleep(20);
        assertThat(states.read(key,1).state()).isEqualTo("failed");assertThat(cache.find(key)).isEmpty();int calls=control.calls.get();ALLOWED.set(true);
        assertThat(body(get(base(media)+"/pyramid")).get("state").asText()).isEqualTo("failed");privateResponse(send(base(media)+"/pyramids","","POST",true),200);assertThat(control.calls).hasValue(calls);
        privateResponse(send(base(media)+"/pyramids?retry=true","","POST",true),202);await(media,"ready");assertThat(control.calls).hasValue(calls+1);
    }
}
