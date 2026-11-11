package co.kr.satu.imagegate.editing.exception;

public final class EditException extends RuntimeException {
    public enum Code { EDIT_HISTORY_UNAVAILABLE, INVALID_EDIT, EDIT_NOT_READY, EDIT_CONFLICT, ANNOTATION_NOT_FOUND, STAMP_NOT_FOUND,
        EDIT_LIMIT_REACHED, STAMP_TOO_LARGE, UNSUPPORTED_STAMP, INVALID_STAMP, EDIT_STORAGE_UNAVAILABLE, CROP_TOO_LARGE, CROP_BUSY, INVALID_CROP, EXPORT_FONT_UNAVAILABLE }
    private final Code code;
    public EditException(Code code) { super(code.name());this.code=code; }
    public EditException(Code code,Throwable cause) { super(code.name(),cause);this.code=code; }
    public Code code() { return code; }
}
