package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.conversion.util.OrientationFixtures;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.*;
import java.nio.file.*;
import java.util.stream.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class ImageOrientationRenderingTests {
    @TempDir Path directory;
    static final int[][] EXPECTED={
        {0,1,2,3,4,5},{2,1,0,5,4,3},{5,4,3,2,1,0},{3,4,5,0,1,2},
        {0,3,1,4,2,5},{3,0,4,1,5,2},{5,2,4,1,3,0},{2,5,1,4,0,3}
    };
    static Stream<Arguments> cases() {
        return IntStream.rangeClosed(1,8).boxed().flatMap(o->Stream.of("jpeg","png","tiff").map(f->Arguments.of(f,o)));
    }
    PreviewRenderer.Result render(byte[] bytes,String name,int page,String work) throws Exception {
        var store=new LocalMediaStore(directory.resolve("originals"),1000000);
        var media=store.register("tenant","alice",name,new ByteArrayInputStream(bytes));
        var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenReturn(true);
        var renderer=new ImagePreviewRenderer(store,(a,m,action)->true,features,
                new Preview(true,1,1,30,30,100000,"v1"),null);
        return renderer.render(media,new ViewerPrincipal("tenant","alice"),page,directory.resolve(work));
    }
    @ParameterizedTest @MethodSource("cases")
    void normalizesAllOrientationsAndPreservesOriginalGeometry(String format,int orientation) throws Exception {
        byte[] bytes=switch(format) {
            case "jpeg" -> OrientationFixtures.jpeg(orientation);
            case "png" -> OrientationFixtures.png(orientation);
            default -> OrientationFixtures.tiff(orientation);
        };
        var result=render(bytes,"image."+format,1,"work");
        var geometry=result.geometry();
        assertThat(geometry).isEqualTo(new ImageGeometry(60,40,orientation));
        int columns=orientation<=4 ? 3 : 2,rows=orientation<=4 ? 2 : 3;
        assertThat(result.width()).isEqualTo(columns*10);assertThat(result.height()).isEqualTo(rows*10);
        var output=ImageIO.read(result.png().toFile());
        for(int i=0;i<6;i++) {
            int actual=output.getRGB(i%columns*10+5,i/columns*10+5);
            int expected=OrientationFixtures.COLORS[EXPECTED[orientation-1][i]];
            for(int shift:new int[]{0,8,16})
                assertThat((actual>>shift)&255).as("%s orientation=%s cell=%s channel=%s",format,orientation,i,shift)
                        .isCloseTo((expected>>shift)&255,within(format.equals("jpeg") ? 35 : 0));
        }
        assertThat(new String(Files.readAllBytes(result.png()),java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("eXIf");
    }
    @Test void readsPageSpecificOrientationWithoutApplyingFirstPageMetadataToSecond() throws Exception {
        byte[] bytes=OrientationFixtures.tiff(6,8);
        var first=render(bytes,"two.tif",1,"page1");
        var second=render(bytes,"two.tif",2,"page2");
        assertThat(first.geometry().orientation()).isEqualTo(6);
        assertThat(second.geometry().orientation()).isEqualTo(8);
        assertThat(ImageIO.read(first.png().toFile()).getRGB(5,5)).isEqualTo(OrientationFixtures.COLORS[3]);
        assertThat(ImageIO.read(second.png().toFile()).getRGB(5,5)).isEqualTo(OrientationFixtures.COLORS[2]);
    }
}
