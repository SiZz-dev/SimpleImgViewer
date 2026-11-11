package co.kr.satu.imagegate.viewer.domain;

import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Owner and version pins are private state, never serialized directly to the browser. */
public record ViewerSession(String id,ViewerPrincipal owner,List<String> mediaIds,Map<String,String> versions,
        String selectedMediaId,Integer page,Double timeSeconds,String theme,boolean leftPanelOpen,
        Instant createdAt,Instant expiresAt) {
    public ViewerSession { mediaIds=List.copyOf(mediaIds);versions=Map.copyOf(versions); }
}
