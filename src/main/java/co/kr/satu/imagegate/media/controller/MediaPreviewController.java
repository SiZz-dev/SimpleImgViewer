package co.kr.satu.imagegate.media.controller;

import co.kr.satu.imagegate.media.controller.dto.PreviewResponse;
import co.kr.satu.imagegate.media.service.MediaPreviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media/{mediaId}")
public final class MediaPreviewController {
    private final MediaPreviewService previews;
    public MediaPreviewController(MediaPreviewService previews) { this.previews=previews; }

    @PostMapping("/previews")
    ResponseEntity<PreviewResponse> request(@PathVariable String mediaId,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="false") boolean retry) {
        var state=previews.request(mediaId,page,retry);
        int status=state.ready() ? 200 : ("queued".equals(state.state()) || "running".equals(state.state())) ? 202 : 200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(PreviewResponse.from(mediaId,state));
    }
    @GetMapping("/pages/{page}")
    ResponseEntity<co.kr.satu.imagegate.media.controller.dto.MediaPageResponse> page(
            @PathVariable String mediaId,@PathVariable int page) {
        var view=previews.page(mediaId,page);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(co.kr.satu.imagegate.media.controller.dto.MediaPageResponse.from(view.media(),view.preview()));
    }
    @GetMapping("/status")
    ResponseEntity<PreviewResponse> status(@PathVariable String mediaId,@RequestParam(defaultValue="1") int page) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(PreviewResponse.from(mediaId,previews.status(mediaId,page)));
    }
    @RequestMapping(value={"/sources/{sourceId}/content","/sources/{sourceId}/thumbnail"},method={RequestMethod.GET,RequestMethod.HEAD})
    void content(@PathVariable String mediaId,@PathVariable String sourceId,
            @RequestParam(defaultValue="1") int page,HttpServletRequest request,HttpServletResponse response) throws IOException {
        boolean thumbnail=request.getRequestURI().endsWith("/thumbnail");
        previews.content(mediaId,page,sourceId,thumbnail,file->{
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
                long length=Files.size(file);
                response.setStatus(200);response.setContentType(MediaType.IMAGE_PNG_VALUE);
                response.setHeader("Cache-Control","no-store");
                response.setHeader("Content-Disposition",thumbnail?"inline; filename=\"thumbnail.png\"":"inline; filename=\"preview.png\"");
                response.setContentLengthLong(length);
                if("HEAD".equals(request.getMethod()))return;
                var output=response.getOutputStream();byte[] buffer=new byte[32768];long remaining=length;
                while(remaining>0){int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                    if(n<0)throw new java.io.EOFException("Incomplete preview");
                    output.write(buffer,0,n);remaining-=n;
                }
            }
        });
    }
}
