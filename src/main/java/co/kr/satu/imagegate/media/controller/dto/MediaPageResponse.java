package co.kr.satu.imagegate.media.controller.dto;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.conversion.util.ImageOrientation;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import java.awt.geom.AffineTransform;

public record MediaPageResponse(String mediaId,String mediaVersion,int page,String state,String errorCode,
        String coordinateSpaceId,Integer displayWidth,Integer displayHeight,boolean originalCoordinatesAvailable,
        boolean editingReady,PreviewResponse.Source source,Integer coordinateWidth,Integer coordinateHeight,
        Integer encodedWidth,Integer encodedHeight,Integer exifOrientation,
        Matrix previewToOriginal,Matrix originalToEncoded,PreviewResponse.Source thumbnail) {
    /** x'=a*x+c*y+e, y'=b*x+d*y+f; coordinates refer to pixel edges. */
    public record Matrix(double a,double b,double c,double d,double e,double f) {
        static Matrix from(AffineTransform transform) {
            double[] values=new double[6];transform.getMatrix(values);
            return new Matrix(values[0],values[1],values[2],values[3],values[4],values[5]);
        }
    }
    public static MediaPageResponse from(MediaRecord media,PreviewStatus status) {
        var source=PreviewResponse.from(media.mediaId(),status).source();
        var geometry=status.ready() ? status.geometry() : null;
        String space=!status.ready() ? null : geometry==null ? "preview:"+status.sourceId()
                : geometry.coordinateSpaceId(media.mediaVersion(),status.page());
        Integer coordinateWidth=status.ready() ? Integer.valueOf(status.width()) : null;
        Integer coordinateHeight=status.ready() ? Integer.valueOf(status.height()) : null;
        Matrix toOriginal=null,toEncoded=null;
        if(geometry!=null) {
            coordinateWidth=geometry.coordinateWidth();coordinateHeight=geometry.coordinateHeight();
            toOriginal=new Matrix((double)geometry.coordinateWidth()/status.width(),0,0,
                    (double)geometry.coordinateHeight()/status.height(),0,0);
            try { toEncoded=Matrix.from(ImageOrientation.encodedToOriginal(geometry).createInverse()); }
            catch(java.awt.geom.NoninvertibleTransformException impossible) { throw new IllegalStateException(impossible); }
        }
        return new MediaPageResponse(media.mediaId(),media.mediaVersion(),status.page(),status.state(),status.errorCode(),
                space,status.ready() ? status.width() : null,status.ready() ? status.height() : null,
                geometry!=null,geometry!=null,source,
                coordinateWidth,coordinateHeight,
                geometry==null ? null : geometry.encodedWidth(),geometry==null ? null : geometry.encodedHeight(),
                geometry==null ? null : geometry.orientation(),toOriginal,toEncoded,PreviewResponse.from(media.mediaId(),status).thumbnail());
    }
}
