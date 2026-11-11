package co.kr.satu.imagegate.viewer.controller;

import co.kr.satu.imagegate.viewer.controller.dto.*;
import co.kr.satu.imagegate.viewer.domain.ViewerOpenOptions;
import co.kr.satu.imagegate.viewer.service.ViewerSessionService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/viewer/sessions")
public final class ViewerSessionController {
    private final ViewerSessionService service;
    public ViewerSessionController(ViewerSessionService service) { this.service=service; }
    @PostMapping
    ResponseEntity<ViewerSessionResponse> create(@Valid @RequestBody ViewerSessionRequest request) {
        var view=service.create(new ViewerOpenOptions(request.mediaIds(),request.selectedMediaId(),
                request.initialPage(),request.initialTimeSeconds(),request.theme(),request.leftPanelOpen()));
        return ResponseEntity.created(URI.create("/api/v1/viewer/sessions/"+view.session().id()))
                .cacheControl(CacheControl.noStore()).body(ViewerSessionResponse.from(view));
    }
    @GetMapping("/{sessionId}")
    ResponseEntity<ViewerSessionResponse> get(@PathVariable String sessionId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ViewerSessionResponse.from(service.get(sessionId)));
    }
    @DeleteMapping("/{sessionId}")
    ResponseEntity<Void> delete(@PathVariable String sessionId) {
        service.delete(sessionId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
