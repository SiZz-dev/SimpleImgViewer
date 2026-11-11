package co.kr.satu.imagegate.editing.controller;

import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.service.CropService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media/{mediaId}/pages/{page}/export")
public final class EditedExportController {
    public record Request(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String mediaVersion,
            @NotBlank @Size(max=160) String coordinateSpaceId,@NotNull Double x,@NotNull Double y,
            @NotNull Double width,@NotNull Double height,@NotNull @Min(0) Double expectedRevision,
            @NotNull Boolean includeAnnotations,@NotNull @Min(25) @Max(200) Double brightness,
            @NotNull Double rotation) {
        EditedExportRequest domain(){return new EditedExportRequest(
                new CropRequest(mediaVersion,coordinateSpaceId,x,y,width,height),integer(expectedRevision),includeAnnotations,integer(brightness),integer(rotation));}
    }
    private static int integer(double value){
        if(!Double.isFinite(value)||value!=Math.rint(value)||value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)
            throw new co.kr.satu.imagegate.editing.exception.EditException(co.kr.satu.imagegate.editing.exception.EditException.Code.INVALID_EDIT);
        return (int)value;
    }
    private final CropService service;
    public EditedExportController(CropService service){this.service=service;}
    @PostMapping
    void export(@PathVariable String mediaId,@PathVariable int page,@Valid @RequestBody Request request,
            HttpServletResponse response) throws IOException {
        service.export(mediaId,page,request.domain(),download->{
            response.setContentType("image/png");response.setHeader("Cache-Control","no-store");
            response.setHeader("Content-Disposition","attachment; filename=\"imagegate-edited-p"+page+".png\"");
            response.setContentLengthLong(download.length());
            response.setHeader("X-Image-Width",""+download.width());response.setHeader("X-Image-Height",""+download.height());
            download.input().transferTo(response.getOutputStream());response.getOutputStream().flush();
        });
    }
}
