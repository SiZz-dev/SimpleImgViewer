package co.kr.satu.imagegate.conversion.util;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TileTimingTests {
    @Test void diagnosticFailureDoesNotReplaceWorkFailureOrItsSuppressedExceptions(){
        var expected=new IOException("work failed");
        assertThatThrownBy(()->{try(var span=TileTiming.start((s,n)->{throw new IllegalStateException("observer failed");},TileTiming.Stage.NATIVE_CONVERT)){throw expected;}})
            .isSameAs(expected).satisfies(e->assertThat(e.getSuppressed()).isEmpty());
    }
    @Test void interruptCannotBeClearedByDiagnostics(){
        try{Thread.currentThread().interrupt();try(var span=TileTiming.start((s,n)->Thread.interrupted(),TileTiming.Stage.NORMALIZE_TILE)){}
            assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}
    }
    @Test void spanReportsOnlyOnceAndDisabledSpanRemainsReusable(){
        var calls=new AtomicInteger();var span=TileTiming.start((s,n)->{assertThat(s).isEqualTo(TileTiming.Stage.TILE_WRITE);assertThat(n).isNotNegative();calls.incrementAndGet();},TileTiming.Stage.TILE_WRITE);
        span.close();span.close();assertThat(calls).hasValue(1);
        for(int i=0;i<2;i++)try(var disabled=TileTiming.start(null,TileTiming.Stage.TILE_WRITE)){}
    }
    @Test void timedClosePreservesBodyFailureAndSuppressedCloseFailure(){
        var body=new IOException("body");var close=new IOException("close");var count=new AtomicInteger();
        java.io.Closeable source=()->{count.incrementAndGet();throw close;};
        assertThatThrownBy(()->{try(var resource=TileTiming.withTimedClose(source,(s,n)->{throw new IllegalStateException("observer");},TileTiming.Stage.CACHE_WRITE_CLOSE)){throw body;}})
            .isSameAs(body).satisfies(e->assertThat(e.getSuppressed()).containsExactly(close));
        assertThat(count).hasValue(1);assertThat(TileTiming.withTimedClose(source,null,TileTiming.Stage.CACHE_WRITE_CLOSE)).isSameAs(source);
    }
    @Test void timedCloseRetainsStandaloneIoFailureAndInterrupt(){
        var close=new IOException("close");java.io.Closeable source=()->{throw close;};
        try{Thread.currentThread().interrupt();assertThatThrownBy(()->{try(var resource=TileTiming.withTimedClose(source,(s,n)->{Thread.interrupted();throw new IllegalStateException();},TileTiming.Stage.CACHE_READ_CLOSE)) {}}).isSameAs(close);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}
    }
}
