package co.kr.satu.imagegate.conversion.util;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;

/** Derives only from the already validated upright preview; never decodes the original again. */
public final class ThumbnailRaster {
    private ThumbnailRaster() {}
    public record Result(Path png,int width,int height) {}
    public static Result write(BufferedImage preview,Path output,int maxEdge) throws IOException {
        PreviewCancellation.check();
        if(maxEdge<64||maxEdge>512)throw new IOException("Invalid thumbnail bounds");
        double ratio=Math.min(1,Math.min((double)maxEdge/preview.getWidth(),(double)maxEdge/preview.getHeight()));
        int width=Math.max(1,(int)Math.floor(preview.getWidth()*ratio)),height=Math.max(1,(int)Math.floor(preview.getHeight()*ratio));
        PreviewCancellation.check();
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);
        try {
            var graphics=image.createGraphics();
            try{
                PreviewCancellation.check();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(preview,0,0,width,height,null);
            }finally{graphics.dispose();}
            PreviewCancellation.check();
            if(!ImageIO.write(image,"png",output.toFile()))throw new IOException("Invalid thumbnail output");
            PreviewCancellation.check();
            if(Files.size(output)>2L*1024*1024)throw new IOException("Invalid thumbnail output");
            PreviewCancellation.check();
            return new Result(output,width,height);
        }finally{image.flush();}
    }
}
