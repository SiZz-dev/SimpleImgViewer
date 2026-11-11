package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual standard JDK decoders through registration, renderer and publication; no native fixtures. */
class ImageFormatCompatibilityTests {
    @TempDir Path directory;
    final ViewerPrincipal actor = new ViewerPrincipal("tenant", "alice");
    final Preview profile = new Preview(true, 1, 2, 32, 32, 100000, "format-v1");

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpeg", "gif", "bmp", "tiff"})
    void standardJdkInputsPublishOnlyNormalizedPngWithVerifiedGeometry(String format) throws Exception {
        byte[] input = image(format);
        var expected = ImageIO.read(new ByteArrayInputStream(input));
        var store = new LocalMediaStore(directory.resolve("originals"), 100000);
        var media = store.register("tenant", "alice", "untrusted-name.bin", new ByteArrayInputStream(input));
        try (var queue = queue(store, null)) {
            queue.submit(media, actor, 1, false);
            var state = await(queue, media);
            assertThat(state.ready()).isTrue();
            assertThat(state.geometry()).isNotNull();
            assertThat(state.geometry().coordinateWidth()).isEqualTo(12);
            assertThat(state.geometry().coordinateHeight()).isEqualTo(8);
            queue.content(media, actor, 1, state.sourceId(), path -> {
                byte[] normalized = Files.readAllBytes(path);
                assertThat(normalized).startsWith(new byte[]{(byte)137,80,78,71,13,10,26,10});
                var actual = ImageIO.read(new ByteArrayInputStream(normalized));
                assertThat(actual.getWidth()).isEqualTo(12);
                assertThat(actual.getHeight()).isEqualTo(8);
                assertThat(actual.getRGB(0,0,12,8,null,0,12))
                        .containsExactly(expected.getRGB(0,0,12,8,null,0,12));
            });
            assertThat(Files.readAllBytes(store.resolveOriginal(media))).containsExactly(input);
        } finally { expected.flush(); }
    }

    @Test void selectedNativeFailureDoesNotFallBackToOtherwiseDecodableTiff() throws Exception {
        var store = new LocalMediaStore(directory.resolve("originals"), 100000);
        byte[] input = image("tiff");
        var media = store.register("tenant", "alice", "page.tif", new ByteArrayInputStream(input));
        var converter = mock(ImageConverter.class);
        when(converter.supportedMimeTypes()).thenReturn(Set.of("image/tiff"));
        when(converter.convert(any())).thenThrow(new ConversionException(
                ConversionException.Code.UNAVAILABLE, "private-command-and-path"));
        try (var queue = queue(store, converter)) {
            queue.submit(media, actor, 1, false);
            var state = await(queue, media);
            assertThat(state.state()).isEqualTo("failed");
            assertThat(state.errorCode()).isEqualTo("CONVERSION_UNAVAILABLE");
            assertThat(state.geometry()).isNull();
            assertThat(state.toString()).doesNotContain("private-command-and-path");
            assertThatThrownBy(() -> queue.content(media,actor,1,state.sourceId(), p -> fail("Must not publish")))
                    .isInstanceOf(MediaException.class);
            queue.submit(media,actor,1,false);
        }
        verify(converter, times(1)).convert(any());
        assertThat(Files.readAllBytes(store.resolveOriginal(media))).containsExactly(input);
    }

    private PreviewQueue queue(LocalMediaStore store, ImageConverter converter) throws Exception {
        var features = mock(FeaturePolicy.class);
        when(features.isEnabled(any())).thenReturn(true);
        return new PreviewQueue(profile,"standard-jdk-test",new PreviewCache(directory.resolve("cache")),
                new ImagePreviewRenderer(store,(a,m,action) -> true,features,profile,converter));
    }

    private PreviewStatus await(PreviewQueue queue, MediaRecord media) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var status = queue.status(media,actor,1);
            if (status.state().equals("ready") || status.state().equals("failed")) return status;
            Thread.sleep(10);
        }
        throw new AssertionError("Preview did not complete");
    }

    private byte[] image(String format) throws Exception {
        var image = new BufferedImage(12,8,BufferedImage.TYPE_INT_RGB);
        try {
            for(int y=0;y<8;y++) for(int x=0;x<12;x++) image.setRGB(x,y,x<6?0xffff0000:0xff0000ff);
            var out = new ByteArrayOutputStream();
            assertThat(ImageIO.write(image,format,out)).as("JDK writer for %s",format).isTrue();
            return out.toByteArray();
        } finally { image.flush(); }
    }
}
