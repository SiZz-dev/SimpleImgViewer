package co.kr.satu.imagegate.viewer.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public final class ViewerPageController {
    @GetMapping({"/viewer","/viewer/"})
    ResponseEntity<Resource> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8"))
                .body(new ClassPathResource("static/viewer/index.html"));
    }
}
