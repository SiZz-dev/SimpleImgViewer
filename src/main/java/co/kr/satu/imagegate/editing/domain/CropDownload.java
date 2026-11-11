package co.kr.satu.imagegate.editing.domain;

import java.io.InputStream;

/** Stream is valid only for the duration of the service's download callback. */
public record CropDownload(InputStream input,long length,int width,int height) {}
