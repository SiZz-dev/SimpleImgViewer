package co.kr.satu.imagegate.conversion.domain;

/** Contains no private filesystem paths or process output. Pages are one-based. */
public record PreviewStatus(String sourceId, int page, String state, String errorCode,
        int width, int height, ImageGeometry geometry,Thumbnail thumbnail) {
    public record Thumbnail(int width,int height) {}
    public PreviewStatus(String sourceId,int page,String state,String errorCode,int width,int height,ImageGeometry geometry){
        this(sourceId,page,state,errorCode,width,height,geometry,null);
    }
    public PreviewStatus(String sourceId,int page,String state,String errorCode,int width,int height) {
        this(sourceId,page,state,errorCode,width,height,null);
    }
    public boolean ready() { return "ready".equals(state); }
    public static PreviewStatus empty(int page, String state, String code) {
        return new PreviewStatus(null,page,state,code,0,0);
    }
}
