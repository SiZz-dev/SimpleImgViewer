package co.kr.satu.imagegate.viewer.domain;

import java.util.List;

public record ViewerOpenOptions(List<String> mediaIds,String selectedMediaId,Integer page,
        Double timeSeconds,String theme,boolean leftPanelOpen) {
    public ViewerOpenOptions { mediaIds=List.copyOf(mediaIds); }
}
