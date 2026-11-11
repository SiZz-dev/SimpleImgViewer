package co.kr.satu.imagegate.conversion.exception;

/** Fixed internal failure; does not expose source paths or native exception messages. */
public final class OriginalOpenTimeoutException extends java.io.IOException {
    public OriginalOpenTimeoutException(){super("Original open admission timed out");}
}
