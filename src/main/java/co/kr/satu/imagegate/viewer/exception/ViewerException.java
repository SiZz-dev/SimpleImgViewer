package co.kr.satu.imagegate.viewer.exception;

public final class ViewerException extends RuntimeException {
    public enum Code { SESSION_NOT_FOUND, SESSION_LIMIT_REACHED, SESSION_MEDIA_CHANGED, INVALID_VIEWER_POSITION }
    private final Code code;
    public ViewerException(Code code) { super(code.name());this.code=code; }
    public Code code() { return code; }
}
