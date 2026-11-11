package co.kr.satu.imagegate.conversion.service;

import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.PreviewCancellation;
import co.kr.satu.imagegate.conversion.util.PreviewTiming;
import static co.kr.satu.imagegate.conversion.util.PreviewTiming.Stage.*;
import co.kr.satu.imagegate.conversion.util.ImageOrientation;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;

/** Re-encodes previews; never publishes original bytes or original metadata. */
public final class ImagePreviewRenderer implements PreviewRenderer {
    private final MediaStore store;
    private final MediaAuthorizationPolicy authorization;
    private final FeaturePolicy features;
    private final Preview profile;
    private final ImageConverter converter;
    private final PreviewTiming timing;
    @FunctionalInterface interface OriginalOpener {java.io.InputStream open(Path path) throws IOException;}
    private final OriginalOpener originalOpener;
    private final OriginalOpenAdmission admission;
    public ImagePreviewRenderer(MediaStore store,MediaAuthorizationPolicy authorization,
            FeaturePolicy features,Preview profile,ImageConverter converter) {
        this(store,authorization,features,profile,converter,null);
    }
    /** Package-local opt-in for trusted diagnostics; not an API or installation/user setting. */
    ImagePreviewRenderer(MediaStore store,MediaAuthorizationPolicy authorization,
            FeaturePolicy features,Preview profile,ImageConverter converter,PreviewTiming timing) {
        this(store,authorization,features,profile,converter,timing,null);
    }
    /** Trusted package-local IO experiment seam; installation admission still applies. */
    ImagePreviewRenderer(MediaStore store,MediaAuthorizationPolicy authorization,
            FeaturePolicy features,Preview profile,ImageConverter converter,PreviewTiming timing,OriginalOpener originalOpener) {
        this(store,authorization,features,profile,converter,timing,originalOpener,PreviewMetrics.NONE);
    }
    ImagePreviewRenderer(MediaStore store,MediaAuthorizationPolicy authorization,
            FeaturePolicy features,Preview profile,ImageConverter converter,PreviewTiming timing,OriginalOpener originalOpener,PreviewMetrics metrics) {
        this.store=store;this.authorization=authorization;this.features=features;
        this.profile=profile;this.converter=converter;this.timing=timing;this.originalOpener=originalOpener;
        this.admission=new OriginalOpenAdmission(profile.originalOpen(),metrics);
    }

    public ImagePreviewRenderer(PreviewMetrics metrics,MediaStore store,MediaAuthorizationPolicy authorization,
            FeaturePolicy features,Preview profile,ImageConverter converter){
        this(store,authorization,features,profile,converter,null,null,metrics);
    }

    @Override public Result render(MediaRecord media,ViewerPrincipal actor,int page,Path work) throws IOException {
        PreviewCancellation.check();
        try(var phase=PreviewTiming.start(timing,ACCESS_BEFORE)){requireAccess(media,actor);}
        Path input;
        try(var phase=PreviewTiming.start(timing,ORIGINAL_RESOLVE)){input=store.resolveOriginal(media);}
        PreviewCancellation.check();
        try(var phase=PreviewTiming.start(timing,ORIGINAL_HASH)){
            java.security.MessageDigest digest;
            try(var detail=PreviewTiming.start(timing,HASH_PREPARE)){digest=PreviewKeys.digest();}
            java.io.InputStream opened;
            try(var detail=PreviewTiming.start(timing,HASH_OPEN)){opened=originalOpener==null?admission.open(input):admission.open(input,originalOpener::open);}
            try(var stream=PreviewTiming.withTimedClose(opened,timing)) {
                byte[] block=new byte[32768];int n;
                while(true) {
                    PreviewCancellation.check();
                    try(var detail=PreviewTiming.start(timing,HASH_READ)){n=stream.read(block);}
                    if(n==-1)break;
                    try(var detail=PreviewTiming.start(timing,HASH_UPDATE)){digest.update(block,0,n);}
                }
            }
            PreviewCancellation.check();
            try(var detail=PreviewTiming.start(timing,HASH_FINAL)){
                if(!HexFormat.of().formatHex(digest.digest()).equals(media.sha256())) throw new IOException("Original changed");
            }
        }
        PreviewCancellation.check();
        try(var phase=PreviewTiming.start(timing,WORK_CREATE)){Files.createDirectory(work);}
        PreviewCancellation.check();
        Result result;boolean external;
        try(var phase=PreviewTiming.start(timing,CONVERTER_SELECT)){
            external=converter!=null && converter.supportedMimeTypes().contains(media.mimeType());
        }
        PreviewCancellation.check();
        if(external) {
            ImageConverter.ConversionResult converted;
            try(var phase=PreviewTiming.start(timing,NATIVE_CONVERT)){
                converted=converter.convert(new ImageConverter.ConversionRequest(input,work.resolve("native"),
                        page,profile.maxWidth(),profile.maxHeight(),ImageConverter.OutputFormat.PNG));
            }
            PreviewCancellation.check();
            result=decode(converted.output(),1,work,Math.min(profile.maxSourcePixels(),4096L*4096),false);
        } else {
            result=decode(input,page,work,profile.maxSourcePixels(),true);
        }
        PreviewCancellation.check();
        try(var phase=PreviewTiming.start(timing,ACCESS_AFTER)){requireAccess(media,actor);}
        PreviewCancellation.check();
        return result;
    }

    private Result decode(Path input,int page,Path work,long maxPixels,boolean original) throws IOException {
        PreviewCancellation.check();
        BufferedImage image=null,output=null;
        ImageGeometry geometry=null;
        try {
            FileImageInputStream opened;
            try(var phase=PreviewTiming.start(timing,DECODER_OPEN)){opened=new FileImageInputStream(input.toFile());}
            try(var stream=opened) {
                PreviewCancellation.check();
                javax.imageio.ImageReader reader;
                try(var phase=PreviewTiming.start(timing,DECODER_SELECT)){
                    var readers=ImageIO.getImageReaders(stream);
                    if(!readers.hasNext()) throw new UnsupportedOperationException("No configured image decoder");
                    reader=readers.next();
                }
                try {
                    PreviewCancellation.check();
                    int index=page-1,w,h;
                    try(var phase=PreviewTiming.start(timing,DECODER_METADATA)){
                        reader.setInput(stream,false,true);
                        PreviewCancellation.check();
                        w=reader.getWidth(index);h=reader.getHeight(index);
                    }
                    PreviewCancellation.check();
                    if(w<1 || h<1 || (long)w*h>maxPixels) throw new IOException("Source pixel limit");
                    try(var phase=PreviewTiming.start(timing,ORIENTATION)){
                        Integer orientation=original && "java.desktop".equals(reader.getClass().getModule().getName())
                                ? ImageOrientation.read(input,reader.getFormatName(),page) : null;
                        if(orientation!=null) geometry=new ImageGeometry(w,h,orientation);
                    }
                    PreviewCancellation.check();
                    try(var phase=PreviewTiming.start(timing,PIXEL_DECODE)){image=reader.read(index);}
                    PreviewCancellation.check();
                    if(image==null || image.getWidth()!=w || image.getHeight()!=h) throw new IOException("Unexpected decoder raster");
                } finally { reader.dispose(); }
            }
            PreviewCancellation.check();
            if(image==null) throw new IOException("Image decode failed");
            int w,h;
            try(var phase=PreviewTiming.start(timing,RESIZE)){
                int originalWidth=geometry==null ? image.getWidth() : geometry.coordinateWidth();
                int originalHeight=geometry==null ? image.getHeight() : geometry.coordinateHeight();
                double ratio=Math.min(1,Math.min((double)profile.maxWidth()/originalWidth,
                        (double)profile.maxHeight()/originalHeight));
                w=Math.max(1,(int)Math.floor(originalWidth*ratio));
                h=Math.max(1,(int)Math.floor(originalHeight*ratio));
                PreviewCancellation.check();
                output=new BufferedImage(w,h,BufferedImage.TYPE_INT_ARGB);
                var graphics=output.createGraphics();
                try {
                    PreviewCancellation.check();
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.scale((double)w/originalWidth,(double)h/originalHeight);
                    if(geometry!=null) graphics.transform(ImageOrientation.encodedToOriginal(geometry));
                    graphics.drawImage(image,0,0,null);
                } finally { graphics.dispose(); }
                image.flush();image=null;
            }
            PreviewCancellation.check();
            Path png=work.resolve("preview.png");
            try(var phase=PreviewTiming.start(timing,PREVIEW_PNG)){
                if(!ImageIO.write(output,"png",png.toFile())) throw new IOException("PNG encoder unavailable");
            }
            PreviewCancellation.check();
            co.kr.satu.imagegate.conversion.util.ThumbnailRaster.Result thumbnail;
            try(var phase=PreviewTiming.start(timing,THUMBNAIL)){
                thumbnail=profile.thumbnailEnabled()?co.kr.satu.imagegate.conversion.util.ThumbnailRaster.write(
                        output,work.resolve("thumbnail.png"),profile.thumbnailMaxEdge()):null;
            }
            PreviewCancellation.check();
            try(var phase=PreviewTiming.start(timing,OUTPUT_CHECK)){
                if(Files.size(png)>32L*1024*1024) throw new IOException("Preview size limit");
            }
            PreviewCancellation.check();
            return new Result(png,w,h,geometry,thumbnail==null?null:thumbnail.png(),thumbnail==null?0:thumbnail.width(),thumbnail==null?0:thumbnail.height());
        } finally {
            if(image!=null)image.flush();
            if(output!=null)output.flush();
        }
    }

    @Override public void close(){admission.close();}

    @Override public void verifyPublication(MediaRecord media,ViewerPrincipal actor,int page) throws IOException {
        requireAccess(media,actor);
    }

    private void requireAccess(MediaRecord expected,ViewerPrincipal actor) throws IOException {
        PreviewCancellation.check();
        var current=store.find(actor.tenantId(),expected.mediaId()).orElseThrow(()->new IOException("Media unavailable"));
        if(!expected.equals(current) || !actor.tenantId().equals(current.tenantId())
                || !authorization.permits(actor,current,MediaAction.VIEW)
                || !features.isEnabled(FeaturePolicy.Feature.IMAGE_VIEW))
            throw new IOException("Media unavailable");
        PreviewCancellation.check();
    }
}
