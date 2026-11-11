package co.kr.satu.imagegate.playback.domain;

import java.nio.file.Path;
public record PlaybackArtifact(Path file,double durationSeconds,String mimeType,int width,int height,String videoCodec,String audioCodec) {}
