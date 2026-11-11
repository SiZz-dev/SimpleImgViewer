package co.kr.satu.imagegate.conversion.repository;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PreviewCachePolicy;

class PreviewCacheLifecycleTests {
    @TempDir Path root;
    final String key="a".repeat(64),other="b".repeat(64);
    @Test void closingKeepsWriterUntilAllDuplicateAndDistinctLeasesFinish() throws Exception {
        var cache=new PreviewCache(root);cache.claimWriter();cache.state(key,1,"running",null);
        var first=cache.lease(key);var second=cache.lease(key);var third=cache.lease(other);
        try{
            cache.close();cache.close();
            assertThatThrownBy(()->cache.lease(key)).isInstanceOf(IOException.class);
            assertBusy();first.close();first.close();second.close();assertBusy();
            cache.state(other,2,"failed","INTERRUPTED");
            var policy=new PreviewCachePolicy(true,Duration.ofHours(1),Duration.ofMinutes(1),1048576);
            assertThat(cache.maintain(policy,System.currentTimeMillis()+Duration.ofDays(8).toMillis()).removed()).isZero();
        }finally{first.close();second.close();third.close();cache.close();}
        try(var next=new PreviewCache(root)){
            next.claimWriter();assertThat(next.read(other,2).errorCode()).isEqualTo("INTERRUPTED");
        }
    }
    @Test void closingRejectsPublicationAndClosedInstanceCannotTouchReopenedStorage() throws Exception {
        var old=new PreviewCache(root);old.claimWriter();old.state(key,1,"running",null);
        var lease=old.lease(key);var file=Files.write(old.entry(key).resolve("input.png"),new byte[]{1,2,3});
        try{
            old.close();
            assertThatThrownBy(()->old.publish(key,1,file,16,16)).isInstanceOf(IOException.class);
            assertThat(file).exists();old.state(key,1,"failed","INTERRUPTED");
        }finally{lease.close();old.close();}
        try(var next=new PreviewCache(root)){
            next.claimWriter();
            assertThatThrownBy(()->old.state(key,1,"running",null)).isInstanceOf(IOException.class);
            assertThatThrownBy(()->old.read(key,1)).isInstanceOf(IOException.class);
            assertThatThrownBy(()->old.entry(other)).isInstanceOf(IOException.class);
            assertThatThrownBy(old::root).isInstanceOf(IOException.class);
            assertThatThrownBy(old::claimWriter).isInstanceOf(IOException.class);
            assertThat(next.read(key,1).errorCode()).isEqualTo("INTERRUPTED");
        }
    }
    @Test void duplicateClaimCannotReleaseTheCurrentWriter() throws Exception {
        try(var cache=new PreviewCache(root)){
            cache.claimWriter();
            assertThatThrownBy(cache::claimWriter).isInstanceOf(IOException.class);
            assertBusy();
            cache.state(key,1,"failed","TEST");
        }
        try(var next=new PreviewCache(root)){next.claimWriter();}
    }
    @Test void writerLockMustBeARegularFile() throws Exception {
        var cache=new PreviewCache(root);
        var lock=Files.createDirectory(cache.root().resolve("worker.lock"));
        assertThatThrownBy(cache::claimWriter).isInstanceOf(IOException.class);
        Files.delete(lock);
        cache.claimWriter();cache.close();
    }
    void assertBusy() {
        assertThatThrownBy(()->{try(var next=new PreviewCache(root)){next.claimWriter();}})
                .isInstanceOfAny(IOException.class,java.nio.channels.OverlappingFileLockException.class);
    }
}
