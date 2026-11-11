package co.kr.satu.imagegate.conversion.service;
import co.kr.satu.imagegate.conversion.domain.TilePyramidLayout;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import co.kr.satu.imagegate.conversion.util.*;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Tiles;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageInputStream;
/** Inspects trusted original metadata; never allocates the original full-size raster. */
public final class NativeTileRenderer implements TileRenderer {
    private final MediaStore store;private final MediaAuthorizationPolicy authorization;private final FeaturePolicy features;
    private final TileNativeConverter converter;private final TilePyramidPublisher publisher;private final Tiles policy;private final co.kr.satu.imagegate.conversion.repository.TileWorkStore workStore;private final String module;
    private final TileTiming timing;
    public NativeTileRenderer(MediaStore store,MediaAuthorizationPolicy authorization,FeaturePolicy features,TileNativeConverter converter,
            TilePyramidPublisher publisher,Tiles policy,Path converted)throws IOException{
        this(store,authorization,features,converter,publisher,policy,converted,new co.kr.satu.imagegate.conversion.repository.TileWorkStore(converted,policy.maxTiles(),policy.maintenance().maxScanEntries()));
    }
    public NativeTileRenderer(MediaStore store,MediaAuthorizationPolicy authorization,FeaturePolicy features,TileNativeConverter converter,
            TilePyramidPublisher publisher,Tiles policy,Path converted,co.kr.satu.imagegate.conversion.repository.TileWorkStore workStore)throws IOException{
        this(store,authorization,features,converter,publisher,policy,converted,workStore,null);
    }
    NativeTileRenderer(MediaStore store,MediaAuthorizationPolicy authorization,FeaturePolicy features,TileNativeConverter converter,
            TilePyramidPublisher publisher,Tiles policy,Path converted,co.kr.satu.imagegate.conversion.repository.TileWorkStore workStore,TileTiming timing)throws IOException{
        this.store=store;this.authorization=authorization;this.features=features;this.converter=converter;this.publisher=publisher;this.policy=policy;
        module=converter.tileIdentity();this.workStore=workStore;this.timing=timing;
    }
    @Override public void verifyAccess(MediaRecord expected,ViewerPrincipal actor)throws IOException{
        PreviewCancellation.check();var current=store.find(actor.tenantId(),expected.mediaId()).orElseThrow(()->new IOException("Media unavailable"));
        if(!current.equals(expected)||!actor.tenantId().equals(current.tenantId())||!authorization.permits(actor,current,MediaAction.VIEW)
                ||!features.isEnabled(FeaturePolicy.Feature.IMAGE_VIEW))throw new IOException("Media unavailable");
    }
    @Override public void render(MediaRecord media,ViewerPrincipal actor,int page,String key,TilePyramidPublisher.CommitGate gate)throws IOException{
        try(var span=TileTiming.start(timing,TileTiming.Stage.ACCESS_BEFORE)){verifyAccess(media,actor);}
        if(media.mediaType()!=MediaType.IMAGE||!"image/tiff".equals(media.mimeType()))throw new UnsupportedOperationException("Tile profile unavailable");
        Path original=store.resolveOriginal(media);
        try(var span=TileTiming.start(timing,TileTiming.Stage.ORIGINAL_HASH_BEFORE)){verifyOriginal(original,media);}
        Img2TilePyramidValidator.Source source;
        try(var span=TileTiming.start(timing,TileTiming.Stage.SOURCE_INSPECT)){source=inspect(original,page);}
        try(var span=TileTiming.start(timing,TileTiming.Stage.MODULE_BEFORE)){if(!module.equals(converter.tileIdentity()))throw new IOException("Tile module changed");}
        co.kr.satu.imagegate.conversion.repository.TileWorkStore.Work owned;
        try(var span=TileTiming.start(timing,TileTiming.Stage.WORK_CREATE)){owned=workStore.begin(key);}Path work=owned.path();
        try{
            Path output;try(var span=TileTiming.start(timing,TileTiming.Stage.NATIVE_CONVERT)){output=converter.generateTiles(original,page,work);}
            publisher.publish(key,output,source,()->{
                try(var span=TileTiming.start(timing,TileTiming.Stage.ORIGINAL_HASH_AFTER)){verifyOriginal(original,media);}
                try(var span=TileTiming.start(timing,TileTiming.Stage.MODULE_AFTER)){if(!module.equals(converter.tileIdentity()))throw new IOException("Tile module changed");}
                try(var span=TileTiming.start(timing,TileTiming.Stage.ACCESS_AFTER)){verifyAccess(media,actor);}
            },gate);
        }finally{try(var span=TileTiming.start(timing,TileTiming.Stage.WORK_CLEANUP)){workStore.finish(owned);}catch(IOException|RuntimeException failure){org.slf4j.LoggerFactory.getLogger(NativeTileRenderer.class).warn("Tile work retained for private inspection");}}
    }
    private void verifyOriginal(Path file,MediaRecord media)throws IOException{
        PreviewCancellation.check();if(!file.toRealPath().equals(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||media.sizeBytes()<8||media.sizeBytes()>104857600||Files.size(file)!=media.sizeBytes())throw new IOException("Original changed");
        var sha=PreviewKeys.digest();long read=0;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] block=new byte[32768];int n;
            while((n=input.read(block))!=-1){PreviewCancellation.check();read+=n;if(read>media.sizeBytes())throw new IOException("Original changed");sha.update(block,0,n);}}
        if(read!=media.sizeBytes()||!HexFormat.of().formatHex(sha.digest()).equals(media.sha256())||!file.toRealPath().equals(file))throw new IOException("Original changed");
    }
    private Img2TilePyramidValidator.Source inspect(Path original,int page)throws IOException{
        if(page<1||page>10000)throw new IOException("Invalid tile page");
        try(var input=Files.newInputStream(original,LinkOption.NOFOLLOW_LINKS)){
            byte[] h=input.readNBytes(4);if(!Arrays.equals(h,new byte[]{73,73,42,0})&&!Arrays.equals(h,new byte[]{77,77,0,42}))throw new UnsupportedOperationException("Classic TIFF required");
        }
        Integer orientation=ImageOrientation.read(original,"TIFF",page);if(orientation==null||orientation!=1)throw new UnsupportedOperationException("Tile orientation unavailable");
        try(var stream=new FileImageInputStream(original.toFile())){
            var reader=ImageIO.getImageReadersByFormatName("TIFF").next();
            try{
                if(!"java.desktop".equals(reader.getClass().getModule().getName()))throw new UnsupportedOperationException("Unverified TIFF reader");
                reader.setInput(stream,false,true);int index=page-1,w=reader.getWidth(index),h=reader.getHeight(index);
                var layout=new TilePyramidLayout(w,h,256,0);
                if((long)w*h>policy.maxSourcePixels()||layout.totalTiles()>policy.maxTiles())throw new IOException("Tile source limit");
                var raw=reader.getRawImageType(index);var color=raw==null?null:raw.getColorModel();
                if(color==null||color.hasAlpha()||color.getNumComponents()!=3||color.getColorSpace().getType()!=java.awt.color.ColorSpace.TYPE_RGB
                        ||raw.getSampleModel().getNumBands()!=3||Arrays.stream(color.getComponentSize()).anyMatch(bits->bits!=8))throw new UnsupportedOperationException("TIFF RGB8 required");
                var directory=javax.imageio.plugins.tiff.TIFFDirectory.createFromMetadata(reader.getImageMetadata(index));
                var bits=directory.getTIFFField(258);var photo=directory.getTIFFField(262);var samples=directory.getTIFFField(277);
                var compression=directory.getTIFFField(259);var planar=directory.getTIFFField(284);
                if(bits==null||bits.getCount()!=3||bits.getAsInt(0)!=8||bits.getAsInt(1)!=8||bits.getAsInt(2)!=8
                        ||photo==null||photo.getAsInt(0)!=2||samples==null||samples.getAsInt(0)!=3||directory.getTIFFField(338)!=null
                        ||(compression!=null&&compression.getAsInt(0)!=1)||(planar!=null&&planar.getAsInt(0)!=1))
                    throw new UnsupportedOperationException("Verified uncompressed contiguous TIFF RGB8 required");
                return new Img2TilePyramidValidator.Source(w,h,page,"TIFF_RGB",1,8,3,module);
            }finally{reader.dispose();}
        }
    }
}
