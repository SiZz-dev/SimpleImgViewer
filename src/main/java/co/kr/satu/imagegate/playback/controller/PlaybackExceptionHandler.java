package co.kr.satu.imagegate.playback.controller;

import co.kr.satu.imagegate.playback.exception.PlaybackException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(basePackageClasses=PlaybackController.class)
public final class PlaybackExceptionHandler {
    @ExceptionHandler(PlaybackException.class)
    ResponseEntity<ProblemDetail> failure(PlaybackException error) {
        var status=switch(error.code()){
            case INVALID_REQUEST->HttpStatus.BAD_REQUEST;
            case NOT_READY->HttpStatus.CONFLICT;
            case INVALID_MEDIA->HttpStatus.UNPROCESSABLE_CONTENT;
            case UNSUPPORTED->HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case LIMIT_EXCEEDED->HttpStatus.valueOf(413);
            case BUSY->HttpStatus.TOO_MANY_REQUESTS;
            case STORAGE_UNAVAILABLE,PROCESS_FAILED->HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problem=ProblemDetail.forStatus(status);problem.setProperty("code","PLAYBACK_"+error.code().name());
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);
    }
}
