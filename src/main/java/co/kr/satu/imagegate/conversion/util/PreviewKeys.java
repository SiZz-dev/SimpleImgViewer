package co.kr.satu.imagegate.conversion.util;

import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class PreviewKeys {
    private PreviewKeys() {}
    public static String key(MediaRecord media, ViewerPrincipal actor, int page,
            Preview profile, String engine) {
        // Length-prefixed values prevent ambiguous concatenation; exact tenant/user case matters.
        var values = new String[]{"preview-png-orientation-v3-thumbnail",actor.tenantId(),actor.userId(),media.mediaId(),
                media.mediaVersion(),media.sha256(),Integer.toString(page),
                Integer.toString(profile.maxWidth()),Integer.toString(profile.maxHeight()),
                Long.toString(profile.maxSourcePixels()),profile.cacheVersion(),engine,
                Boolean.toString(profile.thumbnailEnabled()),Integer.toString(profile.thumbnailMaxEdge()),
                Runtime.version().toString()};
        var text=new StringBuilder();
        for(var value:values) text.append(value.length()).append(':').append(value);
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }
    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
