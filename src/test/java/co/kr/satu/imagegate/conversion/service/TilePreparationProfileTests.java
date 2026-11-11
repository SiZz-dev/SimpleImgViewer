package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.service.img2tile.*;
import co.kr.satu.imagegate.conversion.util.*;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.*;
import tools.jackson.databind.json.JsonMapper;

@Tag("tile-profile") @EnabledOnOs(OS.WINDOWS)
class TilePreparationProfileTests {
    static final class Timing implements TileTiming {
        final EnumMap<Stage,Long> nanos=new EnumMap<>(Stage.class),counts=new EnumMap<>(Stage.class);
        boolean recording;
        public void completed(Stage stage,long elapsed){if(recording){nanos.merge(stage,elapsed,Long::sum);counts.merge(stage,1L,Long::sum);}}
        Map<String,Object> result(){var result=new LinkedHashMap<String,Object>();for(var s:Stage.values())if(nanos.containsKey(s))result.put(s.name(),Map.of("ms",nanos.get(s)/1e6,"calls",counts.get(s)));return result;}
    }
    Path fixture(Path root)throws Exception{
        var image=new BufferedImage(6000,4000,BufferedImage.TYPE_INT_RGB);Path output=root.resolve("synthetic.tif");
        try{var row=new int[6000];for(int y=0;y<4000;y++){for(int x=0;x<6000;x++)row[x]=((x/64+y/64)%2==0)?0xffbedbff:0xffdbeafe;image.setRGB(0,y,6000,1,row,0,6000);}assertThat(ImageIO.write(image,"TIFF",output.toFile())).isTrue();}finally{image.flush();}return output;
    }
    @Test @Timeout(value=8,unit=TimeUnit.MINUTES) void realNativePreparationStagesAndExactPublishedPixels()throws Exception{
        int rounds=Integer.parseInt(System.getProperty("imagegate.tileProfile.rounds","3"));assertThat(rounds).isBetween(1,5);
        String label=System.getProperty("imagegate.tileProfile.label","baseline");assertThat(label).isIn("baseline","optimized","io-detail","png-before","png-after");
        Path parent=Files.createDirectories(Path.of("build/tile-profile-work").toAbsolutePath()),root=Files.createTempDirectory(parent,"run-"),converted=Files.createDirectories(root.resolve("derived"));
        var features=mock(FeaturePolicy.class);when(features.isEnabled(FeaturePolicy.Feature.IMAGE_VIEW)).thenReturn(true);
        var actor=new ViewerPrincipal("synthetic","owner");var policy=ImageGateProperties.Tiles.defaults();var rows=new ArrayList<Map<String,Object>>();boolean passed=false;
        try(var store=new LocalMediaStore(root.resolve("originals"),104857600)){
            Path input=fixture(root);var converter=new Img2TileConverter(Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe")),root.resolve("originals"),converted,Duration.ofSeconds(120),new BoundedProcessExecutor(1));
            String reference=null;
            for(int round=0;round<rounds;round++){
                var timing=new Timing();var work=new TileWorkStore(converted,4096);try(var cache=new TileCache(converted,timing)){
                    var publisher=new TilePyramidPublisher(new Img2TilePyramidValidator(new Img2TilePyramidValidator.Limits(40000000,4096,2097152,268435456),timing),cache,timing);
                    var renderer=new NativeTileRenderer(store,(a,m,action)->a.tenantId().equals(m.tenantId())&&a.userId().equals(m.ownerId()),features,converter,publisher,policy,converted,work,timing);
                    co.kr.satu.imagegate.media.domain.MediaRecord media;try(var stream=Files.newInputStream(input)){media=store.register("synthetic","owner","synthetic.tif",stream);}
                    String key=TileKeys.key(media,actor,1,policy,converter.tileIdentity());long started=System.nanoTime();
                    timing.recording=true;try{renderer.render(media,actor,1,key,action->action.run());}finally{timing.recording=false;}
                    double total=(System.nanoTime()-started)/1e6;
                    var manifest=cache.manifest(key);assertThat(manifest.layout()).isEqualTo(new TilePyramidLayout(6000,4000,256,0));
                    assertThat(timing.counts.get(TileTiming.Stage.CACHE_FORCE)).isEqualTo(manifest.tiles().size()+1L);
                    assertThat(timing.counts.get(TileTiming.Stage.CACHE_CREATE_NEW)).isEqualTo(manifest.tiles().size()+1L);
                    assertThat(timing.counts.get(TileTiming.Stage.CACHE_WRITE_CLOSE)).isEqualTo(manifest.tiles().size()+1L);
                    assertThat(timing.counts.get(TileTiming.Stage.NATIVE_INVENTORY)).isEqualTo(5L);
                    var measured=timing.result();
                    var digest=PreviewKeys.digest();long[] pixels={0};
                    for(var tile:manifest.tiles())cache.withTile(key,manifest.generation(),1,tile.level(),tile.column(),tile.row(),(stream,metadata)->{
                        var image=ImageIO.read(stream);try{var buffer=ByteBuffer.allocate(image.getWidth()*image.getHeight()*4);for(int value:image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()))buffer.putInt(value);digest.update(buffer.array());pixels[0]+=(long)image.getWidth()*image.getHeight();}finally{image.flush();}
                    });
                    String hash=HexFormat.of().formatHex(digest.digest());if(reference==null)reference=hash;assertThat(hash).isEqualTo(reference);
                    assertThat(timing.result()).as("Post-render pixel checks must not contaminate render timings").isEqualTo(measured);
                    try(var stream=Files.newInputStream(store.resolveOriginal(media))){var sha=PreviewKeys.digest();byte[] block=new byte[32768];int n;while((n=stream.read(block))!=-1)sha.update(block,0,n);assertThat(HexFormat.of().formatHex(sha.digest())).isEqualTo(media.sha256());}
                    rows.add(Map.of("round",round+1,"totalMs",total,"stages",timing.result(),"tiles",manifest.tiles().size(),"bytes",manifest.totalBytes(),"decodedPixels",pixels[0],"pixelSha256",hash));
                    System.out.printf(Locale.ROOT,"TILE PROFILE %s round=%d total=%.2fms stages=%s%n",label,round+1,total,timing.result());
                }
            }passed=true;
        }finally{
            Path report=Files.createDirectories(Path.of("build/reports/tile-profile"));new JsonMapper().writerWithDefaultPrettyPrinter().writeValue(report.resolve(label+".json").toFile(),Map.of(
                "passed",passed,"label",label,"sourceWidth",6000,"sourceHeight",4000,"rounds",rows,"java",System.getProperty("java.version"),"scope","Direct real renderer, synthetic TIFF; no queue/HTTP/browser; OS cache retained; no overlapping load; COMMIT includes final original/module/access checks; diagnostics and constructor/registration/pixel verification excluded from total where outside render"));
        }
    }
}
