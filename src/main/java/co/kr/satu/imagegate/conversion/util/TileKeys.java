package co.kr.satu.imagegate.conversion.util;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Tiles;
import java.nio.charset.StandardCharsets;
public final class TileKeys {
    private TileKeys(){}
    public static String key(MediaRecord media,ViewerPrincipal actor,int page,Tiles policy,String module){
        if(page<1||page>10000)throw new IllegalArgumentException("Invalid tile page");
        String[] values={"tiles-rgba8-o1-v1",actor.tenantId(),actor.userId(),media.tenantId(),media.ownerId(),media.mediaId(),media.mediaVersion(),media.sha256(),
                Integer.toString(page),module,"classic-tiff-rgb8-o1-uncompressed-contiguous/tile256/overlap0",policy.cacheVersion(),Long.toString(policy.maxSourcePixels()),
                Integer.toString(policy.maxTiles()),Integer.toString(policy.maxTileBytes()),Long.toString(policy.maxPageBytes()),Runtime.version().toString()};
        var text=new StringBuilder();for(String value:values)text.append(value.length()).append(':').append(value);
        return PreviewKeys.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }
}
