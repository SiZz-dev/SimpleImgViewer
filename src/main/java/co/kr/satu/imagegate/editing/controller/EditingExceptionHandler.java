package co.kr.satu.imagegate.editing.controller;

import co.kr.satu.imagegate.editing.exception.EditException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses=AnnotationController.class)
public final class EditingExceptionHandler {
    @ExceptionHandler(EditException.class)
    ResponseEntity<ProblemDetail> failure(EditException error) {
        var status=switch(error.code()) {
            case INVALID_EDIT -> HttpStatus.BAD_REQUEST;
            case EDIT_NOT_READY,EDIT_CONFLICT,EDIT_HISTORY_UNAVAILABLE -> HttpStatus.CONFLICT;
            case ANNOTATION_NOT_FOUND,STAMP_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case EDIT_LIMIT_REACHED,CROP_BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case STAMP_TOO_LARGE,CROP_TOO_LARGE -> HttpStatus.valueOf(413);
            case UNSUPPORTED_STAMP -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case INVALID_STAMP,INVALID_CROP,EXPORT_FONT_UNAVAILABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case EDIT_STORAGE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var detail=ProblemDetail.forStatus(status);detail.setProperty("code",error.code().name());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(detail);
    }
}
