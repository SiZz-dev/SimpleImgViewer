package co.kr.satu.imagegate.conversion.util;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import java.awt.geom.Point2D;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImageOrientationTests {
    @TempDir Path directory;
    Integer read(byte[] bytes,String format,int page) throws IOException {
        Path path=directory.resolve("input");Files.write(path,bytes);return ImageOrientation.read(path,format,page);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3,4,5,6,7,8})
    void transformInversePreservesCornersAndFractionalPoints(int orientation) throws Exception {
        var geometry=new ImageGeometry(60,40,orientation);
        var forward=ImageOrientation.encodedToOriginal(geometry);var inverse=forward.createInverse();
        for(var point:new Point2D[]{new Point2D.Double(0,0),new Point2D.Double(60,40),
                new Point2D.Double(17.25,23.75)}) {
            var original=forward.transform(point,null);
            assertThat(original.getX()).isBetween(0.0,(double)geometry.coordinateWidth());
            assertThat(original.getY()).isBetween(0.0,(double)geometry.coordinateHeight());
            var restored=inverse.transform(original,null);
            assertThat(restored.getX()).isCloseTo(point.getX(),within(1e-10));
            assertThat(restored.getY()).isCloseTo(point.getY(),within(1e-10));
        }
    }
    @Test void rejectsInvalidOrientationTypesDuplicateExifAndTruncatedSegments() throws Exception {
        for(byte[] bytes:new byte[][]{OrientationFixtures.jpeg(0),OrientationFixtures.jpeg(9),
                OrientationFixtures.addExif(OrientationFixtures.jpeg(1),OrientationFixtures.tiffProfile(6,true)),
                new byte[]{(byte)255,(byte)216,(byte)255,(byte)225,127,(byte)255}}) {
            assertThatThrownBy(()->read(bytes,"jpeg",1)).isInstanceOf(IOException.class);
        }
        byte[] invalidType=OrientationFixtures.tiffProfile(1,true);invalidType[12]=4;
        assertThatThrownBy(()->read(OrientationFixtures.addExif(OrientationFixtures.jpeg(1),invalidType),"jpeg",1))
                .isInstanceOf(IOException.class);
    }
    @Test void rejectsOutOfBoundsIfdsCyclesAndExcessiveEntries() throws Exception {
        byte[] offset=OrientationFixtures.tiffProfile(1,true);ByteBuffer.wrap(offset).order(ByteOrder.LITTLE_ENDIAN).putInt(4,-1);
        assertThatThrownBy(()->read(offset,"tiff",1)).isInstanceOf(IOException.class);
        byte[] cyclic=OrientationFixtures.tiffProfile(1,true);ByteBuffer.wrap(cyclic).order(ByteOrder.LITTLE_ENDIAN).putInt(22,8);
        assertThatThrownBy(()->read(cyclic,"tiff",2)).isInstanceOf(IOException.class);
        byte[] count=OrientationFixtures.tiffProfile(1,true);count[8]=1;count[9]=16;
        assertThatThrownBy(()->read(count,"tiff",1)).isInstanceOf(IOException.class);
    }
    @Test void rejectsPngExifCrcMismatchAndOversizedChunks() throws Exception {
        byte[] png=OrientationFixtures.png(6);png[70]^=1; // within EXIF CRC
        assertThatThrownBy(()->read(png,"png",1)).isInstanceOf(IOException.class);
        byte[] oversized=OrientationFixtures.png(6);ByteBuffer.wrap(oversized).putInt(33,Integer.MAX_VALUE);
        assertThatThrownBy(()->read(oversized,"png",1)).isInstanceOf(IOException.class);
    }
    @Test void bigTiffAndUnknownFormatsNeverClaimVerifiedOrientation() throws Exception {
        byte[] big={'I','I',43,0,8,0,0,0};
        assertThat(read(big,"tiff",1)).isNull();
        assertThat(read(big,"heif",1)).isNull();
    }
}
