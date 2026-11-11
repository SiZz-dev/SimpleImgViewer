package co.kr.satu.imagegate.conversion.exception;

import java.io.IOException;

/** Stable classification; never expose raw paths, commands or process output to API callers. */
public final class ConversionException extends IOException {
    public enum Code { BUSY, TIMEOUT, PROCESS_FAILED, INVALID_INPUT, UNSUPPORTED_PROFILE, INVALID_OUTPUT, UNAVAILABLE }
    private final Code code;
    private final Integer exitCode;

    public ConversionException(Code code, String message) {
        this(code, message, null);
    }

    public ConversionException(Code code, String message, Integer exitCode) {
        super(message);
        this.code = code;
        this.exitCode = exitCode;
    }

    public Code code() { return code; }
    public Integer exitCode() { return exitCode; }
}
