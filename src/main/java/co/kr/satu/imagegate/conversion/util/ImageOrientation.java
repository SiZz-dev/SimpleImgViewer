package co.kr.satu.imagegate.conversion.util;

import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import java.awt.geom.AffineTransform;
import java.io.*;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.CRC32;
import javax.imageio.stream.*;

/** Bounded orientation-only parser. No XML, external references, or recursive IFD traversal. */
public final class ImageOrientation {
    private static final long JPEG_SCAN_LIMIT=16L*1024*1024;
    private ImageOrientation() {}

    /** null means the format's original orientation has not been verified. */
    public static Integer read(Path input,String format,int page) throws IOException {
        try(var stream=new FileImageInputStream(input.toFile())) {
            return switch(format.toLowerCase(Locale.ROOT)) {
                case "jpeg","jpg" -> jpeg(stream);
                case "png" -> png(stream);
                case "tiff","tif" -> tiff(stream,stream.length(),page);
                case "bmp","gif" -> 1;
                default -> null;
            };
        }
    }

    private static int jpeg(ImageInputStream stream) throws IOException {
        if(stream.readUnsignedShort()!=0xffd8) throw invalid();
        Integer orientation=null;
        for(int markers=0;markers<4096;markers++) {
            if(stream.getStreamPosition()>JPEG_SCAN_LIMIT || stream.readUnsignedByte()!=255) throw invalid();
            int marker,fill=0;
            do {
                if(++fill>16) throw invalid();
                marker=stream.readUnsignedByte();
            } while(marker==255);
            if(marker==0xda || marker==0xd9) return orientation==null ? 1 : orientation;
            if(marker==0 || marker==0xd8) throw invalid();
            if(marker==1 || (marker>=0xd0 && marker<=0xd7)) continue;
            int length=stream.readUnsignedShort()-2;
            long start=stream.getStreamPosition();
            range(start,length,Math.min(stream.length(),JPEG_SCAN_LIMIT));
            if(marker==0xe1 && length>=6) {
                byte[] header=new byte[6];stream.readFully(header);
                if(java.util.Arrays.equals(header,new byte[]{'E','x','i','f',0,0})) {
                    if(orientation!=null) throw invalid();
                    byte[] data=new byte[length-6];stream.readFully(data);
                    orientation=embedded(data);
                }
            }
            stream.seek(start+length);
        }
        throw invalid();
    }

    private static int png(ImageInputStream stream) throws IOException {
        if(stream.readLong()!=0x89504e470d0a1a0aL) throw invalid();
        Integer orientation=null;
        for(int chunks=0;chunks<4096;chunks++) {
            long length=Integer.toUnsignedLong(stream.readInt());
            int type=stream.readInt();
            long start=stream.getStreamPosition();
            range(start,length+4,stream.length());
            if(type==0x65584966) { // eXIf: TIFF bytes, without JPEG's Exif prefix
                if(orientation!=null || length>1024*1024) throw invalid();
                byte[] data=new byte[(int)length];stream.readFully(data);
                var crc=new CRC32();crc.update(new byte[]{'e','X','I','f'});crc.update(data);
                if(crc.getValue()!=Integer.toUnsignedLong(stream.readInt())) throw invalid();
                orientation=embedded(data);
            }
            if(type==0x49454e44) {
                if(length!=0) throw invalid();
                return orientation==null ? 1 : orientation;
            }
            stream.seek(start+length+4);
        }
        throw invalid();
    }

    private static int embedded(byte[] data) throws IOException {
        try(var stream=new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Integer orientation=tiff(stream,data.length,1);
            if(orientation==null) throw invalid();
            return orientation;
        }
    }

    private static Integer tiff(ImageInputStream stream,long length,int page) throws IOException {
        range(0,8,length);
        int order=stream.readUnsignedShort();
        if(order!=0x4949 && order!=0x4d4d) throw invalid();
        stream.setByteOrder(order==0x4949 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        int magic=stream.readUnsignedShort();
        if(magic==43) return null; // BigTIFF is deliberately not interpreted as classic TIFF.
        if(magic!=42 || page<1 || page>10000) throw invalid();
        long offset=Integer.toUnsignedLong(stream.readInt());
        var visited=new HashSet<Long>();long entriesRead=0;
        for(int index=1;index<=page;index++) {
            if(offset<8 || !visited.add(offset)) throw invalid();
            range(offset,2,length);stream.seek(offset);
            int count=stream.readUnsignedShort();
            if(count>4096 || (entriesRead+=count)>262144) throw invalid();
            range(offset+2,12L*count+4,length);
            Integer orientation=null;
            for(int i=0;i<count;i++) {
                long entry=offset+2+12L*i;stream.seek(entry);
                int tag=stream.readUnsignedShort();
                if(tag!=274) continue;
                if(orientation!=null || stream.readUnsignedShort()!=3 || stream.readUnsignedInt()!=1) throw invalid();
                int value=stream.readUnsignedShort();
                if(value<1 || value>8) throw invalid();
                orientation=value;
            }
            if(index==page) return orientation==null ? 1 : orientation;
            stream.seek(offset+2+12L*count);
            offset=Integer.toUnsignedLong(stream.readInt());
        }
        throw invalid();
    }

    private static void range(long start,long count,long length) throws IOException {
        if(start<0 || count<0 || start>length || count>length-start) throw invalid();
    }
    private static IOException invalid() { return new IOException("Invalid or excessive orientation metadata"); }

    /** Maps encoded pixel-edge coordinates to upright original pixel-edge coordinates. */
    public static AffineTransform encodedToOriginal(ImageGeometry geometry) {
        int w=geometry.encodedWidth(),h=geometry.encodedHeight();
        return switch(geometry.orientation()) {
            case 1 -> new AffineTransform();
            case 2 -> new AffineTransform(-1,0,0,1,w,0);
            case 3 -> new AffineTransform(-1,0,0,-1,w,h);
            case 4 -> new AffineTransform(1,0,0,-1,0,h);
            case 5 -> new AffineTransform(0,1,1,0,0,0);
            case 6 -> new AffineTransform(0,1,-1,0,h,0);
            case 7 -> new AffineTransform(0,-1,-1,0,h,w);
            case 8 -> new AffineTransform(0,-1,1,0,0,w);
            default -> throw new IllegalArgumentException("Invalid orientation");
        };
    }
}
