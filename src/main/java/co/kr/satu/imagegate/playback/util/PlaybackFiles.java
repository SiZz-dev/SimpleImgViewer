package co.kr.satu.imagegate.playback.util;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Technical boundaries for service-account-only playback storage. Never accepts browser paths. */
public final class PlaybackFiles {
    private PlaybackFiles() {}
    public static Path root(Path configured) throws IOException {
        Path path=configured.toAbsolutePath().normalize();Files.createDirectories(path);
        if(!path.toRealPath().equals(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))throw invalid();
        return path;
    }
    public static Path directory(Path parent,String name,boolean create) throws IOException {
        if(!name.matches("[a-z0-9][a-z0-9._-]{0,127}"))throw invalid();
        Path path=parent.resolve(name);
        if(create&&!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(path); } catch(FileAlreadyExistsException race) { /* verify */ }
        }
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!path.toRealPath().equals(path))throw invalid();
        return path;
    }
    public static void regular(Path file,long max) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().equals(file)
                ||Files.size(file)>max)throw invalid();
    }
    public static Properties read(Path file,long max) throws IOException {
        regular(file,max);var values=new Properties();
        try(var in=Files.newInputStream(file)){values.load(in);}return values;
    }
    public static void write(Path file,Properties values) throws IOException {
        Path tmp=file.resolveSibling("write-"+UUID.randomUUID()+".tmp");
        try {
            try(var out=Files.newOutputStream(tmp,StandardOpenOption.CREATE_NEW)){values.store(out,"ImageGate private playback v1");}
            Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    public static String identity(String value) {
        if(value==null||!value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))throw new IllegalArgumentException("Invalid identity");
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static boolean uuid(String id) {
        return id!=null&&id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    public static IOException invalid(){return new IOException("Invalid private playback storage");}
    public static final class WriterLock implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        public WriterLock(Path root) throws IOException {
            Path file=root.resolve("writer.lock");
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS))regular(file,1024);
            channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            try {
                lock=channel.tryLock();
                if(lock==null)throw new IOException("Editing storage already has a writer");
            } catch(IOException|RuntimeException failure){channel.close();throw failure;}
        }
        @Override public void close() throws IOException { try{lock.release();}finally{channel.close();} }
    }
}
