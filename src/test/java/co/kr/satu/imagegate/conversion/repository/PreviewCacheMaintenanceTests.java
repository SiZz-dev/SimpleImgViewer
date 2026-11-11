package co.kr.satu.imagegate.conversion.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PreviewCachePolicy;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewCacheMaintenanceTests {
    @TempDir Path root;
    final long now=System.currentTimeMillis();
    PreviewCachePolicy policy(long bytes){return new PreviewCachePolicy(true,Duration.ofHours(1),Duration.ofMinutes(1),bytes);}
    String key(int i){return String.format("%064x",i);}
    void ready(PreviewCache cache,int i,int bytes,long age) throws Exception {
        var dir=cache.entry(key(i));
        var file=Files.write(dir.resolve("input.png"),new byte[bytes]);
        cache.publish(key(i),1,file,16,32,new ImageGeometry(60,40,6));
        Files.setLastModifiedTime(dir.resolve("state.properties"),FileTime.fromMillis(now-age));
    }
    @Test void expiresReadyAndFailedEntriesAndPreservesOriginals() throws Exception {
        var original=Files.write(root.resolve("original.wav"),new byte[]{1,2,3});
        {var cache=new PreviewCache(root.resolve("cache"));
            ready(cache,1,1000,7200000);ready(cache,2,1000,1000);
            cache.state(key(3),1,"failed","INVALID_MEDIA");
            Files.setLastModifiedTime(cache.entry(key(3)).resolve("state.properties"),FileTime.fromMillis(now-7200000));
            var result=cache.maintain(policy(1048576),now);
            assertThat(result.removed()).isEqualTo(2);assertThat(result.removedBytes()).isEqualTo(1000);
            assertThat(cache.read(key(1),1).state()).isEqualTo("not_requested");
            assertThat(cache.read(key(2),1).ready()).isTrue();assertThat(cache.read(key(3),1).state()).isEqualTo("not_requested");
            assertThat(Files.readAllBytes(original)).containsExactly(1,2,3);
        }
    }
    @Test void capacityEvictsLeastRecentlyUsedFirst() throws Exception {
        {var cache=new PreviewCache(root.resolve("cache"));
            ready(cache,1,700000,30000);ready(cache,2,700000,20000);ready(cache,3,700000,10000);
            var result=cache.maintain(policy(1048576),now);
            assertThat(result.removed()).isEqualTo(2);assertThat(result.retainedBytes()).isEqualTo(700000);
            assertThat(cache.read(key(1),1).ready()).isFalse();assertThat(cache.read(key(2),1).ready()).isFalse();
            assertThat(cache.read(key(3),1).ready()).isTrue();
        }
    }
    @Test void leasesProtectReadersUntilEveryCallbackHasCompleted() throws Exception {
        {var cache=new PreviewCache(root.resolve("cache"));
            ready(cache,1,700000,7200000);ready(cache,2,700000,1000);
            var first=cache.lease(key(1));var second=cache.lease(key(1));
            var content=cache.content(key(1),1);
            assertThat(cache.maintain(policy(1048576),now+7200000).removed()).isEqualTo(1);
            first.close();first.close();
            assertThat(cache.maintain(policy(1048576),now+7200000).removed()).isZero();
            assertThat(content).isRegularFile();second.close();
            assertThat(cache.maintain(policy(1048576),now+7200000).removed()).isEqualTo(1);
        }
    }
    @Test void acquisitionRefreshesPersistentAccessTimeAcrossRestart() throws Exception {
        {var cache=new PreviewCache(root.resolve("cache"));
            ready(cache,1,1000,7200000);try(var lease=cache.lease(key(1))){assertThat(cache.read(key(1),1).ready()).isTrue();}
        }
        {var cache=new PreviewCache(root.resolve("cache"));
            assertThat(cache.maintain(policy(1048576),System.currentTimeMillis()).removed()).isZero();
        }
    }
    @Test void unknownFilesCorruptMetadataAndWorkDirectoriesAreNeverDeleted() throws Exception {
        {var cache=new PreviewCache(root.resolve("cache"));
            ready(cache,1,1000,7200000);var extra=Files.writeString(cache.entry(key(1)).resolve("unexpected.txt"),"keep");
            cache.state(key(2),1,"running",null);var work=Files.createDirectory(cache.entry(key(2)).resolve("work-test"));
            var secret=Files.writeString(work.resolve("input"),"keep");
            Files.writeString(cache.entry(key(3)).resolve("state.properties"),"key=wrong");
            Files.writeString(root.resolve("cache/previews-v1").resolve(key(4)),"not a directory");
            Files.writeString(cache.entry(key(5)).resolve("state.properties"),"key="+(char)92+"uZZZZ");
            var result=cache.maintain(policy(1048576),now);
            assertThat(result.removed()).isZero();assertThat(result.skipped()).isEqualTo(4);
            assertThat(Files.readString(extra)).isEqualTo("keep");assertThat(Files.readString(secret)).isEqualTo("keep");
        }
    }
    @Test void disabledMaintenanceDoesNotRemoveAnythingAndGeometrySurvives() throws Exception {
        var cache=new PreviewCache(root.resolve("cache"));ready(cache,1,1000,7200000);
        var disabled=new PreviewCachePolicy(false,Duration.ofHours(1),Duration.ofMinutes(1),1048576);
        assertThat(cache.maintain(disabled,now).removed()).isZero();
        assertThat(cache.read(key(1),1).geometry()).isEqualTo(new ImageGeometry(60,40,6));
    }
    @Test void cleanupPassLimitsEvictionsAndRetainsFutureTimestamps() throws Exception {
        {var cache=new PreviewCache(root.resolve("cache"));
            ready(cache,1,1000,-7200000);
            assertThat(cache.maintain(policy(1048576),now).removed()).isZero();
            for(int i=2;i<1004;i++){
                cache.state(key(i),1,"failed","TEST");
                Files.setLastModifiedTime(cache.entry(key(i)).resolve("state.properties"),FileTime.fromMillis(now-7200000));
            }
            assertThat(cache.maintain(policy(1048576),now).removed()).isEqualTo(1000);
            assertThat(cache.maintain(policy(1048576),now).removed()).isEqualTo(2);
            assertThat(cache.read(key(1),1).ready()).isTrue();
        }
    }
    @Test void quotaIncludesThumbnailBytesAndEvictsThePair() throws Exception {
        var cache=new PreviewCache(root.resolve("cache"));var directory=cache.entry(key(1));
        Path large=Files.write(directory.resolve("large.png"),new byte[700000]),small=Files.write(directory.resolve("small.png"),new byte[500000]);
        cache.publish(key(1),1,large,1024,768,null,small,512,384);
        var result=cache.maintain(policy(1048576),now);
        assertThat(result.removed()).isEqualTo(1);assertThat(result.removedBytes()).isEqualTo(1200000);assertThat(directory).doesNotExist();
    }
    @Test void missingThumbnailInvalidatesThePairInsteadOfAdvertisingReady() throws Exception {
        var cache=new PreviewCache(root.resolve("cache"));var directory=cache.entry(key(1));
        Path large=Files.write(directory.resolve("large.png"),new byte[1024]),small=Files.write(directory.resolve("small.png"),new byte[128]);
        cache.publish(key(1),1,large,128,64,null,small,64,32);Files.delete(cache.thumbnail(key(1),1));
        assertThat(cache.read(key(1),1).errorCode()).isEqualTo("CACHE_INVALID");
        assertThatThrownBy(()->cache.thumbnail(key(1),1)).isInstanceOf(java.io.IOException.class);
    }
}
