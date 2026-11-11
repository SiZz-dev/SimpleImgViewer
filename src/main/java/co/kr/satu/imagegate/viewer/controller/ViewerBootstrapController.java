package co.kr.satu.imagegate.viewer.controller;

import co.kr.satu.imagegate.viewer.service.ViewerCapabilitiesService;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/viewer/bootstrap")
public final class ViewerBootstrapController {
    public record Csrf(String headerName,String token) {}
    public record Limits(int maxFiles,long maxUploadBytes,int maxPage,int maxAnnotationsPerPage,int maxNoteLength,int maxStampsPerUser,long maxStampUploadBytes,long maxCropOutputPixels,long maxCropOutputBytes) {}
    public record Bootstrap(Map<String,Boolean> features,Csrf csrf,Limits limits,Instant serverTime) {}
    private final ViewerCapabilitiesService service;
    private final co.kr.satu.imagegate.system.config.ImageGateProperties properties;
    public ViewerBootstrapController(ViewerCapabilitiesService service,co.kr.satu.imagegate.system.config.ImageGateProperties properties) { this.service=service;this.properties=properties; }
    @GetMapping
    ResponseEntity<Bootstrap> get(CsrfToken csrf) {
        var features=service.capabilities();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Bootstrap(features,
                new Csrf(csrf.getHeaderName(),csrf.getToken()),new Limits(100,properties.upload().maxFileBytes(),10000,properties.editing().maxAnnotationsPerPage(),
                        properties.editing().maxNoteLength(),properties.editing().maxStampsPerUser(),properties.editing().maxStampUploadBytes(),properties.crop().maxOutputPixels(),properties.crop().maxOutputBytes()),Instant.now()));
    }
}
