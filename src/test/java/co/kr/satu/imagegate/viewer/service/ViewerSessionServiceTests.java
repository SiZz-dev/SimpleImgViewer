package co.kr.satu.imagegate.viewer.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Viewer;
import co.kr.satu.imagegate.viewer.domain.*;
import co.kr.satu.imagegate.viewer.exception.ViewerException;
import co.kr.satu.imagegate.viewer.repository.ViewerSessionStore;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ViewerSessionServiceTests {
    final MediaService media=mock(MediaService.class);
    final MediaPreviewService previews=mock(MediaPreviewService.class);
    final CurrentActor actors=mock(CurrentActor.class);
    final ViewerPrincipal owner=new ViewerPrincipal("tenant","alice");
    final ViewerSessionStore store=new ViewerSessionStore(new Viewer(Duration.ofMinutes(30),1,1),Clock.systemUTC());
    ViewerOpenOptions options(List<String> ids) {
        return new ViewerOpenOptions(ids,ids.getFirst(),null,null,"dark",true);
    }
    MediaRecord record(String id,String version) {
        return new MediaRecord(id,"tenant","alice",version,"image.png",MediaType.IMAGE,"image/png",10,
                "sha","original.png",Instant.EPOCH);
    }
    ViewerSessionService service() {
        when(actors.require()).thenReturn(owner);
        when(media.permissions(any())).thenReturn(Set.of(MediaAction.VIEW));
        when(previews.status(anyString(),anyInt())).thenReturn(PreviewStatus.empty(1,"disabled","PROCESSING_DISABLED"));
        return new ViewerSessionService(media,previews,actors,store,mock(co.kr.satu.imagegate.playback.service.PlaybackService.class));
    }
    @Test void changedOriginalVersionInvalidatesSessionInsteadOfServingNewBytes() {
        var service=service();
        when(media.require("a",MediaAction.VIEW)).thenReturn(record("a","v1"));
        var view=service.create(options(List.of("a")));
        when(media.require("a",MediaAction.VIEW)).thenReturn(record("a","v2"));
        assertThatThrownBy(()->service.get(view.session().id())).isInstanceOfSatisfying(ViewerException.class,
                e->assertThat(e.code()).isEqualTo(ViewerException.Code.SESSION_MEDIA_CHANGED));
    }
    @Test void unauthorizedSelectionIsRejectedBeforeSessionAllocationOrJobAdmission() {
        var sessions=mock(ViewerSessionStore.class);
        var service=new ViewerSessionService(media,previews,actors,sessions,mock(co.kr.satu.imagegate.playback.service.PlaybackService.class));
        when(media.require("a",MediaAction.VIEW)).thenReturn(record("a","v1"));
        when(media.require("b",MediaAction.VIEW)).thenThrow(new MediaException(MediaException.Code.MEDIA_NOT_FOUND));
        assertThatThrownBy(()->service.create(options(List.of("a","b")))).isInstanceOf(MediaException.class);
        verifyNoInteractions(previews,sessions);
    }
    @Test void failedQueueAdmissionReleasesTheAllocatedSessionSlot() {
        var service=service();
        when(media.require("a",MediaAction.VIEW)).thenReturn(record("a","v1"));
        when(previews.request("a",1,false)).thenThrow(new MediaException(MediaException.Code.PREVIEW_QUEUE_FULL))
                .thenReturn(PreviewStatus.empty(1,"queued",null));
        assertThatThrownBy(()->service.create(options(List.of("a")))).isInstanceOf(MediaException.class);
        assertThat(service.create(options(List.of("a"))).session()).isNotNull();
    }
}
