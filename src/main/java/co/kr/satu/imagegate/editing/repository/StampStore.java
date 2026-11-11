package co.kr.satu.imagegate.editing.repository;

import co.kr.satu.imagegate.editing.domain.StampAsset;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.editing.util.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Editing;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable sanitized per-user stamp library. Only 256px previews are exposed over HTTP. */
public final class StampStore implements AutoCloseable {
    private final Path root;private final Editing limits;private final PrivateFiles.WriterLock lock;
    public StampStore(Path path,Editing limits) throws IOException {
        root=PrivateFiles.root(path);this.limits=limits;lock=new PrivateFiles.WriterLock(root);
    }
    private Path user(String tenant,String owner,boolean create) throws IOException {
        Path parent=PrivateFiles.directory(root,"tenant-"+PrivateFiles.identity(tenant),create);
        return parent==null?null:PrivateFiles.directory(parent,"user-"+PrivateFiles.identity(owner),create);
    }
    public synchronized List<StampAsset> list(ViewerPrincipal actor) throws IOException {
        Path parent=user(actor.tenantId(),actor.userId(),false);if(parent==null)return List.of();
        var result=new ArrayList<StampAsset>();
        try(var children=Files.newDirectoryStream(parent)) {
            for(var child:children) {
                String id=child.getFileName().toString();
                if(PrivateFiles.uuid(id))result.add(find(actor.tenantId(),actor.userId(),id));
                if(result.size()>100)throw PrivateFiles.invalid();
            }
        }
        return result.stream().sorted(Comparator.comparing(StampAsset::fileName).thenComparing(StampAsset::id)).toList();
    }
    public synchronized StampAsset register(ViewerPrincipal actor,String fileName,InputStream input) throws IOException {
        if(list(actor).size()>=limits.maxStampsPerUser())throw new EditException(EditException.Code.EDIT_LIMIT_REACHED);
        if(fileName==null)throw new EditException(EditException.Code.INVALID_STAMP);
        String display=fileName.substring(Math.max(fileName.lastIndexOf('/'),fileName.lastIndexOf('\\'))+1);
        if(display.isBlank()||display.length()>255||display.chars().anyMatch(Character::isISOControl))
            throw new EditException(EditException.Code.INVALID_STAMP);
        Path parent=user(actor.tenantId(),actor.userId(),true);
        String id=UUID.randomUUID().toString();Path staging=Files.createDirectory(parent.resolve("upload-"+id));
        try {
            Path source=staging.resolve("input");long count=0;
            try(var output=Files.newOutputStream(source,StandardOpenOption.CREATE_NEW)) {
                byte[] block=new byte[32768];int n;
                while((n=input.read(block))!=-1) {
                    count+=n;if(count>limits.maxStampUploadBytes())throw new EditException(EditException.Code.STAMP_TOO_LARGE);
                    output.write(block,0,n);
                }
            }
            if(count==0)throw new EditException(EditException.Code.INVALID_STAMP);
            StampSanitizer.Dimensions dims;
            try {dims=StampSanitizer.sanitize(source,staging.resolve("stamp.png"),staging.resolve("preview.png"),limits.maxStampPixels());}
            catch(javax.imageio.IIOException invalid){throw new EditException(EditException.Code.INVALID_STAMP,invalid);}
            Files.delete(source);
            var p=new Properties();p.setProperty("id",id);p.setProperty("name",display);
            p.setProperty("width",""+dims.width());p.setProperty("height",""+dims.height());
            p.setProperty("previewWidth",""+dims.previewWidth());p.setProperty("previewHeight",""+dims.previewHeight());
            PrivateFiles.write(staging.resolve("metadata.properties"),p);
            Files.move(staging,parent.resolve(id),StandardCopyOption.ATOMIC_MOVE);
            return new StampAsset(id,display,dims.width(),dims.height(),dims.previewWidth(),dims.previewHeight());
        } finally {
            if(Files.exists(staging,LinkOption.NOFOLLOW_LINKS)){
                try(var files=Files.newDirectoryStream(staging)){for(var file:files)Files.deleteIfExists(file);}
                Files.deleteIfExists(staging);
            }
        }
    }
    public synchronized StampAsset find(String tenant,String owner,String id) throws IOException {
        if(!PrivateFiles.uuid(id))throw new EditException(EditException.Code.STAMP_NOT_FOUND);
        Path parent=user(tenant,owner,false);
        Path entry=parent==null?null:PrivateFiles.directory(parent,id,false);
        if(entry==null)throw new EditException(EditException.Code.STAMP_NOT_FOUND);
        var p=PrivateFiles.read(entry.resolve("metadata.properties"),8192);
        try {
            if(!id.equals(p.getProperty("id")))throw PrivateFiles.invalid();
            int w=Integer.parseInt(p.getProperty("width")),h=Integer.parseInt(p.getProperty("height")),
                    pw=Integer.parseInt(p.getProperty("previewWidth")),ph=Integer.parseInt(p.getProperty("previewHeight"));
            if(w<1||h<1||w>2048||h>2048||pw<1||ph<1||pw>256||ph>256)throw PrivateFiles.invalid();
            PrivateFiles.regular(entry.resolve("stamp.png"),16L*1024*1024);
            PrivateFiles.regular(entry.resolve("preview.png"),1024*1024);
            return new StampAsset(id,p.getProperty("name"),w,h,pw,ph);
        } catch(IllegalArgumentException|NullPointerException invalid){throw new IOException("Invalid stamp metadata",invalid);}
    }
    public synchronized Path preview(String tenant,String owner,String id) throws IOException {
        find(tenant,owner,id);return PrivateFiles.directory(user(tenant,owner,false),id,false).resolve("preview.png");
    }
    /** Internal full resolution sanitized PNG; never a personal-library HTTP endpoint. */
    public synchronized java.awt.image.BufferedImage raster(String tenant,String owner,String id) throws IOException {
        var asset=find(tenant,owner,id);
        Path parent=user(tenant,owner,false),entry=PrivateFiles.directory(parent,id,false);
        try(var stream=new javax.imageio.stream.FileImageInputStream(entry.resolve("stamp.png").toFile())) {
            var readers=javax.imageio.ImageIO.getImageReaders(stream);
            if(!readers.hasNext())throw PrivateFiles.invalid();
            var reader=readers.next();
            try {
                if(!"java.desktop".equals(reader.getClass().getModule().getName())||!"png".equalsIgnoreCase(reader.getFormatName()))
                    throw PrivateFiles.invalid();
                reader.setInput(stream,false,true);
                if(reader.getWidth(0)!=asset.width()||reader.getHeight(0)!=asset.height())throw PrivateFiles.invalid();
                var image=reader.read(0);
                if(image==null||image.getWidth()!=asset.width()||image.getHeight()!=asset.height())throw PrivateFiles.invalid();
                return image;
            } finally {reader.dispose();}
        }
    }
    @Override public void close() throws IOException {lock.close();}
}
