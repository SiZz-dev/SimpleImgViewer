package co.kr.satu.imagegate.conversion.service;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.util.PreviewTiming;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImagePreviewTimingTests {
    @TempDir Path root;
    @Test void optInStagesPreservePixelsGeometryAndDefaultRenderingWhenObserverFails() throws Exception {
        var profile=new Preview(true,1,1,32,32,10000,"v1");
        var flags=new ImageGateProperties.Features(
                new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
        var policy=new FeaturePolicy(new ImageGateProperties(null,flags,null,profile,null,null,null,null));
        try(var store=new LocalMediaStore(root.resolve("originals"),100000)){
            var image=new BufferedImage(64,32,BufferedImage.TYPE_INT_ARGB);var bytes=new ByteArrayOutputStream();
            try{image.setRGB(0,0,0x80ff0000);ImageIO.write(image,"png",bytes);}finally{image.flush();}
            var media=store.register("tenant","alice","synthetic.png",new ByteArrayInputStream(bytes.toByteArray()));
            var actor=new ViewerPrincipal("tenant","alice");
            var plain=new ImagePreviewRenderer(store,(a,m,action)->true,policy,profile,null);
            var expected=plain.render(media,actor,1,root.resolve("default"));
            var stages=new EnumMap<PreviewTiming.Stage,Integer>(PreviewTiming.Stage.class);
            var measured=new ImagePreviewRenderer(store,(a,m,action)->true,policy,profile,null,(stage,nanos)->{
                assertThat(nanos).isNotNegative();stages.merge(stage,1,Integer::sum);
            });
            var actual=measured.render(media,actor,1,root.resolve("measured"));
            assertThat(Files.readAllBytes(actual.png())).containsExactly(Files.readAllBytes(expected.png()));
            assertThat(Files.readAllBytes(actual.thumbnail())).containsExactly(Files.readAllBytes(expected.thumbnail()));
            assertThat(actual.geometry()).isEqualTo(expected.geometry());
            assertThat(actual.width()).isEqualTo(32);assertThat(actual.height()).isEqualTo(16);
            var wanted=EnumSet.allOf(PreviewTiming.Stage.class);wanted.remove(PreviewTiming.Stage.NATIVE_CONVERT);wanted.removeIf(PreviewTiming.Stage::hashDetail);
            assertThat(stages.keySet().stream().filter(stage->!stage.hashDetail()).toList()).containsExactlyInAnyOrderElementsOf(wanted);
            assertThat(stages.entrySet().stream().filter(entry->!entry.getKey().hashDetail()).map(Map.Entry::getValue).toList()).allMatch(count->count==1);
            for(var stage:PreviewTiming.Stage.values())if(stage.hashDetail())assertThat(stages.get(stage)).isPositive();
            var broken=new ImagePreviewRenderer(store,(a,m,action)->true,policy,profile,null,(stage,nanos)->{
                throw new IllegalArgumentException("Synthetic observer failure");
            });
            var result=broken.render(media,actor,1,root.resolve("broken-observer"));
            assertThat(Files.readAllBytes(result.png())).containsExactly(Files.readAllBytes(expected.png()));
        }
    }

    @Test void multiChunkHashTimingAccountsForEofAndStillRejectsChangedOriginal() throws Exception {
        var profile=new Preview(true,1,1,32,32,100000,"v1");
        var flags=new ImageGateProperties.Features(
                new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
        var policy=new FeaturePolicy(new ImageGateProperties(null,flags,null,profile,null,null,null,null));
        try(var store=new LocalMediaStore(root.resolve("originals"),1000000)){
            var image=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);var bytes=new ByteArrayOutputStream();
            try{
                var random=new Random(83021);
                for(int y=0;y<128;y++)for(int x=0;x<128;x++)image.setRGB(x,y,0xff000000|random.nextInt(0x1000000));
                ImageIO.write(image,"png",bytes);
            }finally{image.flush();}
            var media=store.register("tenant","alice","synthetic.png",new ByteArrayInputStream(bytes.toByteArray()));
            Path original=store.resolveOriginal(media);assertThat(Files.size(original)).isGreaterThan(32768);
            var calls=new EnumMap<PreviewTiming.Stage,Integer>(PreviewTiming.Stage.class);
            var durations=new EnumMap<PreviewTiming.Stage,Long>(PreviewTiming.Stage.class);
            var renderer=new ImagePreviewRenderer(store,(a,m,action)->true,policy,profile,null,(stage,nanos)->{
                calls.merge(stage,1,Integer::sum);durations.merge(stage,nanos,Long::sum);
            });
            var actor=new ViewerPrincipal("tenant","alice");
            assertThat(renderer.render(media,actor,1,root.resolve("valid")).png()).isRegularFile();
            assertThat(calls.get(PreviewTiming.Stage.HASH_UPDATE)).isGreaterThanOrEqualTo(2);
            assertThat(calls.get(PreviewTiming.Stage.HASH_READ)).isEqualTo(calls.get(PreviewTiming.Stage.HASH_UPDATE)+1);
            assertThat(calls.get(PreviewTiming.Stage.HASH_CLOSE)).isEqualTo(1);
            assertThat(calls.get(PreviewTiming.Stage.HASH_FINAL)).isEqualTo(1);
            long nested=durations.entrySet().stream().filter(e->e.getKey().hashDetail()).mapToLong(Map.Entry::getValue).sum();
            assertThat(nested).isLessThanOrEqualTo(durations.get(PreviewTiming.Stage.ORIGINAL_HASH));
            byte[] changed=Files.readAllBytes(original);changed[changed.length-1]^=1;Files.write(original,changed);
            calls.clear();
            assertThatThrownBy(()->renderer.render(media,actor,1,root.resolve("invalid")))
                    .isInstanceOf(IOException.class).hasMessage("Original changed");
            assertThat(calls.get(PreviewTiming.Stage.HASH_CLOSE)).isEqualTo(1);
            assertThat(calls.get(PreviewTiming.Stage.HASH_FINAL)).isEqualTo(1);
            assertThat(calls).doesNotContainKey(PreviewTiming.Stage.WORK_CREATE);
            assertThat(root.resolve("invalid")).doesNotExist();
            var broken=new ImagePreviewRenderer(store,(a,m,action)->true,policy,profile,null,(stage,nanos)->{
                throw new IllegalArgumentException("Synthetic observer failure");
            });
            assertThatThrownBy(()->broken.render(media,actor,1,root.resolve("invalid-observer")))
                    .isInstanceOf(IOException.class).hasMessage("Original changed");
            assertThat(root.resolve("invalid-observer")).doesNotExist();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void experimentalOpenGatePreservesPixelsAndCannotBypassOriginalDigest(boolean runtimeEnabled) throws Exception {
        var profile=new Preview(true,1,1,32,32,10000,"v1",ImageGateProperties.PreviewCachePolicy.defaults(),true,256,
                new ImageGateProperties.OriginalOpenPolicy(runtimeEnabled,1,java.time.Duration.ofSeconds(1)));
        var flags=new ImageGateProperties.Features(
                new ImageGateProperties.Image(true,false,false,false,false,false,false,false,false,false),
                new ImageGateProperties.Playback(false,false,false),new ImageGateProperties.Playback(false,false,false));
        var policy=new FeaturePolicy(new ImageGateProperties(null,flags,null,profile,null,null,null,null));
        try(var store=new LocalMediaStore(root.resolve("originals"),100000)){
            var image=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);var bytes=new ByteArrayOutputStream();
            try{ImageIO.write(image,"png",bytes);}finally{image.flush();}
            var media=store.register("tenant","alice","synthetic.png",new ByteArrayInputStream(bytes.toByteArray()));
            var actor=new ViewerPrincipal("tenant","alice");
            var expected=new ImagePreviewRenderer(store,(a,m,action)->true,policy,new Preview(true,1,1,32,32,10000,"v1"),null).render(media,actor,1,root.resolve("plain"));
            var gate=new co.kr.satu.imagegate.conversion.util.OpenPermitGate(1);
            var actual=new ImagePreviewRenderer(store,(a,m,action)->true,policy,profile,null,null,gate::open)
                    .render(media,actor,1,root.resolve("gated"));
            assertThat(Files.readAllBytes(actual.png())).containsExactly(Files.readAllBytes(expected.png()));
            assertThat(Files.readAllBytes(actual.thumbnail())).containsExactly(Files.readAllBytes(expected.thumbnail()));
            assertThat(gate.stats().get("available")).isEqualTo(1);
            var bad=new ImagePreviewRenderer(store,(a,m,action)->true,policy,profile,null,null,
                    p->new ByteArrayInputStream(new byte[]{1,2,3}));
            assertThatThrownBy(()->bad.render(media,actor,1,root.resolve("wrong-source")))
                    .isInstanceOf(IOException.class).hasMessage("Original changed");
            assertThat(root.resolve("wrong-source")).doesNotExist();
            var denied=new ImagePreviewRenderer(store,(a,m,action)->false,policy,profile,null,null,p->{throw new AssertionError("Unauthorized original open");});
            assertThatThrownBy(()->denied.render(media,actor,1,root.resolve("denied"))).isInstanceOf(IOException.class);
            assertThat(root.resolve("denied")).doesNotExist();
            bad.close();bad.close();
            assertThatThrownBy(()->bad.render(media,actor,1,root.resolve("closed"))).isInstanceOf(InterruptedIOException.class);
            assertThat(root.resolve("closed")).doesNotExist();
        }
    }
}
