package co.kr.satu.imagegate.media.controller.dto;
import co.kr.satu.imagegate.media.domain.MediaTileView;
/** Explicit public allowlist. Never serialize an internal cache manifest/job record. */
public record TilePyramidResponse(String state,int page,String mediaVersion,String errorCode,Pyramid pyramid) {
    public record Transform(int a,int b,int c,int d,int e,int f){}
    public record Pyramid(int schemaVersion,String tileSetId,String mimeType,int rasterWidth,int rasterHeight,
            int tileSize,int tileOverlap,int minLevel,int maxLevel,String coordinateSpaceId,Transform rasterToOriginal,String tileUrlTemplate){}
    public static TilePyramidResponse from(MediaTileView view){
        var state=view.tiles().status();var m=view.tiles().manifest();Pyramid pyramid=null;
        if(m!=null){var layout=m.layout();String id=org.springframework.web.util.UriUtils.encodePathSegment(view.media().mediaId(),java.nio.charset.StandardCharsets.UTF_8);
            pyramid=new Pyramid(1,m.generation(),"image/png",layout.width(),layout.height(),layout.tileSize(),layout.overlap(),0,layout.maxLevel(),
                    "original:"+view.media().mediaVersion()+":"+state.page()+":upright-v1",new Transform(1,0,0,1,0,0),
                    "/api/v1/media/"+id+"/pages/"+state.page()+"/pyramids/"+m.generation()+"/tiles/{level}/{column}/{row}");
        }
        return new TilePyramidResponse(state.state(),state.page(),view.media().mediaVersion(),state.error(),pyramid);
    }
}
