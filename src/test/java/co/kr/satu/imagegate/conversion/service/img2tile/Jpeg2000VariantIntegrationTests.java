package co.kr.satu.imagegate.conversion.service.img2tile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.service.ImageConverter;
import co.kr.satu.imagegate.conversion.service.ImagePreviewRenderer;
import co.kr.satu.imagegate.conversion.service.PreviewQueue;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.conversion.util.process.NativeModuleSnapshot;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("img2tile")
@Tag("ffmpeg")
@EnabledOnOs(OS.WINDOWS)
class Jpeg2000VariantIntegrationTests {
    enum Profile {
        RGB8(false,false,false), GRAY8(true,false,false), RGB16(false,true,false), GRAY16(true,true,false),
        RGB8_HALF(false,false,true), GRAY8_HALF(true,false,true),
        RGB16_HALF(false,true,true), GRAY16_HALF(true,true,true);
        final boolean gray, high, half;
        Profile(boolean gray,boolean high,boolean half){this.gray=gray;this.high=high;this.half=half;}
        String pixelFormat(){return gray?(high?"gray16le":"gray"):(high?"rgb48le":"rgb24");}
        int width(){return half?64:128;}
        int height(){return half?48:96;}
    }
    @TempDir Path root;
    final BoundedProcessExecutor processes = new BoundedProcessExecutor(1);

    @ParameterizedTest
    @EnumSource(Profile.class)
    void verifiesNativeJp2DepthColorAndHalfSizeThroughPublication(Profile profile) throws Exception {
        Path encoded = fixture(profile);
        String originalHash = digest(encoded);
        var store = new LocalMediaStore(root.resolve("originals"),1000000);
        var media = store.register("tenant","alice","variant.jp2",new ByteArrayInputStream(Files.readAllBytes(encoded)));
        Path executable = Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe"));
        var nativeCalls=new java.util.concurrent.atomic.AtomicInteger();
        var converter = new Img2TileConverter(executable,root,Duration.ofSeconds(30),(command,work,timeout)->{
            nativeCalls.incrementAndGet();return processes.execute(command,work,timeout);
        });
        String identity = converter.cacheIdentity();
        if(profile.gray) {
            observeGrayModule(profile,executable,store.resolveOriginal(media));
            assertThatThrownBy(()->converter.convert(new ImageConverter.ConversionRequest(store.resolveOriginal(media),
                    root.resolve("derived/direct"),1,profile.width(),profile.height(),ImageConverter.OutputFormat.PNG)))
                    .isInstanceOfSatisfying(ConversionException.class,e->assertThat(e.code()).isEqualTo(ConversionException.Code.UNSUPPORTED_PROFILE));
            assertThat(root.resolve("derived/direct")).doesNotExist();
            var preview=new Preview(true,1,2,profile.width(),profile.height(),100000,"jp2-variant-v1");
            var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenReturn(true);
            var actor=new ViewerPrincipal("tenant","alice");
            String legacyKey=null;
            if(profile==Profile.GRAY8) {
                String legacyIdentity=identity.substring(0,identity.lastIndexOf('/'));
                try(var oldQueue=new PreviewQueue(preview,legacyIdentity,new PreviewCache(root.resolve("derived/cache")),
                        (m,a,page,work)->{
                            Files.createDirectory(work);Path png=work.resolve("preview.png");
                            var image=new BufferedImage(128,96,BufferedImage.TYPE_INT_ARGB);
                            try {assertThat(ImageIO.write(image,"png",png.toFile())).isTrue();}
                            finally {image.flush();}
                            return new co.kr.satu.imagegate.conversion.service.PreviewRenderer.Result(png,128,96);
                        })) {
                    legacyKey=oldQueue.submit(media,actor,1,false).sourceId();
                    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                    while(!oldQueue.status(media,actor,1).ready()&&System.nanoTime()<deadline)Thread.sleep(10);
                    assertThat(oldQueue.status(media,actor,1).ready()).isTrue();
                }
            }
            try(var queue=new PreviewQueue(preview,identity,new PreviewCache(root.resolve("derived/cache")),
                    new ImagePreviewRenderer(store,(a,m,action)->true,features,preview,converter))) {
                if(legacyKey!=null){
                    assertThat(queue.status(media,actor,1).state()).isEqualTo("not_requested");
                    String oldKey=legacyKey;
                    assertThatThrownBy(()->queue.content(media,actor,1,oldKey,p->fail("Must not reuse legacy gray output")))
                            .isInstanceOf(co.kr.satu.imagegate.media.exception.MediaException.class);
                }
                queue.submit(media,actor,1,false);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                var status=queue.status(media,actor,1);
                while(!status.state().equals("failed")&&System.nanoTime()<deadline){Thread.sleep(20);status=queue.status(media,actor,1);}
                assertThat(status.errorCode()).isEqualTo("CONVERSION_UNSUPPORTED_PROFILE");
                assertThat(status.geometry()).isNull();assertThat(status.thumbnail()).isNull();
                String key=status.sourceId();
                assertThatThrownBy(()->queue.content(media,actor,1,key,p->fail("Must not publish gray JP2")))
                        .isInstanceOf(co.kr.satu.imagegate.media.exception.MediaException.class);
                assertThat(queue.submit(media,actor,1,false).state()).isEqualTo("failed");
            }
            assertThat(nativeCalls).hasValue(0);
            assertThat(digest(encoded)).isEqualTo(originalHash);
            assertThat(digest(store.resolveOriginal(media))).isEqualTo(originalHash);
            assertThat(converter.cacheIdentity()).isEqualTo(identity);
            return;
        }
        var direct = converter.convert(new ImageConverter.ConversionRequest(store.resolveOriginal(media),
                root.resolve("derived/direct"),1,profile.width(),profile.height(),ImageConverter.OutputFormat.PNG));
        var expected = ImageIO.read(direct.output().toFile());
        assertThat(expected).isNotNull();
        try {
            verifyRaster(expected,profile);
            var preview = new Preview(true,1,2,profile.width(),profile.height(),100000,"jp2-variant-v1");
            var features = mock(FeaturePolicy.class);
            when(features.isEnabled(any())).thenReturn(true);
            var actor = new ViewerPrincipal("tenant","alice");
            try(var queue = new PreviewQueue(preview,identity,new PreviewCache(root.resolve("derived/cache")),
                    new ImagePreviewRenderer(store,(a,m,action)->true,features,preview,converter))) {
                queue.submit(media,actor,1,false);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                var status=queue.status(media,actor,1);
                while(!status.ready()&&!status.state().equals("failed")&&System.nanoTime()<deadline){
                    Thread.sleep(20);status=queue.status(media,actor,1);
                }
                assertThat(status.ready()).as("%s: %s",profile,status).isTrue();
                assertThat(status.geometry()).isNull();
                queue.content(media,actor,1,status.sourceId(),path->{
                    var actual=ImageIO.read(path.toFile());
                    assertThat(actual).isNotNull();
                    try {
                        verifyRaster(actual,profile);
                        assertThat(actual.getRGB(0,0,profile.width(),profile.height(),null,0,profile.width()))
                                .containsExactly(expected.getRGB(0,0,profile.width(),profile.height(),null,0,profile.width()));
                    } finally {actual.flush();}
                });
                assertThat(queue.submit(media,actor,1,false).ready()).isTrue();
            }
        } finally {expected.flush();}
        assertThat(digest(encoded)).isEqualTo(originalHash);
        assertThat(digest(store.resolveOriginal(media))).isEqualTo(originalHash);
        assertThat(converter.cacheIdentity()).isEqualTo(identity);
    }

    private Path fixture(Profile profile) throws Exception {
        Path work=Files.createDirectory(root.resolve("generator"));
        var raw=new ByteArrayOutputStream();
        for(int y=0;y<96;y++)for(int x=0;x<128;x++) {
            int rgb=pixel(x/32,y/24,profile.gray);
            for(int component=0;component<(profile.gray?1:3);component++) {
                int value=(rgb>>(16-component*8))&255;
                if(profile.high){int value16=value*257;raw.write(value16&255);raw.write(value16>>>8);}
                else raw.write(value);
            }
        }
        Path input=work.resolve("source.raw"),encoded=work.resolve("source.jp2"),decoded=work.resolve("decoded.raw");
        Files.write(input,raw.toByteArray());
        Path ffmpeg=Path.of(System.getenv().getOrDefault("FFMPEG_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffmpeg.exe")).toRealPath();
        var module=NativeModuleSnapshot.capture(List.of(ffmpeg),false);
        module.verifyContent();
        var result=processes.execute(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-nostdin","-n",
                "-threads","1","-f","rawvideo","-pixel_format",profile.pixelFormat(),"-video_size","128x96","-i",input.toString(),
                "-frames:v","1","-c:v","libopenjpeg","-pix_fmt",profile.pixelFormat(),"-irreversible","0","-format","jp2",
                "-f","image2","-update","1",encoded.toString()),work,Duration.ofSeconds(30));
        module.verifyContent();
        assertThat(result.exitCode()).as("%s synthetic encode: %s",profile,result.output()).isZero();
        assertThat(Files.size(encoded)).isBetween(65L,1000000L);
        var reference=processes.execute(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-nostdin","-n",
                "-threads","1","-i",encoded.toString(),"-frames:v","1","-pix_fmt",profile.pixelFormat(),
                "-f","rawvideo",decoded.toString()),work,Duration.ofSeconds(30));
        module.verifyContent();
        assertThat(reference.exitCode()).as("%s synthetic decode: %s",profile,reference.output()).isZero();
        assertThat(Files.readAllBytes(decoded)).containsExactly(raw.toByteArray());
        return encoded;
    }

    private void verifyRaster(BufferedImage image,Profile profile){
        assertThat(image.getWidth()).isEqualTo(profile.width());
        assertThat(image.getHeight()).isEqualTo(profile.height());
        if(!profile.half) {
            for(int y=0;y<96;y++)for(int x=0;x<128;x++)
                assertThat(image.getRGB(x,y)).as("%s (%s,%s)",profile,x,y).isEqualTo(pixel(x/32,y/24,profile.gray));
        } else {
            // Known constant interiors; do not assume a particular interpolation filter at block boundaries.
            for(int by=0;by<4;by++)for(int bx=0;bx<4;bx++) {
                int x=(2*bx+1)*image.getWidth()/8,y=(2*by+1)*image.getHeight()/8;
                assertThat(image.getRGB(x,y)).as("%s block (%s,%s)",profile,bx,by).isEqualTo(pixel(bx,by,profile.gray));
            }
        }
    }
    private void observeGrayModule(Profile profile,Path executable,Path input)throws Exception {
        var module=NativeModuleSnapshot.capture(List.of(executable.toRealPath()),true);
        Path work=Files.createDirectory(root.resolve("derived/native-observation"));
        Path resources=Files.createDirectory(work.resolve("resource"));
        for(String name:List.of("cmyk.icc","libc9i.xml")){
            Path source=executable.toRealPath().getParent().resolve("resource").resolve(name);
            Path copied=Files.copy(source,resources.resolve(name));module.verifyCopiedResource(source,copied);
        }
        module.verifyContent();
        var result=processes.execute(List.of(executable.toRealPath().toString(),"-page","0","-odir",work.toString(),
                "-otype","png","-oscalesize",Integer.toString(profile.width()),"-oscalename","preview.png",
                "-oscaleonly","-f",input.toString()),work,Duration.ofSeconds(30));
        module.verifyContent();
        assertThat(result.exitCode()).isZero();
        var image=ImageIO.read(work.resolve("preview.png").toFile());
        try {
            int x=3*image.getWidth()/8,y=image.getHeight()/8;
            System.out.println(profile+" native gray sample="+image.getRaster().getSample(x,y,0)+" expected=17");
            assertThat(image.getRaster().getSample(x,y,0)).as("Known native gray mismatch").isNotEqualTo(17);
        } finally {image.flush();}
    }
    private static int pixel(int bx,int by,boolean gray){
        if(gray){int v=(bx+4*by)*17;return 0xff000000|v<<16|v<<8|v;}
        return 0xff000000|(bx*85)<<16|(by*85)<<8|((bx+2*by)%4)*85;
    }
    private String digest(Path path)throws java.io.IOException{
        return HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(path)));
    }
}
