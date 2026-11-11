package co.kr.satu.imagegate.playback.controller.dto;

import co.kr.satu.imagegate.playback.domain.PlaybackInfo;

public record PlaybackResponse(String mediaVersion,String sourceId,String state,String errorCode,double durationSeconds,String mimeType,
            int width,int height,String videoCodec,String audioCodec,long sizeBytes,String url,boolean seekable) {
        public static PlaybackResponse from(String id,String version,PlaybackInfo info){return new PlaybackResponse(version,info.sourceId(),info.state(),info.errorCode(),
            info.durationSeconds(),info.mimeType(),info.width(),info.height(),info.videoCodec(),info.audioCodec(),info.sizeBytes(),
            info.ready()?"/api/v1/media/"+id+"/playback/sources/"+info.sourceId()+"/content":null,info.ready());}
    }
