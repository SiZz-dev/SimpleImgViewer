package co.kr.satu.imagegate.conversion.domain;

/** Original encoded raster and EXIF orientation. Coordinates use pixel edges, not pixel indices. */
public record ImageGeometry(int encodedWidth,int encodedHeight,int orientation) {
    public ImageGeometry {
        if(encodedWidth<1 || encodedHeight<1 || (long)encodedWidth*encodedHeight>40000000
                || orientation<1 || orientation>8) throw new IllegalArgumentException("Invalid image geometry");
    }
    public String coordinateSpaceId(String version,int page) { return "original:"+version+":"+page+":upright-v1"; }
    public int coordinateWidth() { return orientation>=5 ? encodedHeight : encodedWidth; }
    public int coordinateHeight() { return orientation>=5 ? encodedWidth : encodedHeight; }
}
