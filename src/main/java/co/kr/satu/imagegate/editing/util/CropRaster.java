package co.kr.satu.imagegate.editing.util;

import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.conversion.util.ImageOrientation;
import co.kr.satu.imagegate.editing.domain.CropArea;
import co.kr.satu.imagegate.editing.exception.EditException;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Decode verified original pixels, orient and crop in a single destination raster. No preview upscaling. */
public final class CropRaster {
    private CropRaster() {}
    @FunctionalInterface public interface Transform {BufferedImage apply(BufferedImage image) throws IOException;}
    public static void render(Path input,int page,ImageGeometry expected,CropArea area,
            long maxSourcePixels,long maxOutputBytes,Path output) throws IOException {
        render(input,page,expected,area,maxSourcePixels,maxOutputBytes,output,image->image);
    }
    public static void render(Path input,int page,ImageGeometry expected,CropArea area,
            long maxSourcePixels,long maxOutputBytes,Path output,Transform transform) throws IOException {
        BufferedImage raw;
        try(var stream=new FileImageInputStream(input.toFile())) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())throw new EditException(EditException.Code.INVALID_CROP);
            var reader=readers.next();
            try {
                if(!"java.desktop".equals(reader.getClass().getModule().getName()))
                    throw new EditException(EditException.Code.EDIT_NOT_READY);
                reader.setInput(stream,false,true);
                int index=page-1,w=reader.getWidth(index),h=reader.getHeight(index);
                if(w<1||h<1||(long)w*h>maxSourcePixels)throw new EditException(EditException.Code.CROP_TOO_LARGE);
                Integer orientation=ImageOrientation.read(input,reader.getFormatName(),page);
                if(orientation==null||!expected.equals(new ImageGeometry(w,h,orientation)))
                    throw new EditException(EditException.Code.EDIT_CONFLICT);
                raw=reader.read(index);
                if(raw==null||raw.getWidth()!=w||raw.getHeight()!=h)throw new EditException(EditException.Code.INVALID_CROP);
            } finally {reader.dispose();}
        } catch(javax.imageio.IIOException|IndexOutOfBoundsException invalid) {
            throw new EditException(EditException.Code.INVALID_CROP,invalid);
        }
        var cropped=new BufferedImage(area.width(),area.height(),BufferedImage.TYPE_INT_ARGB);
        var g=cropped.createGraphics();
        try {
            g.translate(-area.x(),-area.y());g.transform(ImageOrientation.encodedToOriginal(expected));
            g.drawImage(raw,0,0,null);
        } finally {g.dispose();raw.flush();}
        BufferedImage result=cropped;
        try {
        result=transform.apply(cropped);
        try(var file=Files.newOutputStream(output,StandardOpenOption.CREATE_NEW);
            var bounded=new LimitedOutput(file,maxOutputBytes);
            var encoded=new MemoryCacheImageOutputStream(bounded)) {
            var writer=ImageIO.getImageWritersByFormatName("png").next();
            try {writer.setOutput(encoded);writer.write(result);encoded.flush();}
            finally {writer.dispose();}
        }
        } finally {if(result!=cropped)result.flush();cropped.flush();}
    }
    private static final class LimitedOutput extends FilterOutputStream {
        private final long max;private long count;
        LimitedOutput(OutputStream output,long max){super(output);this.max=max;}
        private void accept(int n) {
            if(n>max-count)throw new EditException(EditException.Code.CROP_TOO_LARGE);
            count+=n;
        }
        @Override public void write(int b) throws IOException {accept(1);out.write(b);}
        @Override public void write(byte[] b,int off,int len) throws IOException {accept(len);out.write(b,off,len);}
    }
}
