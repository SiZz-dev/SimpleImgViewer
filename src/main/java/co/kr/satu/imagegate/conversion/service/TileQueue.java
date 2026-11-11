package co.kr.satu.imagegate.conversion.service;
import co.kr.satu.imagegate.conversion.domain.TileJobStatus;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.util.TileKeys;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Tiles;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Bounded preparation only; reads never enqueue work. Persistent interruptions require explicit retry. */
public final class TileQueue implements AutoCloseable {
    private final Tiles policy;private final String module;private final TileCache cache;private final TileJobStore states;private final TileRenderer renderer;private final Path volume;
    private final ThreadPoolExecutor workers;private final ScheduledExecutorService timer;
    private final Map<String,Job> active=new HashMap<>();private boolean closing;
    private final boolean auditEnabled;
    public TileQueue(Tiles policy,String module,TileCache cache,TileJobStore states,TileRenderer renderer,Path volume){
        this(policy,module,cache,states,renderer,volume,false);
    }
    public TileQueue(Tiles policy,String module,TileCache cache,TileJobStore states,TileRenderer renderer,Path volume,boolean auditEnabled){
        this.auditEnabled=auditEnabled;
        this.policy=policy;this.module=module;this.cache=cache;this.states=states;this.renderer=renderer;this.volume=volume;
        if(!policy.enabled())throw new IllegalArgumentException("Disabled tile queue");
        workers=new ThreadPoolExecutor(policy.workers(),policy.workers(),0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(policy.queueCapacity()),r->{var t=new Thread(r,"imagegate-tiles");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"imagegate-tile-deadline");t.setDaemon(true);return t;});
    }
    public String key(MediaRecord media,ViewerPrincipal actor,int page){return TileKeys.key(media,actor,page,policy,module);}
    public TileJobStatus status(MediaRecord media,ViewerPrincipal actor,int page)throws IOException{return view(media,actor,page).status();}
    public co.kr.satu.imagegate.conversion.domain.TileView view(MediaRecord media,ViewerPrincipal actor,int page)throws IOException{
        renderer.verifyAccess(media,actor);String key=key(media,actor,page);TileJobStatus state;
        synchronized(this){if(closing)throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CLOSED);state=state(key,page);}
        if(!Set.of("ready","not_requested").contains(state.state()))return new co.kr.satu.imagegate.conversion.domain.TileView(state,null);
        var artifact=cache.find(key);var resolved=withArtifact(state,artifact);
        return new co.kr.satu.imagegate.conversion.domain.TileView(resolved,"ready".equals(resolved.state())?artifact.orElseThrow():null);
    }
    @FunctionalInterface public interface ContentWriter {void write(InputStream input,co.kr.satu.imagegate.conversion.domain.TilePyramidManifest.Tile tile)throws IOException;}
    public void content(MediaRecord media,ViewerPrincipal actor,int page,String generation,int level,int column,int row,ContentWriter writer)throws IOException{
        renderer.verifyAccess(media,actor);String key=key(media,actor,page);TileJobStatus state;
        synchronized(this){if(closing)throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CLOSED);state=state(key,page);}
        if(!Set.of("ready","not_requested").contains(state.state()))throw new co.kr.satu.imagegate.conversion.exception.TileUnavailableException();
        // A pruned ready record is allowed, but no conversion is started and the manifest is read only once.
        cache.withTile(key,generation,page,level,column,row,(input,tile)->{renderer.verifyAccess(media,actor);writer.write(input,tile);});
    }
    private TileJobStatus state(String key,int page)throws IOException{
        var state=states.read(key,page);
        if(Set.of("queued","running").contains(state.state())&&!active.containsKey(key))return new TileJobStatus(key,page,"failed","INTERRUPTED");
        return state;
    }
    private TileJobStatus withArtifact(TileJobStatus state,Optional<co.kr.satu.imagegate.conversion.domain.TileCacheManifest> artifact)throws IOException{
        if("ready".equals(state.state())||"not_requested".equals(state.state())){
            if(artifact.isPresent()){if(artifact.get().page()!=state.page())throw new IOException("Tile page mismatch");return new TileJobStatus(state.key(),state.page(),"ready",null);}
            return new TileJobStatus(state.key(),state.page(),"not_requested",null);
        }return state;
    }
    public TileJobStatus submit(MediaRecord media,ViewerPrincipal actor,int page,boolean retry)throws IOException{
        renderer.verifyAccess(media,actor);String key=key(media,actor,page);
        synchronized(this){if(closing)throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CLOSED);var current=state(key,page);
            if(active.containsKey(key)||("failed".equals(current.state())&&!retry))return current;}
        // Never wait for cache stripes while holding the queue monitor: publication takes stripe -> gate.
        var lease=cache.lease(key);boolean accepted=false;
        try{
            var artifact=cache.find(key);if(artifact.isEmpty())cache.checkCapacity(policy.maintenance().maxScanEntries(),policy.workers()+2);
            synchronized(this){
                if(closing)throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CLOSED);var current=withArtifact(state(key,page),artifact);
                if(active.containsKey(key)||"ready".equals(current.state())||("failed".equals(current.state())&&!retry))return current;
                if(active.size()>=policy.workers()+policy.queueCapacity())throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CAPACITY);
                long reservation=Math.addExact(Math.multiplyExact(policy.maxPageBytes(),2),117_440_512L);
                long required=Math.addExact(policy.minFreeBytes(),Math.multiplyExact(reservation,active.size()+1L));
                if(Files.getFileStore(volume).getUsableSpace()<required)throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.SPACE);
                var job=new Job(key,media,actor,page,lease);states.write(new TileJobStatus(key,page,"queued",null));active.put(key,job);
                try{workers.execute(job);accepted=true;}
                catch(RejectedExecutionException rejected){job.error="TILE_QUEUE_FULL";job.finish();throw new co.kr.satu.imagegate.conversion.exception.TileAdmissionException(co.kr.satu.imagegate.conversion.exception.TileAdmissionException.Code.CAPACITY);}
                finally{if(!accepted)active.remove(key);}
                return new TileJobStatus(key,page,"queued",null);
            }
        }finally{if(!accepted)lease.close();}
    }
    public synchronized void cancel(MediaRecord media,ViewerPrincipal actor,int page)throws IOException{
        renderer.verifyAccess(media,actor);Job job=active.get(key(media,actor,page));if(job==null||job.committed)return;
        job.error="INTERRUPTED";if(workers.remove(job)){job.finish();}else if(job.thread!=null)job.thread.interrupt();
    }
    private final class Job implements Runnable {
        final String key;final MediaRecord media;final ViewerPrincipal actor;final int page;final TileCache.Lease lease;
        Thread thread;String error;boolean committed,finished;ScheduledFuture<?> deadline;long expires;
        Job(String key,MediaRecord media,ViewerPrincipal actor,int page,TileCache.Lease lease){this.key=key;this.media=media;this.actor=actor;this.page=page;this.lease=lease;}
        @Override public void run(){
            try{
                synchronized(TileQueue.this){thread=Thread.currentThread();if(closing||error!=null)throw new InterruptedIOException();
                    states.write(new TileJobStatus(key,page,"running",null));
                    expires=System.nanoTime()+policy.timeout().toNanos();
                    deadline=timer.schedule(()->{synchronized(TileQueue.this){if(!finished&&!committed){error="TIMEOUT";thread.interrupt();}}},policy.timeout().toMillis(),TimeUnit.MILLISECONDS);
                }
                renderer.render(media,actor,page,key,action->{synchronized(TileQueue.this){
                    if(System.nanoTime()-expires>=0)error="TIMEOUT";
                    if(closing||error!=null||Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                    action.run();committed=true;
                }});
                synchronized(TileQueue.this){if(!committed)error="TILE_FAILED";}
            }catch(Exception failure){synchronized(TileQueue.this){if(error==null)error=Thread.currentThread().isInterrupted()||closing?"INTERRUPTED"
                    :failure instanceof co.kr.satu.imagegate.conversion.exception.ConversionException conversion
                        &&conversion.code()==co.kr.satu.imagegate.conversion.exception.ConversionException.Code.BUSY?"CONVERSION_BUSY"
                    :failure instanceof UnsupportedOperationException?"TILE_PROFILE_UNAVAILABLE":"TILE_FAILED";}}
            finally{synchronized(TileQueue.this){finish();}}
        }
        void finish(){
            if(finished)return;finished=true;if(deadline!=null)deadline.cancel(false);thread=null;
            boolean interrupted=Thread.interrupted();
            if(auditEnabled&&!committed)try{co.kr.satu.imagegate.audit.service.AuditEvents.failure(co.kr.satu.imagegate.audit.service.AuditEvents.Action.TILE_FAILED,actor,media.mediaId(),page,error==null?"INTERRUPTED":error);}
            catch(RuntimeException failed){org.slf4j.LoggerFactory.getLogger(TileQueue.class).warn("Tile failure audit could not be recorded");}
            try{states.write(new TileJobStatus(key,page,committed?"ready":"failed",committed?null:error==null?"INTERRUPTED":error));}
            catch(IOException|RuntimeException failure){org.slf4j.LoggerFactory.getLogger(TileQueue.class).warn("Tile job state could not be saved");}
            finally{active.remove(key);try{lease.close();}catch(IOException e){org.slf4j.LoggerFactory.getLogger(TileQueue.class).warn("Tile lease could not be released");}if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    @Override public void close()throws IOException{
        synchronized(this){if(closing)return;closing=true;for(var job:active.values())if(!job.committed)job.error="INTERRUPTED";
            var queued=workers.shutdownNow();for(var task:queued)((Job)task).finish();timer.shutdownNow();cache.close();}
    }
}
