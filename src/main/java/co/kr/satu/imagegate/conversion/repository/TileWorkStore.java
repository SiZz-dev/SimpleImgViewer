package co.kr.satu.imagegate.conversion.repository;
import co.kr.satu.imagegate.conversion.domain.TileMaintenanceReport;
import co.kr.satu.imagegate.conversion.util.TileWorkCleanup;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Durable per-job ownership. Unknown legacy directories without an owner are never adopted. */
public final class TileWorkStore {
    private final Path root;private final int maxTiles,maxEntries;private final Set<String> active=new HashSet<>();
    public record Work(String id,String key,Path path){}
    public TileWorkStore(Path converted,int maxTiles)throws IOException{
        this(converted,maxTiles,4096);
    }
    public TileWorkStore(Path converted,int maxTiles,int maxEntries)throws IOException{
        Path parent=converted.toAbsolutePath().normalize();directory(parent);root=parent.resolve("tile-work-v1");this.maxTiles=maxTiles;this.maxEntries=maxEntries;
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(root);directory(root);
    }
    private static void directory(Path p)throws IOException{if(!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)||!p.toRealPath().equals(p))throw new IOException("Invalid tile work storage");}
    private Path marker(String id){return root.resolve("job-"+id+".owner");}
    private static byte[] content(String id,String key){return ("tile-work-owner-v1\n"+id+"\n"+key+"\n").getBytes(StandardCharsets.US_ASCII);}
    private Work owner(Path marker)throws IOException{
        directory(root);if(!Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)||!marker.toRealPath().equals(marker)||Files.size(marker)>256)throw new IOException("Invalid work owner");
        byte[] bytes;try(var in=Files.newInputStream(marker,LinkOption.NOFOLLOW_LINKS)){bytes=in.readNBytes(257);}
        String[] lines=new String(bytes,StandardCharsets.US_ASCII).split("\n",-1);
        if(lines.length!=4||!lines[0].equals("tile-work-owner-v1")||!lines[3].isEmpty()||!lines[2].matches("[a-f0-9]{64}"))throw new IOException("Invalid work owner");
        try{if(!UUID.fromString(lines[1]).toString().equals(lines[1])||!marker.getFileName().toString().equals("job-"+lines[1]+".owner")||!Arrays.equals(bytes,content(lines[1],lines[2])))throw new IOException("Invalid work owner");}
        catch(IllegalArgumentException e){throw new IOException("Invalid work owner",e);}
        return new Work(lines[1],lines[2],root.resolve("job-"+lines[1]));
    }
    public synchronized Work begin(String key)throws IOException{
        if(key==null||!key.matches("[a-f0-9]{64}"))throw new IOException("Invalid tile work key");directory(root);
        int entries=0;try(var children=Files.newDirectoryStream(root)){for(var ignored:children)if(++entries>maxEntries)throw new IOException("Tile work entry capacity");}
        for(String id:active)if(!Files.exists(root.resolve("job-"+id),LinkOption.NOFOLLOW_LINKS))entries++;
        if(entries+2>maxEntries)throw new IOException("Tile work entry capacity");
        String id=UUID.randomUUID().toString();Path path=root.resolve("job-"+id);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Tile work collision");
        try(var channel=java.nio.channels.FileChannel.open(marker(id),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            var bytes=java.nio.ByteBuffer.wrap(content(id,key));while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        active.add(id);return new Work(id,key,path);
    }
    public synchronized void finish(Work work)throws IOException{
        if(!active.contains(work.id()))throw new IOException("Tile work owner changed");
        try{if(!owner(marker(work.id())).equals(work))throw new IOException("Tile work owner changed");TileWorkCleanup.clean(work.path(),maxTiles);if(!owner(marker(work.id())).equals(work))throw new IOException("Tile work owner changed");Files.delete(marker(work.id()));}
        finally{active.remove(work.id());}
    }
    public synchronized TileMaintenanceReport maintain(int maxEntries,long retentionMillis,long now,boolean delete)throws IOException{
        directory(root);var paths=new ArrayList<Path>();try(var children=Files.newDirectoryStream(root)){for(var p:children){if(paths.size()>=maxEntries)throw new IOException("Tile work scan limit");paths.add(p);}}
        var owned=new HashSet<Path>();int inspected=0,removed=0,skipped=0;long retained=0,removedBytes=0;
        for(var path:paths)if(path.getFileName().toString().endsWith(".owner")){
            co.kr.satu.imagegate.conversion.util.PreviewCancellation.check();
            try{
                Work work=owner(path);owned.add(path);owned.add(work.path());if(active.contains(work.id())){skipped++;continue;}
                long bytes=Files.size(path)+TileWorkCleanup.bytes(work.path(),maxTiles);retained+=bytes;inspected++;
                if(delete&&now-Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()>=retentionMillis){
                    if(!owner(path).equals(work))throw new IOException("Work owner changed");TileWorkCleanup.clean(work.path(),maxTiles);
                    if(!owner(path).equals(work))throw new IOException("Work owner changed");Files.delete(path);removed++;retained-=bytes;removedBytes+=bytes;
                }
            }catch(IOException|RuntimeException invalid){skipped++;}
        }
        for(var path:paths)if(!owned.contains(path)&&!path.getFileName().toString().endsWith(".owner"))skipped++;
        return new TileMaintenanceReport(inspected,removed,removedBytes,retained,skipped);
    }
}
