package co.kr.satu.imagegate.conversion.service;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.IOException;
public interface TileRenderer {
    void verifyAccess(MediaRecord media,ViewerPrincipal actor)throws IOException;
    void render(MediaRecord media,ViewerPrincipal actor,int page,String key,TilePyramidPublisher.CommitGate gate)throws IOException;
}
