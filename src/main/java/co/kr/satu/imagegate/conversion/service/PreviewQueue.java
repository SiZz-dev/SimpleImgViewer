package co.kr.satu.imagegate.conversion.service;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

/** Bounded single-instance queue. Interrupted jobs require an explicit authorized retry after restart. */
public final class PreviewQueue implements AutoCloseable {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(PreviewQueue.class);
    private final Preview profile;
    private final PreviewMetrics metrics;
    private final boolean auditEnabled;
    private final String engine;
    private final PreviewCache cache;
    private final PreviewRenderer renderer;
    private final ThreadPoolExecutor workers;
    private final Set<String> active=new HashSet<>();
    private final ScheduledExecutorService maintenance;
    private volatile boolean closing;

    public PreviewQueue(Preview profile,String engine,PreviewCache cache,PreviewRenderer renderer) throws IOException {
        this(profile,engine,cache,renderer,false);
    }
    public PreviewQueue(Preview profile,String engine,PreviewCache cache,PreviewRenderer renderer,boolean auditEnabled) throws IOException {
        this(profile,engine,cache,renderer,auditEnabled,PreviewMetrics.NONE);
    }
    public PreviewQueue(Preview profile,String engine,PreviewCache cache,PreviewRenderer renderer,boolean auditEnabled,PreviewMetrics metrics) throws IOException {
        this.metrics=java.util.Objects.requireNonNull(metrics);this.auditEnabled=auditEnabled;
        this.profile=profile;this.engine=engine;this.cache=cache;this.renderer=renderer;
        workers=new ThreadPoolExecutor(profile.workers(),profile.workers(),0,TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(profile.queueCapacity()),task-> {
                    var thread=new Thread(task,"imagegate-preview");thread.setDaemon(true);return thread;
                },new ThreadPoolExecutor.AbortPolicy());
        maintenance=Executors.newSingleThreadScheduledExecutor(task->{
            var thread=new Thread(task,"imagegate-preview-cache");thread.setDaemon(true);return thread;});
        boolean claimed=false;
        try{
            if(profile.enabled()||profile.cache().enabled()){cache.claimWriter();claimed=true;}
            if(profile.cache().enabled())maintenance.scheduleWithFixedDelay(this::maintainCache,
                    profile.cache().cleanupInterval().toMillis(),profile.cache().cleanupInterval().toMillis(),TimeUnit.MILLISECONDS);
        }catch(IOException|RuntimeException failure){
            renderer.close();workers.shutdownNow();maintenance.shutdownNow();
            if(claimed)try{cache.close();}catch(IOException close){failure.addSuppressed(close);}
            throw failure;
        }
    }
    private void maintainCache(){
        try{var result=cache.maintain(profile.cache(),System.currentTimeMillis());
            if(result.removed()>0)LOG.info("Preview cache cleanup: removed={}, bytes={}, retainedBytes={}",result.removed(),result.removedBytes(),result.retainedBytes());
            if(result.skipped()>0)LOG.warn("Preview cache cleanup skipped {} entries; inspect private storage",result.skipped());
            if(result.retainedBytes()>profile.cache().maxBytes())LOG.warn("Preview cache remains above cleanup target");
        }catch(IOException|RuntimeException failure){LOG.warn("Preview cache cleanup failed; inspect private storage and disk capacity");}
    }

    public String key(MediaRecord media,ViewerPrincipal actor,int page) {
        if(page<1 || page>10000) throw new MediaException(MediaException.Code.INVALID_REQUEST);
        return PreviewKeys.key(media,actor,page,profile,engine);
    }

    public synchronized PreviewStatus status(MediaRecord media,ViewerPrincipal actor,int page) {
        if(!profile.enabled()) return PreviewStatus.empty(page,"disabled","PROCESSING_DISABLED");
        String key=key(media,actor,page);
        try {
            var state=cache.read(key,page);
            if(("queued".equals(state.state()) || "running".equals(state.state())) && !active.contains(key))
                return new PreviewStatus(key,page,"failed","INTERRUPTED",0,0);
            return state;
        } catch(IOException failure) { throw unavailable(failure); }
    }

    public synchronized PreviewStatus submit(MediaRecord media,ViewerPrincipal actor,int page,boolean retry) {
        String key=key(media,actor,page);
        if(!profile.enabled())return status(media,actor,page);
        if(closing){metrics.rejected(PreviewMetrics.Rejection.CLOSING);throw new MediaException(MediaException.Code.PREVIEW_QUEUE_FULL);}
        try(var requestLease=cache.lease(key)){
            var current=status(media,actor,page);
            if(current.ready()||active.contains(key))return current;
            if("failed".equals(current.state())&&!retry)return current;
            if(closing||active.size()>=profile.workers()+profile.queueCapacity()){metrics.rejected(closing?PreviewMetrics.Rejection.CLOSING:PreviewMetrics.Rejection.CAPACITY);throw new MediaException(MediaException.Code.PREVIEW_QUEUE_FULL);}
            cache.state(key,page,"queued",null);var job=new Job(key,media,actor,page,cache.lease(key));active.add(key);
            try{workers.execute(job);}
            catch(RejectedExecutionException rejected){metrics.rejected(PreviewMetrics.Rejection.EXECUTOR);job.cancel("QUEUE_FULL");throw new MediaException(MediaException.Code.PREVIEW_QUEUE_FULL);}
            return new PreviewStatus(key,page,"queued",null,0,0);
        }catch(IOException failure){throw unavailable(failure);}
    }

    private final class Job implements Runnable {
        private final String key;private final MediaRecord media;private final ViewerPrincipal actor;
        private final int page;private final PreviewCache.Lease lease;
        private final PreviewMetrics.JobSample sample;
        private final java.util.concurrent.atomic.AtomicBoolean claimed=new java.util.concurrent.atomic.AtomicBoolean();
        private Job(String key,MediaRecord media,ViewerPrincipal actor,int page,PreviewCache.Lease lease){
            this.key=key;this.media=media;this.actor=actor;this.page=page;this.lease=lease;this.sample=metrics.accepted();
        }
        @Override public void run(){
            if(!claimed.compareAndSet(false,true))return;
            var outcome=PreviewMetrics.Outcome.FAILED;
            try{sample.start();outcome=PreviewQueue.this.run(key,media,actor,page);}finally{release(outcome);}
        }
        private void cancel(String code){
            if(!claimed.compareAndSet(false,true))return;
            try{failed(key,media,actor,page,code);}finally{release(PreviewMetrics.outcome(code));}
        }
        private void release(PreviewMetrics.Outcome outcome){
            try{lease.close();}catch(IOException failure){LOG.warn("Preview cache lease release failed; inspect private storage");}
            finally{
                synchronized(PreviewQueue.this){active.remove(key);}
                sample.finish(outcome);
            }
        }
    }
    private void failed(String key,MediaRecord media,ViewerPrincipal actor,int page,String code){
        try{
            if(auditEnabled)co.kr.satu.imagegate.audit.service.AuditEvents.failure(co.kr.satu.imagegate.audit.service.AuditEvents.Action.PREVIEW_FAILED,actor,media.mediaId(),page,code);
        }catch(RuntimeException failure){LOG.warn("Preview failure audit could not be recorded");}
        finally{
            try{cache.state(key,page,"failed",code);}
            catch(IOException failure){LOG.warn("Preview failure state could not be persisted; inspect private storage");}
        }
    }
    private PreviewMetrics.Outcome run(String key,MediaRecord media,ViewerPrincipal actor,int page) {
        Path work=null;var outcome=PreviewMetrics.Outcome.FAILED;
        try {
            synchronized(this){
                if(closing||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                cache.state(key,page,"running",null);
            }
            work=cache.entry(key).resolve("work-"+UUID.randomUUID());
            var result=renderer.render(media,actor,page,work);
            synchronized(this) {
                if(closing || Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                cache.publish(key,page,result.png(),result.width(),result.height(),result.geometry(),result.thumbnail(),result.thumbnailWidth(),result.thumbnailHeight(),
                        ()->renderer.verifyPublication(media,actor,page));
                outcome=PreviewMetrics.Outcome.READY;
            }
        } catch(Exception failure) {
            String code=failure instanceof java.io.InterruptedIOException || Thread.currentThread().isInterrupted() || closing ? "INTERRUPTED"
                    : failure instanceof co.kr.satu.imagegate.conversion.exception.OriginalOpenTimeoutException ? "ORIGINAL_OPEN_BUSY"
                    : failure instanceof UnsupportedOperationException ? "DECODER_UNAVAILABLE"
                    : failure instanceof co.kr.satu.imagegate.conversion.exception.ConversionException nativeFailure
                    ? "CONVERSION_"+nativeFailure.code().name() : "PREVIEW_FAILED";
            Integer exit=failure instanceof co.kr.satu.imagegate.conversion.exception.ConversionException nativeError
                    ? nativeError.exitCode() : null;
            LOG.warn("Preview failed: source={}, code={}, failureType={}, nativeExit={}",
                    key,code,failure.getClass().getSimpleName(),exit);
            failed(key,media,actor,page,code);outcome=PreviewMetrics.outcome(code);
        } finally {
            if(work!=null) cleanup(work);
        }
        return outcome;
    }

    @FunctionalInterface public interface ContentWriter {void write(Path file) throws IOException;}
    public void content(MediaRecord media,ViewerPrincipal actor,int page,String sourceId,ContentWriter writer) throws IOException {
        content(media,actor,page,sourceId,false,writer);
    }
    public void content(MediaRecord media,ViewerPrincipal actor,int page,String sourceId,boolean thumbnail,ContentWriter writer) throws IOException {
        String expected=key(media,actor,page);
        if(!expected.equals(sourceId))throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
        synchronized(this){if(closing)throw new MediaException(MediaException.Code.PREVIEW_QUEUE_FULL);}
        try(var lease=cache.lease(expected)){
            Path path;
            synchronized(this){
                var current=status(media,actor,page);
                if(!current.ready()||(thumbnail&&current.thumbnail()==null))throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
                try{path=thumbnail?cache.thumbnail(expected,page):cache.content(expected,page);}
                catch(IOException failure){throw new MediaException(MediaException.Code.PREVIEW_NOT_READY,failure);}
            }
            writer.write(path);
        }
    }

    private void cleanup(Path directory) {
        // The caller creates work inside a validated private cache entry; never follow symbolic links.
        try{
            Path base=cache.root();
            if(!directory.isAbsolute()||!directory.equals(directory.normalize())
                    ||!directory.getParent().getParent().equals(base)
                    ||!directory.getParent().getFileName().toString().matches("[a-f0-9]{64}")
                    ||!directory.getFileName().toString().matches("work-[a-f0-9-]{36}")
                    ||!directory.toRealPath().equals(directory))return;
        }catch(IOException invalid){return;}
        try(var walk=Files.walk(directory)) {
            for(var path:walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch(IOException|java.io.UncheckedIOException ignored) { /* Operations guide covers orphan cleanup while stopped. */ }
    }
    private static MediaException unavailable(IOException failure) {
        return new MediaException(MediaException.Code.STORAGE_UNAVAILABLE,failure);
    }

    @Override public void close() throws IOException {
        List<Runnable> queued;
        synchronized(this){
            if(closing)return;
            closing=true;renderer.close();maintenance.shutdownNow();queued=workers.shutdownNow();
        }
        for(var task:queued)((Job)task).cancel("INTERRUPTED");
        cache.close();
        try{
            if(!workers.awaitTermination(5,TimeUnit.SECONDS)||!maintenance.awaitTermination(5,TimeUnit.SECONDS))
                LOG.warn("Preview shutdown deferred; active work retains its storage lock until completion");
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
}
