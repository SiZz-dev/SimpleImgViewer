package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImagePreviewRendererTests {
    @TempDir Path directory;
    @Test void detectsOriginalModificationEvenWhenFileSizeMatchesCatalog() throws Exception {
        var store=new LocalMediaStore(directory.resolve("originals"),100000);
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",out);
        var media=store.register("tenant","alice","image.png",new ByteArrayInputStream(out.toByteArray()));
        Path original=store.resolveOriginal(media);
        byte[] bytes=Files.readAllBytes(original);bytes[bytes.length-1]^=1;Files.write(original,bytes);
        var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenReturn(true);
        var renderer=new ImagePreviewRenderer(store,(a,m,action)->true,features,
                new Preview(true,1,1,32,32,10000,"v1"),null);
        assertThatThrownBy(()->renderer.render(media,new ViewerPrincipal("tenant","alice"),1,directory.resolve("work")))
                .isInstanceOf(IOException.class).hasMessage("Original changed");
        assertThat(directory.resolve("work")).doesNotExist();
    }

    @Test void revokedQueuedJobIsRejectedBeforeDecodeOrNativeExecution() throws Exception {
        var store=new LocalMediaStore(directory.resolve("originals"),100000);
        var out=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",out);
        var media=store.register("tenant","alice","image.png",new ByteArrayInputStream(out.toByteArray()));
        var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenReturn(true);
        var converter=mock(ImageConverter.class);
        var renderer=new ImagePreviewRenderer(store,(a,m,action)->false,features,
                new Preview(true,1,1,32,32,10000,"v1"),converter);
        assertThatThrownBy(()->renderer.render(media,new ViewerPrincipal("tenant","alice"),1,directory.resolve("work")))
                .isInstanceOf(IOException.class);
        verifyNoInteractions(converter);
        assertThat(directory.resolve("work")).doesNotExist();
    }
}
