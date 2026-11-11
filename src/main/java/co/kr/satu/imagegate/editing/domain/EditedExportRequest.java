package co.kr.satu.imagegate.editing.domain;

public record EditedExportRequest(CropRequest region,int expectedRevision,boolean includeAnnotations,int brightness,int rotation) {}
