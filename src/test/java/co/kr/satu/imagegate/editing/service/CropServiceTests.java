package co.kr.satu.imagegate.editing.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class CropServiceTests {
    @TempDir Path root;
    MediaService media=mock(MediaService.class);MediaPreviewService previews=mock(MediaPreviewService.class);
    MediaStore store=mock(MediaStore.class);
    MediaRecord record;CropService service;CropRequest request;
    AnnotationService annotations=mock(AnnotationService.class);
    co.kr.satu.imagegate.system.service.FeaturePolicy features=mock(co.kr.satu.imagegate.system.service.FeaturePolicy.class);
    @BeforeEach void prepare() throws Exception {
        String id=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
        Path input=root.resolve("source.png");ImageIO.write(new BufferedImage(100,80,BufferedImage.TYPE_INT_ARGB),"png",input.toFile());
        var hash=HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(input)));
        record=new MediaRecord(id,"tenant","owner",version,"source.png",MediaType.IMAGE,"image/png",Files.size(input),hash,"source.png",Instant.now());
        when(media.require(id,MediaAction.CROP_AND_SAVE)).thenReturn(record);
        when(previews.page(id,1)).thenReturn(new MediaPage(record,new PreviewStatus("a".repeat(64),1,"ready",null,50,40,new ImageGeometry(100,80,1))));
        when(store.resolveOriginal(record)).thenReturn(input);
        var properties=mock(ImageGateProperties.class);
        when(properties.crop()).thenReturn(new ImageGateProperties.Crop(8000,8000,100000,1));
        when(properties.storage()).thenReturn(new ImageGateProperties.Storage(root,Path.of("originals"),Path.of("derived"),Path.of("annotations"),Path.of("stamps")));
        service=new CropService(media,previews,store,properties,
                annotations,mock(co.kr.satu.imagegate.editing.repository.StampStore.class),features);
        request=new CropRequest(version,"original:"+version+":1:upright-v1",10,10,50,40);
    }
    @Test void slowDownloadsHoldAdmissionSlotAndDisconnectReleasesIt() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->{
                assertThatThrownBy(()->service.download(record.mediaId(),1,request,download->{
                    entered.countDown();
                    try {if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Test timed out");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                    throw new IOException("Disconnected test client");
                })).isInstanceOf(EditException.class);return null;
            });
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(()->service.download(record.mediaId(),1,request,download->fail("Busy request must not run")))
                        .isInstanceOfSatisfying(EditException.class,e->assertThat(e.code()).isEqualTo(EditException.Code.CROP_BUSY));
            } finally {release.countDown();}
            first.get(10,TimeUnit.SECONDS);
        }
        service.download(record.mediaId(),1,request,download->assertThat(download.input().readAllBytes()).isNotEmpty());
        try(var entries=Files.list(root.resolve("derived/crop-work-v1"))){assertThat(entries.toList()).isEmpty();}
    }
    @Test void permissionRevokedDuringRenderingPreventsOutputAndCleansTheWork() throws Exception {
        when(media.require(record.mediaId(),MediaAction.CROP_AND_SAVE)).thenReturn(record)
                .thenThrow(new MediaException(MediaException.Code.MEDIA_NOT_FOUND));
        assertThatThrownBy(()->service.download(record.mediaId(),1,request,d->fail("Revoked output must not be delivered")))
                .isInstanceOf(MediaException.class);
        try(var entries=Files.list(root.resolve("derived/crop-work-v1"))){assertThat(entries.toList()).isEmpty();}
    }
    @Test void unverifiedNativeGeometryNeverUsesPreviewAsOriginalPixels() throws Exception {
        when(previews.page(record.mediaId(),1)).thenReturn(new MediaPage(record,new PreviewStatus("a".repeat(64),1,"ready",null,50,40)));
        assertThatThrownBy(()->service.download(record.mediaId(),1,request,d->fail("Unverified crop must not run")))
                .isInstanceOfSatisfying(EditException.class,e->assertThat(e.code()).isEqualTo(EditException.Code.EDIT_NOT_READY));
        verify(store,never()).resolveOriginal(any());
    }

    @Test void fullPageExportDoesNotRequireCropPermission() throws Exception {
        when(media.require(record.mediaId(),MediaAction.EXPORT_EDITED)).thenReturn(record);
        var full=new CropRequest(record.mediaVersion(),request.coordinateSpaceId(),0,0,100,80);
        service.export(record.mediaId(),1,new EditedExportRequest(full,0,false,100,0),
                d->{assertThat(d.width()).isEqualTo(100);assertThat(d.input().readAllBytes()).isNotEmpty();});
        verify(media,never()).require(record.mediaId(),MediaAction.CROP_AND_SAVE);
    }
    @Test void changedAnnotationRevisionDuringRenderingPreventsDownloadAndReleasesSlot() throws Exception {
        when(media.require(record.mediaId(),MediaAction.EXPORT_EDITED)).thenReturn(record);
        var doc=new AnnotationDocument(record.mediaVersion(),1,request.coordinateSpaceId(),7,List.of());
        when(annotations.exportSnapshot(record.mediaId(),1,record.mediaVersion(),request.coordinateSpaceId(),7))
                .thenReturn(doc).thenThrow(new EditException(EditException.Code.EDIT_CONFLICT));
        assertThatThrownBy(()->service.export(record.mediaId(),1,new EditedExportRequest(request,7,true,100,0),
                d->fail("Changed snapshot must not be delivered")))
                .isInstanceOfSatisfying(EditException.class,e->assertThat(e.code()).isEqualTo(EditException.Code.EDIT_CONFLICT));
        service.export(record.mediaId(),1,new EditedExportRequest(request,0,false,100,0),d->d.input().transferTo(OutputStream.nullOutputStream()));
        try(var entries=Files.list(root.resolve("derived/crop-work-v1"))){assertThat(entries.toList()).isEmpty();}
    }
    @Test void revokedExportPermissionAndDisabledViewAdjustmentsCannotBeBypassed() throws Exception {
        when(media.require(record.mediaId(),MediaAction.EXPORT_EDITED)).thenReturn(record)
                .thenThrow(new MediaException(MediaException.Code.MEDIA_NOT_FOUND));
        assertThatThrownBy(()->service.export(record.mediaId(),1,new EditedExportRequest(request,0,false,100,0),
                d->fail("Revoked export must not be delivered"))).isInstanceOf(MediaException.class);
        assertThatThrownBy(()->service.export(record.mediaId(),1,new EditedExportRequest(request,0,false,125,0),d->fail("Disabled brightness")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->service.export(record.mediaId(),1,new EditedExportRequest(request,0,false,100,90),d->fail("Disabled rotation")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value=MediaAction.class,names={"EXPORT_EDITED","CROP_AND_SAVE"})
    void revocationWhileFinalAnnotationSnapshotIsReadPreventsResponse(MediaAction revoked) throws Exception {
        var allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
        for(var action:List.of(MediaAction.EXPORT_EDITED,MediaAction.CROP_AND_SAVE))
            when(media.require(record.mediaId(),action)).thenAnswer(call->{
                if(action==revoked&&!allowed.get())throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
                return record;
            });
        var area=revoked==MediaAction.CROP_AND_SAVE?request:
                new CropRequest(record.mediaVersion(),request.coordinateSpaceId(),0,0,100,80);
        var edit=new EditedExportRequest(area,7,true,100,0);
        var snapshot=new AnnotationDocument(record.mediaVersion(),1,request.coordinateSpaceId(),7,List.of());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(annotations.exportSnapshot(record.mediaId(),1,record.mediaVersion(),request.coordinateSpaceId(),7))
                .thenReturn(snapshot).thenAnswer(call->{
                    entered.countDown();
                    if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Snapshot gate timed out");
                    return snapshot;
                });
        var delivered=new java.util.concurrent.atomic.AtomicBoolean();
        try(var executor=Executors.newSingleThreadExecutor()) {
            var result=executor.submit(()->{
                assertThatThrownBy(()->service.export(record.mediaId(),1,edit,d->{delivered.set(true);d.input().transferTo(OutputStream.nullOutputStream());}))
                        .isInstanceOf(MediaException.class);
                return null;
            });
            try{assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();allowed.set(false);}
            finally{release.countDown();}
            result.get(10,TimeUnit.SECONDS);
        }finally{release.countDown();}
        assertThat(delivered).isFalse();
        try(var entries=Files.list(root.resolve("derived/crop-work-v1"))){assertThat(entries.toList()).isEmpty();}
        allowed.set(true);
        service.export(record.mediaId(),1,new EditedExportRequest(area,0,false,100,0),d->assertThat(d.input().readAllBytes()).isNotEmpty());
    }
}
