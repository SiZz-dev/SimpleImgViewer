package co.kr.satu.imagegate.editing.util;

import co.kr.satu.imagegate.editing.domain.*;
import java.awt.*;
import java.awt.font.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.text.AttributedString;
import java.util.List;

/** Server rendering of saved annotations. No browser HTML, external URLs or paths. */
public final class EditRaster {
    @FunctionalInterface public interface StampLoader {BufferedImage load(AnnotationItem item) throws IOException;}
    private EditRaster() {}
    public static BufferedImage compose(BufferedImage image,CropArea area,int brightness,int rotation,
            List<AnnotationItem> items,StampLoader stamps) throws IOException {
        if(brightness!=100) {
            double factor=brightness/100.0;
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++) {
                int p=image.getRGB(x,y);
                int r=Math.min(255,(int)Math.round(((p>>>16)&255)*factor));
                int g=Math.min(255,(int)Math.round(((p>>>8)&255)*factor));
                int b=Math.min(255,(int)Math.round((p&255)*factor));
                image.setRGB(x,y,(p&0xff000000)|(r<<16)|(g<<8)|b);
            }
        }
        var g=image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.translate(-area.x(),-area.y());
            for(var item:items) {
                if(item.x()+item.width()<=area.x()||item.y()+item.height()<=area.y()
                        ||item.x()>=area.x()+area.width()||item.y()>=area.y()+area.height())continue;
                var layer=(Graphics2D)g.create();
                try {
                    layer.clip(new Rectangle2D.Double(item.x(),item.y(),item.width(),item.height()));
                    if(item.type()==AnnotationItem.Type.NOTE)note(layer,item);
                    else {
                        var stamp=stamps.load(item);
                        try {
                            var transform=new AffineTransform();transform.translate(item.x(),item.y());
                            transform.scale(item.width()/stamp.getWidth(),item.height()/stamp.getHeight());
                            layer.drawImage(stamp,transform,null);
                        } finally {stamp.flush();}
                    }
                } finally {layer.dispose();}
            }
        } finally {g.dispose();}
        if(rotation==0)return image;
        int w=image.getWidth(),h=image.getHeight();
        var result=new BufferedImage(rotation%180==0?w:h,rotation%180==0?h:w,BufferedImage.TYPE_INT_ARGB);
        var draw=result.createGraphics();
        try {
            switch(rotation) {
                case 90 -> {draw.translate(h,0);draw.rotate(Math.PI/2);}
                case 180 -> {draw.translate(w,h);draw.rotate(Math.PI);}
                case 270 -> {draw.translate(0,w);draw.rotate(-Math.PI/2);}
                default -> throw new IllegalArgumentException("Unsupported rotation");
            }
            draw.drawImage(image,0,0,null);
        } finally {draw.dispose();}
        return result;
    }
    private static void note(Graphics2D g,AnnotationItem item) {
        g.setColor(new Color(255,241,183,235));
        g.fill(new Rectangle2D.Double(item.x(),item.y(),item.width(),item.height()));
        g.setColor(new Color(214,172,68));g.setStroke(new BasicStroke(1));
        g.draw(new Rectangle2D.Double(item.x()+.5,item.y()+.5,Math.max(0,item.width()-1),Math.max(0,item.height()-1)));
        float size=(float)Math.max(8,Math.min(256,item.width()/15));
        var font=new Font(Font.SANS_SERIF,Font.PLAIN,1).deriveFont(size);
        float pad=size*.35f,available=(float)item.width()-2*pad;
        if(available<=0)return;
        g.clip(new Rectangle2D.Double(item.x()+pad,item.y()+pad,available,Math.max(0,item.height()-2*pad)));
        g.setColor(new Color(70,54,19));
        float lineHeight=size*1.4f,y=(float)item.y()+pad;
        for(String paragraph:item.text().replace("\t","    ").split("\n",-1)) {
            if(y>item.y()+item.height())break;
            if(paragraph.isEmpty()){y+=lineHeight;continue;}
            if(font.canDisplayUpTo(paragraph)>=0)throw new co.kr.satu.imagegate.editing.exception.EditException(
                    co.kr.satu.imagegate.editing.exception.EditException.Code.EXPORT_FONT_UNAVAILABLE);
            var text=new AttributedString(paragraph);text.addAttribute(java.awt.font.TextAttribute.FONT,font);
            var measurer=new LineBreakMeasurer(text.getIterator(),g.getFontRenderContext());
            while(measurer.getPosition()<paragraph.length()&&y<item.y()+item.height()) {
                var layout=measurer.nextLayout(available);
                if(layout==null)break;
                layout.draw(g,(float)item.x()+pad,y+layout.getAscent());y+=lineHeight;
            }
        }
    }
}
