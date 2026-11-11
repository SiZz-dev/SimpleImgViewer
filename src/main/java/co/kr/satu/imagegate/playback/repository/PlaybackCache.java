package co.kr.satu.imagegate.playback.repository;

import co.kr.satu.imagegate.playback.domain.*;
import co.kr.satu.imagegate.playback.util.PlaybackFiles;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Serialized short metadata reads/writes avoid Windows replacement/read races. Artifacts are immutable. */
public final class PlaybackCache implements AutoCloseable {
    private final Path root;private final PlaybackFiles.WriterLock lock;private final long maxBytes;
    private final Map<String,Integer> leases=new HashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean sweeping=new java.util.concurrent.atomic.AtomicBoolean();
    private boolean closing,closed;
    public PlaybackCache(Path path,long maxBytes) throws IOException {
        root=PlaybackFiles.root(path);this.maxBytes=maxBytes;lock=new PlaybackFiles.WriterLock(root);
    }
    public synchronized Path entry(String key,boolean create) throws IOException {
        if(closed||key==null||!key.matches("[a-f0-9]{64}"))throw PlaybackFiles.invalid();
        return PlaybackFiles.directory(root,key,create);
    }
    public synchronized PlaybackInfo read(String key) throws IOException {
        var entry=entry(key,false);
        if(entry==null||!Files.exists(entry.resolve("state.properties"),LinkOption.NOFOLLOW_LINKS))
            return new PlaybackInfo(key,"not_requested",null,0,null,0,0,null,null,0);
        var p=PlaybackFiles.read(entry.resolve("state.properties"),8192);
        try {
            if(!key.equals(p.getProperty("key")))throw PlaybackFiles.invalid();
            String state=p.getProperty("state");
            if(!Set.of("queued","running","ready","failed").contains(state))throw PlaybackFiles.invalid();
            if(!state.equals("ready"))return new PlaybackInfo(key,state,p.getProperty("error"),0,null,0,0,null,null,0);
            var info=new PlaybackInfo(key,state,null,Double.parseDouble(p.getProperty("duration")),p.getProperty("mime"),
                    Integer.parseInt(p.getProperty("width")),Integer.parseInt(p.getProperty("height")),
                    p.getProperty("video"),p.getProperty("audio"),Long.parseLong(p.getProperty("bytes")));
            if(!Double.isFinite(info.durationSeconds())||info.durationSeconds()<=0||info.durationSeconds()>7200
                    ||!Set.of("video/webm","audio/mpeg").contains(info.mimeType())
                    ||info.sizeBytes()<1||info.sizeBytes()>maxBytes||info.width()<0||info.height()<0
                    ||info.width()>1920||info.height()>1920)throw PlaybackFiles.invalid();
            Path file=artifact(entry,p);PlaybackFiles.regular(file,maxBytes);
            if(Files.size(file)!=info.sizeBytes()||Files.getLastModifiedTime(file).toMillis()!=Long.parseLong(p.getProperty("modified")))
                throw PlaybackFiles.invalid();
            return info;
        } catch(IllegalArgumentException|NullPointerException invalid){throw new IOException("Invalid playback metadata",invalid);}
    }
    private Path artifact(Path entry,Properties p) throws IOException {
        String name=p.getProperty("artifact","");
        if(!name.matches("result-[a-f0-9-]{36}\\.(webm|mp3)"))throw PlaybackFiles.invalid();
        return entry.resolve(name);
    }
    public synchronized Path content(String key) throws IOException {
        if(!read(key).ready())throw PlaybackFiles.invalid();
        Path entry=entry(key,false);return artifact(entry,PlaybackFiles.read(entry.resolve("state.properties"),8192));
    }
    public synchronized void state(String key,String state,String error) throws IOException {
        var p=new Properties();p.setProperty("key",key);p.setProperty("state",state);
        if(error!=null)p.setProperty("error",error);
        PlaybackFiles.write(entry(key,true).resolve("state.properties"),p);
    }
    public synchronized void publish(String key,PlaybackArtifact result) throws IOException {
        publish(key,result,()->{});
    }
    @FunctionalInterface public interface PublicationCheck {void verify() throws IOException;}
    public synchronized void publish(String key,PlaybackArtifact result,PublicationCheck check) throws IOException {
        java.util.Objects.requireNonNull(check);
        if(closing)throw PlaybackFiles.invalid();
        Path entry=entry(key,true);PlaybackFiles.regular(result.file(),maxBytes);
        if(!result.file().toRealPath().startsWith(entry)||Files.size(result.file())<1)throw PlaybackFiles.invalid();
        String extension=switch(result.mimeType()){case "video/webm"->"webm";case "audio/mpeg"->"mp3";default->throw PlaybackFiles.invalid();};
        Path file=entry.resolve("result-"+UUID.randomUUID()+"."+extension);
        Files.move(result.file(),file,StandardCopyOption.ATOMIC_MOVE);
        try {
            var p=new Properties();p.setProperty("key",key);p.setProperty("state","ready");
            p.setProperty("artifact",file.getFileName().toString());p.setProperty("duration",""+result.durationSeconds());
            p.setProperty("mime",result.mimeType());p.setProperty("width",""+result.width());p.setProperty("height",""+result.height());
            if(result.videoCodec()!=null)p.setProperty("video",result.videoCodec());
            if(result.audioCodec()!=null)p.setProperty("audio",result.audioCodec());
            p.setProperty("bytes",""+Files.size(file));p.setProperty("modified",""+Files.getLastModifiedTime(file).toMillis());
            check.verify();
            PlaybackFiles.write(entry.resolve("state.properties"),p);
        }catch(IOException|RuntimeException|Error failure){
            try{Files.deleteIfExists(file);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    /** The lease spans conversion or the complete response callback, including HEAD/error paths. */
    public synchronized Lease lease(String key) throws IOException {
        if(closing||key==null||!key.matches("[a-f0-9]{64}"))throw PlaybackFiles.invalid();
        touch(key,System.currentTimeMillis());
        leases.merge(key,1,Integer::sum);return new Lease(key);
    }
    private void touch(String key,long now) throws IOException {
        Path directory=entry(key,false);
        if(directory==null)return;
        Path state=directory.resolve("state.properties");
        if(Files.exists(state,LinkOption.NOFOLLOW_LINKS)){
            PlaybackFiles.regular(state,8192);
            if(now-Files.getLastModifiedTime(state).toMillis()>=60000)
                Files.setLastModifiedTime(state,java.nio.file.attribute.FileTime.fromMillis(now));
        }
    }
    public final class Lease implements AutoCloseable {
        private final String key;private boolean released;
        private Lease(String key){this.key=key;}
        @Override public void close() throws IOException {synchronized(PlaybackCache.this){if(released)return;released=true;
            try{touch(key,System.currentTimeMillis());}catch(IOException ignored){/* Maintenance remains conservative. */}
            leases.computeIfPresent(key,(k,n)->n==1?null:n-1);
            closeIfIdle();
        }}
    }
    public record MaintenanceResult(long scanned,long removed,long removedBytes,long retainedBytes,long skipped) {}
    private record Candidate(String key,long accessed,long bytes,String artifact) {}

    /** Bounded candidate memory and at most 1000 removals per pass; no recursive deletion. */
    public MaintenanceResult maintain(co.kr.satu.imagegate.system.config.ImageGateProperties.PlaybackCachePolicy policy,long now) throws IOException {
        if(!policy.enabled()||!sweeping.compareAndSet(false,true))return new MaintenanceResult(0,0,0,0,0);
        long scanned=0,total=0,skipped=0,removed=0,removedBytes=0;
        var oldest=new PriorityQueue<Candidate>(Comparator.comparingLong(Candidate::accessed).reversed());
        try {
            synchronized(this){if(closing)return new MaintenanceResult(0,0,0,0,0);
                if(!root.toRealPath().equals(root))throw PlaybackFiles.invalid();}
            try(var directories=Files.newDirectoryStream(root)){
                for(Path directory:directories){String key=directory.getFileName().toString();
                    if(!key.matches("[a-f0-9]{64}"))continue;
                    scanned++;
                    synchronized(this){if(closing)break;
                        try{var candidate=candidate(key);if(candidate==null)continue;
                            total=Math.addExact(total,candidate.bytes());
                            if(leases.containsKey(key))continue;
                            oldest.add(candidate);if(oldest.size()>1000)oldest.poll();
                        }catch(IOException|ArithmeticException invalid){skipped++;}
                    }
                }
            }
            var ordered=new ArrayList<>(oldest);ordered.sort(Comparator.comparingLong(Candidate::accessed));
            for(var selected:ordered){synchronized(this){if(closing)break;
                if(leases.containsKey(selected.key()))continue;
                try{var current=candidate(selected.key());
                    if(!selected.equals(current))continue;
                    boolean expired=now>=current.accessed()&&now-current.accessed()>=policy.retention().toMillis();
                    if(!expired&&total<=policy.maxBytes())continue;
                    Path directory=entry(current.key(),false);
                    // candidate() rejects extra files, links and work directories. Never follow a tree.
                    if(current.artifact()!=null)Files.delete(directory.resolve(current.artifact()));
                    Files.delete(directory.resolve("state.properties"));Files.delete(directory);
                    total-=current.bytes();removedBytes+=current.bytes();removed++;
                }catch(IOException invalid){skipped++;}
            }}
            return new MaintenanceResult(scanned,removed,removedBytes,total,skipped);
        }finally{sweeping.set(false);}
    }
    private Candidate candidate(String key) throws IOException {
        Path directory=entry(key,false);if(directory==null)return null;
        Path state=directory.resolve("state.properties");
        var properties=PlaybackFiles.read(state,8192);
        if(!key.equals(properties.getProperty("key")))throw PlaybackFiles.invalid();
        String status=properties.getProperty("state");String artifact=null;long bytes=0;
        if("ready".equals(status)){var info=read(key);artifact=artifact(directory,properties).getFileName().toString();bytes=info.sizeBytes();}
        else if(!"failed".equals(status))return null;
        try(var files=Files.newDirectoryStream(directory)){
            for(Path file:files){String name=file.getFileName().toString();
                if(!name.equals("state.properties")&&!name.equals(artifact))throw PlaybackFiles.invalid();
                PlaybackFiles.regular(file,name.equals("state.properties")?8192:maxBytes);
            }
        }
        return new Candidate(key,Files.getLastModifiedTime(state).toMillis(),bytes,artifact);
    }
    /** Reject new leases immediately; existing conversions/readers keep the writer lock until released. */
    @Override public synchronized void close() throws IOException {
        closing=true;
        closeIfIdle();
    }
    private void closeIfIdle() throws IOException {
        if(closing&&!closed&&leases.isEmpty()){
            closed=true;
            lock.close();
        }
    }
}
