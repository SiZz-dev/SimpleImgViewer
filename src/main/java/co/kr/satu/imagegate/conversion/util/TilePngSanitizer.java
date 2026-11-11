package co.kr.satu.imagegate.conversion.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.*;
import javax.imageio.*;
import javax.imageio.stream.*;

/** Creates a fresh RGBA8 raster and default PNG metadata, one bounded tile at a time. */
public final class TilePngSanitizer {
    private TilePngSanitizer() {}
    public static byte[] normalize(byte[] input,int width,int height) throws IOException {
        return normalize(input,width,height,null);
    }
    public static byte[] normalize(byte[] input,int width,int height,TileTiming timing) throws IOException {
        if(input.length>2_097_152 || width<1 || height<1 || width>256 || height>256)
            throw new IOException("Invalid tile normalization bounds");
        BufferedImage decoded=null,clean=null;
        try {
            try(var span=TileTiming.start(timing,TileTiming.Stage.PNG_DECODE)){decoded=TilePngVerifier.decode(input,width,height);}
            PreviewCancellation.check();
            try(var span=TileTiming.start(timing,TileTiming.Stage.PNG_COPY)){
                clean=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
                // A new standard ARGB raster owns this buffer; getRGB supplies the same straight-alpha sRGB values.
                // No decoded/native buffer is shared, and no temporary pixel array or second setRGB pass is needed.
                decoded.getRGB(0,0,width,height,((DataBufferInt)clean.getRaster().getDataBuffer()).getData(),0,width);
            }
            var bytes=new ByteArrayOutputStream();
            try(var span=TileTiming.start(timing,TileTiming.Stage.PNG_ENCODE)){
            var writer=ImageIO.getImageWritersByFormatName("PNG").next();
            try(var output=new MemoryCacheImageOutputStream(new FilterOutputStream(bytes) {
                int count;
                @Override public void write(int b) throws IOException { reserve(1);out.write(b); }
                @Override public void write(byte[] b,int off,int len) throws IOException { reserve(len);out.write(b,off,len); }
                void reserve(int length) throws IOException { if(length>2_097_152-count)throw new IOException("Tile output limit");count+=length; }
            })) {
                writer.setOutput(output); writer.write(null,new IIOImage(clean,null,null),writer.getDefaultWriteParam());
            } finally { writer.dispose(); }
            }
            PreviewCancellation.check();
            try(var span=TileTiming.start(timing,TileTiming.Stage.PNG_OUTPUT_CHECK)){
                byte[] result=bytes.toByteArray(); TilePngVerifier.verify(result,width,height); verifyClean(result);return result;
            }
        } finally { if(decoded!=null)decoded.flush();if(clean!=null)clean.flush(); }
    }
    /** Called only after structural/CRC validation. Public cache PNGs contain no ancillary chunks. */
    public static void verifyClean(byte[] bytes) throws IOException {
        var data=java.nio.ByteBuffer.wrap(bytes);data.position(8);
        while(data.hasRemaining()) {
            int length=data.getInt(),type=data.getInt();
            if(type!=0x49484452 && type!=0x49444154 && type!=0x49454e44)throw new IOException("Unexpected normalized PNG metadata");
            data.position(data.position()+length+4);
        }
    }
}
