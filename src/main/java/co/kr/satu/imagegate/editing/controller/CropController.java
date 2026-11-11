package co.kr.satu.imagegate.editing.controller;

import co.kr.satu.imagegate.editing.domain.CropRequest;
import co.kr.satu.imagegate.editing.service.CropService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media/{mediaId}/pages/{page}/crop")
public final class CropController {
    public record Request(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String mediaVersion,
            @NotBlank @Size(max=160) String coordinateSpaceId,
            @NotNull Double x,@NotNull Double y,@NotNull Double width,@NotNull Double height) {
        CropRequest domain(){return new CropRequest(mediaVersion,coordinateSpaceId,x,y,width,height);}
    }
    private final CropService service;
    public CropController(CropService service){this.service=service;}
    @PostMapping
    void download(@PathVariable String mediaId,@PathVariable int page,@Valid @RequestBody Request request,
            HttpServletResponse response) throws IOException {
        service.download(mediaId,page,request.domain(),download->{
            response.setContentType("image/png");response.setHeader("Cache-Control","no-store");
            response.setHeader("Content-Disposition","attachment; filename=\"imagegate-crop-p"+page+".png\"");
            response.setHeader("X-Image-Width",""+download.width());response.setHeader("X-Image-Height",""+download.height());
            response.setContentLengthLong(download.length());
            download.input().transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        });
    }
}
