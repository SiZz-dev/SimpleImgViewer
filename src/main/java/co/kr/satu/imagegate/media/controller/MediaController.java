package co.kr.satu.imagegate.media.controller;

import co.kr.satu.imagegate.media.controller.dto.MediaResponse;
import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.service.MediaService;
import co.kr.satu.imagegate.media.service.MediaPreviewService;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/media")
public class MediaController {
    private final MediaService media;
    private final MediaPreviewService previews;
    private final co.kr.satu.imagegate.playback.service.PlaybackService playback;
    public MediaController(MediaService media,MediaPreviewService previews,co.kr.satu.imagegate.playback.service.PlaybackService playback) { this.media=media;this.previews=previews;this.playback=playback; }

    @PostMapping(consumes="multipart/form-data")
    ResponseEntity<MediaResponse> register(@RequestPart("file") MultipartFile file) {
        var record=media.register(file.getOriginalFilename(),file::getInputStream);
        var ready=record.mediaType()!=co.kr.satu.imagegate.media.domain.MediaType.IMAGE?playback.afterRegistration(record):null;
        return ResponseEntity.created(URI.create("/api/v1/media/"+record.mediaId()))
                .cacheControl(CacheControl.noStore())
                .body(MediaResponse.from(record,media.permissions(record),previews.afterRegistration(record),ready));
    }

    @GetMapping("/{mediaId}")
    ResponseEntity<MediaResponse> get(@PathVariable String mediaId) {
        var record=media.require(mediaId,MediaAction.VIEW);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(MediaResponse.from(record,media.permissions(record),previews.status(mediaId,1),record.mediaType()==co.kr.satu.imagegate.media.domain.MediaType.IMAGE?null:playback.status(mediaId)));
    }
}
