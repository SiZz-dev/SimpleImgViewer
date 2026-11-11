package co.kr.satu.imagegate.playback.exception;

public final class PlaybackException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_READY, INVALID_MEDIA, UNSUPPORTED, LIMIT_EXCEEDED, BUSY, STORAGE_UNAVAILABLE, PROCESS_FAILED }
    private final Code code;
    public PlaybackException(Code code){super(code.name());this.code=code;}
    public PlaybackException(Code code,Throwable cause){super(code.name(),cause);this.code=code;}
    public Code code(){return code;}
}
