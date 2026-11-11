package co.kr.satu.imagegate.playback.service;

import co.kr.satu.imagegate.playback.domain.*;
import co.kr.satu.imagegate.playback.exception.PlaybackException;
import co.kr.satu.imagegate.playback.repository.PlaybackCache;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** No conversion on GET. Every public read and range resolves current file authorization. */
@Service
public final class PlaybackService implements AutoCloseable {
    public record Content(MediaRecord record,PlaybackInfo info,Path path) {}
    @FunctionalInterface public interface ContentWriter {void write(Content content) throws IOException;}
    private final MediaService media;private final MediaStore store;private final MediaAuthorizationPolicy policy;
    private final CurrentActor actors;private final FeaturePolicy features;
    private final ImageGateProperties.PlaybackProcessing profile;private final PlaybackCache cache;
    private final boolean auditEnabled;
    private final PlaybackEngine engine;private final ThreadPoolExecutor workers;private final Semaphore streams;
    private final ScheduledExecutorService maintenance;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PlaybackService.class);
    private final Set<String> active=new HashSet<>();private boolean closing;
    public PlaybackService(MediaService media,MediaStore store,MediaAuthorizationPolicy policy,CurrentActor actors,
            FeaturePolicy features,ImageGateProperties properties,PlaybackCache cache,
            org.springframework.beans.factory.ObjectProvider<PlaybackEngine> engine) {
        this.media=media;this.store=store;this.policy=policy;this.actors=actors;this.features=features;this.cache=cache;
        profile=properties.playback();auditEnabled=properties.audit().enabled();
        PlaybackEngine selected;try{selected=engine.getIfAvailable();}catch(RuntimeException e){try{cache.close();}catch(IOException close){e.addSuppressed(close);}throw e;}
        this.engine=selected;streams=new Semaphore(profile.maxStreams());
        workers=new ThreadPoolExecutor(profile.workers(),profile.workers(),0,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(profile.queueCapacity()),r->{var t=new Thread(r,"imagegate-playback");t.setDaemon(true);return t;});
        maintenance=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"imagegate-playback-cache");t.setDaemon(true);return t;});
        if(profile.cache().enabled())maintenance.scheduleWithFixedDelay(this::maintainCache,
                profile.cache().cleanupInterval().toMillis(),profile.cache().cleanupInterval().toMillis(),TimeUnit.MILLISECONDS);
    }
    private MediaRecord require(String id) {
        var record=media.require(id,MediaAction.VIEW);
        if(record.mediaType()==MediaType.IMAGE)throw new PlaybackException(PlaybackException.Code.UNSUPPORTED);
        return record;
    }
    private String key(MediaRecord record) {
        var d=PreviewKeys.digest();
        String value=record.tenantId()+"\n"+record.mediaId()+"\n"+record.mediaVersion()+"\n"+record.sha256()+"\n"+(engine==null?"disabled":engine.identity());
        return HexFormat.of().formatHex(d.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    public synchronized PlaybackInfo status(String id) {
        var record=require(id);
        if(!profile.enabled()||engine==null)return PlaybackInfo.empty("disabled","PLAYBACK_DISABLED");
        return status(record);
    }
    private PlaybackInfo status(MediaRecord record) {
        try {
            String key=key(record);var info=cache.read(key);
            if(Set.of("queued","running").contains(info.state())&&!active.contains(key))
                return new PlaybackInfo(key,"failed","INTERRUPTED",0,null,0,0,null,null,0);
            return info;
        } catch(IOException e){throw storage(e);}
    }
    public synchronized PlaybackInfo request(String id,boolean retry) {
        var record=require(id);
        if(!profile.enabled()||engine==null)return PlaybackInfo.empty("disabled","PLAYBACK_DISABLED");
        if(closing)throw new PlaybackException(PlaybackException.Code.BUSY);
        String key=key(record);
        try(var requestLease=cache.lease(key)) {
            var info=status(record);
            if(info.ready()||active.contains(key)||("failed".equals(info.state())&&!retry))return info;
            if(closing||active.size()>=profile.workers()+profile.queueCapacity())throw new PlaybackException(PlaybackException.Code.BUSY);
            cache.state(key,"queued",null);var actor=actors.require();
            var job=new Job(record,actor,key,cache.lease(key));active.add(key);
            try {workers.execute(job);}
            catch(RejectedExecutionException rejected){job.cancel("QUEUE_FULL");throw new PlaybackException(PlaybackException.Code.BUSY);}
            return new PlaybackInfo(key,"queued",null,0,null,0,0,null,null,0);
        } catch(IOException e){throw storage(e);}
    }
    /** Owns the lease even when shutdown drains a task before its Runnable is ever invoked. */
    private final class Job implements Runnable {
        private final MediaRecord record;
        private final ViewerPrincipal actor;
        private final String key;
        private final PlaybackCache.Lease lease;
        private final java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean();
        private Job(MediaRecord record,ViewerPrincipal actor,String key,PlaybackCache.Lease lease){
            this.record=record;this.actor=actor;this.key=key;this.lease=lease;
        }
        @Override public void run(){
            if(!claimed.compareAndSet(false,true))return;
            try{PlaybackService.this.run(record,actor,key);}finally{release();}
        }
        private void cancel(String code){
            if(!claimed.compareAndSet(false,true))return;
            try{failed(record,actor,key,code);}finally{release();}
        }
        private void release(){
            try{lease.close();}catch(IOException failure){LOG.warn("Playback cache lease release failed; inspect private storage");}
            finally{synchronized(PlaybackService.this){active.remove(key);}}
        }
    }
    private void failed(MediaRecord record,ViewerPrincipal actor,String key,String code){
        try {
            if(auditEnabled)co.kr.satu.imagegate.audit.service.AuditEvents.failure(co.kr.satu.imagegate.audit.service.AuditEvents.Action.PLAYBACK_FAILED,actor,record.mediaId(),0,code);
        } catch(RuntimeException failure){
            LOG.warn("Playback failure audit could not be recorded");
        } finally {
            try{cache.state(key,"failed",code);}catch(IOException failure){LOG.warn("Playback failure state could not be persisted; inspect private storage");}
        }
    }
    private void maintainCache() {
        try {
            var result=cache.maintain(profile.cache(),System.currentTimeMillis());
            if(result.removed()>0)LOG.info("Playback cache cleanup: removed={}, bytes={}, retainedBytes={}",result.removed(),result.removedBytes(),result.retainedBytes());
            if(result.skipped()>0)LOG.warn("Playback cache cleanup skipped {} entries; inspect private storage",result.skipped());
            if(result.retainedBytes()>profile.cache().maxBytes())LOG.warn("Playback cache remains above cleanup target; protected entries or pass limit may defer eviction");
        }catch(IOException|RuntimeException failure){LOG.warn("Playback cache cleanup failed; inspect private storage and disk capacity");}
    }

    /** Registration classifies only; the selected file is explicitly prepared by session/POST. */
    public PlaybackInfo afterRegistration(MediaRecord record) {return status(record.mediaId());}
    private void access(MediaRecord expected,ViewerPrincipal actor) throws IOException {
        var current=store.find(actor.tenantId(),expected.mediaId()).orElseThrow(()->new IOException("Unavailable media"));
        if(!expected.equals(current)||!actor.tenantId().equals(current.tenantId())||!policy.permits(actor,current,MediaAction.VIEW)
                ||!features.isEnabled(expected.mediaType()==MediaType.VIDEO?FeaturePolicy.Feature.VIDEO_PLAYBACK:FeaturePolicy.Feature.AUDIO_PLAYBACK))
            throw new IOException("Unavailable media");
    }
    private void run(MediaRecord record,ViewerPrincipal actor,String key) {
        Path work=null;
        try {
            synchronized(this){
                if(closing||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                cache.state(key,"running",null);
            }
            access(record,actor);
            Path original=store.resolveOriginal(record);var digest=PreviewKeys.digest();
            try(var in=Files.newInputStream(original)){byte[] block=new byte[32768];int n;while((n=in.read(block))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();digest.update(block,0,n);}}
            if(!record.sha256().equals(HexFormat.of().formatHex(digest.digest())))throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);
            work=Files.createDirectory(cache.entry(key,true).resolve("work-"+UUID.randomUUID()));
            var result=engine.prepare(record,original,work);access(record,actor);
            synchronized(this){if(closing||Thread.currentThread().isInterrupted())throw new InterruptedIOException();cache.publish(key,result,()->access(record,actor));}
        } catch(Exception e) {
            String code;
            synchronized(this){
                code=closing||e instanceof InterruptedIOException||Thread.currentThread().isInterrupted()?"INTERRUPTED"
                        :e instanceof PlaybackException p?"PLAYBACK_"+p.code().name()
                        :e instanceof co.kr.satu.imagegate.conversion.exception.ConversionException c?"PROCESS_"+c.code().name()
                        :"PLAYBACK_FAILED";
            }
            failed(record,actor,key,code);
        } finally {
            if(work!=null)cleanup(work);
        }
    }
    private static void cleanup(Path work) {
        try(var files=Files.list(work)){for(var path:files.toList())if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))Files.deleteIfExists(path);}
        catch(IOException ignored){}
        try{Files.deleteIfExists(work);}catch(IOException ignored){}
    }
    public void content(String id,String sourceId,ContentWriter writer) throws IOException {
        var record=require(id);
        if(!profile.enabled()||engine==null||!key(record).equals(sourceId))
            throw new co.kr.satu.imagegate.media.exception.MediaException(co.kr.satu.imagegate.media.exception.MediaException.Code.MEDIA_NOT_FOUND);
        synchronized(this){if(closing)throw new PlaybackException(PlaybackException.Code.BUSY);}
        if(!streams.tryAcquire())throw new PlaybackException(PlaybackException.Code.BUSY);
        try(var lease=cache.lease(sourceId)) {
            PlaybackInfo info;Path path;
            synchronized(this){info=status(record);if(!info.ready())throw new PlaybackException(PlaybackException.Code.NOT_READY);path=cache.content(sourceId);}
            if(!record.equals(require(id)))throw new PlaybackException(PlaybackException.Code.NOT_READY);
            writer.write(new Content(record,info,path));
        } finally {streams.release();}
    }
    private static PlaybackException storage(IOException e){return new PlaybackException(PlaybackException.Code.STORAGE_UNAVAILABLE,e);}
    @jakarta.annotation.PreDestroy
    @Override public void close() throws IOException {
        List<Runnable> queued;
        synchronized(this){
            if(closing)return;
            closing=true;
            maintenance.shutdownNow();
            queued=workers.shutdownNow();
        }
        for(var task:queued)((Job)task).cancel("INTERRUPTED");
        // Outstanding job/response leases defer writer-lock release, even if the wait below expires.
        cache.close();
        try{
            if(!workers.awaitTermination(5,TimeUnit.SECONDS)||!maintenance.awaitTermination(5,TimeUnit.SECONDS))
                LOG.warn("Playback shutdown deferred; active work retains its storage lock until completion");
        }catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
