package co.kr.satu.imagegate.viewer.controller.dto;

import co.kr.satu.imagegate.media.controller.dto.MediaResponse;
import co.kr.satu.imagegate.viewer.domain.ViewerSessionView;
import java.time.Instant;
import java.util.List;

public record ViewerSessionResponse(String sessionId,Instant createdAt,Instant expiresAt,
        String selectedMediaId,Integer initialPage,Double initialTimeSeconds,String positionState,
        String theme,boolean leftPanelOpen,List<MediaResponse> media) {
    public static ViewerSessionResponse from(ViewerSessionView view) {
        var s=view.session();
        return new ViewerSessionResponse(s.id(),s.createdAt(),s.expiresAt(),s.selectedMediaId(),s.page(),s.timeSeconds(),
                view.positionState(),s.theme(),s.leftPanelOpen(),view.media().stream()
                .map(m->MediaResponse.from(m.record(),m.permissions(),m.preview(),m.playback())).toList());
    }
}
