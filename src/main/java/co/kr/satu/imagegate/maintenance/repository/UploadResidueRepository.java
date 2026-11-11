package co.kr.satu.imagegate.maintenance.repository;

import co.kr.satu.imagegate.maintenance.domain.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;

/** Fixed-depth attribute inventory. Never opens payload/catalog content or creates/deletes storage. */
public final class UploadResidueRepository {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private final Runnable beforeRecheck;
    public UploadResidueRepository(){this(()->{});}
    UploadResidueRepository(Runnable beforeRecheck){this.beforeRecheck=beforeRecheck;}
    public static final class StorageInUseException extends IOException {}
    private static final class Limit extends IOException {}
    private static final class UnexpectedLayout extends IOException {}
    private record Stamp(long size,Object key,FileTime modified,FileTime created,boolean directory) {}
    private static Stamp stamp(Path path,boolean directory) throws IOException {
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if((directory?!a.isDirectory():!a.isRegularFile())||!path.toRealPath().equals(path))throw new IOException("Invalid residue boundary");
        return new Stamp(a.size(),a.fileKey(),a.lastModifiedTime(),a.creationTime(),a.isDirectory());
    }
    /** Shared lock on an existing empty quota marker only. No CREATE/WRITE access or fallback. */
    private static final class Guard implements AutoCloseable {
        final Path path;final Stamp original;FileChannel channel;FileLock lock;
        Guard(Path root) throws IOException {
            path=root.resolve(".quota-writer.lock");
            original=Files.exists(path,LinkOption.NOFOLLOW_LINKS)?stamp(path,false):null;
            if(original==null)return;
            if(original.size()!=0)throw new IOException("Invalid quota marker");
            try{
                channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
                try{lock=channel.tryLock(0,Long.MAX_VALUE,true);}
                catch(OverlappingFileLockException busy){throw new StorageInUseException();}
                if(lock==null)throw new StorageInUseException();
                verify();
            }catch(IOException|RuntimeException failure){if(channel!=null)channel.close();throw failure;}
        }
        void verify() throws IOException {
            if(original==null){if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Quota marker appeared");}
            else if(lock==null||!lock.isValid()||!original.equals(stamp(path,false)))throw new IOException("Quota marker changed");
        }
        @Override public void close() throws IOException {
            try{if(lock!=null&&lock.isValid())lock.release();}finally{if(channel!=null)channel.close();}
        }
    }
    private static final class Budget {
        final UploadResidueOptions options;long scanned,bytes,published;boolean limited;
        final List<UploadResidueReport.Item> items=new ArrayList<>();
        Budget(UploadResidueOptions options){this.options=options;}
        void next() throws Limit {if(scanned>=options.maxEntries()){limited=true;throw new Limit();}scanned++;}
        void room() throws Limit {if(items.size()>=options.maxItems()){limited=true;throw new Limit();}}
        void observe(long bytes) throws Limit {if(bytes>options.maxBytes()-this.bytes){limited=true;throw new Limit();}this.bytes+=bytes;}
        void issue(String path,String outcome) throws Limit {room();items.add(new UploadResidueReport.Item(path,outcome,null,null,null,null));}
    }
    public UploadResidueReport inspect(UploadResidueOptions options,Instant now) throws IOException {
        Path root=options.originalsRoot();var rootStamp=stamp(root,true);var b=new Budget(options);
        try(var guard=new Guard(root)){
            try(var tenants=Files.newDirectoryStream(root)){
                for(Path tenant:tenants){
                    b.next();String name=tenant.getFileName().toString();
                    if(name.equals(".quota-writer.lock")){if(guard.original==null)throw new IOException("Quota marker appeared");continue;}
                    if(!name.matches("tenant-[a-f0-9]{64}")){b.issue(null,"UNEXPECTED_ROOT_ENTRY");continue;}
                    try{
                        var tenantStamp=stamp(tenant,true);
                        try(var entries=Files.newDirectoryStream(tenant)){
                            for(Path entry:entries){
                                b.next();String id=entry.getFileName().toString();
                                // Published-name entries are counted only; no directory traversal or catalog access.
                                if(id.matches(UUID)){b.published++;continue;}
                                if(!id.matches("[.]upload-"+UUID)){b.issue(null,"UNEXPECTED_TENANT_ENTRY");continue;}
                                b.room();String relative=name+"/"+id;
                                try{b.items.add(inspectUpload(root,rootStamp,tenant,tenantStamp,entry,relative,b));}
                                catch(Limit limit){throw limit;}
                                catch(UnexpectedLayout invalid){b.issue(relative,"UNEXPECTED_LAYOUT");}
                                catch(IOException|IllegalArgumentException invalid){b.issue(relative,"INVALID_OR_UNREADABLE");}
                            }
                        }
                        if(!tenantStamp.equals(stamp(tenant,true)))throw new IOException("Tenant changed during inventory");
                    }catch(Limit limit){throw limit;}
                    catch(IOException invalid){b.issue(null,"INVALID_OR_CHANGED_TENANT");}
                }
            }catch(Limit limit){/* Explicit partial inventory; no deletion decision can follow. */}
            guard.verify();
            if(!rootStamp.equals(stamp(root,true)))throw new IOException("Original root changed during inventory");
        }
        int recognized=(int)b.items.stream().filter(i->i.outcome().equals("RECOGNIZED_RESIDUE")||i.outcome().equals("EMPTY_UPLOAD_DIRECTORY")).count();
        return new UploadResidueReport(1,now,b.scanned,b.bytes,b.limited,b.published,recognized,List.copyOf(b.items));
    }
    private UploadResidueReport.Item inspectUpload(Path root,Stamp rootStamp,Path tenant,Stamp tenantStamp,
            Path entry,String relative,Budget b) throws IOException {
        var entryStamp=stamp(entry,true);
        String id=entry.getFileName().toString().substring(".upload-".length());
        if(Files.exists(tenant.resolve(id),LinkOption.NOFOLLOW_LINKS))
            return new UploadResidueReport.Item(relative,"PUBLISHED_ID_COLLISION",null,null,null,null);
        var files=new LinkedHashMap<Path,Stamp>();boolean pending=false,original=false,metadata=false;long bytes=0;
        Instant latest=entryStamp.modified().toInstant();
        try(var contents=Files.newDirectoryStream(entry)){
            for(Path file:contents){
                b.next();if(files.size()>=3)throw new UnexpectedLayout();
                String name=file.getFileName().toString();
                if(!name.equals("pending")&&!name.equals("metadata.properties")&&!name.matches("original[.][a-z0-9]{1,8}"))throw new UnexpectedLayout();
                var fileStamp=stamp(file,false);
                if(name.equals("metadata.properties")&&fileStamp.size()>16384)throw new UnexpectedLayout();
                b.observe(fileStamp.size());files.put(file,fileStamp);
                if(fileStamp.modified().toInstant().isAfter(latest))latest=fileStamp.modified().toInstant();
                if(name.equals("pending")){pending=true;bytes+=fileStamp.size();}
                else if(name.equals("metadata.properties"))metadata=true;
                else {if(original)throw new UnexpectedLayout();original=true;bytes+=fileStamp.size();}
            }
        }
        if((pending&&(original||metadata))||(metadata&&!original))throw new UnexpectedLayout();
        String layout=files.isEmpty()?"EMPTY":pending?"PENDING":metadata?"READY_TO_PUBLISH":"RENAMED";
        beforeRecheck.run();
        boolean changed=!entryStamp.equals(stamp(entry,true))||!tenantStamp.equals(stamp(tenant,true))||!rootStamp.equals(stamp(root,true));
        for(var file:files.entrySet())if(!file.getValue().equals(stamp(file.getKey(),false)))changed=true;
        if(changed)return new UploadResidueReport.Item(relative,"CHANGED_DURING_SCAN",null,null,null,null);
        return new UploadResidueReport.Item(relative,files.isEmpty()?"EMPTY_UPLOAD_DIRECTORY":"RECOGNIZED_RESIDUE",layout,bytes,files.size(),latest);
    }
}
