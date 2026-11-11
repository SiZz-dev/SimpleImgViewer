package co.kr.satu.imagegate.media.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.service.*;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

class MediaPreviewGeometryTests {
    final MediaService media=mock(MediaService.class);final CurrentActor actors=mock(CurrentActor.class);
    final PreviewQueue previews=mock(PreviewQueue.class);final TileQueue tiles=mock(TileQueue.class);
    final ObjectProvider<TileQueue> provider=mock(ObjectProvider.class);final ImageGateProperties properties=mock(ImageGateProperties.class);
    final ViewerPrincipal actor=new ViewerPrincipal("tenant","alice");
    final MediaRecord record=new MediaRecord("id","tenant","alice","v1","image.tif",MediaType.IMAGE,"image/tiff",100,"a".repeat(64),"private",Instant.EPOCH);
    final PreviewStatus preview=new PreviewStatus("b".repeat(64),1,"ready",null,513,257,null,new PreviewStatus.Thumbnail(128,64));
    MediaPreviewService service;
    @BeforeEach void setup(){
        when(media.require("id",MediaAction.VIEW)).thenReturn(record);when(actors.require()).thenReturn(actor);
        when(previews.status(record,actor,1)).thenReturn(preview);when(provider.getIfAvailable()).thenReturn(tiles);
        var policy=mock(ImageGateProperties.Tiles.class);when(policy.enabled()).thenReturn(true);when(properties.tiles()).thenReturn(policy);
        service=new MediaPreviewService(media,actors,previews,provider,properties);
    }
    TileView ready(int page,int width,int height){return new TileView(new TileJobStatus("key",page,"ready",null),
        new TileCacheManifest(1,"png-rgba8-o1-v1","key","generation","a".repeat(64),page,new TilePyramidLayout(width,height,256,0),0,List.of()));}
    @Test void enrichesOnlyCurrentActorPageAndPreservesPreviewIdentity()throws Exception{
        when(tiles.view(record,actor,1)).thenReturn(ready(1,1026,514));var result=service.page("id",1).preview();
        assertThat(result.geometry()).isEqualTo(new ImageGeometry(1026,514,1));assertThat(result.sourceId()).isEqualTo(preview.sourceId());
        assertThat(result.thumbnail()).isEqualTo(preview.thumbnail());assertThat(result.width()).isEqualTo(513);
        verify(tiles).view(record,actor,1);verify(tiles,never()).submit(any(),any(),anyInt(),anyBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"not_requested","queued","running","failed"})
    void noGeometryWithoutReadyProof(String state)throws Exception{
        when(tiles.view(record,actor,1)).thenReturn(new TileView(new TileJobStatus("key",1,state,null),null));
        assertThat(service.page("id",1).preview()).isEqualTo(preview);
    }
    @Test void mismatchedPageAndAspectStayUneditable()throws Exception{
        when(tiles.view(record,actor,1)).thenReturn(ready(2,1026,514),ready(1,1026,900));
        assertThat(service.page("id",1).preview().geometry()).isNull();assertThat(service.page("id",1).preview().geometry()).isNull();
    }
    @Test void disabledTilesAndExistingGeometryNeverConsultTileStorage(){
        when(properties.tiles().enabled()).thenReturn(false);assertThat(service.page("id",1).preview()).isEqualTo(preview);verifyNoInteractions(provider,tiles);
        when(properties.tiles().enabled()).thenReturn(true);var known=new PreviewStatus(preview.sourceId(),1,"ready",null,513,257,new ImageGeometry(513,257,1));
        when(previews.status(record,actor,1)).thenReturn(known);assertThat(service.page("id",1).preview()).isEqualTo(known);verifyNoInteractions(provider,tiles);
    }
    @Test void corruptOrUnavailableProofFailsClosed()throws Exception{
        when(tiles.view(record,actor,1)).thenThrow(new IOException("private path"));
        assertThatThrownBy(()->service.page("id",1)).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.STORAGE_UNAVAILABLE));
    }
    @Test void revocationAfterProofCannotReturnGeometry()throws Exception{
        when(tiles.view(record,actor,1)).thenReturn(ready(1,1026,514));
        when(media.require("id",MediaAction.VIEW)).thenReturn(record).thenThrow(new MediaException(MediaException.Code.MEDIA_NOT_FOUND));
        assertThatThrownBy(()->service.page("id",1)).isInstanceOf(MediaException.class);
    }
    @Test void versionReplacementDuringLookupCannotReturnGeometry()throws Exception{
        when(tiles.view(record,actor,1)).thenReturn(ready(1,1026,514));
        var newer=new MediaRecord(record.mediaId(),record.tenantId(),record.ownerId(),"v2",record.fileName(),record.mediaType(),record.mimeType(),record.sizeBytes(),record.sha256(),record.storedFileName(),record.createdAt());
        when(media.require("id",MediaAction.VIEW)).thenReturn(record,newer);
        assertThatThrownBy(()->service.page("id",1)).isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.MEDIA_NOT_FOUND));
    }
    @Test void nonTiffAndAbsentOptionalQueueCannotInventGeometry(){
        when(provider.getIfAvailable()).thenReturn(null);assertThat(service.page("id",1).preview()).isEqualTo(preview);
        var png=new MediaRecord(record.mediaId(),record.tenantId(),record.ownerId(),"v1","image.png",MediaType.IMAGE,"image/png",100,record.sha256(),"private",Instant.EPOCH);
        when(media.require("id",MediaAction.VIEW)).thenReturn(png);when(previews.status(png,actor,1)).thenReturn(preview);
        clearInvocations(provider);assertThat(service.page("id",1).preview()).isEqualTo(preview);verifyNoInteractions(provider,tiles);
    }
}
