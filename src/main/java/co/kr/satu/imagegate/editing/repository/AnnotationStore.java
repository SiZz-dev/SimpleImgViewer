package co.kr.satu.imagegate.editing.repository;

import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.editing.util.PrivateFiles;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;

/** Atomic page snapshots, optimistic revision checking and one JVM writer per storage root. */
public final class AnnotationStore implements AutoCloseable {
    private final Path root;
    private final PrivateFiles.WriterLock lock;
    public AnnotationStore(Path directory) throws IOException {
        root=PrivateFiles.root(directory);lock=new PrivateFiles.WriterLock(root);
    }
    private Path file(MediaRecord media,int page,boolean create) throws IOException {
        if(!PrivateFiles.uuid(media.mediaId())||!PrivateFiles.uuid(media.mediaVersion())||page<1||page>10000)
            throw PrivateFiles.invalid();
        Path parent=PrivateFiles.directory(root,"tenant-"+PrivateFiles.identity(media.tenantId()),create);
        if(parent==null)return null;
        parent=PrivateFiles.directory(parent,media.mediaId(),create);if(parent==null)return null;
        parent=PrivateFiles.directory(parent,media.mediaVersion(),create);if(parent==null)return null;
        return parent.resolve("page-"+page+".properties");
    }
    public synchronized AnnotationDocument read(MediaRecord media,int page,String space) throws IOException {
        Path file=file(media,page,false);
        if(file==null||!Files.exists(file,LinkOption.NOFOLLOW_LINKS))
            return new AnnotationDocument(media.mediaVersion(),page,space,0,List.of());
        var p=PrivateFiles.read(file,2L*1024*1024);
        try {
            if(!media.mediaVersion().equals(p.getProperty("version"))||!space.equals(p.getProperty("space"))
                    ||page!=Integer.parseInt(p.getProperty("page")))throw PrivateFiles.invalid();
            int revision=Integer.parseInt(p.getProperty("revision")),count=Integer.parseInt(p.getProperty("count"));
            if(revision<0||count<0||count>100)throw PrivateFiles.invalid();
            var items=new ArrayList<AnnotationItem>();var ids=new HashSet<String>();
            for(int i=0;i<count;i++) {
                String k="item."+i+".",id=p.getProperty(k+"id");
                if(!PrivateFiles.uuid(id)||!ids.add(id))throw PrivateFiles.invalid();
                var type=AnnotationItem.Type.valueOf(p.getProperty(k+"type"));
                double x=Double.parseDouble(p.getProperty(k+"x")),y=Double.parseDouble(p.getProperty(k+"y")),
                        w=Double.parseDouble(p.getProperty(k+"width")),h=Double.parseDouble(p.getProperty(k+"height"));
                if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(w)||!Double.isFinite(h)
                        ||x<0||y<0||w<1||h<1||x+w>40000000||y+h>40000000)throw PrivateFiles.invalid();
                String text=p.getProperty(k+"text"),stamp=p.getProperty(k+"stamp"),owner=p.getProperty(k+"stampOwner");
                if(type==AnnotationItem.Type.NOTE&&(text==null||text.isBlank()||text.length()>2000||stamp!=null))throw PrivateFiles.invalid();
                if(type==AnnotationItem.Type.STAMP&&(!PrivateFiles.uuid(stamp)||owner==null||text!=null))throw PrivateFiles.invalid();
                if(owner!=null)PrivateFiles.identity(owner);
                items.add(new AnnotationItem(id,type,x,y,w,h,text,stamp,owner,
                        Instant.parse(p.getProperty(k+"created")),Instant.parse(p.getProperty(k+"updated"))));
            }
            return new AnnotationDocument(media.mediaVersion(),page,space,revision,items);
        } catch(IllegalArgumentException|NullPointerException invalid){throw new IOException("Invalid annotation snapshot",invalid);}
    }
    public synchronized AnnotationDocument update(MediaRecord media,int page,String space,int revision,
            UnaryOperator<List<AnnotationItem>> operation) throws IOException {
        var current=read(media,page,space);
        if(current.revision()!=revision||revision==Integer.MAX_VALUE)throw new EditException(EditException.Code.EDIT_CONFLICT);
        var items=List.copyOf(operation.apply(new ArrayList<>(current.items())));
        if(items.size()>100)throw new EditException(EditException.Code.EDIT_LIMIT_REACHED);
        var next=new AnnotationDocument(media.mediaVersion(),page,space,revision+1,items);
        var p=new Properties();p.setProperty("version",media.mediaVersion());p.setProperty("page",""+page);
        p.setProperty("space",space);p.setProperty("revision",""+next.revision());p.setProperty("count",""+items.size());
        for(int i=0;i<items.size();i++) {
            var item=items.get(i);String k="item."+i+".";
            p.setProperty(k+"id",item.id());p.setProperty(k+"type",item.type().name());
            p.setProperty(k+"x",""+item.x());p.setProperty(k+"y",""+item.y());
            p.setProperty(k+"width",""+item.width());p.setProperty(k+"height",""+item.height());
            if(item.text()!=null)p.setProperty(k+"text",item.text());
            if(item.stampId()!=null){p.setProperty(k+"stamp",item.stampId());p.setProperty(k+"stampOwner",item.stampOwner());}
            p.setProperty(k+"created",item.createdAt().toString());p.setProperty(k+"updated",item.updatedAt().toString());
        }
        PrivateFiles.write(file(media,page,true),p);return next;
    }
    @Override public void close() throws IOException {lock.close();}
}
