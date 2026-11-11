package co.kr.satu.imagegate.editing.util;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.editing.domain.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditRasterTests {
    BufferedImage image(int w,int h,int color) {
        var image=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)image.setRGB(x,y,color);return image;
    }
    AnnotationItem item(AnnotationItem.Type type,double x,double y,double w,double h,String text) {
        return new AnnotationItem("item",type,x,y,w,h,text,type==AnnotationItem.Type.STAMP?"stamp":null,
                type==AnnotationItem.Type.STAMP?"owner":null,Instant.EPOCH,Instant.EPOCH);
    }
    @Test void brightnessChangesBaseOnlyAndPreservesTransparentStampPixels() throws Exception {
        var base=image(20,20,0xff283c50);
        var stamp=image(4,4,0x00000000);stamp.setRGB(1,1,0xff00ff00);
        var output=EditRaster.compose(base,new CropArea(10,10,20,20),150,0,
                List.of(item(AnnotationItem.Type.STAMP,12,12,4,4,null)),i->stamp);
        assertThat(output.getRGB(0,0)).isEqualTo(0xff3c5a78);
        assertThat(output.getRGB(2,2)).isEqualTo(0xff3c5a78);
        assertThat(output.getRGB(3,3)).isEqualTo(0xff00ff00);
    }
    @Test void brightnessPreservesAlphaAndClampsComponents() throws Exception {
        var base=image(1,1,0x80c86432);
        var output=EditRaster.compose(base,new CropArea(0,0,1,1),200,0,List.of(),i->{throw new AssertionError();});
        assertThat(output.getRGB(0,0)).isEqualTo(0x80ffc864);
    }
    @ParameterizedTest @ValueSource(ints={0,90,180,270})
    void rotatesAllPixelsAfterCompositionWithIndependentCornerOracle(int rotation) throws Exception {
        var base=image(3,2,Color.BLACK.getRGB());
        int[] colors={Color.RED.getRGB(),Color.GREEN.getRGB(),Color.BLUE.getRGB(),
                Color.CYAN.getRGB(),Color.MAGENTA.getRGB(),Color.YELLOW.getRGB()};
        for(int i=0;i<6;i++)base.setRGB(i%3,i/3,colors[i]);
        var output=EditRaster.compose(base,new CropArea(0,0,3,2),100,rotation,List.of(),i->{throw new AssertionError();});
        assertThat(output.getWidth()).isEqualTo(rotation%180==0?3:2);
        assertThat(output.getHeight()).isEqualTo(rotation%180==0?2:3);
        int expected=switch(rotation){case 0->0;case 90->3;case 180->5;default->2;};
        assertThat(output.getRGB(0,0)).isEqualTo(colors[expected]);
        for(int i=0;i<6;i++){
            int x=i%3,y=i/3;
            int ox=switch(rotation){case 90->1-y;case 180->2-x;case 270->y;default->x;};
            int oy=switch(rotation){case 90->x;case 180->1-y;case 270->2-x;default->y;};
            assertThat(output.getRGB(ox,oy)).isEqualTo(colors[i]);
        }
    }
    @Test void notesAreClippedAndUntrustedTextIsRenderedAsText() throws Exception {
        var base=image(180,80,Color.WHITE.getRGB());
        var note=item(AnnotationItem.Type.NOTE,20,10,120,50,"검토 메모\n<img src=x>");
        var output=EditRaster.compose(base,new CropArea(0,0,180,80),100,0,List.of(note),i->{throw new AssertionError();});
        assertThat(output.getRGB(0,0)).isEqualTo(Color.WHITE.getRGB());
        assertThat(output.getRGB(141,30)).isEqualTo(Color.WHITE.getRGB());
        int dark=0;
        for(int y=12;y<58;y++)for(int x=22;x<138;x++)if((output.getRGB(x,y)&0xff)<100)dark++;
        assertThat(dark).isGreaterThan(10);
    }
    @Test void offRegionStampsAreNotLoadedAndPartialOnesAreClipped() throws Exception {
        var base=image(10,10,Color.WHITE.getRGB());
        var outside=item(AnnotationItem.Type.STAMP,0,0,10,10,null);
        var partial=item(AnnotationItem.Type.STAMP,18,18,10,10,null);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var output=EditRaster.compose(base,new CropArea(20,20,10,10),100,0,List.of(outside,partial),
                i->{calls.incrementAndGet();return image(10,10,Color.RED.getRGB());});
        assertThat(calls.get()).isEqualTo(1);
        assertThat(output.getRGB(0,0)).isEqualTo(Color.RED.getRGB());
        assertThat(output.getRGB(9,9)).isEqualTo(Color.WHITE.getRGB());
    }

    @Test void missingGlyphFailsRatherThanSilentlyReplacingMemoContent() {
        var note=item(AnnotationItem.Type.NOTE,0,0,120,50,"\uffff");
        assertThatThrownBy(()->EditRaster.compose(image(120,50,Color.WHITE.getRGB()),new CropArea(0,0,120,50),100,0,
                List.of(note),i->{throw new AssertionError();}))
                .isInstanceOfSatisfying(co.kr.satu.imagegate.editing.exception.EditException.class,
                    e->assertThat(e.code()).isEqualTo(co.kr.satu.imagegate.editing.exception.EditException.Code.EXPORT_FONT_UNAVAILABLE));
    }
}
