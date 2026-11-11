package co.kr.satu.imagegate.editing.domain;

import java.util.List;

public record AnnotationDocument(String mediaVersion,int page,String coordinateSpaceId,int revision,
        List<AnnotationItem> items) {
    public AnnotationDocument { items=List.copyOf(items); }
}
