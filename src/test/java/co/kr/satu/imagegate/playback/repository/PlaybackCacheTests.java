package co.kr.satu.imagegate.playback.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.playback.domain.PlaybackArtifact;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PlaybackCachePolicy;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaybackCacheTests {
    @TempDir Path root;
    final long now=System.currentTimeMillis();
    PlaybackCachePolicy policy(long bytes){return new PlaybackCachePolicy(true,Duration.ofHours(1),Duration.ofMinutes(1),bytes);}
    String key(int i){return String.format("%064x",i);}
    void ready(PlaybackCache cache,int i,int bytes,long age) throws Exception {
        var dir=cache.entry(key(i),true);
        var file=Files.write(dir.resolve("input.mp3"),new byte[bytes]);
        cache.publish(key(i),new PlaybackArtifact(file,3,"audio/mpeg",0,0,null,"mp3"));
        Files.setLastModifiedTime(dir.resolve("state.properties"),FileTime.fromMillis(now-age));
    }
    @Test void expiresReadyAndFailedEntriesAndPreservesOriginals() throws Exception {
        var original=Files.write(root.resolve("original.wav"),new byte[]{1,2,3});
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000)){
            ready(cache,1,1000,7200000);ready(cache,2,1000,1000);
            cache.state(key(3),"failed","INVALID_MEDIA");
            Files.setLastModifiedTime(cache.entry(key(3),false).resolve("state.properties"),FileTime.fromMillis(now-7200000));
            var result=cache.maintain(policy(1048576),now);
            assertThat(result.removed()).isEqualTo(2);assertThat(result.removedBytes()).isEqualTo(1000);
            assertThat(cache.read(key(1)).state()).isEqualTo("not_requested");
            assertThat(cache.read(key(2)).ready()).isTrue();assertThat(cache.read(key(3)).state()).isEqualTo("not_requested");
            assertThat(Files.readAllBytes(original)).containsExactly(1,2,3);
        }
    }
    @Test void capacityEvictsLeastRecentlyUsedFirst() throws Exception {
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000)){
            ready(cache,1,700000,30000);ready(cache,2,700000,20000);ready(cache,3,700000,10000);
            var result=cache.maintain(policy(1048576),now);
            assertThat(result.removed()).isEqualTo(2);assertThat(result.retainedBytes()).isEqualTo(700000);
            assertThat(cache.read(key(1)).ready()).isFalse();assertThat(cache.read(key(2)).ready()).isFalse();
            assertThat(cache.read(key(3)).ready()).isTrue();
        }
    }
    @Test void leasesProtectReadersUntilEveryCallbackHasCompleted() throws Exception {
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000)){
            ready(cache,1,700000,7200000);ready(cache,2,700000,1000);
            var first=cache.lease(key(1));var second=cache.lease(key(1));
            var content=cache.content(key(1));
            assertThat(cache.maintain(policy(1048576),now+7200000).removed()).isEqualTo(1);
            first.close();first.close();
            assertThat(cache.maintain(policy(1048576),now+7200000).removed()).isZero();
            assertThat(content).isRegularFile();second.close();
            assertThat(cache.maintain(policy(1048576),now+7200000).removed()).isEqualTo(1);
        }
    }
    @Test void acquisitionRefreshesPersistentAccessTimeAcrossRestart() throws Exception {
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000)){
            ready(cache,1,1000,7200000);try(var lease=cache.lease(key(1))){assertThat(cache.read(key(1)).ready()).isTrue();}
        }
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000)){
            assertThat(cache.maintain(policy(1048576),System.currentTimeMillis()).removed()).isZero();
        }
    }
    @Test void unknownFilesCorruptMetadataAndWorkDirectoriesAreNeverDeleted() throws Exception {
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000)){
            ready(cache,1,1000,7200000);var extra=Files.writeString(cache.entry(key(1),false).resolve("unexpected.txt"),"keep");
            cache.state(key(2),"running",null);var work=Files.createDirectory(cache.entry(key(2),false).resolve("work-test"));
            var secret=Files.writeString(work.resolve("input"),"keep");
            Files.writeString(cache.entry(key(3),true).resolve("state.properties"),"key=wrong");
            Files.writeString(root.resolve("cache").resolve(key(4)),"not a directory");
            var result=cache.maintain(policy(1048576),now);
            assertThat(result.removed()).isZero();assertThat(result.skipped()).isEqualTo(3);
            assertThat(Files.readString(extra)).isEqualTo("keep");assertThat(Files.readString(secret)).isEqualTo("keep");
        }
    }
    @Test void disabledMaintenanceAndClosedCacheDoNotRemoveAnything() throws Exception {
        var cache=new PlaybackCache(root.resolve("cache"),2000000);
        ready(cache,1,1000,7200000);
        var disabled=new PlaybackCachePolicy(false,Duration.ofHours(1),Duration.ofMinutes(1),1048576);
        assertThat(cache.maintain(disabled,now).removed()).isZero();cache.close();cache.close();
        assertThat(cache.maintain(policy(1048576),now).removed()).isZero();
        assertThatThrownBy(()->cache.lease(key(1))).isInstanceOf(java.io.IOException.class);
    }
    @Test void cleanupPassLimitsEvictionsAndRetainsFutureTimestamps() throws Exception {
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000)){
            ready(cache,1,1000,-7200000);
            assertThat(cache.maintain(policy(1048576),now).removed()).isZero();
            for(int i=2;i<1004;i++){
                cache.state(key(i),"failed","TEST");
                Files.setLastModifiedTime(cache.entry(key(i),false).resolve("state.properties"),FileTime.fromMillis(now-7200000));
            }
            assertThat(cache.maintain(policy(1048576),now).removed()).isEqualTo(1000);
            assertThat(cache.maintain(policy(1048576),now).removed()).isEqualTo(2);
            assertThat(cache.read(key(1)).ready()).isTrue();
        }
    }

    @Test void closingRejectsNewLeasesAndKeepsWriterUntilEveryExistingLeaseIsReleased() throws Exception {
        Path directory=root.resolve("cache");var cache=new PlaybackCache(directory,2000000);
        ready(cache,1,1000,7200000);
        var first=cache.lease(key(1));var second=cache.lease(key(1));var other=cache.lease(key(2));
        try{
            cache.close();cache.close();
            assertThatThrownBy(()->cache.lease(key(1))).isInstanceOf(java.io.IOException.class);
            assertWriterBusy(directory);
            assertThat(cache.maintain(policy(1048576),now+7200000).removed()).isZero();
            first.close();first.close();second.close();
            assertWriterBusy(directory);
            // A leased job may persist its failure during deferred close.
            cache.state(key(2),"failed","INTERRUPTED");
        }finally{first.close();second.close();other.close();cache.close();}
        try(var reopened=new PlaybackCache(directory,2000000)){
            assertThat(reopened.read(key(2)).errorCode()).isEqualTo("INTERRUPTED");
        }
    }

    @Test void closingRejectsPublicationEvenWhileAnExistingLeaseRetainsTheLock() throws Exception {
        try(var cache=new PlaybackCache(root.resolve("cache"),2000000);var lease=cache.lease(key(1))){
            var dir=cache.entry(key(1),true);
            var file=Files.write(dir.resolve("input.mp3"),new byte[100]);
            cache.close();
            assertThatThrownBy(()->cache.publish(key(1),new PlaybackArtifact(file,3,"audio/mpeg",0,0,null,"mp3")))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(file).isRegularFile();
        }
    }

    @Test void releasedInstanceCannotReadOrWriteAfterAnotherWriterOpens() throws Exception {
        Path directory=root.resolve("cache");var old=new PlaybackCache(directory,2000000);
        old.state(key(1),"failed","INTERRUPTED");old.close();
        try(var reopened=new PlaybackCache(directory,2000000)){
            assertThatThrownBy(()->old.state(key(1),"running",null)).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(()->old.read(key(1))).isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(()->old.entry(key(2),true)).isInstanceOf(java.io.IOException.class);
            assertThat(reopened.read(key(1)).errorCode()).isEqualTo("INTERRUPTED");
        }
    }

    void assertWriterBusy(Path directory) {
        assertThatThrownBy(()->{try(var unexpected=new PlaybackCache(directory,2000000)){}})
                .isInstanceOfAny(java.io.IOException.class,java.nio.channels.OverlappingFileLockException.class);
    }
}
