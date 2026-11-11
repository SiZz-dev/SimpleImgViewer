package co.kr.satu.imagegate.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MediaServiceTests {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void disabledMediaTypeIsRejectedBeforeStorage() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("tenant","alice"),"unused",List.of()));
        var store=mock(MediaStore.class);
        var service=new MediaService(new CurrentActor(),store,(a,m,action)->true,mock(FeaturePolicy.class));
        byte[] png={(byte)137,80,78,71,13,10,26,10};
        assertThatThrownBy(()->service.register("file.png",new org.springframework.core.io.ByteArrayResource(png)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(store);
    }

    @Test void reevaluatesAuthoritativePermissionAfterRevocation() throws Exception {
        var actor=new ViewerPrincipal("tenant","alice");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor,"unused",List.of()));
        var store=mock(MediaStore.class);
        var record=new MediaRecord("id","tenant","alice","version","file.png",MediaType.IMAGE,
                "image/png",10,"hash","original.png",Instant.now());
        when(store.find("tenant","id")).thenReturn(Optional.of(record));
        var features=mock(FeaturePolicy.class);
        when(features.isEnabled(any())).thenReturn(true);
        var allowed=new AtomicBoolean(true);
        var service=new MediaService(new CurrentActor(),store,(a,m,action)->allowed.get(),features);
        assertThat(service.require("id",MediaAction.VIEW)).isSameAs(record);
        allowed.set(false);
        assertThatThrownBy(()->service.require("id",MediaAction.VIEW))
                .isInstanceOfSatisfying(MediaException.class,e->
                        assertThat(e.code()).isEqualTo(MediaException.Code.MEDIA_NOT_FOUND));
        verify(store,times(2)).find("tenant","id");
    }
}
