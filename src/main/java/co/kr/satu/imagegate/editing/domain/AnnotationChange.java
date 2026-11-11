package co.kr.satu.imagegate.editing.domain;

public record AnnotationChange(String mediaVersion,String coordinateSpaceId,int expectedRevision,
        AnnotationItem.Type type,double x,double y,double width,double height,String text,String stampId) {}
