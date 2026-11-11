package co.kr.satu.imagegate.conversion.service.img2tile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.service.ImageConverter;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("img2tile")
@EnabledOnOs(OS.WINDOWS)
class Img2TileIntegrationTests {
    @TempDir Path directory;

    @Test
    void convertsBothTiffPagesAndRejectsInvalidPageUsingRealExecutable() throws Exception {
        Path executable = Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE",
                "D:/DG_module/img2tile/img2tile.exe"));
        var converter = new Img2TileConverter(executable, directory, Duration.ofSeconds(30),
                new BoundedProcessExecutor(1));
        Path input = directory.resolve("originals/two pages.tif");
        var writer = ImageIO.getImageWritersByFormatName("TIFF").next();
        try (var stream = ImageIO.createImageOutputStream(input.toFile())) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (Color color : new Color[]{Color.RED, Color.BLUE}) {
                var image = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                graphics.setColor(color);
                graphics.fillRect(0, 0, 1600, 1200);
                graphics.dispose();
                writer.writeToSequence(new IIOImage(image, null, null), writer.getDefaultWriteParam());
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
        for (int page = 1; page <= 2; page++) {
            var format = page == 1 ? ImageConverter.OutputFormat.PNG : ImageConverter.OutputFormat.JPEG;
            var result = converter.convert(new ImageConverter.ConversionRequest(input,
                    directory.resolve("derived/page " + page), page, 800, 600, format));
            assertThat(result.width()).isEqualTo(800);
            assertThat(result.height()).isEqualTo(600);
            int pixel = ImageIO.read(result.output().toFile()).getRGB(100, 100);
            if (page == 1) {
                assertThat((pixel >> 16) & 255).isGreaterThan(240);
                assertThat(pixel & 255).isLessThan(15);
            } else {
                assertThat(pixel & 255).isGreaterThan(240);
                assertThat((pixel >> 16) & 255).isLessThan(15);
            }
        }
        assertThatThrownBy(() -> converter.convert(new ImageConverter.ConversionRequest(input,
                directory.resolve("derived/invalid"), 3, 800, 600, ImageConverter.OutputFormat.PNG)))
                .isInstanceOfSatisfying(ConversionException.class, error ->
                        assertThat(error.exitCode()).isEqualTo(-8));
        assertThat(Files.exists(directory.resolve("derived/invalid/preview.png"))).isFalse();
    }

    @org.junit.jupiter.api.RepeatedTest(3)
    void processesRegisteredTiffThroughRealWorkerAndCachesThePublishedPreview() throws Exception {
        var store=new co.kr.satu.imagegate.media.repository.LocalMediaStore(directory.resolve("originals"),1000000);
        var bytes=new java.io.ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(64,32,BufferedImage.TYPE_INT_RGB),"TIFF",bytes);
        var media=store.register("tenant","alice","sample.tif",new java.io.ByteArrayInputStream(bytes.toByteArray()));
        var actor=new co.kr.satu.imagegate.security.domain.ViewerPrincipal("tenant","alice");
        Path executable=Path.of(System.getenv().getOrDefault("IMG2TILE_EXECUTABLE","D:/DG_module/img2tile/img2tile.exe"));
        var converter=new Img2TileConverter(executable,directory,Duration.ofSeconds(30),new BoundedProcessExecutor(1));
        var profile=new co.kr.satu.imagegate.system.config.ImageGateProperties.Preview(true,1,2,32,32,100000,"v1");
        var features=org.mockito.Mockito.mock(co.kr.satu.imagegate.system.service.FeaturePolicy.class);
        org.mockito.Mockito.when(features.isEnabled(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        var renderer=new co.kr.satu.imagegate.conversion.service.ImagePreviewRenderer(store,(a,m,action)->true,
                features,profile,converter);
        var cache=new co.kr.satu.imagegate.conversion.repository.PreviewCache(directory.resolve("derived"));
        var error=new java.util.concurrent.atomic.AtomicReference<Exception>();
        co.kr.satu.imagegate.conversion.service.PreviewRenderer diagnostic=(m,a,p,w)-> {
            try { return renderer.render(m,a,p,w); } catch(Exception e) { error.set(e);throw e; }
        };
        try(var queue=new co.kr.satu.imagegate.conversion.service.PreviewQueue(profile,converter.cacheIdentity(),cache,diagnostic)) {
            String key=queue.submit(media,actor,1,false).sourceId();
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            co.kr.satu.imagegate.conversion.domain.PreviewStatus state;
            do {
                state=queue.status(media,actor,1);
                if(state.ready() || "failed".equals(state.state())) break;
                Thread.sleep(20);
            } while(System.nanoTime()<end);
            assertThat(state.state()).as("state=%s, cause=%s",state,error.get()).isEqualTo("ready");
            assertThat(state.geometry()).isNull(); // Native source dimensions/orientation are not inferred from preview.
            assertThat(state.width()).isEqualTo(32);assertThat(state.height()).isEqualTo(16);
            queue.content(media,actor,1,key,path->assertThat(ImageIO.read(path.toFile()).getWidth()).isEqualTo(32));
            assertThat(queue.submit(media,actor,1,false).ready()).isTrue();
        }
    }
}
