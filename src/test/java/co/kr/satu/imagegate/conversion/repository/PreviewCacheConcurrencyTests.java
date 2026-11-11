package co.kr.satu.imagegate.conversion.repository;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviewCacheConcurrencyTests {
    @TempDir Path root;
    @Test void statusPollingAndAtomicStateReplacementCanRunTogether() throws Exception {
        var cache=new PreviewCache(root);String key="a".repeat(64);
        cache.state(key,1,"queued",null);
        try(var executor=Executors.newFixedThreadPool(5)) {
            var start=new CountDownLatch(1);var futures=new ArrayList<Future<?>>();
            futures.add(executor.submit(()->{
                start.await();
                for(int i=0;i<300;i++)cache.state(key,1,"running",null);
                return null;
            }));
            for(int reader=0;reader<4;reader++)futures.add(executor.submit(()->{
                start.await();
                for(int i=0;i<600;i++)assertThat(cache.read(key,1).state()).isIn("queued","running");
                return null;
            }));
            start.countDown();
            for(var future:futures)future.get(30,TimeUnit.SECONDS);
        }
    }
}
