package co.kr.satu.imagegate.media.controller.dto;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;

public record PreviewResponse(String state,int page,String errorCode,Source source,Source thumbnail) {
    public PreviewResponse(String state,int page,String errorCode,Source source){this(state,page,errorCode,source,null); }
    public record Source(String sourceId,String role,int page,String mimeType,int width,int height,String url) {}
    public static PreviewResponse from(String mediaId,PreviewStatus status) {
        Source source=status.ready() ? new Source(status.sourceId(),"preview",status.page(),"image/png",
                status.width(),status.height(),"/api/v1/media/"+mediaId+"/sources/"+status.sourceId()
                        +"/content?page="+status.page()) : null;
        Source thumbnail=status.ready()&&status.thumbnail()!=null?new Source(status.sourceId(),"thumbnail",status.page(),"image/png",
                status.thumbnail().width(),status.thumbnail().height(),"/api/v1/media/"+mediaId+"/sources/"+status.sourceId()+"/thumbnail?page="+status.page()):null;
        return new PreviewResponse(status.state(),status.page(),status.errorCode(),source,thumbnail);
    }
}
