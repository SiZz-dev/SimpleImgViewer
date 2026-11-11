package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.MediaAuthorizationPolicy;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.*;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImagePreviewThumbnailTests {
    @TempDir Path root;
    @Test void configuredBoundsAndDisableSwitchApplyToRasterAndSourceIdentity() throws Exception {
        Path input=root.resolve("input.png");ImageIO.write(new BufferedImage(129,257,BufferedImage.TYPE_INT_ARGB),"png",input.toFile());
        var actor=new ViewerPrincipal("tenant","alice");var media=new MediaRecord("id","tenant","alice","v1","input.png",MediaType.IMAGE,"image/png",Files.size(input),PreviewKeys.sha256(Files.readAllBytes(input)),"input.png",Instant.EPOCH);
        var store=mock(MediaStore.class);when(store.find("tenant","id")).thenReturn(Optional.of(media));when(store.resolveOriginal(media)).thenReturn(input);
        var auth=mock(MediaAuthorizationPolicy.class);when(auth.permits(any(),any(),any())).thenReturn(true);
        var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenReturn(true);
        var keys=new HashSet<String>();
        for(int edge:new int[]{0,64,512}){
            var profile=new Preview(true,1,1,1600,1600,16000000,"v1",PreviewCachePolicy.defaults(),edge!=0,edge==0?256:edge);
            keys.add(PreviewKeys.key(media,actor,1,profile,"jdk"));
            var result=new ImagePreviewRenderer(store,auth,features,profile,null).render(media,actor,1,root.resolve("work-"+edge));
            if(edge==0){assertThat(result.thumbnail()).isNull();assertThat(root.resolve("work-0/thumbnail.png")).doesNotExist();}
            else{var thumb=ImageIO.read(result.thumbnail().toFile());assertThat(thumb.getHeight()).isEqualTo(Math.min(edge,257));assertThat(thumb.getWidth()).isLessThanOrEqualTo(edge);}
        }
        assertThat(keys).hasSize(3);
    }
}
