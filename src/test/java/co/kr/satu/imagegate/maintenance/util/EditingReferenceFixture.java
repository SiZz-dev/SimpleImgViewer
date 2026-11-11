package co.kr.satu.imagegate.maintenance.util;
import co.kr.satu.imagegate.editing.repository.*;
import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.util.PrivateFiles;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Editing;
import co.kr.satu.imagegate.maintenance.domain.EditingReferenceOptions;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
/** Synthetic stopped stores built through real runtime repositories; never packaged. */
public final class EditingReferenceFixture {
    public static final Editing LIMITS=new Editing(100,2000,100,1048576,4194304);
    public static final String TENANT="privateTenant",OWNER="privateOwner",STAMP_OWNER="sharedOwner";
    public final Path originals,annotations,stamps,page,stampEntry,originalEntry;
    public final MediaRecord media;public final StampAsset asset;
    public EditingReferenceFixture(Path root) throws Exception {
        originals=root.resolve("originals");annotations=root.resolve("annotations");stamps=root.resolve("stamps");
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(16,12,BufferedImage.TYPE_INT_ARGB),"png",bytes);
        byte[] png=bytes.toByteArray();
        try(var store=new LocalMediaStore(originals,1048576)){media=store.register(TENANT,OWNER,"private-original.png",new ByteArrayInputStream(png));originalEntry=store.resolveOriginal(media).getParent();}
        try(var library=new StampStore(stamps,LIMITS);var docs=new AnnotationStore(annotations)){
            asset=library.register(new ViewerPrincipal(TENANT,STAMP_OWNER),"private-stamp.png",new ByteArrayInputStream(png));
            var now=Instant.EPOCH;
            var note=new AnnotationItem(UUID.randomUUID().toString(),AnnotationItem.Type.NOTE,0,0,8,8,"private note text",null,null,now,now);
            var mark=new AnnotationItem(UUID.randomUUID().toString(),AnnotationItem.Type.STAMP,8,0,8,8,null,asset.id(),STAMP_OWNER,now,now);
            docs.update(media,1,"original:"+media.mediaVersion()+":1:upright-v1",0,items->List.of(note,mark));
            docs.update(media,2,"original:"+media.mediaVersion()+":2:upright-v1",0,items->List.of());
        }
        page=annotations.resolve("tenant-"+PrivateFiles.identity(TENANT)).resolve(media.mediaId()).resolve(media.mediaVersion()).resolve("page-1.properties");
        stampEntry=stamps.resolve("tenant-"+PrivateFiles.identity(TENANT)).resolve("user-"+PrivateFiles.identity(STAMP_OWNER)).resolve(asset.id());
    }
    public EditingReferenceOptions options(){return new EditingReferenceOptions(originals,annotations,stamps,true,100,10000,10485760);}
    public void property(Path file,String key,String value) throws Exception {
        var p=new Properties();try(var in=Files.newInputStream(file)){p.load(in);}if(value==null)p.remove(key);else p.setProperty(key,value);
        try(var out=Files.newOutputStream(file)){p.store(out,"synthetic fixture");}
    }
}
