package co.kr.satu.imagegate.conversion.util;

import static org.assertj.core.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.*;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

class TilePngSanitizerTests {
    static java.util.stream.Stream<Arguments> codecFixtures()throws IOException{
        return PngCodecProfileTests.fixtures().stream().map(f->Arguments.of(f.name(),f));
    }
    @ParameterizedTest(name="{0}") @MethodSource("codecFixtures")
    void ownedBufferMatchesReferenceBytesAndSurvivesDiagnosticFailures(String name,PngCodecProfileTests.Fixture fixture)throws Exception{
        byte[] before=fixture.input().clone();var stages=new java.util.ArrayList<TileTiming.Stage>();
        byte[] result=TilePngSanitizer.normalize(fixture.input(),fixture.width(),fixture.height(),(s,n)->{stages.add(s);throw new IllegalStateException("observer");});
        assertThat(result).isEqualTo(PngCodecProfileTests.encode(fixture,PngCodecProfileTests.Candidate.REFERENCE_COPY));PngCodecProfileTests.verifyPixels(fixture,result);
        assertThat(fixture.input()).isEqualTo(before);assertThat(stages).containsExactly(TileTiming.Stage.PNG_DECODE,TileTiming.Stage.PNG_COPY,TileTiming.Stage.PNG_ENCODE,TileTiming.Stage.PNG_OUTPUT_CHECK);
    }
    @Test void preservesInvisibleRgbAndLowAlphaExactly()throws Exception{
        var image=new BufferedImage(3,2,BufferedImage.TYPE_INT_ARGB);int[] pixels={0x00123456,0x01020304,0x7fabcdef,0x80aabbcc,0xffffffff,0xff000000};
        try{image.setRGB(0,0,3,2,pixels,0,3);byte[] input=PngCodecProfileTests.png(image,true);var fixture=new PngCodecProfileTests.Fixture("alpha-sentinels",3,2,pixels,input);
            byte[] result=TilePngSanitizer.normalize(input,3,2);PngCodecProfileTests.verifyPixels(fixture,result);assertThat(result).isEqualTo(PngCodecProfileTests.encode(fixture,PngCodecProfileTests.Candidate.REFERENCE_COPY));
        }finally{image.flush();}
    }
    @Test void removesTextWhilePreservingRgbAndAlphaPixels() throws Exception {
        var image=new BufferedImage(3,2,BufferedImage.TYPE_INT_ARGB);
        int[] pixels={0,0x11223344,0xffabcdef,0xffffffff,0x80706050,0xff000000};image.setRGB(0,0,3,2,pixels,0,3);
        var writer=ImageIO.getImageWritersByFormatName("PNG").next();var bytes=new ByteArrayOutputStream();
        try(var output=new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(output);var param=writer.getDefaultWriteParam();
            var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param);
            var tree=(IIOMetadataNode)metadata.getAsTree("javax_imageio_png_1.0");var text=new IIOMetadataNode("tEXt");var entry=new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword","Comment");entry.setAttribute("value","private-stamp-source-gps");text.appendChild(entry);tree.appendChild(text);
            metadata.setFromTree("javax_imageio_png_1.0",tree);writer.write(null,new IIOImage(image,null,metadata),param);
        } finally {writer.dispose();image.flush();}
        byte[] nativePng=bytes.toByteArray();assertThat(new String(nativePng,java.nio.charset.StandardCharsets.ISO_8859_1)).contains("private-stamp-source-gps");
        byte[] clean=TilePngSanitizer.normalize(nativePng,3,2);TilePngSanitizer.verifyClean(clean);
        assertThat(new String(clean,java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("private-stamp-source-gps");
        var decoded=ImageIO.read(new ByteArrayInputStream(clean));
        try { assertThat(decoded.getRGB(0,0,3,2,null,0,3)).containsExactly(pixels); } finally {decoded.flush();}
    }
    @Test void rejectsUnsupportedSizeAndCorruption() {
        assertThatThrownBy(()->TilePngSanitizer.normalize(new byte[100],257,1)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->TilePngSanitizer.normalize(new byte[100],1,1)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->TilePngSanitizer.normalize(new byte[2097153],1,1)).isInstanceOf(IOException.class);
    }
    @Test void boundedDecodeRejectsOversizedDimensionsAndInputBeforeDecoding(){
        assertThatThrownBy(()->TilePngVerifier.decode(new byte[100],40000000,1)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->TilePngVerifier.decode(new byte[2097153],1,1)).isInstanceOf(IOException.class);
    }
    @Test void validCrcCannotHideInvalidCompressedRaster()throws Exception{
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();
        try{ImageIO.write(image,"PNG",out);}finally{image.flush();}
        byte[] bytes=out.toByteArray();var buffer=java.nio.ByteBuffer.wrap(bytes);buffer.position(8);
        while(buffer.hasRemaining()){
            int size=buffer.getInt(),start=buffer.position(),type=buffer.getInt(),content=buffer.position();
            if(type==0x49444154){java.util.Arrays.fill(bytes,content,content+size,(byte)0);var crc=new java.util.zip.CRC32();crc.update(bytes,start,size+4);buffer.putInt(content+size,(int)crc.getValue());break;}
            buffer.position(content+size+4);
        }
        assertThatThrownBy(()->TilePngVerifier.decode(bytes,1,1)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->TilePngSanitizer.normalize(bytes,1,1)).isInstanceOf(IOException.class);
    }
}
