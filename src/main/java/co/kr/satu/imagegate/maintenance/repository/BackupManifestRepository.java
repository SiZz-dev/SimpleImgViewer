package co.kr.satu.imagegate.maintenance.repository;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageScope;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
/** Stopped private stores only. No writer, decoder, repair, config import or signature verifier. */
public final class BackupManifestRepository {
    private static final int MANIFEST_BYTES=4194304,MANIFEST_ENTRIES=10000;
    private final Runnable beforeRecheck;
    public BackupManifestRepository(){this(()->{});}
    BackupManifestRepository(Runnable beforeRecheck){this.beforeRecheck=beforeRecheck;}
    private record Snapshot(boolean directory,long size,Object key,FileTime modified,FileTime created) {}
    private record Entry(int line,boolean directory,String area,String relative,long size,String sha) {
        String key(){return area+"/"+relative;}
    }
    private static Snapshot snapshot(Path path) throws IOException {
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if((!a.isDirectory()&&!a.isRegularFile())||!path.toRealPath().equals(path))throw new IOException("Unsafe boundary");
        return new Snapshot(a.isDirectory(),a.size(),a.fileKey(),a.lastModifiedTime(),a.creationTime());
    }
    private static MessageDigest digest() {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private static byte[] readManifest(Path path) throws IOException {
        if(snapshot(path).directory()||Files.size(path)>MANIFEST_BYTES)throw new IOException("Invalid manifest");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            var bytes=in.readNBytes(MANIFEST_BYTES+1);if(bytes.length>MANIFEST_BYTES)throw new IOException("Manifest limit");return bytes;
        }
    }
    private static Map<String,Entry> parse(byte[] bytes) throws IOException {
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        String[] lines=text.split("\n",-1);if(lines.length<4||lines.length>MANIFEST_ENTRIES+2)throw new IOException("Invalid manifest length");
        if(!lines[0].replaceFirst("\r$","").equals("IMAGEGATE-MANIFEST\t1"))throw new IOException("Invalid manifest header");
        var result=new LinkedHashMap<String,Entry>();
        for(int i=1;i<lines.length;i++){
            String line=lines[i].replaceFirst("\r$","");if(i==lines.length-1&&line.isEmpty())continue;
            if(line.length()>2048)throw new IOException("Manifest line limit");
            String[] f=line.split("\t",-1);
            if(f.length!=5||!Set.of("D","F").contains(f[0])||!Set.of("originals","annotations","stamps").contains(f[1]))throw new IOException("Invalid entry");
            String relative=f[2];String[] segments=relative.split("/",-1);
            if(relative.length()>1024||segments.length>8)throw new IOException("Invalid relative path");
            if(!relative.equals("."))for(String segment:segments){
                if(!segment.matches("[a-z0-9_.-]{1,128}")||segment.equals(".")||segment.equals("..")||segment.endsWith(".")
                        ||segment.matches("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?"))throw new IOException("Unsafe relative path");
            }
            boolean directory=f[0].equals("D");
            if(!f[3].matches("0|[1-9][0-9]{0,12}"))throw new IOException("Invalid size");
            long size=Long.parseLong(f[3]);
            if(size>1099511627776L||(directory?(size!=0||!f[4].equals("-")):!f[4].matches("[a-f0-9]{64}"))
                    ||(relative.equals(".")&&!directory))throw new IOException("Invalid digest entry");
            var entry=new Entry(i+1,directory,f[1],relative,size,f[4]);
            if(result.putIfAbsent(entry.key(),entry)!=null||result.size()>MANIFEST_ENTRIES)throw new IOException("Duplicate entry");
        }
        for(String area:List.of("originals","annotations","stamps"))if(!result.containsKey(area+"/."))throw new IOException("Missing root entry");
        for(Entry entry:result.values())if(!entry.relative().equals(".")){
            int slash=entry.relative().lastIndexOf('/');String parent=slash<0?".":entry.relative().substring(0,slash);
            Entry p=result.get(entry.area()+"/"+parent);if(p==null||!p.directory())throw new IOException("Missing directory entry");
        }
        return result;
    }
    private static final class Limit extends IOException {}
    private static final class Scan {
        final BackupManifestOptions options;final Map<String,Entry> expected;
        final Map<Path,Snapshot> snapshots=new LinkedHashMap<>();final Set<String> seen=new HashSet<>();
        final List<BackupManifestReport.Item> items=new ArrayList<>();long scanned,bytes;int matched;boolean limited;
        Scan(BackupManifestOptions options,Map<String,Entry> expected){this.options=options;this.expected=expected;}
        void item(Entry e,String outcome) throws Limit {
            if(items.size()>=MANIFEST_ENTRIES){limited=true;throw new Limit();}
            items.add(new BackupManifestReport.Item(e==null?null:e.line(),outcome));if(outcome.equals("MATCHED"))matched++;
        }
        void visit(Path path,Path root,String area,int depth) throws IOException {
            if(scanned>=options.maxEntries()){limited=true;throw new Limit();}scanned++;
            String relative=path.equals(root)?".":root.relativize(path).toString().replace('\\','/');
            String key=area+"/"+relative;Entry e=expected.get(key);seen.add(key);
            Snapshot a;
            try{a=snapshot(path);}catch(IOException invalid){item(e,"UNSAFE_OR_UNREADABLE");return;}
            snapshots.put(path,a);
            if(e==null)item(null,"UNEXPECTED_ENTRY");
            else if(e.directory()!=a.directory())item(e,"TYPE_MISMATCH");
            else if(a.directory())item(e,"MATCHED");
            else if(e.size()!=a.size())item(e,"SIZE_MISMATCH");
            else {
                if(a.size()>options.maxBytes()-bytes){limited=true;throw new Limit();}
                var sha=digest();long count=0;
                try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
                    byte[] buffer=new byte[32768];int n;
                    while(count<a.size()&&(n=in.read(buffer,0,(int)Math.min(buffer.length,a.size()-count)))!=-1){count+=n;bytes+=n;sha.update(buffer,0,n);}
                    if(count!=a.size()||in.read()!=-1||!a.equals(snapshot(path)))throw new IOException("File changed");
                }
                item(e,e.sha().equals(HexFormat.of().formatHex(sha.digest()))?"MATCHED":"CHECKSUM_MISMATCH");
            }
            if(a.directory()){
                if(depth>=8){item(e,"DEPTH_LIMIT");limited=true;return;}
                try(var children=Files.newDirectoryStream(path)){for(Path child:children)visit(child,root,area,depth+1);}
            }
        }
    }
    public BackupManifestReport inspect(BackupManifestOptions options,Instant now) throws IOException {
        try(var scope=new ReadOnlyStorageScope(options.originalsRoot(),options.annotationsRoot(),options.stampsRoot(),options.workersStopped())){
            return inspectWithinScope(options,now,scope);
        }
    }
    /** Reuses a live, root-bound scope; callers cannot bypass marker validation. */
    public BackupManifestReport inspectWithinScope(BackupManifestOptions options,Instant now,ReadOnlyStorageScope scope) throws IOException {
        scope.verify(options.originalsRoot(),options.annotationsRoot(),options.stampsRoot());
        var roots=Map.of("originals",options.originalsRoot(),"annotations",options.annotationsRoot(),"stamps",options.stampsRoot());
        // Revalidate canonical roots before touching markers or following manifest paths.
        for(Path root:roots.values())if(!snapshot(root).directory())throw new IOException("Missing root");
        Snapshot manifestStamp=snapshot(options.manifest());byte[] manifestBytes=readManifest(options.manifest());
        if(!HexFormat.of().formatHex(digest().digest(manifestBytes)).equals(options.manifestSha256()))throw new IOException("Manifest digest mismatch");
        var expected=parse(manifestBytes);var scan=new Scan(options,expected);
        {
            try{
                for(String area:List.of("originals","annotations","stamps"))scan.visit(roots.get(area),roots.get(area),area,0);
                for(Entry entry:expected.values())if(!scan.seen.contains(entry.key()))scan.item(entry,"MISSING_ENTRY");
            }catch(Limit limit){scan.limited=true;}
            beforeRecheck.run();
            for(var entry:scan.snapshots.entrySet())if(!entry.getValue().equals(snapshot(entry.getKey())))throw new IOException("Storage changed");
            if(!manifestStamp.equals(snapshot(options.manifest()))||!Arrays.equals(manifestBytes,readManifest(options.manifest())))throw new IOException("Manifest changed");
            scope.verify();
            return new BackupManifestReport(1,now,scan.scanned,scan.bytes,scan.limited,scan.matched,
                    scan.items.size()-scan.matched,List.copyOf(scan.items));
        }
    }
}
