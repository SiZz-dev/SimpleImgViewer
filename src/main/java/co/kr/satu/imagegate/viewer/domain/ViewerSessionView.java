package co.kr.satu.imagegate.viewer.domain;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.media.domain.*;
import java.util.List;
import java.util.Set;

public record ViewerSessionView(ViewerSession session,List<MediaView> media,String positionState) {
    public record MediaView(MediaRecord record,Set<MediaAction> permissions,PreviewStatus preview,co.kr.satu.imagegate.playback.domain.PlaybackInfo playback) {
        public MediaView(MediaRecord record,Set<MediaAction> permissions,PreviewStatus preview){this(record,permissions,preview,null);}
    }
}
