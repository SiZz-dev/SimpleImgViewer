package co.kr.satu.imagegate.conversion.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.util.*;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import co.kr.satu.imagegate.system.config.ImageGateProperties.TileMaintenance;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class TileCacheMaintenanceTests {
    @TempDir Path temp;
    static TileCacheManifest publish(TileCache cache,String key)throws Exception{
        var image=new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_ARGB);var buffer=new ByteArrayOutputStream();
        try{ImageIO.write(image,"PNG",buffer);}finally{image.flush();}byte[] bytes=buffer.toByteArray();
        var tile=new TilePyramidManifest.Tile(0,0,0,1,1,bytes.length,PreviewKeys.sha256(bytes));
        var source=new TilePyramidManifest(1,"img2tile-tiff-rgb8-o1-v1",Img2TilePyramidValidator.MODULE,1,new TilePyramidLayout(1,1,256,0),"original-upright-pixel-edges","b".repeat(64),bytes.length+100,List.of(tile));
        return cache.publish(key,source,new TileCache.SourceTiles(){public byte[] normalize(TilePyramidManifest.Tile tile){return bytes;}public void verifyBeforeCommit(){}});
    }
    TileMaintenance policy(long bytes){return new TileMaintenance(true,Duration.ofDays(7),Duration.ofMinutes(5),bytes,Duration.ofDays(1),Duration.ofHours(1),32);}
    @Test void recountsWithoutMutationThenExpiresOnlyUnpinnedGenerations()throws Exception{
        try(var cache=new TileCache(temp)){
            String key="a".repeat(64);publish(cache,key);long future=System.currentTimeMillis()+Duration.ofDays(8).toMillis();
            var before=cache.maintain(policy(1000000),future,false);assertThat(before.inspected()).isEqualTo(1);assertThat(before.retainedBytes()).isPositive();assertThat(before.removed()).isZero();
            try(var pin=cache.lease(key)){assertThat(cache.maintain(policy(1000000),future,true).removed()).isZero();}
            var after=cache.maintain(policy(1000000),future,true);assertThat(after.removed()).isEqualTo(1);assertThat(after.removedBytes()).isEqualTo(before.retainedBytes());assertThat(cache.find(key)).isEmpty();
        }
    }
    @Test void runningTransferSurvivesSweepUntilCallbackFinishes()throws Exception{
        try(var cache=new TileCache(temp)){
            String key="a".repeat(64);var manifest=publish(cache,key);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
            try{
                var transfer=executor.submit(()->{cache.withTile(key,manifest.generation(),0,0,0,(stream,tile)->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}assertThat(stream.readAllBytes()).isNotEmpty();});return null;});
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();long now=System.currentTimeMillis()+Duration.ofDays(8).toMillis();
                assertThat(cache.maintain(policy(1000000),now,true).removed()).isZero();release.countDown();transfer.get(10,TimeUnit.SECONDS);
                assertThat(cache.maintain(policy(1000000),now,true).removed()).isEqualTo(1);
            }finally{release.countDown();executor.shutdownNow();}
        }
    }
    @Test void pressureUsesContentAccessAndReadTimeSurvivesReopen()throws Exception{
        String a="a".repeat(64),b="b".repeat(64);long firstBytes;
        try(var cache=new TileCache(temp)){
            var first=publish(cache,a);Thread.sleep(15);publish(cache,b);firstBytes=cache.maintain(policy(Long.MAX_VALUE),System.currentTimeMillis(),false).retainedBytes()/2;
            Thread.sleep(15);cache.withTile(a,first.generation(),0,0,0,(stream,tile)->stream.readAllBytes());
            assertThat(cache.maintain(policy(firstBytes+10),System.currentTimeMillis(),true).removed()).isEqualTo(1);assertThat(cache.find(a)).isPresent();assertThat(cache.find(b)).isEmpty();
        }
        try(var reopened=new TileCache(temp)){assertThat(reopened.maintain(policy(Long.MAX_VALUE),System.currentTimeMillis(),true).removed()).isZero();}
    }
    @Test void preservesCorruptAndUnknownArtifactsAndIncompleteOrphans()throws Exception{
        try(var cache=new TileCache(temp)){
            String key="a".repeat(64);publish(cache,key);Path tile=temp.resolve("tiles-v1/"+key+"/0_0_0.png");byte[] bytes=Files.readAllBytes(tile);bytes[30]^=1;Files.write(tile,bytes);
            Path orphan=Files.createDirectory(temp.resolve("tiles-v1/.work-"+UUID.randomUUID()));Files.writeString(orphan.resolve("0_0_0.png"),"unknown");
            Files.writeString(temp.resolve("tiles-v1/unknown.txt"),"preserve");var report=cache.maintain(policy(1),System.currentTimeMillis()+Duration.ofDays(8).toMillis(),true);
            assertThat(report.removed()).isZero();assertThat(report.skipped()).isGreaterThanOrEqualTo(3);assertThat(Files.readString(orphan.resolve("0_0_0.png"))).isEqualTo("unknown");assertThat(Files.exists(tile)).isTrue();
        }
    }
    @Test void reclaimsCompleteManifestBackedOrphansOnly()throws Exception{
        try(var cache=new TileCache(temp)){
            String key="a".repeat(64);publish(cache,key);Path orphan=temp.resolve("tiles-v1/.work-"+UUID.randomUUID());Files.move(temp.resolve("tiles-v1/"+key),orphan);
            Files.setLastModifiedTime(orphan,java.nio.file.attribute.FileTime.fromMillis(1));
            assertThat(cache.maintain(policy(Long.MAX_VALUE),System.currentTimeMillis(),true).removed()).isEqualTo(1);assertThat(orphan).doesNotExist();
        }
    }
    @Test void scanAndAdmissionEntryBudgetsFailBeforeDeletion()throws Exception{
        try(var cache=new TileCache(temp)){
            for(int i=0;i<18;i++)Files.writeString(temp.resolve("tiles-v1/unknown"+i),"retain");
            var limited=new TileMaintenance(true,Duration.ofDays(7),Duration.ofMinutes(5),1000000,Duration.ofDays(1),Duration.ofHours(1),16);
            assertThatThrownBy(()->cache.maintain(limited,System.currentTimeMillis(),true)).isInstanceOf(IOException.class);
            assertThatThrownBy(()->cache.checkCapacity(16,3)).isInstanceOf(IOException.class);
            assertThat(Files.readString(temp.resolve("tiles-v1/unknown0"))).isEqualTo("retain");
        }
    }
}
