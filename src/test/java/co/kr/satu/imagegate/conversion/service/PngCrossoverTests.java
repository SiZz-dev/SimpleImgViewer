package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.service.img2tile.*;
import co.kr.satu.imagegate.conversion.util.*;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import tools.jackson.databind.json.JsonMapper;

/** Full real native pipeline in one JVM. Fresh caches prevent normalization being skipped. */
@Tag("png-crossover") @Tag("img2tile") @EnabledOnOs(OS.WINDOWS)
class PngCrossoverTests {
    enum Mode { REFERENCE, OWNED }
    record Artifact(Path converted,String key,String generation,String expectedEncodedHash){}
    final List<Map<String,Object>> samples=new ArrayList<>();final List<Artifact> artifacts=new ArrayList<>();
    final ViewerPrincipal actor=new ViewerPrincipal("synthetic","owner");final ImageGateProperties.Tiles policy=ImageGateProperties.Tiles.defaults();
    com.sun.management.ThreadMXBean threads;String expectedEncodedHash,expectedPixelHash;int slot;
    static long gcCount(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g->Math.max(0,g.getCollectionCount())).sum();}
    static long gcTime(){return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(g->Math.max(0,g.getCollectionTime())).sum();}
    String encodedHash(co.kr.satu.imagegate.conversion.domain.TileCacheManifest manifest){
        var sha=PreviewKeys.digest();for(var tile:manifest.tiles())sha.update(tile.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));return HexFormat.of().formatHex(sha.digest());
    }
    void run(Path base,LocalMediaStore store,MediaRecord media,Img2TileConverter converter,FeaturePolicy features,Mode mode,int pair,int position,boolean warm)throws Exception{
        Path converted=Files.createDirectory(base.resolve(String.format(Locale.ROOT,"%02d",slot++)));
        try(var cache=new TileCache(converted)){
            var validator=new Img2TilePyramidValidator();TilePyramidPublisher.Normalizer normalizer=mode==Mode.REFERENCE?ReferenceTilePngNormalizer::normalize:TilePngSanitizer::normalize;
            var publisher=new TilePyramidPublisher(validator,cache,null,normalizer);
            var renderer=new NativeTileRenderer(store,(a,m,action)->a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId()),features,converter,publisher,policy,converted);
            String key=TileKeys.key(media,actor,1,policy,converter.tileIdentity());long id=Thread.currentThread().threadId();
            long gcBefore=gcCount(),gcTimeBefore=gcTime();long cpuBefore=threads.isThreadCpuTimeEnabled()?threads.getCurrentThreadCpuTime():-1;
            long allocatedBefore=threads.getThreadAllocatedBytes(id),start=System.nanoTime();renderer.render(media,actor,1,key,action->action.run());long elapsed=System.nanoTime()-start;
            long allocatedAfter=threads.getThreadAllocatedBytes(id),cpuAfter=threads.isThreadCpuTimeEnabled()?threads.getCurrentThreadCpuTime():-1;
            long gcAfter=gcCount(),gcTimeAfter=gcTime();
            assertThat(allocatedBefore).isNotNegative();assertThat(allocatedAfter).isGreaterThanOrEqualTo(allocatedBefore);
            var manifest=cache.manifest(key);assertThat(manifest.tiles()).hasSize(521);assertThat(manifest.layout().width()).isEqualTo(6000);assertThat(manifest.layout().height()).isEqualTo(4000);
            String hash=encodedHash(manifest);if(expectedEncodedHash==null)expectedEncodedHash=hash;assertThat(hash).isEqualTo(expectedEncodedHash);
            artifacts.add(new Artifact(converted,key,manifest.generation(),hash));
            if(!warm){var row=new LinkedHashMap<String,Object>();row.put("pair",pair);row.put("position",position);row.put("mode",mode.name());row.put("elapsedMs",elapsed/1e6);
                row.put("renderThreadAllocatedBytes",allocatedAfter-allocatedBefore);row.put("renderThreadCpuMs",cpuBefore<0||cpuAfter<0?-1:(cpuAfter-cpuBefore)/1e6);
                row.put("gcCountDelta",gcAfter-gcBefore);row.put("gcTimeMsDelta",gcTimeAfter-gcTimeBefore);row.put("tiles",manifest.tiles().size());row.put("outputBytes",manifest.totalBytes());row.put("encodedTileHashesSha256",hash);samples.add(row);
                System.out.printf(Locale.ROOT,"PNG CROSSOVER pair=%d position=%d mode=%s elapsed=%.2fms allocated=%.2fMiB gc=%d/%dms%n",pair,position,mode,elapsed/1e6,(allocatedAfter-allocatedBefore)/1048576.0,gcAfter-gcBefore,gcTimeAfter-gcTimeBefore);
            }
        }
    }
    void verifyAllPixels()throws Exception{
        for(var artifact:artifacts)try(var cache=new TileCache(artifact.converted())){
            var manifest=cache.manifest(artifact.key());assertThat(encodedHash(manifest)).isEqualTo(artifact.expectedEncodedHash());var pixels=PreviewKeys.digest();long[] count={0};
            for(var tile:manifest.tiles())cache.withTile(artifact.key(),artifact.generation(),1,tile.level(),tile.column(),tile.row(),(input,metadata)->{
                var image=ImageIO.read(input);try{int[] rgb=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());var bytes=ByteBuffer.allocate(rgb.length*4);for(int value:rgb)bytes.putInt(value);pixels.update(bytes.array());count[0]+=rgb.length;}finally{image.flush();}
            });
            assertThat(count[0]).isEqualTo(32000189);String hash=HexFormat.of().formatHex(pixels.digest());if(expectedPixelHash==null)expectedPixelHash=hash;assertThat(hash).isEqualTo(expectedPixelHash);
        }
    }
    @Test @Timeout(value=10,unit=TimeUnit.MINUTES) void alternateFullNativePreparationWithThreadAllocationAndGc()throws Exception{
        int pairs=Integer.parseInt(System.getProperty("imagegate.pngCrossover.pairs","4"));assertThat(pairs).isBetween(2,6);assertThat(pairs%2).as("Balanced reference-first and owned-first pairs").isZero();
        var bean=ManagementFactory.getThreadMXBean();assertThat(bean).isInstanceOf(com.sun.management.ThreadMXBean.class);threads=(com.sun.management.ThreadMXBean)bean;
        assertThat(threads.isThreadAllocatedMemorySupported()).as("This experiment requires real allocation counters").isTrue();if(!threads.isThreadAllocatedMemoryEnabled())threads.setThreadAllocatedMemoryEnabled(true);
        if(threads.isThreadCpuTimeSupported()&&!threads.isThreadCpuTimeEnabled())threads.setThreadCpuTimeEnabled(true);
        Path root=Files.createTempDirectory(Files.createDirectories(Path.of("build/png-crossover-work").toAbsolutePath()),"run-"),base=Files.createDirectory(root.resolve("derived"));
        boolean passed=false;try(var store=new LocalMediaStore(root.resolve("originals"),104857600)){
            Path input=new TilePreparationProfileTests().fixture(root);MediaRecord media;try(var bytes=Files.newInputStream(input)){media=store.register("synthetic","owner","synthetic.tif",bytes);}
            var converter=new Img2TileConverter(Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe")),root.resolve("originals"),base,Duration.ofSeconds(120),new BoundedProcessExecutor(1));
            var features=mock(FeaturePolicy.class);when(features.isEnabled(FeaturePolicy.Feature.IMAGE_VIEW)).thenReturn(true);
            run(base,store,media,converter,features,Mode.REFERENCE,0,0,true);run(base,store,media,converter,features,Mode.OWNED,0,1,true);
            for(int pair=1;pair<=pairs;pair++){var order=pair%2==1?List.of(Mode.REFERENCE,Mode.OWNED):List.of(Mode.OWNED,Mode.REFERENCE);
                for(int position=0;position<2;position++)run(base,store,media,converter,features,order.get(position),pair,position,false);
            }
            // Defer large independent decode allocations until every measured interval has finished.
            verifyAllPixels();var sha=PreviewKeys.digest();try(var bytes=Files.newInputStream(store.resolveOriginal(media))){byte[] block=new byte[32768];int n;while((n=bytes.read(block))!=-1)sha.update(block,0,n);}
            assertThat(HexFormat.of().formatHex(sha.digest())).isEqualTo(media.sha256());passed=true;
        }finally{
            var report=new LinkedHashMap<String,Object>();report.put("passed",passed);report.put("generatedAt",Instant.now().toString());report.put("java",System.getProperty("java.version"));report.put("maxHeapBytes",Runtime.getRuntime().maxMemory());report.put("pairs",pairs);report.put("warmups",2);report.put("samples",samples);
            report.put("allArtifactsVerified",passed);report.put("encodedTileHashesSha256",expectedEncodedHash);report.put("pixelSha256",expectedPixelHash);
            report.put("scope","Same JVM/original/version/converter, alternating pair order, fresh cache per run, no explicit GC or diagnostics. Render includes native conversion, all validation/normalization/force/publication/cleanup. Constructor/registration/warmup and final independent pixel/SHA checks excluded.");
            report.put("allocationMeaning","Measured render-thread Java allocation includes renderer/file/image/serializer work; excludes native allocations and other threads, not RSS or peak live heap. GC counters cover the JVM and cannot be attributed exclusively to a mode. OS page cache, desktop load and temporal drift remain uncontrolled. No HTTP/SSO/browser timing.");
            Path directory=Files.createDirectories(Path.of("build/reports/png-crossover"));new JsonMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve("latest.json").toFile(),report);
        }
    }
}
