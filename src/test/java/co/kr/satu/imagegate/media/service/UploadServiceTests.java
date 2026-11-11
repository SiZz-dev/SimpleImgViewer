package co.kr.satu.imagegate.media.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Upload;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.exception.MediaException;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
class UploadServiceTests {
    @Test void busyAdmissionNeverOpensInputAndFailedInputReleasesItsSlot() throws Exception {
        var actor=new ViewerPrincipal("t","u");var current=mock(CurrentActor.class);when(current.require()).thenReturn(actor);
        var gate=new UploadAdmission(new Upload(100,1,1,0));var store=mock(MediaStore.class);
        var service=new MediaService(current,store,(a,m,p)->true,mock(FeaturePolicy.class),gate);var opened=new AtomicBoolean();
        try(var held=gate.acquire(actor)){
            assertThatThrownBy(()->service.register("x.png",()->{opened.set(true);throw new IOException();}))
                .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.UPLOAD_BUSY));
        }
        assertThat(opened.get()).isFalse();
        assertThatThrownBy(()->service.register("x.png",()->{throw new IOException("private-path");}))
            .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.STORAGE_UNAVAILABLE));
        try(var next=gate.acquire(actor)){}verifyNoInteractions(store);
    }
    @Test void invalidMediaAndDisabledFeatureReleaseInputAndRegistrationSlot() throws Exception {
        var actor=new ViewerPrincipal("t","u");var current=mock(CurrentActor.class);when(current.require()).thenReturn(actor);
        var gate=new UploadAdmission(new Upload(100,1,1,0));var store=mock(MediaStore.class);
        var service=new MediaService(current,store,(a,m,p)->true,mock(FeaturePolicy.class),gate);
        for(byte[] data:new byte[][]{new byte[0],new byte[]{1,2,3},new byte[]{(byte)137,80,78,71,13,10,26,10}}){
            var closed=new AtomicBoolean();var input=new ByteArrayInputStream(data){@Override public void close(){closed.set(true);}};
            assertThatThrownBy(()->service.register("x.png",()->input)).isInstanceOf(RuntimeException.class);
            assertThat(closed.get()).isTrue();try(var next=gate.acquire(actor)){}
        }
        verifyNoInteractions(store);
    }
}
