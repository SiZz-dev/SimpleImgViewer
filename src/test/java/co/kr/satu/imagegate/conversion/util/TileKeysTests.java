package co.kr.satu.imagegate.conversion.util;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Tiles;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class TileKeysTests {
    @Test void isolatesActorPageVersionHashModuleAndPolicyWithoutFilenames(){
        var m=new MediaRecord("media","tenant","alice","v1","secret.tif",MediaType.IMAGE,"image/tiff",100,"a".repeat(64),"private",Instant.EPOCH);
        var a=new ViewerPrincipal("tenant","alice");var p=Tiles.defaults();String key=TileKeys.key(m,a,1,p,"module");
        assertThat(key).matches("[a-f0-9]{64}");var keys=new HashSet<String>();keys.add(key);
        keys.add(TileKeys.key(m,new ViewerPrincipal("tenant","bob"),1,p,"module"));keys.add(TileKeys.key(m,new ViewerPrincipal("other","alice"),1,p,"module"));
        keys.add(TileKeys.key(m,a,2,p,"module"));keys.add(TileKeys.key(m,a,1,p,"other-module"));
        keys.add(TileKeys.key(new MediaRecord(m.mediaId(),m.tenantId(),m.ownerId(),"v2",m.fileName(),m.mediaType(),m.mimeType(),m.sizeBytes(),m.sha256(),m.storedFileName(),m.createdAt()),a,1,p,"module"));
        keys.add(TileKeys.key(new MediaRecord(m.mediaId(),m.tenantId(),m.ownerId(),m.mediaVersion(),m.fileName(),m.mediaType(),m.mimeType(),m.sizeBytes(),"b".repeat(64),m.storedFileName(),m.createdAt()),a,1,p,"module"));
        assertThat(keys).hasSize(7);
        assertThat(TileKeys.key(new MediaRecord(m.mediaId(),m.tenantId(),m.ownerId(),m.mediaVersion(),"renamed.tif",m.mediaType(),m.mimeType(),m.sizeBytes(),m.sha256(),"elsewhere",m.createdAt()),a,1,p,"module")).isEqualTo(key);
        assertThatThrownBy(()->TileKeys.key(m,a,0,p,"module")).isInstanceOf(IllegalArgumentException.class);
    }
}
