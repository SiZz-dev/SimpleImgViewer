package co.kr.satu.imagegate.media.controller.dto;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import java.util.*;

public record MediaResponse(String mediaId,String mediaVersion,String fileName,String mediaType,
        String originalMimeType,long sizeBytes,String status,Map<String,Boolean> permissions,
        List<PreviewResponse.Source> sources,String statusUrl,PreviewResponse preview,co.kr.satu.imagegate.playback.controller.dto.PlaybackResponse playback) {
    public static MediaResponse from(MediaRecord media,Set<MediaAction> actions,PreviewStatus state) {
        return from(media,actions,state,null);
    }
    public static MediaResponse from(MediaRecord media,Set<MediaAction> actions,PreviewStatus state,
            co.kr.satu.imagegate.playback.domain.PlaybackInfo playback) {
        var preview=PreviewResponse.from(media.mediaId(),state);
        String status=state.ready() ? "ready" : "failed".equals(state.state()) ? "failed" : "processing";
        if(playback!=null)status=playback.ready()?"ready":"failed".equals(playback.state())?"failed":"disabled".equals(playback.state())?"disabled":"processing";
        return new MediaResponse(media.mediaId(),media.mediaVersion(),media.fileName(),
                media.mediaType().name().toLowerCase(Locale.ROOT),media.mimeType(),media.sizeBytes(),
                status,Map.of("view",actions.contains(MediaAction.VIEW),
                        "annotate",actions.contains(MediaAction.ANNOTATE),
                        "stamp",actions.contains(MediaAction.STAMP),
                        "cropAndSave",actions.contains(MediaAction.CROP_AND_SAVE),
                        "exportEdited",actions.contains(MediaAction.EXPORT_EDITED),
                        "downloadOriginal",actions.contains(MediaAction.DOWNLOAD_ORIGINAL),
                        "print",actions.contains(MediaAction.PRINT)),
                preview.source()==null ? List.of() : preview.thumbnail()==null?List.of(preview.source()):List.of(preview.source(),preview.thumbnail()),
                playback==null?"/api/v1/media/"+media.mediaId()+"/status?page="+state.page():"/api/v1/media/"+media.mediaId()+"/playback",playback==null?preview:null,
                playback==null?null:co.kr.satu.imagegate.playback.controller.dto.PlaybackResponse.from(media.mediaId(),media.mediaVersion(),playback));
    }
}
