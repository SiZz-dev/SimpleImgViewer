package co.kr.satu.imagegate.conversion.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreviewPublicationAuthorizationTests {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(strings={"permission","version","feature"})
    void finalPublicationRechecksCurrentAccessAfterPrivateArtifactsAreStaged(String change)throws Exception {
        var actualStore=new LocalMediaStore(root.resolve("originals"),100000);
        var bytes=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var media=actualStore.register("tenant","alice","synthetic.png",new ByteArrayInputStream(bytes.toByteArray()));
        Path original=actualStore.resolveOriginal(media),cacheRoot=root.resolve("cache");
        var current=new AtomicReference<>(media);var allowed=new AtomicBoolean(true);var enabled=new AtomicBoolean(true);
        var store=mock(MediaStore.class);
        when(store.find("tenant",media.mediaId())).thenAnswer(c->Optional.of(current.get()));
        when(store.resolveOriginal(media)).thenReturn(original);
        var features=mock(FeaturePolicy.class);when(features.isEnabled(any())).thenAnswer(c->enabled.get());
        var profile=new Preview(true,1,2,32,32,100000,"publication-v1");
        var actor=new ViewerPrincipal("tenant","alice");
        var renderer=spy(new ImagePreviewRenderer(store,(a,m,action)->allowed.get(),features,profile,null));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{
            entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Publication gate timed out");
            return call.callRealMethod();
        }).when(renderer).verifyPublication(media,actor,1);
        try(var queue=new PreviewQueue(profile,"engine",new PreviewCache(cacheRoot),renderer)) {
            queue.submit(media,actor,1,false);
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                try(var files=Files.walk(cacheRoot)){assertThat(files.anyMatch(p->p.getFileName().toString().startsWith("result-"))).isTrue();}
                switch(change){
                    case "permission"->allowed.set(false);
                    case "feature"->enabled.set(false);
                    case "version"->current.set(new MediaRecord(media.mediaId(),media.tenantId(),media.ownerId(),UUID.randomUUID().toString(),
                            media.fileName(),media.mediaType(),media.mimeType(),media.sizeBytes(),media.sha256(),media.storedFileName(),media.createdAt()));
                    default->throw new AssertionError(change);
                }
            }finally{release.countDown();}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            var state=queue.status(media,actor,1);
            while(!state.state().equals("failed")&&System.nanoTime()<deadline){Thread.sleep(10);state=queue.status(media,actor,1);}
            assertThat(state.state()).isEqualTo("failed");assertThat(state.errorCode()).isEqualTo("PREVIEW_FAILED");
            assertThat(state.thumbnail()).isNull();
        }finally{release.countDown();}
        try(var files=Files.walk(cacheRoot)){assertThat(files.noneMatch(p->p.getFileName().toString().startsWith("result-"))).isTrue();}
        assertThat(Files.readAllBytes(original)).containsExactly(bytes.toByteArray());
        allowed.set(true);enabled.set(true);current.set(media);
        try(var queue=new PreviewQueue(profile,"engine",new PreviewCache(cacheRoot),
                new ImagePreviewRenderer(store,(a,m,action)->allowed.get(),features,profile,null))) {
            assertThat(queue.submit(media,actor,1,false).state()).isEqualTo("failed");
            queue.submit(media,actor,1,true);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!queue.status(media,actor,1).ready()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(queue.status(media,actor,1).ready()).isTrue();
        }
    }
}
