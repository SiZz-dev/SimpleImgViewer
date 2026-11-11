package co.kr.satu.imagegate.conversion.service.img2tile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import co.kr.satu.imagegate.conversion.service.ImageConverter;
import co.kr.satu.imagegate.conversion.util.process.ProcessExecutor;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Img2TileConverterTests {
    @TempDir Path directory;
    Path executable;
    Path storage;
    Path input;

    @BeforeEach
    void prepare() throws Exception {
        executable = Files.createFile(directory.resolve("converter.exe"));
        Files.createDirectory(directory.resolve("resource"));
        Files.writeString(directory.resolve("resource/libc9i.xml"), "<root/>");
        Files.write(directory.resolve("resource/cmyk.icc"), new byte[]{1});
        storage = directory.resolve("media");
        Files.createDirectories(storage.resolve("originals"));
        input = storage.resolve("originals/test.tif");
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "TIFF", input.toFile());
    }

    @Test
    void mapsPageAndValidatesActualOutputAndBoundingBox() throws Exception {
        var captured = new AtomicReference<java.util.List<String>>();
        var converter = converter((command, job, timeout) -> {
            captured.set(command);
            ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB),
                    "png", job.resolve("preview.png").toFile());
            return new ProcessExecutor.Result(0, "result=0", false);
        });
        var result = converter.convert(request(input, "good", 2, 800, 400));
        assertThat(captured.get()).containsSubsequence("-page", "1");
        assertThat(result.width()).isEqualTo(533);
        assertThat(result.height()).isEqualTo(400);
        assertThat(result.mimeType()).isEqualTo("image/png");
        assertThat(ImageIO.read(result.output().toFile()).getWidth()).isEqualTo(533);
    }

    @Test void cacheIdentitySeparatesResultsFromBeforeJp2AdmissionPolicy() throws Exception {
        var converter=converter((command,job,timeout)->{throw new AssertionError("Must not run");});
        assertThat(converter.cacheIdentity()).endsWith("/jp2-rgb-v2");
        var module=co.kr.satu.imagegate.conversion.util.process.NativeModuleSnapshot.capture(java.util.List.of(executable),true);
        assertThat(converter.cacheIdentity()).isNotEqualTo("img2tile/"+module.fingerprint());
    }

    @Test
    void rejectsOutsideInputOutputAndUnknownActualFormat() throws Exception {
        var converter = converter((command, job, timeout) -> { throw new AssertionError("Must not run"); });
        Path outside = Files.copy(input, directory.resolve("outside.tif"));
        assertThatThrownBy(() -> converter.convert(request(outside, "out", 1, 800, 600)))
                .isInstanceOf(ConversionException.class);
        var outsideOutput = new ImageConverter.ConversionRequest(input, directory.resolve("escape"),
                1, 800, 600, ImageConverter.OutputFormat.PNG);
        assertThatThrownBy(() -> converter.convert(outsideOutput)).isInstanceOf(ConversionException.class);
        Files.writeString(input, "not a TIFF");
        assertThatThrownBy(() -> converter.convert(request(input, "invalid", 1, 800, 600)))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void rejectsStaleDirectoryWithoutRunningConverter() throws Exception {
        var converter = converter((command, job, timeout) -> { throw new AssertionError("Must not run"); });
        Files.createDirectory(storage.resolve("derived/existing"));
        assertThatThrownBy(() -> converter.convert(request(input, "existing", 1, 800, 600)))
                .isInstanceOf(ConversionException.class);
    }

    @Test
    void neverTreatsFailedProcessAsSuccessEvenWithAnImage() throws Exception {
        var converter = converter((command, job, timeout) -> {
            ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB),
                    "png", job.resolve("preview.png").toFile());
            return new ProcessExecutor.Result(-8, "result=-8", false);
        });
        assertThatThrownBy(() -> converter.convert(request(input, "failed", 1, 800, 600)))
                .isInstanceOfSatisfying(ConversionException.class, error -> {
                    assertThat(error.code()).isEqualTo(ConversionException.Code.PROCESS_FAILED);
                    assertThat(error.exitCode()).isEqualTo(-8);
                });
    }

    @Test
    void rejectsMissingInvalidAndMislabeledOutput() throws Exception {
        for (String kind : new String[]{"missing", "text", "jpeg"}) {
            var converter = converter((command, job, timeout) -> {
                if (kind.equals("text")) Files.writeString(job.resolve("preview.png"), "bad image");
                if (kind.equals("jpeg")) ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB),
                        "jpeg", job.resolve("preview.png").toFile());
                return new ProcessExecutor.Result(0, "", false);
            });
            assertThatThrownBy(() -> converter.convert(request(input, kind, 1, 800, 600)))
                    .isInstanceOfSatisfying(ConversionException.class, error ->
                            assertThat(error.code()).isEqualTo(ConversionException.Code.INVALID_OUTPUT));
        }
    }

    @Test
    void rejectsOversizedPreviewBeforeProcessExecution() throws Exception {
        var converter = converter((command, job, timeout) -> { throw new AssertionError("Must not run"); });
        assertThatThrownBy(() -> converter.convert(request(input, "huge", 1, 4097, 600)))
                .isInstanceOf(ConversionException.class);
    }


    @Test void changedLibraryBlocksExecutionAndDoesNotRebaseEvenAfterRestoration() throws Exception {
        Path library=Files.write(directory.resolve("codec.dll"),new byte[]{1,2,3});var mtime=Files.getLastModifiedTime(library);
        var calls=new java.util.concurrent.atomic.AtomicInteger();var converter=converter((command,job,timeout)->{calls.incrementAndGet();throw new AssertionError("Must not run");});
        String before=converter.cacheIdentity();Files.write(library,new byte[]{9,9,9});Files.setLastModifiedTime(library,mtime);
        assertThatThrownBy(()->converter.convert(request(input,"changed",1,100,100))).isInstanceOf(ConversionException.class);
        assertThat(Files.exists(storage.resolve("derived/changed"))).isFalse();assertThat(calls.get()).isZero();
        Files.write(library,new byte[]{1,2,3});Files.setLastModifiedTime(library,mtime);
        assertThatThrownBy(converter::cacheIdentity).isInstanceOf(ConversionException.class);
        assertThat(converter((command,job,timeout)->{throw new AssertionError("Must not run");}).cacheIdentity()).isEqualTo(before);
    }
    @Test void changedResourceCannotBeCopiedOrExecutedUnderOldIdentity() throws Exception {
        var converter=converter((command,job,timeout)->{throw new AssertionError("Must not run");});
        Path resource=directory.resolve("resource/libc9i.xml");var modified=Files.getLastModifiedTime(resource);
        Files.writeString(resource,"<evil/>");Files.setLastModifiedTime(resource,modified);
        assertThatThrownBy(()->converter.convert(request(input,"resource-changed",1,100,100))).isInstanceOf(ConversionException.class);
        assertThat(Files.exists(storage.resolve("derived/resource-changed"))).isFalse();
    }
    @Test void moduleChangeDuringNativeCallCannotReturnAValidImage() throws Exception {
        Path library=Files.write(directory.resolve("codec.dll"),new byte[]{1,2,3});var modified=Files.getLastModifiedTime(library);
        var converter=converter((command,job,timeout)->{
            ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"png",job.resolve("preview.png").toFile());
            Files.write(library,new byte[]{9,9,9});Files.setLastModifiedTime(library,modified);
            return new ProcessExecutor.Result(0,"",false);
        });
        assertThatThrownBy(()->converter.convert(request(input,"during",1,100,100))).isInstanceOf(ConversionException.class);
        assertThatThrownBy(converter::cacheIdentity).isInstanceOf(ConversionException.class);
    }
    @Test void newConverterAfterReviewedReplacementHasADifferentCacheIdentity() throws Exception {
        var old=converter((command,job,timeout)->{throw new AssertionError("Must not run");});String identity=old.cacheIdentity();
        Files.write(executable,new byte[]{7,8,9});assertThatThrownBy(old::cacheIdentity).isInstanceOf(ConversionException.class);
        var replacement=converter((command,job,timeout)->{
            ImageIO.write(new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB),"png",job.resolve("preview.png").toFile());return new ProcessExecutor.Result(0,"",false);
        });
        assertThat(replacement.cacheIdentity()).isNotEqualTo(identity);
        assertThat(replacement.convert(request(input,"reviewed",1,100,100)).mimeType()).isEqualTo("image/png");
    }

    private Img2TileConverter converter(ProcessExecutor executor) throws Exception {
        return new Img2TileConverter(executable, storage, Duration.ofSeconds(10), executor);
    }

    private ImageConverter.ConversionRequest request(Path source, String name, int page, int w, int h) {
        return new ImageConverter.ConversionRequest(source, storage.resolve("derived").resolve(name),
                page, w, h, ImageConverter.OutputFormat.PNG);
    }
}
