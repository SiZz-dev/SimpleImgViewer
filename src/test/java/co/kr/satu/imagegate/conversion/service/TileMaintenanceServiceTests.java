package co.kr.satu.imagegate.conversion.service;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.repository.*;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class TileMaintenanceServiceTests {
    @TempDir Path temp;
    Tiles policy(boolean enabled){return new Tiles(true,1,1,2,Duration.ofSeconds(10),1000,10,1000,10000,0,"v1",new TileMaintenance(enabled,Duration.ofDays(7),Duration.ofMinutes(5),1000000,Duration.ofDays(1),Duration.ofHours(1),32));}
    void publish(TileCache cache,String key)throws Exception{
        var image=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);var output=new ByteArrayOutputStream();
        try{ImageIO.write(image,"PNG",output);}finally{image.flush();}byte[] bytes=output.toByteArray();
        var tile=new TilePyramidManifest.Tile(0,0,0,1,1,bytes.length,PreviewKeys.sha256(bytes));
        var source=new TilePyramidManifest(1,"img2tile-tiff-rgb8-o1-v1",Img2TilePyramidValidator.MODULE,1,new TilePyramidLayout(1,1,256,0),"original-upright-pixel-edges","b".repeat(64),bytes.length+100,List.of(tile));
        cache.publish(key,source,new TileCache.SourceTiles(){public byte[] normalize(TilePyramidManifest.Tile t){return bytes;}public void verifyBeforeCommit(){}});
    }
    @Test void startupRecountsAndPersistsInterruptionWithoutDeletingOrStartingJobs()throws Exception{
        try(var cache=new TileCache(temp)){
            var states=new TileJobStore(temp,2);String key="a".repeat(64);states.write(new TileJobStatus(key,1,"running",null));
            var oldWork=new TileWorkStore(temp,10);var owned=oldWork.begin(key);Files.createDirectory(owned.path());
            try(var maintenance=new TileMaintenanceService(cache,states,new TileWorkStore(temp,10),policy(true))){
                assertThat(maintenance.latest().recoveredStates()).isEqualTo(1);assertThat(states.read(key,1).error()).isEqualTo("INTERRUPTED");assertThat(owned.path()).exists();
                var result=maintenance.runOnce(System.currentTimeMillis()+Duration.ofDays(2).toMillis());
                assertThat(result.work().removed()).isEqualTo(1);assertThat(result.removedStates()).isEqualTo(1);assertThat(states.read(key,1).state()).isEqualTo("not_requested");
            }
        }
    }
    @Test void prunedReadyHistoryStillResolvesCacheAndEvictionBecomesNotRequested()throws Exception{
        try(var cache=new TileCache(temp)){
            var states=new TileJobStore(temp,2);var renderer=new TileRenderer(){public void verifyAccess(MediaRecord m,ViewerPrincipal a){}public void render(MediaRecord m,ViewerPrincipal a,int p,String key,TilePyramidPublisher.CommitGate gate){throw new AssertionError("no conversion");}};
            var actor=new ViewerPrincipal("tenant","alice");var media=new MediaRecord("id","tenant","alice","v1","x.tif",MediaType.IMAGE,"image/tiff",100,"a".repeat(64),"x",Instant.EPOCH);
            try(var queue=new TileQueue(policy(true),"module",cache,states,renderer,temp)){
                String key=queue.key(media,actor,1);publish(cache,key);states.write(new TileJobStatus(key,1,"ready",null));
                try(var maintenance=new TileMaintenanceService(cache,states,new TileWorkStore(temp,10),policy(true))){
                    assertThat(maintenance.runOnce(System.currentTimeMillis()).removedStates()).isEqualTo(1);assertThat(states.read(key,1).state()).isEqualTo("not_requested");
                    assertThat(queue.status(media,actor,1).state()).isEqualTo("ready");cache.evict(key);assertThat(queue.status(media,actor,1).state()).isEqualTo("not_requested");
                }
            }
        }
    }
    @Test void preservesFailureMarkerWithOrphanGenerationAndRespectsDisabledDeletion()throws Exception{
        try(var cache=new TileCache(temp)){
            var states=new TileJobStore(temp,2);String key="a".repeat(64);publish(cache,key);states.write(new TileJobStatus(key,1,"failed","INTERRUPTED"));
            try(var maintenance=new TileMaintenanceService(cache,states,new TileWorkStore(temp,10),policy(true))){
                assertThat(maintenance.runOnce(System.currentTimeMillis()+Duration.ofDays(2).toMillis()).removedStates()).isZero();assertThat(states.read(key,1).state()).isEqualTo("failed");
            }
            try(var disabled=new TileMaintenanceService(cache,states,new TileWorkStore(temp,10),policy(false))){
                var report=disabled.runOnce(System.currentTimeMillis()+Duration.ofDays(30).toMillis());assertThat(report.cache().removed()).isZero();assertThat(report.removedStates()).isZero();
            }
        }
    }
    @Test void changedStateSnapshotCannotDeleteNewQueuedJob()throws Exception{
        try(var cache=new TileCache(temp)){
            var states=new TileJobStore(temp,2);String key="a".repeat(64);states.write(new TileJobStatus(key,1,"failed","TILE_FAILED"));var snapshot=states.snapshots().getFirst();
            states.write(new TileJobStatus(key,1,"queued",null));assertThat(states.remove(snapshot)).isFalse();assertThat(states.read(key,1).state()).isEqualTo("queued");
        }
    }
}
