package co.kr.satu.imagegate.media.exception;

public final class MediaException extends RuntimeException {
    public enum Code { MEDIA_NOT_FOUND, INVALID_REQUEST, FILE_TOO_LARGE, UNSUPPORTED_MEDIA,
        INVALID_MEDIA, TENANT_STORAGE_QUOTA_EXCEEDED, STORAGE_UNAVAILABLE, UPLOAD_BUSY, INSUFFICIENT_STORAGE, PREVIEW_QUEUE_FULL, PREVIEW_NOT_READY, TILE_QUEUE_FULL, TILE_LIMIT_EXCEEDED }
    private final Code code;
    public MediaException(Code code) { super(code.name()); this.code = code; }
    public MediaException(Code code, Throwable cause) { super(code.name(), cause); this.code = code; }
    public Code code() { return code; }
}
