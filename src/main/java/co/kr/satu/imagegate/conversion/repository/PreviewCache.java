package co.kr.satu.imagegate.conversion.repository;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.UUID;

/** Private immutable PNG artifacts, per-key metadata locks, and a lease-aware writer lifetime. */
public final class PreviewCache implements AutoCloseable {
    private final Path root;
    private final java.util.concurrent.locks.ReentrantReadWriteLock lifecycle=new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private final java.util.concurrent.atomic.AtomicInteger leaseCount=new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean closing;
    private boolean closed;
    private java.nio.channels.FileChannel lockChannel;
    private java.nio.channels.FileLock writerLock;

    /** The queue claims the existing worker.lock once, before admitting any work. */
    public void claimWriter() throws IOException {
        lifecycle.writeLock().lock();
        try{
            if(closing||closed||writerLock!=null||leaseCount.get()!=0)throw new IOException("Preview cache is unavailable");
            Path file=root().resolve("worker.lock");
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                    ||!file.toRealPath().equals(file)||Files.size(file)>1024))throw new IOException("Invalid worker lock");
            var channel=java.nio.channels.FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try{
                var acquired=channel.tryLock();
                if(acquired==null)throw new IOException("Preview storage already has a writer");
                lockChannel=channel;writerLock=acquired;
            }catch(IOException|RuntimeException failure){channel.close();throw failure;}
        }finally{lifecycle.writeLock().unlock();}
    }
    // Shared guards preserve the existing per-key metadata concurrency; close is an exclusive barrier.
    private Operation operation(boolean admission) throws IOException {
        lifecycle.readLock().lock();
        if(closed||(admission&&closing)){
            lifecycle.readLock().unlock();
            throw new IOException("Preview cache is unavailable");
        }
        return new Operation();
    }
    private final class Operation implements AutoCloseable {
        @Override public void close(){lifecycle.readLock().unlock();}
    }
    @Override public void close() throws IOException {
        lifecycle.writeLock().lock();
        try{closing=true;closeIfIdle();}finally{lifecycle.writeLock().unlock();}
    }
    private void closeIfIdle() throws IOException {
        if(closing&&!closed&&leaseCount.get()==0){
            closed=true;
            try{if(writerLock!=null)writerLock.release();}
            finally{if(lockChannel!=null)lockChannel.close();}
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String,Integer> leases=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean sweeping=new java.util.concurrent.atomic.AtomicBoolean();
    // Bounded stripes prevent Windows replacement while this instance has metadata open.
    // Separate keys keep rendering and most metadata reads independent.
    private final Object[] metadataLocks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private Object metadataLock(String key){return metadataLocks[(key==null?0:key.hashCode())&63];}
    public PreviewCache(Path converted) { root=converted.toAbsolutePath().normalize().resolve("previews-v1"); }

    public Path root() throws IOException {
        try(var operation=operation(false)){
            Files.createDirectories(root);
            Path real=root.toRealPath();
            if(!real.equals(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid preview storage boundary");
            return real;
        }
    }

    public Path entry(String key) throws IOException {
        try(var operation=operation(false)){
            if(key==null || !key.matches("[0-9a-f]{64}")) throw new IOException("Invalid preview key");
            Path path=root().resolve(key);
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(path); }
                catch(FileAlreadyExistsException race) { /* verify below */ }
            }
            if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
                throw new IOException("Invalid preview entry");
            return path;
        }
    }

    private Path existingEntry(String key) throws IOException {
        if(key==null || !key.matches("[0-9a-f]{64}")) throw new IOException("Invalid preview key");
        Path path=root().resolve(key);
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return null;
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path))
            throw new IOException("Invalid preview entry");
        return path;
    }

    public PreviewStatus read(String key,int page) throws IOException {
        try(var operation=operation(false)){
            synchronized(metadataLock(key)){return readSnapshot(key,page);}
        }
    }
    private PreviewStatus readSnapshot(String key,int page) throws IOException {
        Path entry=existingEntry(key);
        if(entry==null) return PreviewStatus.empty(page,"not_requested",null);
        Path file=entry.resolve("state.properties");
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) return PreviewStatus.empty(page,"not_requested",null);
        var p=load(file,entry);
        try {
            if(!key.equals(p.getProperty("key")) || page!=Integer.parseInt(p.getProperty("page")))
                throw new IOException("Invalid preview state");
            String state=p.getProperty("state");
            if(!java.util.Set.of("queued","running","ready","failed").contains(state))
                throw new IOException("Invalid preview state");
            if(!"ready".equals(state))
                return new PreviewStatus(key,page,state,p.getProperty("error"),0,0);
            Path artifact=artifact(p,entry);
            if(artifact==null) return PreviewStatus.empty(page,"failed","CACHE_INVALID");
            int width=Integer.parseInt(p.getProperty("width")),height=Integer.parseInt(p.getProperty("height"));
            if(width<1 || height<1 || width>4096 || height>4096) throw new IOException("Invalid preview bounds");
            ImageGeometry geometry=null;
            if("upright-v1".equals(p.getProperty("geometry")))
                geometry=new ImageGeometry(Integer.parseInt(p.getProperty("encodedWidth")),
                        Integer.parseInt(p.getProperty("encodedHeight")),Integer.parseInt(p.getProperty("orientation")));
            PreviewStatus.Thumbnail thumbnail=null;
            if(p.containsKey("thumbnailArtifact")){
                Path small=thumbnailArtifact(p,entry);int tw=Integer.parseInt(p.getProperty("thumbnailWidth")),th=Integer.parseInt(p.getProperty("thumbnailHeight"));
                if(small==null||tw<1||th<1||tw>512||th>512||tw>width||th>height)return PreviewStatus.empty(page,"failed","CACHE_INVALID");
                thumbnail=new PreviewStatus.Thumbnail(tw,th);
            }
            return new PreviewStatus(key,page,state,null,width,height,geometry,thumbnail);
        } catch(IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("Invalid preview state",invalid);
        }
    }

    public Path content(String key,int page) throws IOException {
        try(var operation=operation(false)){
            synchronized(metadataLock(key)){return contentSnapshot(key,page);}
        }
    }
    private Path contentSnapshot(String key,int page) throws IOException {
        Path entry=existingEntry(key);
        if(entry==null) throw new IOException("Preview unavailable");
        var p=load(entry.resolve("state.properties"),entry);
        if(!key.equals(p.getProperty("key")) || !Integer.toString(page).equals(p.getProperty("page"))
                || !"ready".equals(p.getProperty("state"))) throw new IOException("Preview unavailable");
        Path result=artifact(p,entry);
        if(result==null) throw new IOException("Preview unavailable");
        return result;
    }

    public Path thumbnail(String key,int page) throws IOException {
        try(var operation=operation(false)){
            synchronized(metadataLock(key)){
                var status=readSnapshot(key,page);
                if(!status.ready()||status.thumbnail()==null)throw new IOException("Thumbnail unavailable");
                Path entry=existingEntry(key);return thumbnailArtifact(load(entry.resolve("state.properties"),entry),entry);
            }
        }
    }
    private Path thumbnailArtifact(Properties p,Path entry) throws IOException {
        var translated=new Properties();translated.setProperty("artifact",p.getProperty("thumbnailArtifact",""));
        translated.setProperty("bytes",p.getProperty("thumbnailBytes",""));translated.setProperty("modified",p.getProperty("thumbnailModified",""));
        Path file=artifact(translated,entry);return file==null||Files.size(file)>2L*1024*1024?null:file;
    }
    private Path artifact(Properties p,Path entry) throws IOException {
        String name=p.getProperty("artifact","");
        if(!name.matches("result-[0-9a-f-]{36}\\.png")) return null;
        Path path=entry.resolve(name);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().getParent().equals(entry))
            return null;
        try {
            return Files.size(path)>0&&Files.size(path)<=32L*1024*1024&&Files.size(path)==Long.parseLong(p.getProperty("bytes"))
                    && Files.getLastModifiedTime(path).toMillis()==Long.parseLong(p.getProperty("modified"))
                    ? path : null;
        } catch(NumberFormatException invalid) { return null; }
    }

    public void state(String key,int page,String state,String error) throws IOException {
        try(var operation=operation(false)){
            synchronized(metadataLock(key)){writeState(key,page,state,error);}
        }
    }
    private void writeState(String key,int page,String state,String error) throws IOException {
        var p=base(key,page,state);
        if(error!=null) p.setProperty("error",error);
        save(entry(key),p);
    }

    public void publish(String key,int page,Path png,int width,int height) throws IOException {
        publish(key,page,png,width,height,null);
    }

    public void publish(String key,int page,Path png,int width,int height,ImageGeometry geometry) throws IOException {
        publish(key,page,png,width,height,geometry,null,0,0);
    }
    public void publish(String key,int page,Path png,int width,int height,ImageGeometry geometry,Path thumbnail,int tw,int th) throws IOException {
        publish(key,page,png,width,height,geometry,thumbnail,tw,th,()->{});
    }
    @FunctionalInterface public interface PublicationCheck {void verify() throws IOException;}
    public void publish(String key,int page,Path png,int width,int height,ImageGeometry geometry,Path thumbnail,int tw,int th,PublicationCheck check) throws IOException {
        java.util.Objects.requireNonNull(check);
        try(var operation=operation(true)){
            synchronized(metadataLock(key)){publishSnapshot(key,page,png,width,height,geometry,thumbnail,tw,th,check);}
        }
    }
    private void publishSnapshot(String key,int page,Path png,int width,int height,ImageGeometry geometry,Path thumbnail,int tw,int th,PublicationCheck check) throws IOException {
        Path entry=entry(key);
        if(width<1 || height<1 || width>4096 || height>4096) throw new IOException("Invalid preview dimensions");
        if(!Files.isRegularFile(png,LinkOption.NOFOLLOW_LINKS)
                || !png.toRealPath().startsWith(entry) || Files.size(png)<1 || Files.size(png)>32L*1024*1024)
            throw new IOException("Invalid preview output");
        if(thumbnail!=null&&(tw<1||th<1||tw>512||th>512||tw>width||th>height
                ||!Files.isRegularFile(thumbnail,LinkOption.NOFOLLOW_LINKS)||!thumbnail.toRealPath().startsWith(entry)
                ||Files.size(thumbnail)<1||Files.size(thumbnail)>2L*1024*1024))throw new IOException("Invalid thumbnail output");
        Path published=entry.resolve("result-"+UUID.randomUUID()+".png");
        var moved=new java.util.ArrayList<Path>(2);
        try {
            Files.move(png,published,StandardCopyOption.ATOMIC_MOVE);
            moved.add(published);
            var p=base(key,page,"ready");
            p.setProperty("artifact",published.getFileName().toString());
            if(thumbnail!=null){
                Path small=entry.resolve("result-"+UUID.randomUUID()+".png");Files.move(thumbnail,small,StandardCopyOption.ATOMIC_MOVE);
                moved.add(small);
                p.setProperty("thumbnailArtifact",small.getFileName().toString());p.setProperty("thumbnailWidth",""+tw);p.setProperty("thumbnailHeight",""+th);
                p.setProperty("thumbnailBytes",""+Files.size(small));p.setProperty("thumbnailModified",""+Files.getLastModifiedTime(small).toMillis());
            }
            if(geometry!=null) {
                p.setProperty("geometry","upright-v1");
                p.setProperty("encodedWidth",Integer.toString(geometry.encodedWidth()));
                p.setProperty("encodedHeight",Integer.toString(geometry.encodedHeight()));
                p.setProperty("orientation",Integer.toString(geometry.orientation()));
            }
            p.setProperty("width",Integer.toString(width)); p.setProperty("height",Integer.toString(height));
            p.setProperty("bytes",Long.toString(Files.size(published)));
            p.setProperty("modified",Long.toString(Files.getLastModifiedTime(published).toMillis()));
            // Digest recorded once for offline integrity audit; hot reads use immutable private files.
            try(var input=Files.newInputStream(published)) {
                var digest=PreviewKeys.digest();
                byte[] block=new byte[32768]; int n;
                while((n=input.read(block))!=-1) digest.update(block,0,n);
                p.setProperty("sha256",java.util.HexFormat.of().formatHex(digest.digest()));
            }
            check.verify();
            save(entry,p);
        } catch(IOException|RuntimeException|Error failure) {
            for(var artifact:moved)try{Files.deleteIfExists(artifact);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    private static Properties base(String key,int page,String state) {
        var p=new Properties(); p.setProperty("key",key);p.setProperty("page",Integer.toString(page));
        p.setProperty("state",state);return p;
    }
    private static Properties load(Path file,Path entry) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().getParent().equals(entry) || Files.size(file)>8192)
            throw new IOException("Invalid preview metadata");
        var p=new Properties();
        try(var in=Files.newInputStream(file)) { p.load(in); }
        catch(IllegalArgumentException malformed){throw new IOException("Invalid preview metadata",malformed);}
        return p;
    }
    private static void save(Path entry,Properties p) throws IOException {
        Path temp=entry.resolve("state-"+UUID.randomUUID()+".tmp");
        try {
            try(var out=Files.newOutputStream(temp,StandardOpenOption.CREATE_NEW)) {
                p.store(out,"ImageGate preview v1");
            }
            Files.move(temp,entry.resolve("state.properties"),StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    /** A lease must span the complete render or HTTP response, never just path lookup. */
    public Lease lease(String key) throws IOException {
        try(var operation=operation(true)){
            synchronized(metadataLock(key)){
                if(key==null||!key.matches("[a-f0-9]{64}"))throw new IOException("Invalid preview key");
                touch(key,System.currentTimeMillis());leases.merge(key,1,Integer::sum);leaseCount.incrementAndGet();return new Lease(key);
            }
        }
    }
    private void touch(String key,long now) throws IOException {
        Path directory=existingEntry(key);if(directory==null)return;
        Path state=directory.resolve("state.properties");
        if(Files.exists(state,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(state,LinkOption.NOFOLLOW_LINKS)||!state.toRealPath().equals(state)||Files.size(state)>8192)
                throw new IOException("Invalid preview metadata");
            if(now-Files.getLastModifiedTime(state).toMillis()>=60000)
                Files.setLastModifiedTime(state,java.nio.file.attribute.FileTime.fromMillis(now));
        }
    }
    public final class Lease implements AutoCloseable {
        private final String key;private boolean released;
        private Lease(String key){this.key=key;}
        @Override public void close() throws IOException {
            lifecycle.readLock().lock();
            try{synchronized(metadataLock(key)){
                if(released)return;
                released=true;
                try{touch(key,System.currentTimeMillis());}catch(IOException ignored){/* Maintenance remains conservative. */}
                leases.computeIfPresent(key,(k,n)->n==1?null:n-1);
                leaseCount.decrementAndGet();
            }}finally{lifecycle.readLock().unlock();}
            if(closing){
                lifecycle.writeLock().lock();
                try{closeIfIdle();}finally{lifecycle.writeLock().unlock();}
            }
        }
    }
    public record MaintenanceResult(long scanned,long removed,long removedBytes,long retainedBytes,long skipped) {}
    private record Candidate(String key,long accessed,long bytes,String artifact,String thumbnail) {}

    /** Bounded candidate memory and at most 1000 removals per pass; no recursive deletion. */
    public MaintenanceResult maintain(co.kr.satu.imagegate.system.config.ImageGateProperties.PreviewCachePolicy policy,long now) throws IOException {
        if(closing||!policy.enabled()||!sweeping.compareAndSet(false,true))return new MaintenanceResult(0,0,0,0,0);
        long scanned=0,total=0,skipped=0,removed=0,removedBytes=0;
        var oldest=new PriorityQueue<Candidate>(Comparator.comparingLong(Candidate::accessed).reversed());
        try {
            Path base=root();
            try(var directories=Files.newDirectoryStream(base)){
                for(Path directory:directories){String key=directory.getFileName().toString();
                    if(!key.matches("[a-f0-9]{64}"))continue;
                    scanned++;
                    try(var operation=operation(false)){
                        if(closing)break;
                        synchronized(metadataLock(key)){
                            try{var candidate=candidate(key);if(candidate==null)continue;
                                total=Math.addExact(total,candidate.bytes());
                                if(leases.containsKey(key))continue;
                                oldest.add(candidate);if(oldest.size()>1000)oldest.poll();
                            }catch(IOException|ArithmeticException invalid){skipped++;}
                        }
                    }
                }
            }
            var ordered=new ArrayList<>(oldest);ordered.sort(Comparator.comparingLong(Candidate::accessed));
            for(var selected:ordered){
                String key=selected.key();
                try(var operation=operation(false)){
                    if(closing)break;
                    synchronized(metadataLock(key)){
                        if(leases.containsKey(selected.key()))continue;
                        try{var current=candidate(selected.key());
                            if(!selected.equals(current))continue;
                            boolean expired=now>=current.accessed()&&now-current.accessed()>=policy.retention().toMillis();
                            if(!expired&&total<=policy.maxBytes())continue;
                            Path directory=existingEntry(current.key());
                            // candidate() rejects extra files, links and work directories. Never follow a tree.
                            if(current.thumbnail()!=null)Files.delete(directory.resolve(current.thumbnail()));
                            if(current.artifact()!=null)Files.delete(directory.resolve(current.artifact()));
                            Files.delete(directory.resolve("state.properties"));Files.delete(directory);
                            total-=current.bytes();removedBytes+=current.bytes();removed++;
                        }catch(IOException invalid){skipped++;}
                    }
                }
            }
            return new MaintenanceResult(scanned,removed,removedBytes,total,skipped);
        }finally{sweeping.set(false);}
    }
    private Candidate candidate(String key) throws IOException {
        Path directory=existingEntry(key);if(directory==null)return null;
        Path state=directory.resolve("state.properties");
        var properties=load(state,directory);
        if(!key.equals(properties.getProperty("key")))throw new IOException("Invalid private preview storage");
        int page;try{page=Integer.parseInt(properties.getProperty("page"));}catch(RuntimeException invalid){throw new IOException("Invalid preview page",invalid);}
        if(page<1||page>10000)throw new IOException("Invalid preview page");
        String status=properties.getProperty("state");String artifact=null,thumbnail=null;long bytes=0;
        if("ready".equals(status)){var info=readSnapshot(key,page);if(!info.ready())throw new IOException("Invalid cached preview");
            artifact=artifact(properties,directory).getFileName().toString();bytes=Files.size(directory.resolve(artifact));
            if(info.thumbnail()!=null){thumbnail=thumbnailArtifact(properties,directory).getFileName().toString();
                if(thumbnail.equals(artifact))throw new IOException("Invalid thumbnail identity");bytes+=Files.size(directory.resolve(thumbnail));}}
        else if(!"failed".equals(status)){
            if("queued".equals(status)||"running".equals(status))return null;
            throw new IOException("Invalid preview state");
        }
        try(var files=Files.newDirectoryStream(directory)){
            for(Path file:files){String name=file.getFileName().toString();
                if(!name.equals("state.properties")&&!name.equals(artifact)&&!name.equals(thumbnail))throw new IOException("Invalid private preview storage");
                if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file)
                        ||Files.size(file)>(name.equals("state.properties")?8192:32L*1024*1024))throw new IOException("Invalid preview file");
            }
        }
        return new Candidate(key,Files.getLastModifiedTime(state).toMillis(),bytes,artifact,thumbnail);
    }
}
