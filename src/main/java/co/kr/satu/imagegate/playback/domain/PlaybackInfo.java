package co.kr.satu.imagegate.playback.domain;

public record PlaybackInfo(String sourceId,String state,String errorCode,double durationSeconds,
        String mimeType,int width,int height,String videoCodec,String audioCodec,long sizeBytes) {
    public boolean ready(){return "ready".equals(state);}
    public static PlaybackInfo empty(String state,String error){return new PlaybackInfo(null,state,error,0,null,0,0,null,null,0);}
}
