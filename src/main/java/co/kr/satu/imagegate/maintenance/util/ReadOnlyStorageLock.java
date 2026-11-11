package co.kr.satu.imagegate.maintenance.util;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
/** Shared lock of an existing empty private marker only; never creates/writes a file. */
public final class ReadOnlyStorageLock implements AutoCloseable {
    public static final class StorageInUseException extends IOException {}
    private final Path path;
    private final BasicFileAttributes before;
    private FileChannel channel;
    private FileLock lock;
    public ReadOnlyStorageLock(Path root,String name) throws IOException {
        if(!name.equals("writer.lock")&&!name.equals(".quota-writer.lock"))throw new IllegalArgumentException("Invalid lock name");
        path=root.resolve(name);before=Files.exists(path,LinkOption.NOFOLLOW_LINKS)?attributes():null;
        if(before==null){if(!Files.notExists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Cannot inspect private lock");return;}
        try{
            channel=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
            try{lock=channel.tryLock(0,Long.MAX_VALUE,true);}catch(OverlappingFileLockException busy){throw new StorageInUseException();}
            if(lock==null)throw new StorageInUseException();verify();
        }catch(IOException|RuntimeException failure){if(channel!=null)channel.close();throw failure;}
    }
    private BasicFileAttributes attributes() throws IOException {
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isRegularFile()||a.size()!=0||!path.toRealPath().equals(path))throw new IOException("Invalid private lock");return a;
    }
    public void verify() throws IOException {
        if(before==null){if(!Files.notExists(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Private lock appeared");return;}
        var after=attributes();
        if(lock==null||!lock.isValid()||!java.util.Objects.equals(before.fileKey(),after.fileKey())
                ||!before.lastModifiedTime().equals(after.lastModifiedTime())||!before.creationTime().equals(after.creationTime()))
            throw new IOException("Private lock changed");
    }
    @Override public void close() throws IOException {try{if(lock!=null&&lock.isValid())lock.release();}finally{if(channel!=null)channel.close();}}
}
