package co.kr.satu.imagegate.maintenance.repository;

import co.kr.satu.imagegate.maintenance.domain.MaintenanceOptions;
import co.kr.satu.imagegate.maintenance.domain.MaintenanceReport;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

/** Offline only: exact cache names, existing writer locks, fixed-depth allowlisted work trees. */
public final class CacheOrphanRepository {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Set<String> PREVIEW_FILES=Set.of("preview.png","thumbnail.png","native/preview.png","native/preview.jpg","native/resized.tmp","native/resource/cmyk.icc","native/resource/libc9i.xml");
    private static final Set<String> PREVIEW_DIRS=Set.of("native","native/resource");
    private static final Set<String> PLAYBACK_FILES=Set.of("playback.webm","playback.mp3");
    private record Stamp(Path path,boolean directory,long size,long modified,Object key,long created,String digest) {}
    private record Candidate(String cache,Path root,Path entry,Path state,String kind,List<Stamp> nodes,Stamp stateStamp,Properties stateValues,Stamp rootStamp,Stamp entryStamp,long bytes) {}
    private record Lock(FileChannel channel,FileLock lock) implements AutoCloseable {
        public void close() throws IOException {try{lock.release();}finally{channel.close();}}
    }
    public static final class StorageInUseException extends IOException {}
    private static final class ScanLimit extends IOException {}
    private static final class Budget {
        long scanned,hashed;final long max;
        Budget(long max){this.max=max;}
        void next() throws ScanLimit {if(scanned>=max)throw new ScanLimit();scanned++;}
    }
    private static Stamp stamp(Path path) throws IOException {
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if((!a.isRegularFile()&&!a.isDirectory())||!path.toRealPath().equals(path))throw new IOException("Unsafe cache entry");
        return new Stamp(path,a.isDirectory(),a.size(),a.lastModifiedTime().toMillis(),a.fileKey(),a.creationTime().toMillis(),null);
    }
    private static boolean same(Stamp a,Stamp b){return a!=null&&b!=null&&a.path.equals(b.path)&&a.directory==b.directory&&a.size==b.size&&a.modified==b.modified&&Objects.equals(a.key,b.key)&&a.created==b.created&&Objects.equals(a.digest,b.digest);}
    private static Stamp snapshot(Stamp file,Budget budget) throws IOException {
        if(file.directory||file.key!=null)return file;
        // Windows BasicFileAttributes may omit fileKey. Do not depend on private JDK internals/native libraries.
        if(file.size>536870912L||file.created<=0)throw new IOException("Unverifiable or oversized orphan");
        if(budget.hashed>1073741824L-file.size)throw new ScanLimit();budget.hashed+=file.size;
        java.security.MessageDigest digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException e){throw new IOException("Digest unavailable");}
        long count=0;try(var in=Files.newInputStream(file.path,LinkOption.NOFOLLOW_LINKS)){
            byte[] block=new byte[32768];int n;while((n=in.read(block))!=-1){count+=n;if(count>file.size||count>536870912L)throw new IOException("Changing orphan");digest.update(block,0,n);}
        }
        if(count!=file.size||!same(file,stamp(file.path)))throw new IOException("Changing orphan");
        return new Stamp(file.path,file.directory,file.size,file.modified,file.key,file.created,HexFormat.of().formatHex(digest.digest()));
    }

    private static Lock acquire(Path cache,String name) throws IOException {
        var s=stamp(cache);if(!s.directory)throw new IOException("Invalid cache root");
        Path file=cache.resolve(name);var f=stamp(file);if(f.directory||f.size>1024)throw new IOException("Invalid cache lock");
        var channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock;
            try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new StorageInUseException();}
            if(lock==null)throw new StorageInUseException();
            if(!same(f,stamp(file))){lock.release();throw new IOException("Cache lock changed");}
            return new Lock(channel,lock);
        }catch(IOException|RuntimeException e){channel.close();throw e;}
    }
    private static Properties state(Path entry,String key,boolean preview) throws IOException {
        Path path=entry.resolve("state.properties");var s=stamp(path);if(s.directory||s.size>8192)throw new IOException("Invalid state");
        var p=new Properties();try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] data=in.readNBytes(8193);if(data.length>8192)throw new IOException("State size limit");p.load(new ByteArrayInputStream(data));}catch(IllegalArgumentException e){throw new IOException("Invalid state");}
        Set<String> known=preview?Set.of("key","page","state","error","artifact","width","height","sha256","bytes","modified","geometry","encodedWidth","encodedHeight","orientation","thumbnailArtifact","thumbnailWidth","thumbnailHeight","thumbnailBytes","thumbnailModified")
            :Set.of("key","state","error","artifact","duration","mime","width","height","video","audio","bytes","modified");
        if(!known.containsAll(p.stringPropertyNames()))throw new IOException("Unknown state format");
        if(!key.equals(p.getProperty("key"))||!Set.of("queued","running","ready","failed").contains(p.getProperty("state","")))throw new IOException("Invalid state");
        if(preview){try{int page=Integer.parseInt(p.getProperty("page"));if(page<1||page>10000)throw new NumberFormatException();}catch(RuntimeException e){throw new IOException("Invalid page");}}
        if(p.getProperty("state").equals("ready")){
            referenced(entry,p,"artifact","bytes","modified",preview);
            if(preview&&p.containsKey("thumbnailArtifact"))referenced(entry,p,"thumbnailArtifact","thumbnailBytes","thumbnailModified",true);
        }else if(p.containsKey("artifact")||p.containsKey("thumbnailArtifact"))throw new IOException("Unexpected reference");
        return p;
    }
    private static void referenced(Path entry,Properties p,String name,String bytes,String modified,boolean preview) throws IOException {
        String artifact=p.getProperty(name,"");if(!artifact.matches("result-"+UUID+(preview?"[.]png":"[.](webm|mp3)")))throw new IOException("Invalid reference");
        var file=stamp(entry.resolve(artifact));
        try{if(file.directory||file.size<1||file.size!=Long.parseLong(p.getProperty(bytes))||file.modified!=Long.parseLong(p.getProperty(modified)))throw new IOException("Invalid reference");}
        catch(RuntimeException e){throw new IOException("Invalid reference");}
    }
    private static boolean old(Stamp file,long cutoff){return file.modified<=cutoff&&file.modified>=0;}
    private static List<Stamp> work(Path directory,boolean preview,Budget budget,long cutoff) throws IOException {
        var nodes=new ArrayList<Stamp>();var top=stamp(directory);if(!top.directory||!old(top,cutoff))throw new IOException("Recent or invalid work");nodes.add(top);
        scanWork(directory,directory,preview,nodes,budget,cutoff);
        return List.copyOf(nodes);
    }
    private static void scanWork(Path base,Path directory,boolean preview,List<Stamp> nodes,Budget budget,long cutoff) throws IOException {
        try(var children=Files.newDirectoryStream(directory)){
            for(Path file:children){budget.next();String relative=base.relativize(file).toString().replace('\\','/');var info=stamp(file);
                if(!old(info,cutoff))throw new IOException("Recent work content");
                if(info.directory){if(!preview||!PREVIEW_DIRS.contains(relative))throw new IOException("Unknown work directory");nodes.add(info);scanWork(base,file,true,nodes,budget,cutoff);}
                else{if(!(preview?PREVIEW_FILES:PLAYBACK_FILES).contains(relative))throw new IOException("Unknown work content");nodes.add(snapshot(info,budget));}
            }
        }
    }
    private static long bytes(List<Stamp> nodes) throws IOException {long total=0;try{for(var n:nodes)if(!n.directory)total=Math.addExact(total,n.size);}catch(ArithmeticException e){throw new IOException("Invalid size");}return total;}
    private static boolean result(String name,boolean preview){return name.matches("result-"+UUID+(preview?"[.]png":"[.](webm|mp3)"));}
    private static boolean temporary(String name,boolean preview){return name.matches((preview?"state-":"write-")+UUID+"[.]tmp");}
    private static String relative(Candidate c){return c.root.relativize(c.nodes.getFirst().path).toString().replace('\\','/');}
    private static boolean references(Properties p,String name){return name.equals(p.getProperty("artifact"))||name.equals(p.getProperty("thumbnailArtifact"));}
    private static Candidate candidate(Path root,Path entry,Path file,boolean preview,Properties values,Stamp stateStamp,Budget budget,long cutoff) throws IOException {
        String name=file.getFileName().toString();List<Stamp> nodes;String kind;
        if(name.matches("work-"+UUID)){nodes=work(file,preview,budget,cutoff);kind="WORK";}
        else if(result(name,preview)&&!references(values,name)){var f=stamp(file);if(f.directory||!old(f,cutoff))return null;nodes=List.of(snapshot(f,budget));kind="UNREFERENCED_RESULT";}
        else if(temporary(name,preview)){var f=stamp(file);if(f.directory||!old(f,cutoff))return null;nodes=List.of(snapshot(f,budget));kind="METADATA_TEMP";}
        else return null;
        return new Candidate(preview?"preview":"playback",root,entry,entry.resolve("state.properties"),kind,nodes,stateStamp,values,stamp(root),stamp(entry),bytes(nodes));
    }
    private final Runnable beforeApply;
    public CacheOrphanRepository(){this(()->{});}
    CacheOrphanRepository(Runnable beforeApply){this.beforeApply=Objects.requireNonNull(beforeApply);}
    private record Selection(List<Candidate> candidates,long scanned,boolean limited,int skipped,long bytes) {}
    private static Selection select(Map<Path,Boolean> roots,MaintenanceOptions options,long cutoff) throws IOException {
        var candidates=new ArrayList<Candidate>();var budget=new Budget(options.maxEntries());boolean limited=false;int skipped=0;long bytes=0;
        try {
            scan: for(var root:roots.entrySet()){
                boolean preview=root.getValue();
                try(var entries=Files.newDirectoryStream(root.getKey())){
                    for(Path entry:entries){budget.next();String key=entry.getFileName().toString();if(!key.matches("[a-f0-9]{64}"))continue;
                        try{
                            if(!stamp(entry).directory)throw new IOException("Invalid cache key");
                            var stateStamp=stamp(entry.resolve("state.properties"));var values=state(entry,key,preview);
                            try(var children=Files.newDirectoryStream(entry)){
                                for(Path child:children){budget.next();
                                    try{var c=candidate(root.getKey(),entry,child,preview,values,stateStamp,budget,cutoff);if(c!=null){long sum=Math.addExact(bytes,c.bytes);candidates.add(c);bytes=sum;}}
                                    catch(ScanLimit e){throw e;}catch(IOException|ArithmeticException unsafe){skipped++;}
                                    if(candidates.size()>=options.maxCandidates()){limited=true;break scan;}
                                }
                            }
                        }catch(ScanLimit e){throw e;}catch(IOException|IllegalArgumentException unsafe){skipped++;}
                    }
                }
            }
        }catch(ScanLimit stopped){limited=true;}
        return new Selection(List.copyOf(candidates),budget.scanned,limited,skipped,bytes);
    }
    public MaintenanceReport run(MaintenanceOptions options,Instant now) throws IOException {
        Path converted=options.convertedRoot();if(!stamp(converted).directory)throw new IOException("Invalid converted root");
        var locks=new ArrayList<Lock>();var roots=new LinkedHashMap<Path,Boolean>();
        long cutoff=now.minusSeconds(options.minAgeHours()*3600L).toEpochMilli();
        try {
            for(String name:List.of("previews-v1","playback-v1")){
                Path cache=converted.resolve(name);if(!Files.exists(cache,LinkOption.NOFOLLOW_LINKS))continue;
                boolean preview=name.equals("previews-v1");locks.add(acquire(cache,preview?"worker.lock":"writer.lock"));roots.put(cache,preview);
            }
            if(roots.isEmpty())throw new IOException("No existing cache roots");
            var selected=select(roots,options,cutoff);var items=new ArrayList<MaintenanceReport.Item>();
            int skipped=selected.skipped,deleted=0;long deletedBytes=0;
            if(options.apply())beforeApply.run();
            for(var c:selected.candidates){String outcome="DRY_RUN";long reclaimed=0;
                if(options.apply()){
                    var removal=remove(c,cutoff);reclaimed=removal.bytes;deletedBytes=Math.addExact(deletedBytes,reclaimed);
                    if(removal.complete){outcome="DELETED";deleted++;}else{outcome=removal.nodes>0?"PARTIAL_DELETE":"SKIPPED_CHANGED_OR_IO";skipped++;}
                }
                items.add(new MaintenanceReport.Item(c.cache,relative(c),c.kind,outcome,c.bytes,reclaimed));
            }
            return new MaintenanceReport(1,now.toString(),options.apply(),selected.scanned,selected.limited,selected.bytes,deleted,deletedBytes,skipped,List.copyOf(items));
        }finally{
            IOException failure=null;for(int i=locks.size()-1;i>=0;i--)try{locks.get(i).close();}catch(IOException e){failure=e;}if(failure!=null)throw failure;
        }
    }
    private record Removal(long bytes,int nodes,boolean complete) {}
    private static boolean identity(Stamp a,Stamp b){return a!=null&&b!=null&&a.directory==b.directory&&a.path.equals(b.path)&&(a.key!=null?Objects.equals(a.key,b.key):a.created>0&&a.created==b.created); }
    private static Removal remove(Candidate c,long cutoff) {
        long reclaimed=0;int removed=0;try {
        // Recheck roots, metadata and every child. Unknown/recent content blocks the whole work tree.
        if(!identity(c.rootStamp,stamp(c.root))||!identity(c.entryStamp,stamp(c.entry))||!same(c.stateStamp,stamp(c.state))||!c.stateValues.equals(state(c.entry,c.entry.getFileName().toString(),c.cache.equals("preview"))))throw new IOException("Changed state");
        var first=c.nodes.getFirst();if(!first.path.startsWith(c.root)||first.path.equals(c.root))throw new IOException("Invalid deletion boundary");
        List<Stamp> current=first.directory?work(first.path,c.cache.equals("preview"),new Budget(32),cutoff):List.of(snapshot(stamp(first.path),new Budget(32)));
        if(current.size()!=c.nodes.size())throw new IOException("Changed work tree");
        var before=c.nodes.stream().collect(java.util.stream.Collectors.toMap(Stamp::path,s->s));
        for(var n:current)if(!old(n,cutoff)||!same(n,before.get(n.path)))throw new IOException("Changed file identity");
        var ordered=new ArrayList<>(current);ordered.sort(Comparator.comparingInt((Stamp n)->n.path.getNameCount()).reversed());
        for(var selected:ordered){
            // Canonical absolute paths must still be inside the explicitly selected cache root.
            if(!selected.path.startsWith(c.root)||!selected.path.toRealPath().equals(selected.path)||!identity(c.rootStamp,stamp(c.root))||!identity(c.entryStamp,stamp(c.entry)))throw new IOException("Changed boundary");
            if(!selected.directory&&!same(selected,snapshot(stamp(selected.path),new Budget(32))))throw new IOException("Changed leaf");
            if(selected.directory&&!identity(selected,stamp(selected.path)))throw new IOException("Changed directory");
            Files.delete(selected.path);removed++;if(!selected.directory)reclaimed=Math.addExact(reclaimed,selected.size);
        }
        return new Removal(reclaimed,removed,true);
        }catch(IOException|ArithmeticException changed){return new Removal(reclaimed,removed,false);}
    }
}
