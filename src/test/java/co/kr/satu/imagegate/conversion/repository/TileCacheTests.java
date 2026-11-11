package co.kr.satu.imagegate.conversion.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.util.*;
import co.kr.satu.imagegate.conversion.service.img2tile.Img2TilePyramidValidator;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TileCacheTests {
    @TempDir Path temp;
    final String key="a".repeat(64);
    byte[] png() throws Exception {
        var bytes=new ByteArrayOutputStream();var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);
        try {image.setRGB(0,0,0xff112233);ImageIO.write(image,"PNG",bytes);}finally{image.flush();}return bytes.toByteArray();
    }
    TilePyramidManifest source(byte[] png) {
        var tile=new TilePyramidManifest.Tile(0,0,0,1,1,png.length,PreviewKeys.sha256(png));
        return new TilePyramidManifest(1,"img2tile-tiff-rgb8-o1-v1",Img2TilePyramidValidator.MODULE,1,new TilePyramidLayout(1,1,256,0),
                "original-upright-pixel-edges","b".repeat(64),png.length+128,List.of(tile));
    }
    TileCache.SourceTiles input(byte[] png) {
        return new TileCache.SourceTiles(){public byte[] normalize(TilePyramidManifest.Tile tile){return png;}public void verifyBeforeCommit() {}};
    }
    Path entry(){return temp.resolve("tiles-v1").resolve(key);}
    @Test void publishesCompleteGenerationReadsAndReopens() throws Exception {
        byte[] png=png();TileCacheManifest manifest;
        try(var cache=new TileCache(temp)) {
            manifest=cache.publish(key,source(png),input(png));assertThat(cache.manifest(key)).isEqualTo(manifest);
            cache.withTile(key,manifest.generation(),0,0,0,(in,tile)->assertThat(in.readAllBytes()).isEqualTo(png));
            assertThat(cache.publish(key,source(png),new TileCache.SourceTiles(){
                public byte[] normalize(TilePyramidManifest.Tile t){throw new AssertionError("must reuse");}public void verifyBeforeCommit(){}
            })).isEqualTo(manifest);
            try(var paths=Files.list(entry())){assertThat(paths.map(p->p.getFileName().toString()).toList()).containsExactlyInAnyOrder("manifest.json","0_0_0.png");}
        }
        try(var reopened=new TileCache(temp)) { assertThat(reopened.manifest(key)).isEqualTo(manifest);assertThat(reopened.evict(key)).isTrue();assertThat(Files.exists(entry())).isFalse(); }
    }
    @ParameterizedTest @ValueSource(strings={"tile","precommit","interrupt"})
    void failureNeverPublishesAndCleansOwnedStaging(String kind) throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            assertThatThrownBy(()->cache.publish(key,source(png),new TileCache.SourceTiles(){
                public byte[] normalize(TilePyramidManifest.Tile tile)throws IOException{if(kind.equals("tile"))throw new IOException("synthetic");return png;}
                public void verifyBeforeCommit()throws IOException{if(kind.equals("interrupt"))Thread.currentThread().interrupt();else throw new IOException("changed");}
            })).isInstanceOf(IOException.class);
            if(kind.equals("interrupt")){assertThat(Thread.currentThread().isInterrupted()).isTrue();Thread.interrupted();}
            assertThat(Files.exists(entry())).isFalse();
            try(var paths=Files.list(temp.resolve("tiles-v1"))){assertThat(paths.map(p->p.getFileName().toString()).toList()).containsExactly("worker.lock");}
        }finally{Thread.interrupted();}
    }
    @Test void activeReadPreventsEvictionAndDefersWriterReleaseOnClose() throws Exception {
        byte[] png=png();var cache=new TileCache(temp);var manifest=cache.publish(key,source(png),input(png));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try {
            var read=executor.submit(()->{cache.withTile(key,manifest.generation(),0,0,0,(in,tile)->{
                entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){throw new IOException(e);}
                assertThat(in.readAllBytes()).isEqualTo(png);
            });return null;});
            assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(cache.evict(key)).isFalse();cache.close();
            assertThatThrownBy(()->new TileCache(temp)).isInstanceOf(IOException.class);
            assertThatThrownBy(()->cache.manifest(key)).isInstanceOf(IOException.class);
            release.countDown();read.get(10,TimeUnit.SECONDS);
            try(var reopened=new TileCache(temp)){assertThat(reopened.evict(key)).isTrue();}
        }finally{release.countDown();executor.shutdownNow();cache.close();}
    }
    @Test void failingConsumerReleasesLease() throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            var manifest=cache.publish(key,source(png),input(png));
            assertThatThrownBy(()->cache.withTile(key,manifest.generation(),0,0,0,(in,t)->{throw new IOException("disconnected");})).isInstanceOf(IOException.class);
            assertThat(cache.evict(key)).isTrue();
        }
    }
    @ParameterizedTest @ValueSource(strings={"hash","generation","coordinate","key","manifest","duplicate","trailing","unknown","missing","null"})
    void rejectsTamperedContentAndInvalidRequests(String kind) throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            var manifest=cache.publish(key,source(png),input(png));
            if(kind.equals("hash")){byte[] changed=png.clone();changed[30]^=1;Files.write(entry().resolve("0_0_0.png"),changed);}
            if(Set.of("manifest","duplicate","trailing","unknown","missing","null").contains(kind)) {
                Path file=entry().resolve("manifest.json");String text=Files.readString(file);
                text=switch(kind){case "manifest"->text.replace("png-rgba8-o1-v1","unsupported");case "duplicate"->text.replaceFirst("\\{","{\"schemaVersion\":1,");case "trailing"->text+" {}";case "missing"->text.replace("\"column\":0,","");case "null"->text.replace("\"column\":0","\"column\":null");default->text.replaceFirst("\\{","{\"unexpected\":1,");};Files.writeString(file,text);
            }
            assertThatThrownBy(()->cache.withTile(kind.equals("key")?"../bad":key,kind.equals("generation")?UUID.randomUUID().toString():manifest.generation(),
                    kind.equals("coordinate")?1:0,0,0,(in,t)->{throw new AssertionError("must not expose bytes");})).isInstanceOfAny(IOException.class,IllegalArgumentException.class);
        }
    }
    @Test void preservesUnknownFilesAndRejectsSourceConflict() throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            cache.publish(key,source(png),input(png));var old=source(png);
            var changed=new TilePyramidManifest(old.schemaVersion(),old.profile(),old.moduleIdentity(),2,old.layout(),old.coordinateSpace(),old.descriptorSha256(),old.totalBytes(),old.tiles());
            assertThatThrownBy(()->cache.publish(key,changed,input(png))).isInstanceOf(IOException.class);
            Path unknown=entry().resolve("retain.txt");Files.writeString(unknown,"retain");
            assertThatThrownBy(()->cache.evict(key)).isInstanceOf(IOException.class);assertThat(Files.readString(unknown)).isEqualTo("retain");
            assertThat(Files.exists(entry().resolve("0_0_0.png"))).isTrue();
        }
    }
    @Test void validatesWorkerMarkerAndExclusiveWriter() throws Exception {
        try(var cache=new TileCache(temp)){assertThatThrownBy(()->new TileCache(temp)).isInstanceOf(IOException.class);}
        Files.delete(temp.resolve("tiles-v1/worker.lock"));Files.createDirectory(temp.resolve("tiles-v1/worker.lock"));
        assertThatThrownBy(()->new TileCache(temp)).isInstanceOf(IOException.class);
    }
    @Test void rejectsMismatchedExpectedPageBeforeDeliveringBytes()throws Exception{
        byte[] png=png();try(var cache=new TileCache(temp)){
            var manifest=cache.publish(key,source(png),input(png));
            assertThatThrownBy(()->cache.withTile(key,manifest.generation(),2,0,0,0,(stream,tile)->{throw new AssertionError("must not deliver");}))
                    .isInstanceOf(co.kr.satu.imagegate.conversion.exception.TileUnavailableException.class);
            assertThat(cache.evict(key)).isTrue();
        }
    }
    @Test void doesNotExposeStagingWhileProducerIsBlocked() throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
            try {
                var publishing=executor.submit(()->cache.publish(key,source(png),new TileCache.SourceTiles(){
                    public byte[] normalize(TilePyramidManifest.Tile tile){return png;}
                    public void verifyBeforeCommit()throws IOException{entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){throw new IOException(e);}}
                }));
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();assertThat(Files.exists(entry())).isFalse();
                release.countDown();assertThat(publishing.get(10,TimeUnit.SECONDS).key()).isEqualTo(key);assertThat(Files.exists(entry())).isTrue();
            }finally{release.countDown();executor.shutdownNow();}
        }
    }
    @Test void refusesCorruptReuseAndPartialOrUnknownGenerations() throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            cache.publish(key,source(png),input(png));Files.delete(entry().resolve("0_0_0.png"));
            assertThatThrownBy(()->cache.publish(key,source(png),input(png))).isInstanceOf(IOException.class);
            assertThatThrownBy(()->cache.evict(key)).isInstanceOf(IOException.class);
            assertThat(Files.exists(entry().resolve("manifest.json"))).isTrue();
        }
    }
    @Test void sourceChangesDuringNormalizationPreventPublication() throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            assertThatThrownBy(()->cache.publish(key,source(png),new TileCache.SourceTiles(){
                public byte[] normalize(TilePyramidManifest.Tile tile){return png;}
                public void verifyBeforeCommit()throws IOException{
                    try(var paths=Files.list(temp.resolve("tiles-v1"))){
                        Path stage=paths.filter(p->p.getFileName().toString().startsWith(".work-")).findFirst().orElseThrow();
                        byte[] changed=png.clone();changed[30]^=1;Files.write(stage.resolve("0_0_0.png"),changed);
                    }
                }
            })).isInstanceOf(IOException.class);
            assertThat(Files.exists(entry())).isFalse();
        }
    }
    @Test void rejectsWindowsJunctionInPublishedContent() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        byte[] png=png();try(var cache=new TileCache(temp)) {
            var manifest=cache.publish(key,source(png),input(png));Path outside=temp.resolve("moved-entry");Files.move(entry(),outside);
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",entry().toString(),outside.toString()).redirectErrorStream(true).start();
            assertThat(process.waitFor()).isZero();
            try{
                assertThatThrownBy(()->cache.withTile(key,manifest.generation(),0,0,0,(in,t)->{throw new AssertionError();})).isInstanceOf(IOException.class);
                assertThatThrownBy(()->cache.evict(key)).isInstanceOf(IOException.class);
                assertThat(Files.exists(outside.resolve("0_0_0.png"))).isTrue();
            }finally{Files.delete(entry());}
        }
    }
    @Test void preservesCollidingFileThatWasNeverCreatedByThePublisher() throws Exception {
        byte[] png=png();try(var cache=new TileCache(temp)) {
            var collision=new java.util.concurrent.atomic.AtomicReference<Path>();
            assertThatThrownBy(()->cache.publish(key,source(png),new TileCache.SourceTiles(){
                public byte[] normalize(TilePyramidManifest.Tile tile)throws IOException {
                    try(var paths=Files.list(temp.resolve("tiles-v1"))){
                        Path stage=paths.filter(p->p.getFileName().toString().startsWith(".work-")).findFirst().orElseThrow();
                        collision.set(stage.resolve("0_0_0.png"));Files.writeString(collision.get(),"unknown-owner");
                    }
                    return png;
                }
                public void verifyBeforeCommit(){throw new AssertionError();}
            })).isInstanceOf(IOException.class);
            assertThat(Files.readString(collision.get())).isEqualTo("unknown-owner");assertThat(Files.exists(entry())).isFalse();
        }
    }
}
