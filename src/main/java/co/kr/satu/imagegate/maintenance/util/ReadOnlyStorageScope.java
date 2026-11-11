package co.kr.satu.imagegate.maintenance.util;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
/** Owns existing shared markers throughout sequential inspections; never creates storage. */
public final class ReadOnlyStorageScope implements AutoCloseable {
    private record Stamp(Object key,FileTime modified,FileTime created,long size) {}
    private final List<Path> roots;private final List<Stamp> before=new ArrayList<>();
    private final List<ReadOnlyStorageLock> locks=new ArrayList<>();
    private final Thread owner=Thread.currentThread();private boolean closed;
    public ReadOnlyStorageScope(Path originals,Path annotations,Path stamps,boolean workersStopped) throws IOException {
        roots=List.of(originals,annotations,stamps);
        if(!workersStopped)throw new IllegalArgumentException("Stopped workers required");
        for(int i=0;i<roots.size();i++){
            Path p=roots.get(i);if(!p.isAbsolute()||!p.equals(p.normalize()))throw new IllegalArgumentException("Invalid root");
            for(int j=0;j<i;j++)if(p.startsWith(roots.get(j))||roots.get(j).startsWith(p))throw new IllegalArgumentException("Overlapping roots");
            before.add(stamp(p));
        }
        try{
            locks.add(new ReadOnlyStorageLock(originals,".quota-writer.lock"));
            locks.add(new ReadOnlyStorageLock(annotations,"writer.lock"));
            locks.add(new ReadOnlyStorageLock(stamps,"writer.lock"));verify();
        }catch(IOException|RuntimeException failure){try{close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private static Stamp stamp(Path p) throws IOException {
        var a=Files.readAttributes(p,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!a.isDirectory()||!p.toRealPath().equals(p))throw new IOException("Unsafe root");
        return new Stamp(a.fileKey(),a.lastModifiedTime(),a.creationTime(),a.size());
    }
    public void verify(Path originals,Path annotations,Path stamps) throws IOException {
        if(!roots.equals(List.of(originals,annotations,stamps)))throw new IOException("Scope roots mismatch");verify();
    }
    public void verifyOriginals(Path originals) throws IOException {
        if(!roots.getFirst().equals(originals))throw new IOException("Scope originals mismatch");verify();
    }
    public void verify() throws IOException {
        if(closed||Thread.currentThread()!=owner)throw new IOException("Inactive scope");
        for(int i=0;i<roots.size();i++)if(!before.get(i).equals(stamp(roots.get(i))))throw new IOException("Root changed");
        for(var lock:locks)lock.verify();
    }
    @Override public void close() throws IOException {
        if(Thread.currentThread()!=owner)throw new IOException("Scope thread mismatch");
        if(closed)return;closed=true;IOException failure=null;
        for(int i=locks.size()-1;i>=0;i--)try{locks.get(i).close();}catch(IOException cleanup){if(failure==null)failure=cleanup;else failure.addSuppressed(cleanup);}
        if(failure!=null)throw failure;
    }
}
