package co.kr.satu.imagegate.playback.util;

import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Cooperative monotonic budget for a single playback preparation, including its native calls. */
public final class PlaybackDeadline {
    private final LongSupplier clock;
    private final long started;
    private final long budget;

    public PlaybackDeadline(Duration timeout, LongSupplier clock) {
        Objects.requireNonNull(timeout);
        this.clock = Objects.requireNonNull(clock);
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Playback timeout must be positive and at most five minutes");
        budget = timeout.toNanos();
        started = clock.getAsLong();
    }

    public Duration remaining() throws InterruptedIOException, ConversionException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Playback preparation interrupted");
        long elapsed = clock.getAsLong() - started;
        if (elapsed < 0 || elapsed >= budget)
            throw new ConversionException(ConversionException.Code.TIMEOUT, "Playback preparation timed out");
        return Duration.ofNanos(budget - elapsed);
    }

    public void check() throws InterruptedIOException, ConversionException {
        remaining();
    }
}
