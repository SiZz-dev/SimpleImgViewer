package co.kr.satu.imagegate.conversion.util;

import java.io.InterruptedIOException;

/** Cooperative checkpoint; preserves the caller's interrupt flag and never claims to stop a decoder. */
public final class PreviewCancellation {
    private PreviewCancellation() {}
    public static void check() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Preview rendering interrupted");
    }
}
