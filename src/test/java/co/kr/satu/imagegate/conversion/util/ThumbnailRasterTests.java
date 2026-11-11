package co.kr.satu.imagegate.conversion.util;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThumbnailRasterTests {
    @TempDir Path root;
    @Test void boundsLandscapeAndPortraitWithoutUpscaling() throws Exception {
        for(int[] dims:new int[][]{{1024,768,256,192},{100,400,64,256},{31,17,31,17}}){
            var input=new BufferedImage(dims[0],dims[1],BufferedImage.TYPE_INT_ARGB);
            var result=ThumbnailRaster.write(input,root.resolve("test-"+dims[0]+".png"),256);
            assertThat(result.width()).isEqualTo(dims[2]);assertThat(result.height()).isEqualTo(dims[3]);
            var image=ImageIO.read(result.png().toFile());assertThat(image.getWidth()).isEqualTo(dims[2]);assertThat(image.getHeight()).isEqualTo(dims[3]);
        }
    }
    @Test void preservesAlphaAndDoesNotModifyInput() throws Exception {
        var input=new BufferedImage(20,20,BufferedImage.TYPE_INT_ARGB);input.setRGB(1,1,0x80ff0000);
        var result=ThumbnailRaster.write(input,root.resolve("alpha.png"),256);var decoded=ImageIO.read(result.png().toFile());
        assertThat(decoded.getRGB(0,0)>>>24).isZero();assertThat(decoded.getRGB(1,1)>>>24).isEqualTo(128);
        assertThat(input.getRGB(1,1)).isEqualTo(0x80ff0000);
    }
    @Test void rejectsUnsupportedBounds() {
        var input=new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB);
        for(int edge:new int[]{0,63,513})assertThatThrownBy(()->ThumbnailRaster.write(input,root.resolve("bad.png"),edge)).isInstanceOf(java.io.IOException.class);
    }

    @Test void cancelledInputDoesNotCreateOutputOrFlushBorrowedPreview() throws Exception {
        var input=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB){
            @Override public int getWidth(){throw new AssertionError("Cancelled input inspected");}
            @Override public void flush(){throw new AssertionError("Borrowed input flushed");}
        };
        try{
            Thread.currentThread().interrupt();
            assertThatThrownBy(()->ThumbnailRaster.write(input,root.resolve("cancel.png"),256)).isInstanceOf(java.io.InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
        assertThat(root.resolve("cancel.png")).doesNotExist();
    }
    @Test void cancellationDuringBoundsStopsEncodingWithoutFlushingInput() throws Exception {
        var input=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB){
            @Override public int getWidth(){Thread.currentThread().interrupt();return super.getWidth();}
            @Override public void flush(){throw new AssertionError("Borrowed input flushed");}
        };
        try{
            assertThatThrownBy(()->ThumbnailRaster.write(input,root.resolve("cancel.png"),256)).isInstanceOf(java.io.InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
        assertThat(root.resolve("cancel.png")).doesNotExist();
    }
    @Test void cancellationAfterThumbnailWriteDoesNotReturnAnArtifact() throws Exception {
        var input=new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB);
        Path output=root.resolve("cancel.png");
        try(var io=org.mockito.Mockito.mockStatic(ImageIO.class)){
            io.when(()->ImageIO.write(org.mockito.ArgumentMatchers.any(java.awt.image.RenderedImage.class),
                    org.mockito.ArgumentMatchers.eq("png"),org.mockito.ArgumentMatchers.any(java.io.File.class))).thenAnswer(c->{
                Files.write(output,new byte[]{1,2,3});Thread.currentThread().interrupt();return true;
            });
            assertThatThrownBy(()->ThumbnailRaster.write(input,output,256)).isInstanceOf(java.io.InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
        assertThat(output).exists();assertThat(input.getWidth()).isEqualTo(16);
    }
}
