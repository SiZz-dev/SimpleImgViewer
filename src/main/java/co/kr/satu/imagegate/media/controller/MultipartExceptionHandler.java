package co.kr.satu.imagegate.media.controller;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import co.kr.satu.imagegate.media.util.UploadReadTimeouts;

/** Parsing can fail before a controller is selected, so this narrow advice has no package selector. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MultipartExceptionHandler {
    @ExceptionHandler(MultipartException.class)
    ResponseEntity<ProblemDetail> readTimeout(MultipartException failure) {
        if(UploadReadTimeouts.causedBySocketTimeout(failure)){
            var detail=ProblemDetail.forStatus(HttpStatus.REQUEST_TIMEOUT);detail.setProperty("code","UPLOAD_READ_TIMEOUT");
            return ResponseEntity.status(408).cacheControl(CacheControl.noStore()).body(detail);
        }
        throw failure; // Keep unrelated multipart failures on the existing resolver path.
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> tooLarge() {
        var detail=ProblemDetail.forStatus(HttpStatus.CONTENT_TOO_LARGE);detail.setProperty("code","FILE_TOO_LARGE");
        return ResponseEntity.status(413).cacheControl(CacheControl.noStore()).body(detail);
    }
}
