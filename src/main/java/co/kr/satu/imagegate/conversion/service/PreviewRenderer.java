package co.kr.satu.imagegate.conversion.service;

import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface PreviewRenderer extends AutoCloseable {
    @Override default void close() {}
    Result render(MediaRecord media, ViewerPrincipal actor, int page, Path work) throws IOException;
    /** Trusted renderer admission immediately before ready metadata; production implementations recheck access. */
    default void verifyPublication(MediaRecord media,ViewerPrincipal actor,int page) throws IOException {}
    record Result(Path png,int width,int height,co.kr.satu.imagegate.conversion.domain.ImageGeometry geometry,Path thumbnail,int thumbnailWidth,int thumbnailHeight) {
        public Result(Path png,int width,int height,co.kr.satu.imagegate.conversion.domain.ImageGeometry geometry){this(png,width,height,geometry,null,0,0); }
        public Result(Path png,int width,int height) { this(png,width,height,null); }
    }
}
