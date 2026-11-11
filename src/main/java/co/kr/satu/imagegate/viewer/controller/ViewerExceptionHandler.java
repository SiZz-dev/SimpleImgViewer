package co.kr.satu.imagegate.viewer.controller;

import co.kr.satu.imagegate.viewer.exception.ViewerException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses=ViewerSessionController.class)
public final class ViewerExceptionHandler {
    @ExceptionHandler(ViewerException.class)
    ResponseEntity<ProblemDetail> failure(ViewerException exception) {
        var status=switch(exception.code()) {
            case SESSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case SESSION_LIMIT_REACHED -> HttpStatus.TOO_MANY_REQUESTS;
            case SESSION_MEDIA_CHANGED -> HttpStatus.CONFLICT;
            case INVALID_VIEWER_POSITION -> HttpStatus.BAD_REQUEST;
        };
        var problem=ProblemDetail.forStatus(status);problem.setProperty("code",exception.code().name());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);
    }
}
