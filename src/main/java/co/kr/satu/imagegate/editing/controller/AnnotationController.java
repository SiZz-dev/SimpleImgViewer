package co.kr.satu.imagegate.editing.controller;

import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.service.AnnotationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media/{mediaId}/pages/{page}/annotations")
public final class AnnotationController {
    public record Change(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String mediaVersion,
            @NotBlank @Size(max=160) String coordinateSpaceId,@NotNull @Min(0) Integer expectedRevision,
            @NotNull @Pattern(regexp="note|stamp") String type,@NotNull Double x,@NotNull Double y,@NotNull Double width,@NotNull Double height,
            @Size(max=2000) String text,@Pattern(regexp="[0-9a-f-]{36}") String stampId) {
        AnnotationChange domain(){return new AnnotationChange(mediaVersion,coordinateSpaceId,expectedRevision,
                AnnotationItem.Type.valueOf(type.toUpperCase(Locale.ROOT)),x,y,width,height,text,stampId);}
    }
    public record Delete(@NotBlank String mediaVersion,@NotBlank @Size(max=160) String coordinateSpaceId,
            @NotNull @Min(0) Integer expectedRevision) {}
    public record Item(String id,String type,double x,double y,double width,double height,String text,
            String stampId,String stampUrl) {}
    public record Document(String mediaVersion,int page,String coordinateSpaceId,int revision,List<Item> items,AnnotationService.History history) {}
    private final AnnotationService service;
    public AnnotationController(AnnotationService service){this.service=service;}
    private ResponseEntity<Document> response(String id,AnnotationDocument doc,int status) {
        var items=doc.items().stream().map(i->new Item(i.id(),i.type().name().toLowerCase(Locale.ROOT),
                i.x(),i.y(),i.width(),i.height(),i.text(),i.stampId(),i.stampId()==null?null:
                "/api/v1/media/"+id+"/pages/"+doc.page()+"/annotations/"+i.id()+"/stamp/content")).toList();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .eTag("\""+doc.revision()+"\"").body(new Document(doc.mediaVersion(),doc.page(),doc.coordinateSpaceId(),doc.revision(),items,service.historyStatus(id,doc)));
    }
    @GetMapping ResponseEntity<Document> get(@PathVariable String mediaId,@PathVariable int page) {
        return response(mediaId,service.get(mediaId,page),200);
    }
    @PostMapping ResponseEntity<Document> create(@PathVariable String mediaId,@PathVariable int page,@Valid @RequestBody Change change) {
        return response(mediaId,service.create(mediaId,page,change.domain()),201);
    }
    @PutMapping("/{annotationId}") ResponseEntity<Document> replace(@PathVariable String mediaId,@PathVariable int page,
            @PathVariable String annotationId,@Valid @RequestBody Change change) {
        return response(mediaId,service.replace(mediaId,page,annotationId,change.domain()),200);
    }
    @DeleteMapping("/{annotationId}") ResponseEntity<Document> delete(@PathVariable String mediaId,@PathVariable int page,
            @PathVariable String annotationId,@Valid @RequestBody Delete change) {
        return response(mediaId,service.delete(mediaId,page,annotationId,change.mediaVersion(),change.coordinateSpaceId(),change.expectedRevision()),200);
    }
    @PostMapping("/history/{direction}") ResponseEntity<Document> history(@PathVariable String mediaId,@PathVariable int page,
            @PathVariable @Pattern(regexp="undo|redo") String direction,@Valid @RequestBody Delete request){
        boolean redo=switch(direction){case "undo"->false;case "redo"->true;default->throw new co.kr.satu.imagegate.editing.exception.EditException(co.kr.satu.imagegate.editing.exception.EditException.Code.INVALID_EDIT);};
        return response(mediaId,service.history(mediaId,page,request.mediaVersion(),request.coordinateSpaceId(),request.expectedRevision(),redo),200);
    }
    @GetMapping("/{annotationId}/stamp/content") ResponseEntity<Resource> content(@PathVariable String mediaId,@PathVariable int page,
            @PathVariable String annotationId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .header("Content-Disposition","inline; filename=\"stamp.png\"")
                .body(new FileSystemResource(service.stampContent(mediaId,page,annotationId)));
    }
}
