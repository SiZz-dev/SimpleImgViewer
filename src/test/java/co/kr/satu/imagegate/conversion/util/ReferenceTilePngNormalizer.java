package co.kr.satu.imagegate.conversion.util;

/** Test-only pre-optimization copy pipeline. Never packaged or selectable through application settings. */
public final class ReferenceTilePngNormalizer {
    private ReferenceTilePngNormalizer(){}
    public static byte[] normalize(byte[] input,int width,int height,TileTiming timing)throws java.io.IOException{
        return PngCodecProfileTests.encode(input,width,height,PngCodecProfileTests.Candidate.REFERENCE_COPY);
    }
}
