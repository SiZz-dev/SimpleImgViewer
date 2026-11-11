package co.kr.satu.imagegate.media.repository;

import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.media.util.MediaHeaderDetector;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Private local catalog. The service account must exclusively own the storage directories. */
public final class LocalMediaStore implements MediaStore, AutoCloseable {
    private co.kr.satu.imagegate.media.service.TenantOriginalQuota quotas =
            new co.kr.satu.imagegate.media.service.TenantOriginalQuota(0, tenant -> 0);
    private java.nio.channels.FileChannel writerChannel;
    private java.nio.channels.FileLock writerLock;
    private Path lockedRoot;
    private volatile boolean closed;
    private final Path originals;
    private final long maxInputBytes;
    private final long minFreeBytes;
    private final SpaceProbe space;
    @FunctionalInterface interface SpaceProbe {long usable(Path path) throws IOException;}

    public LocalMediaStore(Path originals, long maxInputBytes) {
        this(originals,maxInputBytes,0);
    }
    public LocalMediaStore(Path originals,long maxInputBytes,long minFreeBytes){
        this(originals,maxInputBytes,minFreeBytes,path->Files.getFileStore(path).getUsableSpace());
    }
    LocalMediaStore(Path originals,long maxInputBytes,long minFreeBytes,SpaceProbe space){
        if(minFreeBytes<0)throw new IllegalArgumentException("Invalid disk reserve");
        this.minFreeBytes=minFreeBytes;this.space=space;
        if (maxInputBytes < 1) throw new IllegalArgumentException("Input limit must be positive");
        this.originals = originals.toAbsolutePath().normalize();
        this.maxInputBytes = maxInputBytes;
    }

    /** A quota-enabled local store has one writer JVM; committed originals remain readable. */
    public LocalMediaStore(Path originals,long maxInputBytes,long minFreeBytes,long tenantMaxBytes) throws IOException {
        this(originals,maxInputBytes,minFreeBytes);
        if(tenantMaxBytes<0||tenantMaxBytes>1125899906842624L)throw new IllegalArgumentException("Invalid tenant quota");
        if(tenantMaxBytes>0){
            try {
                lockedRoot=root(true);
                if(!lockedRoot.equals(this.originals))throw new IOException("Unsafe original quota root");
                Path lock=lockedRoot.resolve(".quota-writer.lock");
                if(Files.exists(lock,LinkOption.NOFOLLOW_LINKS)&&(!Files.isRegularFile(lock,LinkOption.NOFOLLOW_LINKS)
                        ||!lock.toRealPath().equals(lock)||Files.size(lock)!=0))throw new IOException("Invalid original writer lock");
                writerChannel=java.nio.channels.FileChannel.open(lock,java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                try { writerLock=writerChannel.tryLock(); }
                catch(java.nio.channels.OverlappingFileLockException busy){throw new IOException("Original storage already has a quota writer");}
                if(writerLock==null)throw new IOException("Original storage already has a quota writer");
                quotas=new co.kr.satu.imagegate.media.service.TenantOriginalQuota(tenantMaxBytes,this::measureUsage);
            } catch(IOException|RuntimeException failure){if(writerChannel!=null)writerChannel.close();throw failure;}
        }
    }

    private long measureUsage(String tenantId) throws IOException {
        try { return scanUsage(tenantId); }
        catch(RuntimeException invalid){throw new IOException("Cannot measure original usage",invalid);}
    }

    private long scanUsage(String tenantId) throws IOException {
        Path root=root(false);
        if(root==null||!root.equals(lockedRoot)||writerLock==null||!writerLock.isValid())
            throw new IOException("Original quota writer unavailable");
        Path tenant=directory(root,tenantFolder(tenantId),false);
        if(tenant==null)return 0;
        long total=0;int scanned=0;
        try(var entries=Files.newDirectoryStream(tenant)){
            for(Path entry:entries){
                if(++scanned>100000)throw new IOException("Original quota scan limit");
                String id=entry.getFileName().toString();
                directory(tenant,id,false);
                boolean staging=id.matches("\\.upload-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
                if(!staging&&!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                    throw new IOException("Unexpected original quota entry");
                MediaRecord record=staging?null:find(tenantId,id).orElseThrow(()->new IOException("Missing original catalog"));
                int files=0;
                try(var contents=Files.newDirectoryStream(entry)){
                    for(Path file:contents){
                        if(++files>3||++scanned>100000||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                                ||!file.toRealPath().equals(file))throw new IOException("Invalid original quota content");
                        String name=file.getFileName().toString();long size=Files.size(file);
                        if(name.equals("metadata.properties")){if(size>16384)throw new IOException("Invalid quota metadata");continue;}
                        if(staging?!(name.equals("pending")||name.matches("original\\.[a-z0-9]{1,8}")):!name.equals(record.storedFileName()))
                            throw new IOException("Unexpected quota content");
                        try{total=Math.addExact(total,size);}catch(ArithmeticException overflow){throw new IOException("Original usage overflow");}
                    }
                }
            }
        }
        return total;
    }

    @Override public void close() throws IOException {
        quotas.stop();
        closed=true;
        try { if(writerLock!=null&&writerLock.isValid())writerLock.release(); }
        finally { if(writerChannel!=null)writerChannel.close(); }
    }

    @Override
    public MediaRecord register(String tenantId, String ownerId, String fileName, InputStream input)
            throws IOException {
        if(closed)throw new IOException("Original writer closed");
        identifier(tenantId); identifier(ownerId);
        String displayName = displayName(fileName);
        Path root = root(true), tenant = directory(root, tenantFolder(tenantId), true);
        try(var quota=quotas.acquire(tenantId)) {
            checkSpace(tenant,0);
            String id = UUID.randomUUID().toString();
            Path staging = Files.createDirectory(tenant.resolve(".upload-" + id));
            Path pending = staging.resolve("pending");
            try {
                var digest = MessageDigest.getInstance("SHA-256");
                long count = 0, sinceCheck=1048576;
                try (var data = new DigestInputStream(input, digest);
                        var output = Files.newOutputStream(pending)) {
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    while ((read = data.read(buffer)) != -1) {
                        count += read;
                        if (count > maxInputBytes) throw new MediaException(MediaException.Code.FILE_TOO_LARGE);
                        if(sinceCheck>=1048576){checkSpace(staging,read);sinceCheck=0;}
                        quota.grow(read);
                        output.write(buffer, 0, read);sinceCheck+=read;
                    }
                }
                if (count == 0) throw new MediaException(MediaException.Code.INVALID_MEDIA);
                byte[] header;
                try (var data = Files.newInputStream(pending)) { header = data.readNBytes(512); }
                var candidate = MediaHeaderDetector.detect(header);
                if (candidate == null) throw new MediaException(MediaException.Code.UNSUPPORTED_MEDIA);
                String stored = "original." + candidate.extension();
                Files.move(pending, staging.resolve(stored));
                var record = new MediaRecord(id, tenantId, ownerId, UUID.randomUUID().toString(),
                        displayName, candidate.type(), candidate.mimeType(), count,
                        HexFormat.of().formatHex(digest.digest()), stored, Instant.now());
                checkSpace(staging,0);
                writeMetadata(staging.resolve("metadata.properties"), record);
                // Same filesystem, no non-atomic fallback. Incomplete uploads are never catalog entries.
                Files.move(staging, tenant.resolve(id), StandardCopyOption.ATOMIC_MOVE);
                quota.commit();
                return record;
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            } finally {
                try { cleanupStaging(staging); }
                catch(IOException|RuntimeException failure){quota.invalidate();throw failure;}
            }
        }
    }

    private void checkSpace(Path path,long nextBytes) throws IOException {
        if(minFreeBytes>0&&space.usable(path)<minFreeBytes+nextBytes)
            throw new MediaException(MediaException.Code.INSUFFICIENT_STORAGE);
    }

    @Override
    public Optional<MediaRecord> find(String tenantId, String mediaId) throws IOException {
        identifier(tenantId); identifier(mediaId);
        if(!mediaId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return Optional.empty();
        Path root = root(false);
        if (root == null) return Optional.empty();
        Path tenant = directory(root, tenantFolder(tenantId), false);
        if (tenant == null) return Optional.empty();
        Path entry = directory(tenant, mediaId, false);
        if (entry == null) return Optional.empty();
        Path metadata = entry.resolve("metadata.properties");
        if (!Files.isRegularFile(metadata,LinkOption.NOFOLLOW_LINKS)
                || !metadata.toRealPath().getParent().equals(entry) || Files.size(metadata)>16384)
            throw new IOException("Invalid private catalog");
        var values = new Properties();
        try (var stream = Files.newInputStream(metadata)) { values.load(stream); }
        try {
            var record = new MediaRecord(values.getProperty("mediaId"),values.getProperty("tenantId"),
                    values.getProperty("ownerId"),values.getProperty("version"),values.getProperty("fileName"),
                    MediaType.valueOf(values.getProperty("type")),values.getProperty("mime"),
                    Long.parseLong(values.getProperty("size")),values.getProperty("sha256"),
                    values.getProperty("storedFileName"),Instant.parse(values.getProperty("createdAt")));
            if (!mediaId.equals(record.mediaId()) || !tenantId.equals(record.tenantId())
                    || record.ownerId()==null || record.storedFileName()==null
                    || !record.storedFileName().matches("original\\.[a-z0-9]+"))
                throw new IOException("Invalid private catalog identity");
            Path blob = entry.resolve(record.storedFileName());
            if (!Files.isRegularFile(blob,LinkOption.NOFOLLOW_LINKS)
                    || !blob.toRealPath().getParent().equals(entry) || Files.size(blob)!=record.sizeBytes())
                throw new IOException("Invalid private catalog content");
            return Optional.of(record);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new IOException("Invalid private catalog",invalid);
        }
    }

    @Override
    public Path resolveOriginal(MediaRecord expected) throws IOException {
        var actual=find(expected.tenantId(),expected.mediaId()).orElseThrow(()->new IOException("Missing original"));
        if(!expected.equals(actual)) throw new IOException("Original identity changed");
        Path root=root(false);
        Path tenant=directory(root,tenantFolder(actual.tenantId()),false);
        Path entry=directory(tenant,actual.mediaId(),false);
        Path path=entry.resolve(actual.storedFileName());
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().getParent().equals(entry))
            throw new IOException("Invalid original boundary");
        return path;
    }

    private Path root(boolean create) throws IOException {
        if (create) Files.createDirectories(originals);
        if (!Files.exists(originals,LinkOption.NOFOLLOW_LINKS)) return null;
        return originals.toRealPath();
    }

    private static Path directory(Path parent,String name,boolean create) throws IOException {
        Path path = parent.resolve(name);
        if (create && !Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createDirectory(path); }
            catch (java.nio.file.FileAlreadyExistsException race) { /* verify below */ }
        }
        if (!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) return null;
        Path real = path.toRealPath();
        if (!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !real.equals(path))
            throw new IOException("Invalid private storage boundary");
        return real;
    }

    private static void cleanupStaging(Path staging) throws IOException {
        if (!Files.exists(staging,LinkOption.NOFOLLOW_LINKS)) return;
        // Only exact files created by this method; never recursively follow unknown content.
        try (var files = Files.newDirectoryStream(staging)) {
            for (Path file : files) Files.deleteIfExists(file);
        }
        Files.deleteIfExists(staging);
    }

    private static String displayName(String name) {
        if (name == null) throw new MediaException(MediaException.Code.INVALID_REQUEST);
        String value = name.substring(Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'))+1);
        if (value.isBlank() || value.length()>255 || value.chars().anyMatch(Character::isISOControl))
            throw new MediaException(MediaException.Code.INVALID_REQUEST);
        return value;
    }

    private static void identifier(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}"))
            throw new MediaException(MediaException.Code.INVALID_REQUEST);
    }

    private static String tenantFolder(String tenantId) {
        try {
            return "tenant-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void writeMetadata(Path path,MediaRecord record) throws IOException {
        var p = new Properties();
        p.setProperty("mediaId",record.mediaId()); p.setProperty("tenantId",record.tenantId());
        p.setProperty("ownerId",record.ownerId()); p.setProperty("version",record.mediaVersion());
        p.setProperty("fileName",record.fileName()); p.setProperty("type",record.mediaType().name());
        p.setProperty("mime",record.mimeType()); p.setProperty("size",Long.toString(record.sizeBytes()));
        p.setProperty("sha256",record.sha256()); p.setProperty("storedFileName",record.storedFileName());
        p.setProperty("createdAt",record.createdAt().toString());
        try (var stream = Files.newOutputStream(path)) { p.store(stream,"ImageGate private catalog v1"); }
    }
}
