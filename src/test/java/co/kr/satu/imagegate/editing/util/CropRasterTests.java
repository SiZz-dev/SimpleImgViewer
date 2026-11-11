package co.kr.satu.imagegate.editing.util;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.conversion.util.OrientationFixtures;
import co.kr.satu.imagegate.editing.domain.CropArea;
import co.kr.satu.imagegate.editing.exception.EditException;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CropRasterTests {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8})
    void cropsExactUprightPixelsForEveryExifOrientation(int orientation) throws Exception {
        Path input=root.resolve("source.png"),output=root.resolve("crop.png");
        Files.write(input,OrientationFixtures.png(orientation));
        var geometry=new ImageGeometry(60,40,orientation);
        // Independent encoded-pixel oracle for the center of the upright top-left color block.
        int[] expected={0,0,2,5,3,0,3,5,2};
        CropRaster.render(input,1,geometry,new CropArea(2,2,12,12),2400,100000,output);
        var actual=ImageIO.read(output.toFile());
        assertThat(actual.getWidth()).isEqualTo(12);assertThat(actual.getHeight()).isEqualTo(12);
        for(int y=0;y<12;y++)for(int x=0;x<12;x++)
            assertThat(actual.getRGB(x,y)).isEqualTo(OrientationFixtures.COLORS[expected[orientation]]);
    }
    @Test void tiffPageAndPixelLimitsAreAppliedToOriginal() throws Exception {
        Path input=root.resolve("pages.tif"),output=root.resolve("crop.png");
        Files.write(input,OrientationFixtures.tiff(1,6));
        CropRaster.render(input,2,new ImageGeometry(60,40,6),new CropArea(2,2,10,10),2400,100000,output);
        assertThat(ImageIO.read(output.toFile()).getRGB(5,5)).isEqualTo(OrientationFixtures.COLORS[3]);
        assertThatThrownBy(()->CropRaster.render(input,2,new ImageGeometry(60,40,6),
                new CropArea(0,0,1,1),2399,100000,root.resolve("limited.png"))).isInstanceOf(EditException.class);
    }
    @Test void pngByteLimitIsEnforcedAndWrongGeometryCannotBeUsed() throws Exception {
        Path input=root.resolve("source.png");Files.write(input,OrientationFixtures.png(1));
        assertThatThrownBy(()->CropRaster.render(input,1,new ImageGeometry(60,40,1),new CropArea(0,0,60,40),
                2400,32,root.resolve("large.png"))).isInstanceOfAny(EditException.class,java.io.IOException.class);
        assertThatThrownBy(()->CropRaster.render(input,1,new ImageGeometry(40,60,1),new CropArea(0,0,10,10),
                2400,100000,root.resolve("bad.png"))).isInstanceOf(EditException.class);
    }
}
