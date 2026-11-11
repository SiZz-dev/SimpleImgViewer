package co.kr.satu.imagegate.conversion.service.img2tile;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.service.ImageConverter;
import co.kr.satu.imagegate.conversion.service.ImagePreviewRenderer;
import co.kr.satu.imagegate.conversion.service.PreviewQueue;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.conversion.util.process.NativeModuleSnapshot;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthesized lossless RGB8 only; no downloads, business media or new runtime codec. */
@Tag("img2tile")
@Tag("ffmpeg")
@EnabledOnOs(OS.WINDOWS)
class Jpeg2000IntegrationTests {
    @TempDir Path root;
    final Path ffmpeg = Path.of(System.getenv().getOrDefault("FFMPEG_EXECUTABLE",
            "D:/DG_module/ffmpeg/bin/ffmpeg.exe"));
    final Path executable = Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE",
            "D:/DG_module/img2tile/img2tile.exe"));
    final ViewerPrincipal actor = new ViewerPrincipal("tenant", "alice");
    final BoundedProcessExecutor processes = new BoundedProcessExecutor(1);

    @ParameterizedTest
    @ValueSource(strings = {"jp2"})
    void losslessRgbReachesNativePreviewWithoutInventingOriginalGeometry(String format) throws Exception {
        Path encoded = fixture(format);
        byte[] input = Files.readAllBytes(encoded);
        String hash = digest(encoded);
        var store = new LocalMediaStore(root.resolve("originals"), 1000000);
        var media = store.register("tenant", "alice", "synthetic."+format, new ByteArrayInputStream(input));
        assertThat(media.mimeType()).isEqualTo(format.equals("jp2") ? "image/jp2" : "image/j2c");
        var converter = new Img2TileConverter(executable,root,Duration.ofSeconds(30),processes);
        String identity = converter.cacheIdentity();
        var direct = converter.convert(new ImageConverter.ConversionRequest(store.resolveOriginal(media),
                root.resolve("derived/direct"),1,128,96,ImageConverter.OutputFormat.PNG));
        assertPixels(direct.output());
        var profile = new Preview(true,1,2,128,96,100000,"jp2-test-v1");
        var features = mock(FeaturePolicy.class);
        when(features.isEnabled(any())).thenReturn(true);
        try (var queue = new PreviewQueue(profile,identity,new PreviewCache(root.resolve("derived/cache")),
                new ImagePreviewRenderer(store,(a,m,action)->true,features,profile,converter))) {
            queue.submit(media,actor,1,false);
            var state = await(queue,media);
            assertThat(state.ready()).as("Native result: %s",state).isTrue();
            assertThat(state.width()).isEqualTo(128);
            assertThat(state.height()).isEqualTo(96);
            assertThat(state.geometry()).isNull();
            queue.content(media,actor,1,state.sourceId(),this::assertPixels);
            assertThat(queue.submit(media,actor,1,false).ready()).isTrue();
        }
        assertThat(digest(encoded)).isEqualTo(hash);
        assertThat(digest(store.resolveOriginal(media))).isEqualTo(hash);
        assertThat(converter.cacheIdentity()).isEqualTo(identity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"j2k", "j2c", "jpc", "jp2"})
    void currentModuleRejectsValidRawCodestreamEvenWithAlternativeExtension(String extension) throws Exception {
        Path encoded = fixture("j2k");
        byte[] bytes = Files.readAllBytes(encoded);
        var store = new LocalMediaStore(root.resolve("originals"),1000000);
        var media = store.register("tenant","alice","raw."+extension,new ByteArrayInputStream(bytes));
        assertThat(media.mimeType()).isEqualTo("image/j2c");
        var converter = new Img2TileConverter(executable,root,Duration.ofSeconds(30),processes);
        String identity = converter.cacheIdentity();
        Path alias = root.resolve("originals/raw-probe."+extension);
        Files.write(alias,bytes);
        assertThatThrownBy(() -> converter.convert(new ImageConverter.ConversionRequest(alias,
                root.resolve("derived/direct"),1,128,96,ImageConverter.OutputFormat.PNG)))
                .isInstanceOfSatisfying(ConversionException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(ConversionException.Code.PROCESS_FAILED);
                    assertThat(failure.exitCode()).isEqualTo(1);
                });
        assertThat(root.resolve("derived/direct/preview.png")).doesNotExist();
        // Check the public worker classification once; the other cases isolate native extension handling.
        if(extension.equals("j2k")) {
            var profile = new Preview(true,1,2,128,96,100000,"raw-j2k-test-v1");
            var features = mock(FeaturePolicy.class);
            when(features.isEnabled(any())).thenReturn(true);
            try(var queue = new PreviewQueue(profile,identity,new PreviewCache(root.resolve("derived/cache")),
                    new ImagePreviewRenderer(store,(a,m,action)->true,features,profile,converter))) {
                queue.submit(media,actor,1,false);
                var state = await(queue,media);
                assertThat(state.state()).isEqualTo("failed");
                assertThat(state.errorCode()).isEqualTo("CONVERSION_PROCESS_FAILED");
                assertThat(state.geometry()).isNull();
                assertThat(state.thumbnail()).isNull();
                assertThatThrownBy(() -> queue.content(media,actor,1,state.sourceId(),p -> fail("Must not publish")))
                        .isInstanceOf(co.kr.satu.imagegate.media.exception.MediaException.class);
                assertThat(queue.submit(media,actor,1,false).state()).isEqualTo("failed");
            }
        }
        assertThat(digest(store.resolveOriginal(media))).isEqualTo(digest(encoded));
        assertThat(Files.readAllBytes(alias)).containsExactly(bytes);
        assertThat(converter.cacheIdentity()).isEqualTo(identity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jp2", "j2k"})
    void truncatedRealCodestreamDoesNotReturnValidatedOutput(String format) throws Exception {
        Path encoded = fixture(format);
        var converter = new Img2TileConverter(executable,root,Duration.ofSeconds(30),processes);
        Path truncated = root.resolve("originals/truncated."+format);
        Files.write(truncated,Arrays.copyOf(Files.readAllBytes(encoded),64));
        String hash = digest(truncated);
        assertThatThrownBy(() -> converter.convert(new ImageConverter.ConversionRequest(truncated,
                root.resolve("derived/rejected"),1,128,96,ImageConverter.OutputFormat.PNG)))
                .isInstanceOf(ConversionException.class);
        assertThat(root.resolve("derived/rejected/preview.png")).doesNotExist();
        assertThat(digest(truncated)).isEqualTo(hash);
        converter.cacheIdentity(); // A failed input does not reapprove or invalidate unchanged modules.
    }

    private Path fixture(String format) throws Exception {
        Path work = Files.createDirectory(root.resolve("generator"));
        Path source = work.resolve("source.png"), encoded = work.resolve("source."+format);
        var image = new BufferedImage(128,96,BufferedImage.TYPE_INT_RGB);
        try {
            for(int y=0;y<96;y++) for(int x=0;x<128;x++) image.setRGB(x,y,pixel(x,y));
            assertThat(ImageIO.write(image,"png",source.toFile())).isTrue();
        } finally { image.flush(); }
        var module = NativeModuleSnapshot.capture(List.of(ffmpeg.toRealPath()),false);
        module.verifyContent();
        var result = processes.execute(List.of(ffmpeg.toRealPath().toString(),"-hide_banner","-loglevel","error",
                "-nostdin","-n","-threads","1","-i",source.toString(),"-frames:v","1","-c:v","libopenjpeg",
                "-pix_fmt","rgb24","-irreversible","0","-format",format,"-f","image2","-update","1",encoded.toString()),
                work,Duration.ofSeconds(30));
        module.verifyContent();
        assertThat(result.exitCode()).as("Synthetic encoding: %s",result.output()).isZero();
        assertThat(Files.size(encoded)).isBetween(65L,1000000L);
        // Independent FFmpeg decode verifies that the generated file represents the intended raster.
        Path reference = work.resolve("decoded.png");
        var decoded = processes.execute(List.of(ffmpeg.toRealPath().toString(),"-hide_banner","-loglevel","error",
                "-nostdin","-n","-threads","1","-i",encoded.toString(),"-frames:v","1",reference.toString()),
                work,Duration.ofSeconds(30));
        module.verifyContent();
        assertThat(decoded.exitCode()).as("Synthetic decode: %s",decoded.output()).isZero();
        assertPixels(reference);
        return encoded;
    }

    private static int pixel(int x,int y) {
        // Non-square, asymmetric RGB pattern catches flips, rotations and channel swaps.
        return 0xff000000 | ((x*2)&255)<<16 | ((y*2)&255)<<8 | ((x*3+y*5)&255);
    }
    private void assertPixels(Path png) throws java.io.IOException {
        var image = ImageIO.read(png.toFile());
        assertThat(image).isNotNull();
        try {
            assertThat(image.getWidth()).isEqualTo(128);
            assertThat(image.getHeight()).isEqualTo(96);
            for(int y=0;y<96;y++) for(int x=0;x<128;x++)
                assertThat(image.getRGB(x,y)).as("Pixel (%s,%s)",x,y).isEqualTo(pixel(x,y));
        } finally { image.flush(); }
    }
    private PreviewStatus await(PreviewQueue queue,MediaRecord media) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<deadline) {
            var state=queue.status(media,actor,1);
            if(state.ready()||state.state().equals("failed"))return state;
            Thread.sleep(20);
        }
        throw new AssertionError("Native preview did not complete");
    }
    private String digest(Path path) throws java.io.IOException {
        return HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(path)));
    }
}
