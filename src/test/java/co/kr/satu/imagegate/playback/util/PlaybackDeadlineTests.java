package co.kr.satu.imagegate.playback.util;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PlaybackDeadlineTests {
    @Test void monotonicCounterWrapKeepsRemainingBudgetAndExactBoundaryExpires() throws Exception {
        var clock=new AtomicLong(Long.MAX_VALUE-5);
        var deadline=new PlaybackDeadline(Duration.ofNanos(10),clock::get);
        clock.addAndGet(7);
        assertThat(deadline.remaining()).isEqualTo(Duration.ofNanos(3));
        clock.addAndGet(3);
        assertThatThrownBy(deadline::check).isInstanceOfSatisfying(ConversionException.class,
                e->assertThat(e.code()).isEqualTo(ConversionException.Code.TIMEOUT));
    }
    @Test void cancellationWinsEvenWhenDeadlineExpiredWithoutClearingInterrupt() {
        var clock=new AtomicLong();var deadline=new PlaybackDeadline(Duration.ofNanos(1),clock::get);
        clock.incrementAndGet();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(deadline::check).isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {Thread.interrupted();}
    }
    @Test void rejectsInvalidOrUnboundedBudgets() {
        for(var duration:new Duration[]{Duration.ZERO,Duration.ofNanos(-1),Duration.ofMinutes(5).plusNanos(1)})
            assertThatIllegalArgumentException().isThrownBy(()->new PlaybackDeadline(duration,System::nanoTime));
    }
}
