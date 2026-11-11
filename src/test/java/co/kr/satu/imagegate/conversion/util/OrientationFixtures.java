package co.kr.satu.imagegate.conversion.util;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import java.util.zip.CRC32;
import javax.imageio.*;
import javax.imageio.plugins.tiff.*;

public final class OrientationFixtures {
    public static final int[] COLORS={Color.RED.getRGB(),Color.GREEN.getRGB(),Color.BLUE.getRGB(),
            Color.CYAN.getRGB(),Color.MAGENTA.getRGB(),Color.YELLOW.getRGB()};
    private OrientationFixtures() {}
    public static BufferedImage raster() {
        var image=new BufferedImage(60,40,BufferedImage.TYPE_INT_RGB);
        var g=image.createGraphics();
        try { for(int i=0;i<6;i++) { g.setColor(new Color(COLORS[i]));g.fillRect(i%3*20,i/3*20,20,20); } }
        finally { g.dispose(); }
        return image;
    }
    public static byte[] tiffProfile(int orientation,boolean little) {
        var b=ByteBuffer.allocate(26).order(little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
        b.put((byte)(little?'I':'M')).put((byte)(little?'I':'M')).putShort((short)42).putInt(8);
        b.putShort((short)1).putShort((short)274).putShort((short)3).putInt(1).putShort((short)orientation);
        b.putShort((short)0).putInt(0);return b.array();
    }
    public static byte[] jpeg(int orientation) throws IOException {
        var out=new ByteArrayOutputStream();ImageIO.write(raster(),"jpeg",out);
        return addExif(out.toByteArray(),tiffProfile(orientation,true));
    }
    public static byte[] addExif(byte[] jpeg,byte[] profile) throws IOException {
        var out=new ByteArrayOutputStream();var data=new DataOutputStream(out);
        data.write(jpeg,0,2);data.writeShort(0xffe1);data.writeShort(profile.length+8);
        data.write(new byte[]{'E','x','i','f',0,0});data.write(profile);
        data.write(jpeg,2,jpeg.length-2);return out.toByteArray();
    }
    public static byte[] png(int orientation) throws IOException {
        var out=new ByteArrayOutputStream();ImageIO.write(raster(),"png",out);
        byte[] png=out.toByteArray(),profile=tiffProfile(orientation,false);
        var result=new ByteArrayOutputStream();var data=new DataOutputStream(result);
        data.write(png,0,33); // after IHDR
        data.writeInt(profile.length);byte[] type={'e','X','I','f'};
        data.write(type);data.write(profile);
        var crc=new CRC32();crc.update(type);crc.update(profile);data.writeInt((int)crc.getValue());
        data.write(png,33,png.length-33);return result.toByteArray();
    }
    public static byte[] tiff(int... orientations) throws IOException {
        var out=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("TIFF").next();
        try(var stream=ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);writer.prepareWriteSequence(null);
            for(int orientation:orientations) {
                var image=raster();var param=writer.getDefaultWriteParam();
                var metadata=writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image),param);
                var directory=TIFFDirectory.createFromMetadata(metadata);
                var tag=BaselineTIFFTagSet.getInstance().getTag(BaselineTIFFTagSet.TAG_ORIENTATION);
                directory.addTIFFField(new TIFFField(tag,TIFFTag.TIFF_SHORT,1,new char[]{(char)orientation}));
                writer.writeToSequence(new IIOImage(image,null,directory.getAsMetadata()),param);
            }
            writer.endWriteSequence();
        } finally { writer.dispose(); }
        return out.toByteArray();
    }
}
