package co.kr.satu.imagegate.media.controller;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=MediaTileController.class)
public final class TileRequestExceptionHandler {
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> invalid(){var problem=ProblemDetail.forStatus(400);problem.setProperty("code","INVALID_REQUEST");
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").body(problem);}
}
