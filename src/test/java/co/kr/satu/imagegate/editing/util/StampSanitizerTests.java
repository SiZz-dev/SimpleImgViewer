package co.kr.satu.imagegate.editing.util;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StampSanitizerTests {
    @TempDir Path root;
    @Test void keepsPrivateResolutionAndAlphaWhileServingSmallPreview() throws Exception {
        var image=new BufferedImage(1024,512,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();g.setColor(new Color(220,20,20,180));g.fillRect(128,128,768,256);g.dispose();
        Path input=root.resolve("input.png"),output=root.resolve("full.png"),preview=root.resolve("preview.png");
        ImageIO.write(image,"png",input.toFile());
        var size=StampSanitizer.sanitize(input,output,preview,4194304);
        assertThat(size).isEqualTo(new StampSanitizer.Dimensions(1024,512,256,128));
        var full=ImageIO.read(output.toFile());var small=ImageIO.read(preview.toFile());
        assertThat(full.getWidth()).isEqualTo(1024);assertThat(small.getWidth()).isEqualTo(256);
        assertThat(small.getRGB(0,0)>>>24).isZero();
        assertThat(full.getRGB(512,256)>>>24).isBetween(175,185);
    }
    @Test void rejectsInputBeforeDecodeWhenPixelPolicyIsExceeded() throws Exception {
        Path input=root.resolve("input.png"),output=root.resolve("full.png"),preview=root.resolve("preview.png");
        ImageIO.write(new BufferedImage(100,100,BufferedImage.TYPE_INT_RGB),"png",input.toFile());
        assertThatThrownBy(()->StampSanitizer.sanitize(input,output,preview,9999)).isInstanceOf(EditException.class);
        assertThat(output).doesNotExist();assertThat(preview).doesNotExist();
    }
}
