package co.kr.satu.imagegate.conversion.util;
import static org.assertj.core.api.Assertions.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
class PreviewTimingTests {
    @Test void disabledSpanDoesNotConsumeAnInterrupt(){
        try{
            Thread.currentThread().interrupt();
            try(var span=PreviewTiming.start(null,PreviewTiming.Stage.ORIGINAL_HASH)){}
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
    }
    @Test void completedSpanReportsOnceWithFixedStageAndNonnegativeElapsed(){
        var calls=new AtomicInteger();
        var span=PreviewTiming.start((stage,nanos)->{
            assertThat(stage).isEqualTo(PreviewTiming.Stage.PIXEL_DECODE);
            assertThat(nanos).isNotNegative();calls.incrementAndGet();
        },PreviewTiming.Stage.PIXEL_DECODE);
        span.close();span.close();assertThat(calls).hasValue(1);
    }
    @Test void failingObserverCannotReplaceBodyFailureOrClearPriorInterrupt(){
        try{
            Thread.currentThread().interrupt();
            assertThatThrownBy(()->{
                try(var span=PreviewTiming.start((stage,nanos)->{
                    Thread.interrupted();throw new IllegalStateException("Observer failure");
                },PreviewTiming.Stage.PREVIEW_PNG)){throw new java.io.IOException("Body failure");}
            }).isInstanceOf(java.io.IOException.class).hasMessage("Body failure");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
    }

    @Test void disabledCloseTimingReturnsOriginalStreamAndEnabledReadsDelegate() throws Exception {
        var source=new java.io.ByteArrayInputStream(new byte[]{1,2,3,4});
        assertThat(PreviewTiming.withTimedClose(source,null)).isSameAs(source);
        var closes=new AtomicInteger();
        try(var stream=PreviewTiming.withTimedClose(source,(stage,nanos)->{
            assertThat(stage).isEqualTo(PreviewTiming.Stage.HASH_CLOSE);closes.incrementAndGet();
        })){
            byte[] block=new byte[3];
            assertThat(stream.read()).isEqualTo(1);
            assertThat(stream.read(block,1,2)).isEqualTo(2);assertThat(block).containsExactly(0,2,3);
            assertThat(stream.read(block)).isEqualTo(1);assertThat(block[0]).isEqualTo((byte)4);
            assertThat(stream.read()).isEqualTo(-1);
        }
        assertThat(closes).hasValue(1);
    }
    @Test void timedClosePreservesSuppressedCloseFailureAndRecordsClosure() {
        var closes=new AtomicInteger();
        var source=new java.io.ByteArrayInputStream(new byte[0]){
            @Override public void close() throws java.io.IOException{throw new java.io.IOException("Close failure");}
        };
        assertThatThrownBy(()->{
            try(var stream=PreviewTiming.withTimedClose(source,(stage,nanos)->closes.incrementAndGet())){
                throw new java.io.IOException("Read failure");
            }
        }).isInstanceOfSatisfying(java.io.IOException.class,e->{
            assertThat(e).hasMessage("Read failure");
            assertThat(e.getSuppressed()).hasSize(1);assertThat(e.getSuppressed()[0]).hasMessage("Close failure");
        });
        assertThat(closes).hasValue(1);
    }
}
