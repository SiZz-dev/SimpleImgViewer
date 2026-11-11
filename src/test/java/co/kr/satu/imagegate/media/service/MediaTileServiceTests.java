package co.kr.satu.imagegate.media.service;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import co.kr.satu.imagegate.conversion.domain.TilePyramidManifest;
import co.kr.satu.imagegate.conversion.exception.*;
import co.kr.satu.imagegate.conversion.service.TileQueue;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
class MediaTileServiceTests {
    final MediaService media=mock(MediaService.class);final CurrentActor actors=mock(CurrentActor.class);final TileQueue queue=mock(TileQueue.class);
    final ImageGateProperties p=mock(ImageGateProperties.class);final ObjectProvider<TileQueue> queues=mock(ObjectProvider.class);
    final ViewerPrincipal actor=new ViewerPrincipal("tenant","alice");
    final MediaRecord record=new MediaRecord("id","tenant","alice","v1","private.tif",MediaType.IMAGE,"image/tiff",100,"a".repeat(64),"private-path",Instant.EPOCH);
    final String generation=UUID.randomUUID().toString();
    MediaTileService service;
    @BeforeEach void init(){when(p.tiles()).thenReturn(new ImageGateProperties.Tiles(true,1,1,16,Duration.ofSeconds(10),1000,10,1000,10000,0,"v1"));when(actors.require()).thenReturn(actor);when(queues.getIfAvailable()).thenReturn(queue);when(media.require("id",MediaAction.VIEW)).thenReturn(record);service=new MediaTileService(media,actors,queues,p);}
    void delivery()throws Exception{doAnswer(call->{TileQueue.ContentWriter callback=call.getArgument(7);callback.write(new ByteArrayInputStream(new byte[]{1}),new TilePyramidManifest.Tile(0,0,0,1,1,1,"a".repeat(64)));return null;})
        .when(queue).content(any(),any(),anyInt(),anyString(),anyInt(),anyInt(),anyInt(),any());}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void rechecksRevocationOrVersionAfterBytesAreVerifiedBeforeHeaders(boolean version)throws Exception{
        delivery();if(version){var newer=new MediaRecord(record.mediaId(),record.tenantId(),record.ownerId(),"v2",record.fileName(),record.mediaType(),record.mimeType(),record.sizeBytes(),record.sha256(),record.storedFileName(),record.createdAt());when(media.require("id",MediaAction.VIEW)).thenReturn(record,newer);}
        else when(media.require("id",MediaAction.VIEW)).thenReturn(record).thenThrow(new MediaException(MediaException.Code.MEDIA_NOT_FOUND));
        var sent=new AtomicBoolean();assertThatThrownBy(()->service.content("id",1,generation,0,0,0,(in,tile)->sent.set(true))).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.MEDIA_NOT_FOUND));assertThat(sent).isFalse();
    }
    @Test void propagatesClientDisconnectRatherThanRewritingACommittedResponse()throws Exception{
        delivery();var failure=new IOException("client disconnected");assertThatThrownBy(()->service.content("id",1,generation,0,0,0,(in,t)->{throw failure;})).isSameAs(failure);
    }
    @ParameterizedTest @ValueSource(strings={"CAPACITY","SPACE","LIMIT","CLOSED"})
    void admissionErrorsExposeOnlyFixedPublicCodes(String code)throws Exception{
        doThrow(new TileAdmissionException(TileAdmissionException.Code.valueOf(code))).when(queue).submit(record,actor,1,false);
        var expected=switch(code){case "CAPACITY"->MediaException.Code.TILE_QUEUE_FULL;case "SPACE"->MediaException.Code.INSUFFICIENT_STORAGE;case "LIMIT"->MediaException.Code.TILE_LIMIT_EXCEEDED;default->MediaException.Code.STORAGE_UNAVAILABLE;};
        assertThatThrownBy(()->service.view("id",1,true,false)).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(expected));
    }
    @Test void unavailableGenerationMapsToNotFoundAndStorageFailureIsRedacted()throws Exception{
        doThrow(new TileUnavailableException()).when(queue).content(any(),any(),anyInt(),anyString(),anyInt(),anyInt(),anyInt(),any());
        assertThatThrownBy(()->service.content("id",1,generation,0,0,0,(in,t)->fail("must not deliver"))).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.MEDIA_NOT_FOUND));
        doThrow(new IOException("private-path")).when(queue).content(any(),any(),anyInt(),anyString(),anyInt(),anyInt(),anyInt(),any());
        assertThatThrownBy(()->service.content("id",1,generation,0,0,0,(in,t)->fail("must not deliver"))).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.getMessage()).isEqualTo("STORAGE_UNAVAILABLE"));
    }
}
