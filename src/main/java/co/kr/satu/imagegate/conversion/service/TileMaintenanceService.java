package co.kr.satu.imagegate.conversion.service;
import co.kr.satu.imagegate.conversion.domain.TileMaintenanceReport;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Tiles;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
/** Startup recovery under the writer, then a dedicated bounded periodic sweep. No conversion submission. */
public final class TileMaintenanceService implements AutoCloseable {
    public record Report(TileMaintenanceReport cache,TileMaintenanceReport work,int stateCount,int removedStates,int recoveredStates){}
    private final TileCache cache;private final TileJobStore states;private final TileWorkStore work;private final Tiles policy;
    private final ScheduledExecutorService scheduler;private volatile Report latest;private final int recovered;private volatile boolean closing;private boolean started;private final Object sweepLock=new Object();
    public TileMaintenanceService(TileCache cache,TileJobStore states,TileWorkStore work,Tiles policy)throws IOException{
        this.cache=cache;this.states=states;this.work=work;this.policy=policy;
        try(var lease=cache.lease()){recovered=states.recoverInterrupted();latest=sweep(System.currentTimeMillis(),false);}
        scheduler=Executors.newSingleThreadScheduledExecutor(r->{var thread=new Thread(r,"imagegate-tile-maintenance");thread.setDaemon(true);return thread;});
    }
    public synchronized void start(){if(!closing&&!started&&policy.maintenance().enabled()){started=true;scheduler.scheduleWithFixedDelay(()->{
        try{var report=runOnce(System.currentTimeMillis());if(report.cache().skipped()+report.work().skipped()>0)org.slf4j.LoggerFactory.getLogger(TileMaintenanceService.class).warn("Tile maintenance retained active or unverified entries");}
        catch(IOException|RuntimeException failed){org.slf4j.LoggerFactory.getLogger(TileMaintenanceService.class).warn("Tile maintenance failed; inspect private storage");}
    },policy.maintenance().interval().toMillis(),policy.maintenance().interval().toMillis(),TimeUnit.MILLISECONDS);}}
    public Report latest(){return latest;}
    public Report runOnce(long now)throws IOException{
        synchronized(sweepLock){if(closing)throw new IOException("Tile maintenance closed");try(var lease=cache.lease()){latest=sweep(now,policy.maintenance().enabled());return latest;}}
    }
    private Report sweep(long now,boolean delete)throws IOException{
        var p=policy.maintenance();var cacheReport=cache.maintain(p,now,delete);co.kr.satu.imagegate.conversion.util.PreviewCancellation.check();var workReport=work.maintain(p.maxScanEntries(),p.workRetention().toMillis(),now,delete);
        var snapshots=new ArrayList<>(states.snapshots());snapshots.sort(Comparator.comparingLong(TileJobStore.Snapshot::modified).thenComparing(s->s.state().key()));
        int remaining=snapshots.size(),removed=0,target=policy.maxTrackedJobs()-policy.workers()-policy.queueCapacity();
        if(delete)for(var snapshot:snapshots){
            co.kr.satu.imagegate.conversion.util.PreviewCancellation.check();
            String state=snapshot.state().state();boolean expired=now-snapshot.modified()>=p.historyRetention().toMillis();
            if(!Set.of("ready","failed").contains(state)||(!expired&&!(state.equals("ready")&&remaining>target)))continue;
            var artifact=cache.find(snapshot.state().key());
            if(state.equals("failed")&&artifact.isPresent())continue;
            if(artifact.isPresent()&&artifact.get().page()!=snapshot.state().page())continue;
            if(states.remove(snapshot)){removed++;remaining--;}
        }
        return new Report(cacheReport,workReport,remaining,removed,recovered);
    }
    @Override public synchronized void close(){closing=true;scheduler.shutdownNow();}
}
