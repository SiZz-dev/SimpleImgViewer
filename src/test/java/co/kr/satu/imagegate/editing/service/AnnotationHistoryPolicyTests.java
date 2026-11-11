package co.kr.satu.imagegate.editing.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.editing.repository.*;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.time.*;
import org.junit.jupiter.api.Test;

class AnnotationHistoryPolicyTests {
    @Test void disabledHistoryCannotMutateEvenWithFileViewAndPreparedCoordinates(){
        var media=mock(MediaService.class);var previews=mock(MediaPreviewService.class);var store=mock(AnnotationStore.class);
        var history=mock(AnnotationHistoryStore.class);var properties=mock(ImageGateProperties.class);
        when(properties.editing()).thenReturn(new ImageGateProperties.Editing(100,2000,100,5242880,4194304,
                new ImageGateProperties.EditHistory(false,20,128,8,Duration.ofMinutes(30))));
        var record=new MediaRecord("id","tenant","alice","version","file.png",MediaType.IMAGE,"image/png",100,"sha","file.png",Instant.EPOCH);
        var geometry=new ImageGeometry(100,100,1);when(media.require("id",MediaAction.VIEW)).thenReturn(record);
        when(previews.page("id",1)).thenReturn(new MediaPage(record,new PreviewStatus("source",1,"ready",null,100,100,geometry)));
        var service=new AnnotationService(media,previews,store,mock(StampService.class),mock(StampStore.class),mock(CurrentActor.class),mock(FeaturePolicy.class),properties,history);
        assertThatThrownBy(()->service.history("id",1,"version",geometry.coordinateSpaceId("version",1),0,false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(store,history);
    }
}
