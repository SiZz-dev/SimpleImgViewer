package co.kr.satu.imagegate.viewer.service;

import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.viewer.domain.*;
import co.kr.satu.imagegate.viewer.exception.ViewerException;
import co.kr.satu.imagegate.viewer.repository.ViewerSessionStore;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public final class ViewerSessionService {
    private final MediaService media;
    private final MediaPreviewService previews;
    private final CurrentActor actors;
    private final ViewerSessionStore sessions;
    private final co.kr.satu.imagegate.playback.service.PlaybackService playback;
    public ViewerSessionService(MediaService media,MediaPreviewService previews,CurrentActor actors,
            ViewerSessionStore sessions,co.kr.satu.imagegate.playback.service.PlaybackService playback) {
        this.media=media;this.previews=previews;this.actors=actors;this.sessions=sessions;this.playback=playback;
    }

    public ViewerSessionView create(ViewerOpenOptions options) {
        var records=authorized(options.mediaIds()); // all-or-nothing, before any job or session
        var selected=records.stream().filter(m->m.mediaId().equals(options.selectedMediaId())).findFirst()
                .orElseThrow(()->new ViewerException(ViewerException.Code.INVALID_VIEWER_POSITION));
        try { MediaPositionValidator.validateType(options.page(),options.timeSeconds(),selected.mediaType()); }
        catch(IllegalArgumentException invalid) { throw new ViewerException(ViewerException.Code.INVALID_VIEWER_POSITION); }
        var normalized=new ViewerOpenOptions(options.mediaIds(),options.selectedMediaId(),
                selected.mediaType()==MediaType.IMAGE ? options.page()==null ? 1 : options.page() : null,
                selected.mediaType()!=MediaType.IMAGE ? options.timeSeconds()==null ? 0.0 : options.timeSeconds() : null,
                options.theme(),options.leftPanelOpen());
        var versions=new LinkedHashMap<String,String>();
        for(var record:records) versions.put(record.mediaId(),record.mediaVersion());
        var session=sessions.create(actors.require(),normalized,versions);
        try {
            if(selected.mediaType()==MediaType.IMAGE)
                previews.request(selected.mediaId(),session.page(),false);
            else playback.request(selected.mediaId(),false);
            return view(session);
        } catch(RuntimeException failure) {
            sessions.delete(session.id(),actors.require());
            throw failure;
        }
    }
    public ViewerSessionView get(String id) { return view(sessions.require(id,actors.require())); }
    public void delete(String id) { sessions.delete(id,actors.require()); }

    private List<MediaRecord> authorized(List<String> ids) {
        var records=new ArrayList<MediaRecord>();
        for(String id:ids) records.add(media.require(id,MediaAction.VIEW));
        return List.copyOf(records);
    }
    private ViewerSessionView view(ViewerSession session) {
        var records=authorized(session.mediaIds());
        // Complete authorization and version checks before constructing any response.
        for(var record:records) if(!record.mediaVersion().equals(session.versions().get(record.mediaId())))
            throw new ViewerException(ViewerException.Code.SESSION_MEDIA_CHANGED);
        var views=new ArrayList<ViewerSessionView.MediaView>();
        String position="pending";
        for(var record:records) {
            boolean selected=record.mediaId().equals(session.selectedMediaId());
            int page=selected && session.page()!=null ? session.page() : 1;
            var preview=previews.status(record.mediaId(),page);
            var replay=record.mediaType()==MediaType.IMAGE?null:playback.status(record.mediaId());
            views.add(new ViewerSessionView.MediaView(record,media.permissions(record),preview,replay));
            if(selected&&record.mediaType()!=MediaType.IMAGE){
                var info=replay;
                if(info.ready()){if(session.timeSeconds()==null||!Double.isFinite(session.timeSeconds())
                        ||session.timeSeconds()<0||session.timeSeconds()>=info.durationSeconds())
                    throw new ViewerException(ViewerException.Code.INVALID_VIEWER_POSITION);position="validated";}
                else position="failed".equals(info.state())?"failed":"pending";
            }
            if(selected && record.mediaType()==MediaType.IMAGE)
                position=preview.ready() ? "validated" : "failed".equals(preview.state()) ? "failed" : "pending";
        }
        return new ViewerSessionView(session,List.copyOf(views),position);
    }
}
