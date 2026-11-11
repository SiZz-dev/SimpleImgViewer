package co.kr.satu.imagegate.conversion.repository;
import co.kr.satu.imagegate.conversion.domain.TileJobStatus;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Bounded persistent states. Caller must hold the TileCache writer/lease for this converted root. */
public final class TileJobStore {
    private final Path root;private final int limit;
    public TileJobStore(Path converted,int limit)throws IOException{
        if(limit<1||limit>1024)throw new IllegalArgumentException();this.limit=limit;
        root=converted.toAbsolutePath().normalize().resolve("tile-jobs-v1");
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(root);boundary();inventory();
    }
    private void boundary()throws IOException{if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!root.toRealPath().equals(root))throw new IOException("Invalid tile state storage");}
    private Path file(String key)throws IOException{if(key==null||!key.matches("[a-f0-9]{64}"))throw new IOException("Invalid tile key");boundary();return root.resolve(key+".properties");}
    private void regular(Path p)throws IOException{if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||!p.toRealPath().equals(p)||Files.size(p)>4096)throw new IOException("Invalid tile state");}
    private int inventory()throws IOException{
        boundary();int count=0;try(var children=Files.newDirectoryStream(root)){for(var child:children){
            if(++count>limit||!child.getFileName().toString().matches("[a-f0-9]{64}[.]properties"))throw new IOException("Tile state capacity or inventory invalid");regular(child);
        }}return count;
    }
    public synchronized TileJobStatus read(String key,int page)throws IOException{
        Path file=file(key);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return new TileJobStatus(key,page,"not_requested",null);regular(file);
        byte[] bytes;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){bytes=input.readNBytes(4097);}if(bytes.length>4096)throw new IOException("Invalid tile state");
        var p=properties(bytes);
        try{
            var state=new TileJobStatus(p.getProperty("key"),Integer.parseInt(p.getProperty("page","0")),p.getProperty("state"),p.getProperty("error"));
            if(!key.equals(state.key())||page!=state.page()||!valid(state))throw new IOException("Invalid tile state");return state;
        }catch(IllegalArgumentException|NullPointerException invalid){throw new IOException("Invalid tile state",invalid);}
    }
    private Properties properties(byte[] bytes)throws IOException{
        var p=new Properties(){@Override public synchronized Object put(Object key,Object value){if(containsKey(key))throw new IllegalArgumentException("Duplicate tile state field");return super.put(key,value);}};
        try{p.load(new ByteArrayInputStream(bytes));var expected=new HashSet<>(Set.of("key","page","state"));if(p.containsKey("error"))expected.add("error");
            if(!p.stringPropertyNames().equals(expected))throw new IOException("Unknown tile state fields");return p;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid tile state",invalid);}
    }
    private static boolean valid(TileJobStatus s){return s.page()>0&&s.page()<=10000&&Set.of("queued","running","ready","failed").contains(s.state())
            &&(s.error()==null||Set.of("INTERRUPTED","TIMEOUT","TILE_FAILED","ACCESS_DENIED","TILE_PROFILE_UNAVAILABLE","CONVERSION_BUSY","TILE_QUEUE_FULL").contains(s.error()))
            &&("failed".equals(s.state())==(s.error()!=null));}
    public synchronized void write(TileJobStatus status)throws IOException{
        if(!valid(status))throw new IOException("Invalid tile state");Path target=file(status.key());
        if(!Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&inventory()>=limit)throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CAPACITY);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))regular(target);
        var p=new Properties();p.setProperty("key",status.key());p.setProperty("page",""+status.page());p.setProperty("state",status.state());if(status.error()!=null)p.setProperty("error",status.error());
        var bytes=new ByteArrayOutputStream();p.store(bytes,null);Path temp=root.resolve(".state-"+UUID.randomUUID());
        boolean owned=false;
        try{
            try(var output=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                owned=true;var data=java.nio.ByteBuffer.wrap(bytes.toByteArray());while(data.hasRemaining())output.write(data);output.force(true);
            }
            boundary();Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{if(owned&&Files.exists(temp,LinkOption.NOFOLLOW_LINKS)){regular(temp);Files.delete(temp);}}
    }
    public record Snapshot(TileJobStatus state,long modified,String sha256){}
    public synchronized List<Snapshot> snapshots()throws IOException{
        inventory();var result=new ArrayList<Snapshot>();
        try(var children=Files.newDirectoryStream(root)){for(var path:children){
            regular(path);byte[] bytes=bounded(path);var p=properties(bytes);
            String key=path.getFileName().toString().replace(".properties","");
            try{var state=read(key,Integer.parseInt(p.getProperty("page","0")));
                if(!Arrays.equals(bytes,bounded(path)))throw new IOException("Tile state changed");
                result.add(new Snapshot(state,Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis(),co.kr.satu.imagegate.conversion.util.PreviewKeys.sha256(bytes)));
            }catch(RuntimeException e){throw new IOException("Invalid tile state",e);}
        }}return List.copyOf(result);
    }
    private byte[] bounded(Path path)throws IOException{regular(path);try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=input.readNBytes(4097);if(bytes.length>4096)throw new IOException("Invalid tile state");return bytes;}}
    /** Startup only, before queue admission, under the shared cache writer. Never starts conversions. */
    public synchronized int recoverInterrupted()throws IOException{
        int recovered=0;for(var snapshot:snapshots())if(Set.of("queued","running").contains(snapshot.state().state())){
            write(new TileJobStatus(snapshot.state().key(),snapshot.state().page(),"failed","INTERRUPTED"));recovered++;
        }return recovered;
    }
    /** No cache/queue callbacks while holding the state lock. Caller first resolves cache ownership. */
    public synchronized boolean remove(Snapshot snapshot)throws IOException{
        if(!Set.of("ready","failed").contains(snapshot.state().state()))return false;
        Path path=file(snapshot.state().key());if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis()!=snapshot.modified()
                ||!co.kr.satu.imagegate.conversion.util.PreviewKeys.sha256(bounded(path)).equals(snapshot.sha256()))return false;
        if(!read(snapshot.state().key(),snapshot.state().page()).equals(snapshot.state()))return false;
        Files.delete(path);return true;
    }
}
