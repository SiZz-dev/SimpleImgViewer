package co.kr.satu.imagegate.editing.controller;

import co.kr.satu.imagegate.editing.domain.StampAsset;
import co.kr.satu.imagegate.editing.service.StampService;
import java.util.List;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public final class StampController {
    public record Stamp(String stampId,String fileName,int width,int height,int previewWidth,int previewHeight,String contentUrl) {
        static Stamp from(StampAsset asset){return new Stamp(asset.id(),asset.fileName(),asset.width(),asset.height(),
                asset.previewWidth(),asset.previewHeight(),"/api/v1/stamps/"+asset.id()+"/content");}
    }
    private final StampService service;public StampController(StampService service){this.service=service;}
    @GetMapping("/api/v1/stamps") ResponseEntity<List<Stamp>> list(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list().stream().map(Stamp::from).toList());
    }
    @PostMapping(value="/api/v1/media/{mediaId}/stamps",consumes="multipart/form-data")
    ResponseEntity<Stamp> upload(@PathVariable String mediaId,@RequestPart("file") MultipartFile file){
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
                .body(Stamp.from(service.upload(mediaId,file.getOriginalFilename(),file::getInputStream)));
    }
    @GetMapping("/api/v1/stamps/{stampId}/content") ResponseEntity<Resource> content(@PathVariable String stampId){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG)
                .header("Content-Disposition","inline; filename=\"stamp.png\"")
                .body(new FileSystemResource(service.ownedContent(stampId)));
    }
}
