package co.kr.satu.imagegate.editing.util;

import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.conversion.util.ImageOrientation;
import co.kr.satu.imagegate.editing.exception.EditException;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;

public final class StampSanitizer {
    public record Dimensions(int width,int height,int previewWidth,int previewHeight) {}
    private StampSanitizer() {}
    public static Dimensions sanitize(Path input,Path output,Path preview,long maxPixels) throws IOException {
        BufferedImage raw;ImageGeometry geometry;
        try(var stream=new FileImageInputStream(input.toFile())) {
            var readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())throw new EditException(EditException.Code.UNSUPPORTED_STAMP);
            var reader=readers.next();
            try {
                reader.setInput(stream,false,true);String format=reader.getFormatName().toLowerCase(java.util.Locale.ROOT);
                if(!java.util.Set.of("png","jpeg","jpg").contains(format)
                        ||!"java.desktop".equals(reader.getClass().getModule().getName()))
                    throw new EditException(EditException.Code.UNSUPPORTED_STAMP);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||w>2048||h>2048||(long)w*h>maxPixels)throw new EditException(EditException.Code.STAMP_TOO_LARGE);
                Integer orientation;
                try {orientation=ImageOrientation.read(input,format,1);}
                catch(IOException malformed){throw new EditException(EditException.Code.INVALID_STAMP,malformed);}
                if(orientation==null)throw new EditException(EditException.Code.INVALID_STAMP);
                geometry=new ImageGeometry(w,h,orientation);raw=reader.read(0);
                if(raw==null||raw.getWidth()!=w||raw.getHeight()!=h)throw new EditException(EditException.Code.INVALID_STAMP);
            } finally {reader.dispose();}
        }
        var image=new BufferedImage(geometry.coordinateWidth(),geometry.coordinateHeight(),BufferedImage.TYPE_INT_ARGB);
        var graphics=image.createGraphics();
        try {graphics.transform(ImageOrientation.encodedToOriginal(geometry));graphics.drawImage(raw,0,0,null);}
        finally {graphics.dispose();raw.flush();}
        try {
            if(!ImageIO.write(image,"png",output.toFile())||Files.size(output)>16L*1024*1024)
                throw new EditException(EditException.Code.STAMP_TOO_LARGE);
            double scale=Math.min(1,256.0/Math.max(image.getWidth(),image.getHeight()));
            int w=Math.max(1,(int)Math.floor(image.getWidth()*scale)),h=Math.max(1,(int)Math.floor(image.getHeight()*scale));
            var small=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);var g=small.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(image,0,0,w,h,null);
                if(!ImageIO.write(small,"png",preview.toFile()))throw new IOException("PNG encoder unavailable");
            } finally {g.dispose();small.flush();}
            return new Dimensions(image.getWidth(),image.getHeight(),w,h);
        } finally {image.flush();}
    }
}
