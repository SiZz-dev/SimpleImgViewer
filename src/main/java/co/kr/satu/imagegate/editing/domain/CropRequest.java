package co.kr.satu.imagegate.editing.domain;

public record CropRequest(String mediaVersion,String coordinateSpaceId,double x,double y,double width,double height) {}
