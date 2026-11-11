package co.kr.satu.imagegate.media.controller;

import co.kr.satu.imagegate.media.exception.MediaException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages={"co.kr.satu.imagegate.media.controller",
        "co.kr.satu.imagegate.viewer.controller","co.kr.satu.imagegate.editing.controller","co.kr.satu.imagegate.playback.controller"})
public class MediaExceptionHandler {
    @ExceptionHandler(MediaException.class)
    ResponseEntity<ProblemDetail> mediaFailure(MediaException failure) {
        HttpStatus status=switch(failure.code()) {
            case UPLOAD_BUSY, PREVIEW_QUEUE_FULL, TILE_QUEUE_FULL -> HttpStatus.TOO_MANY_REQUESTS;
            case PREVIEW_NOT_READY -> HttpStatus.CONFLICT;
            case MEDIA_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case FILE_TOO_LARGE -> HttpStatus.valueOf(413);
            case UNSUPPORTED_MEDIA -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case INVALID_MEDIA -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TENANT_STORAGE_QUOTA_EXCEEDED, INSUFFICIENT_STORAGE, TILE_LIMIT_EXCEEDED -> HttpStatus.INSUFFICIENT_STORAGE;
            case STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return problem(status,failure.code().name());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status,String code) {
        var detail=ProblemDetail.forStatus(status);
        detail.setProperty("code",code);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(detail);
    }
}
