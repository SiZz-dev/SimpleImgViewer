package co.kr.satu.imagegate.maintenance.repository;

import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.media.util.MediaHeaderDetector;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageLock;
import co.kr.satu.imagegate.maintenance.util.ReadOnlyStorageScope;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Read-only, stopped-storage inspection. Never creates locks, folders, state or repair files. */
public final class OriginalIntegrityRepository {
    private static final String UUID="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Set<String> FIELDS=Set.of("mediaId","tenantId","ownerId","version","fileName","type","mime","size","sha256","storedFileName","createdAt");
    private final Runnable beforeHash,beforeFinish;
    public OriginalIntegrityRepository(){this(()->{},()->{});}
    OriginalIntegrityRepository(Runnable beforeHash){this(beforeHash,()->{});}
    OriginalIntegrityRepository(Runnable beforeHash,Runnable beforeFinish){this.beforeHash=beforeHash;this.beforeFinish=beforeFinish;}
    private record Stamp(long size,Object key,java.nio.file.attribute.FileTime modified,java.nio.file.attribute.FileTime created,boolean directory) {}
    private static Stamp stamp(Path path,boolean directory) throws IOException {
        var a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if((directory?!a.isDirectory():!a.isRegularFile())||!path.toRealPath().equals(path))throw new IOException("Unsafe original boundary");
        return new Stamp(a.size(),a.fileKey(),a.lastModifiedTime(),a.creationTime(),a.isDirectory());
    }
    private static byte[] metadata(Path path) throws IOException {
        if(stamp(path,false).size()>16384)throw new IOException("Catalog limit");
        try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] bytes=in.readNBytes(16385);if(bytes.length>16384)throw new IOException("Catalog limit");return bytes;
        }
    }
    private static MessageDigest digest() throws IOException {
        try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IOException("Digest unavailable");}
    }
    private static String hash(byte[] data) throws IOException {return HexFormat.of().formatHex(digest().digest(data));}
    private static boolean identifier(String s){return s!=null&&s.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");}
    private static final class Limit extends IOException {}
    private static final class Budget {
        final OriginalIntegrityOptions options;long scanned,bytes;boolean limited;int verified;
        final List<OriginalIntegrityReport.Item> items=new ArrayList<>();
        final Map<Path,Stamp> stable=new LinkedHashMap<>();
        final Map<Path,String> catalogs=new LinkedHashMap<>();
        Budget(OriginalIntegrityOptions options){this.options=options;}
        void next() throws Limit {if(scanned>=options.maxEntries()){limited=true;throw new Limit();}scanned++;}
        void item(String id,String outcome) throws Limit {
            if(items.size()>=options.maxFiles()){limited=true;throw new Limit();}
            items.add(new OriginalIntegrityReport.Item(id,outcome));if(outcome.equals("VERIFIED"))verified++;
        }
    }
    public OriginalIntegrityReport inspect(OriginalIntegrityOptions options,Instant now) throws IOException {
        var before=stamp(options.originalsRoot(),true);
        try(var lock=new ReadOnlyStorageLock(options.originalsRoot(),".quota-writer.lock")){
            var report=inspectStopped(options,now);
            if(!before.equals(stamp(options.originalsRoot(),true)))throw new IOException("Original root changed");
            lock.verify();return report;
        }
    }
    /** Uses the original-root lock already owned by the combined acceptance scope. */
    public OriginalIntegrityReport inspectWithinScope(OriginalIntegrityOptions options,Instant now,ReadOnlyStorageScope scope) throws IOException {
        scope.verifyOriginals(options.originalsRoot());
        var report=inspectStopped(options,now);scope.verifyOriginals(options.originalsRoot());return report;
    }
    private OriginalIntegrityReport inspectStopped(OriginalIntegrityOptions options,Instant now) throws IOException {
        Path root=options.originalsRoot();var rootStamp=stamp(root,true);var b=new Budget(options);
        try(var tenants=Files.newDirectoryStream(root)) {
            for(Path tenant:tenants){
                b.next();String name=tenant.getFileName().toString();
                if(name.equals(".quota-writer.lock")){
                    try{if(stamp(tenant,false).size()!=0)b.item(null,"INVALID_OR_UNREADABLE");}
                    catch(IOException invalid){b.item(null,"INVALID_OR_UNREADABLE");}
                    continue;
                }
                if(!name.matches("tenant-[a-f0-9]{64}")){b.item(null,"UNEXPECTED_ENTRY");continue;}
                try {
                    var tenantStamp=stamp(tenant,true);b.stable.put(tenant,tenantStamp);
                    try(var entries=Files.newDirectoryStream(tenant)) {
                        for(Path entry:entries){
                            b.next();String id=entry.getFileName().toString();
                            if(!id.matches(UUID)){b.item(null,"UNEXPECTED_ENTRY");continue;}
                            if(b.items.size()>=options.maxFiles()){b.limited=true;throw new Limit();}
                            String outcome;
                            try{outcome=verify(root,rootStamp,tenant,tenantStamp,entry,id,b);}
                            catch(Limit limit){throw limit;}
                            catch(IOException|IllegalArgumentException|NullPointerException invalid){outcome="INVALID_OR_UNREADABLE";}
                            b.item(id,outcome);
                        }
                    }
                }catch(Limit limit){throw limit;}
                catch(IOException invalid){b.item(null,"INVALID_OR_UNREADABLE");}
            }
        }catch(Limit limit){/* A bounded partial report is intentional. */}
        beforeFinish.run();
        for(var observed:b.stable.entrySet())if(!observed.getValue().equals(stamp(observed.getKey(),observed.getValue().directory())))
            throw new IOException("Previously inspected entry changed");
        for(var catalog:b.catalogs.entrySet())if(!catalog.getValue().equals(hash(metadata(catalog.getKey())))
                ||!b.stable.get(catalog.getKey()).equals(stamp(catalog.getKey(),false)))
            throw new IOException("Previously inspected catalog changed");
        if(!rootStamp.equals(stamp(root,true)))throw new IOException("Original root changed");
        return new OriginalIntegrityReport(1,now,b.scanned,b.bytes,b.limited,b.verified,b.items.size()-b.verified,List.copyOf(b.items));
    }
    private String verify(Path root,Stamp rootStamp,Path tenant,Stamp tenantStamp,Path entry,String id,Budget b) throws IOException {
        var entryStamp=stamp(entry,true);Path meta=entry.resolve("metadata.properties");
        var metaStamp=stamp(meta,false);byte[] raw=metadata(meta);var p=new Properties();p.load(new ByteArrayInputStream(raw));
        String tenantId=p.getProperty("tenantId"),stored=p.getProperty("storedFileName"),expected=p.getProperty("sha256"),version=p.getProperty("version"),name=p.getProperty("fileName");
        if(!p.stringPropertyNames().equals(FIELDS)||!id.equals(p.getProperty("mediaId"))||!identifier(tenantId)
                ||!identifier(p.getProperty("ownerId"))||version==null||!version.matches(UUID)
                ||!tenant.getFileName().toString().equals("tenant-"+hash(tenantId.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                ||stored==null||!stored.matches("original\\.[a-z0-9]{1,8}")||expected==null||!expected.matches("[a-f0-9]{64}")
                ||name==null||name.isBlank()||name.length()>255||name.chars().anyMatch(Character::isISOControl))return "INVALID_OR_UNREADABLE";
        Instant.parse(p.getProperty("createdAt"));long size=Long.parseLong(p.getProperty("size"));
        if(size<1||size>1099511627776L)return "INVALID_OR_UNREADABLE";
        Path blob=entry.resolve(stored);var blobStamp=stamp(blob,false);
        if(blobStamp.size()!=size)return "SIZE_MISMATCH";
        try(var files=Files.newDirectoryStream(entry)) {
            for(Path file:files){b.next();String f=file.getFileName().toString();if(!f.equals(stored)&&!f.equals("metadata.properties"))return "UNEXPECTED_ENTRY";}
        }
        if(size>b.options.maxBytes()-b.bytes){b.limited=true;throw new Limit();}
        beforeHash.run();
        if(!entryStamp.equals(stamp(entry,true))||!metaStamp.equals(stamp(meta,false))||!Arrays.equals(raw,metadata(meta))
                ||!blobStamp.equals(stamp(blob,false)))return "CHANGED_DURING_SCAN";
        var sha=digest();long count=0;byte[] header;
        try(var in=Files.newInputStream(blob,LinkOption.NOFOLLOW_LINKS)) {
            header=in.readNBytes((int)Math.min(size,512));b.bytes+=header.length;count+=header.length;sha.update(header);
            byte[] block=new byte[32768];int n;
            // Request at most the remaining advertised size, then read one extra byte to detect growth.
            while(count<size&&(n=in.read(block,0,(int)Math.min(block.length,size-count)))!=-1){count+=n;b.bytes+=n;sha.update(block,0,n);}
            if(in.read()!=-1)return "CHANGED_DURING_SCAN";
        }
        if(count!=size||!blobStamp.equals(stamp(blob,false))||!metaStamp.equals(stamp(meta,false))
                ||!Arrays.equals(raw,metadata(meta))||!entryStamp.equals(stamp(entry,true))
                ||!tenantStamp.equals(stamp(tenant,true))||!rootStamp.equals(stamp(root,true)))return "CHANGED_DURING_SCAN";
        if(!expected.equals(HexFormat.of().formatHex(sha.digest())))return "CHECKSUM_MISMATCH";
        var candidate=MediaHeaderDetector.detect(header);
        if(candidate==null||!stored.equals("original."+candidate.extension())||!candidate.type().name().equals(p.getProperty("type"))
                ||!candidate.mimeType().equals(p.getProperty("mime")))return "INVALID_OR_UNREADABLE";
        b.stable.put(entry,entryStamp);b.stable.put(meta,metaStamp);b.stable.put(blob,blobStamp);
        b.catalogs.put(meta,hash(raw));
        return "VERIFIED";
    }
}
