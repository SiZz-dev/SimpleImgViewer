package co.kr.satu.imagegate.conversion.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Checks a bounded RGB/RGBA8 PNG and decodes one tile, without writing files or retaining its raster. */
public final class TilePngVerifier {
    private static final byte[] SIGNATURE = {(byte)137,80,78,71,13,10,26,10};
    private TilePngVerifier() {}
    public static void verify(byte[] bytes, int width, int height) throws IOException {
        var image=decode(bytes,width,height);image.flush();
    }
    /** CRC/structure and dimensions are checked before decoding. Caller owns and must flush the returned raster. */
    public static java.awt.image.BufferedImage decode(byte[] bytes,int width,int height)throws IOException {
        if(width<1||height<1||width>256||height>256||bytes.length>2_097_152)invalid();
        if(bytes.length < 57 || !Arrays.equals(Arrays.copyOf(bytes,8),SIGNATURE)) invalid();
        var data=ByteBuffer.wrap(bytes); data.position(8);
        boolean header=false, pixels=false, endedPixels=false, end=false;
        int chunks=0;
        while(data.remaining()>0) {
            if(++chunks>256 || data.remaining()<12 || end) invalid();
            int length=data.getInt(), typeStart=data.position();
            if(length<0 || length>data.remaining()-8) invalid();
            int type=data.getInt(), content=data.position();
            for(int i=typeStart;i<typeStart+4;i++) {
                int c=bytes[i]&255;
                if(!((c>='A'&&c<='Z')||(c>='a'&&c<='z'))) invalid();
            }
            if((bytes[typeStart+2]&32)!=0) invalid();
            var crc=new CRC32(); crc.update(bytes,typeStart,length+4);
            if((int)crc.getValue()!=data.getInt(content+length)) invalid();
            if(!header && type!=0x49484452) invalid();
            switch(type) {
                case 0x49484452 -> { // IHDR
                    if(header || length!=13 || data.getInt(content)!=width || data.getInt(content+4)!=height
                            || bytes[content+8]!=8 || (bytes[content+9]!=2&&bytes[content+9]!=6)
                            || bytes[content+10]!=0 || bytes[content+11]!=0 || bytes[content+12]!=0) invalid();
                    header=true;
                }
                case 0x49444154 -> { if(endedPixels) invalid(); pixels=true; } // IDAT
                case 0x49454e44 -> { if(length!=0 || !pixels) invalid(); end=true; } // IEND
                case 0x504c5445 -> { if(pixels || length<3 || length>768 || length%3!=0) invalid(); }
                default -> { if((bytes[typeStart]&32)==0) invalid(); }
            }
            if(pixels && type!=0x49444154) endedPixels=true;
            data.position(content+length+4);
        }
        if(!end) invalid();
        PreviewCancellation.check();
        var readers=ImageIO.getImageReadersByFormatName("PNG");
        if(!readers.hasNext()) invalid();
        var reader=readers.next();
        java.awt.image.BufferedImage image=null;boolean accepted=false;
        try {
            try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                reader.setInput(input,true,true);
                if(reader.getWidth(0)!=width || reader.getHeight(0)!=height) invalid();
                image=reader.read(0);
                if(image==null) invalid();
                if(image.getWidth()!=width || image.getHeight()!=height) invalid();
            }
            PreviewCancellation.check();accepted=true;return image;
        } finally { reader.dispose();if(!accepted&&image!=null)image.flush(); }
    }
    private static void invalid() throws IOException { throw new IOException("Invalid tile PNG"); }
}
