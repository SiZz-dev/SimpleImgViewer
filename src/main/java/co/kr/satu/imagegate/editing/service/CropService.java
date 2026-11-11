package co.kr.satu.imagegate.editing.service;

import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.editing.util.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import java.io.*;
import java.nio.file.*;
import java.util.HexFormat;
import java.util.List;
import co.kr.satu.imagegate.editing.repository.StampStore;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public final class CropService {
    @FunctionalInterface public interface DownloadWriter {void write(CropDownload download) throws IOException;}
    private final MediaService media;private final MediaPreviewService previews;private final MediaStore store;
    private final ImageGateProperties properties;private final Semaphore slots;
    private final AnnotationService annotations;private final StampStore stamps;private final FeaturePolicy features;
    public CropService(MediaService media,MediaPreviewService previews,MediaStore store,ImageGateProperties properties,
            AnnotationService annotations,StampStore stamps,FeaturePolicy features) {
        this.media=media;this.previews=previews;this.store=store;this.properties=properties;
        slots=new Semaphore(properties.crop().maxConcurrentJobs());
        this.annotations=annotations;this.stamps=stamps;this.features=features;
    }
    public void download(String id,int page,CropRequest request,DownloadWriter writer) throws IOException {
        run(id,page,request,null,writer);
    }
    public void export(String id,int page,EditedExportRequest request,DownloadWriter writer) throws IOException {
        if(request.brightness()<25||request.brightness()>200
                ||!java.util.Set.of(0,90,180,270).contains(request.rotation())||request.expectedRevision()<0)
            throw new EditException(EditException.Code.INVALID_EDIT);
        if((request.brightness()!=100&&!features.isEnabled(FeaturePolicy.Feature.BRIGHTNESS))
                ||(request.rotation()!=0&&!features.isEnabled(FeaturePolicy.Feature.ROTATION)))
            throw new org.springframework.security.access.AccessDeniedException("View adjustment disabled");
        run(id,page,request.region(),request,writer);
    }
    private void run(String id,int page,CropRequest request,EditedExportRequest edit,DownloadWriter writer) throws IOException {
        var action=edit==null?MediaAction.CROP_AND_SAVE:MediaAction.EXPORT_EDITED;
        var record=media.require(id,action);
        var view=previews.page(id,page);
        var geometry=view.preview().geometry();
        if(!view.preview().ready()||geometry==null)throw new EditException(EditException.Code.EDIT_NOT_READY);
        if(!record.mediaVersion().equals(view.media().mediaVersion())
                ||!record.mediaVersion().equals(request.mediaVersion())
                ||!geometry.coordinateSpaceId(record.mediaVersion(),page).equals(request.coordinateSpaceId()))
            throw new EditException(EditException.Code.EDIT_CONFLICT);
        double[] numbers={request.x(),request.y(),request.width(),request.height()};
        for(double n:numbers)if(!Double.isFinite(n)||n!=Math.rint(n)||n<0||n>Integer.MAX_VALUE)
            throw new EditException(EditException.Code.INVALID_EDIT);
        var area=new CropArea((int)numbers[0],(int)numbers[1],(int)numbers[2],(int)numbers[3]);
        if(area.width()<1||area.height()<1||area.x()>geometry.coordinateWidth()-area.width()
                ||area.y()>geometry.coordinateHeight()-area.height())throw new EditException(EditException.Code.INVALID_EDIT);
        boolean partial=area.x()!=0||area.y()!=0||area.width()!=geometry.coordinateWidth()||area.height()!=geometry.coordinateHeight();
        if(edit!=null&&partial)media.require(id,MediaAction.CROP_AND_SAVE);
        if((long)area.width()*area.height()>properties.crop().maxOutputPixels()
                ||(long)geometry.encodedWidth()*geometry.encodedHeight()>properties.crop().maxSourcePixels())
            throw new EditException(EditException.Code.CROP_TOO_LARGE);
        if(!slots.tryAcquire())throw new EditException(EditException.Code.CROP_BUSY);
        Path work=null,output=null;
        try {
            var snapshot=edit!=null&&edit.includeAnnotations()?annotations.exportSnapshot(id,page,request.mediaVersion(),
                    request.coordinateSpaceId(),edit.expectedRevision()):null;
            Path input=store.resolveOriginal(record);
            var digest=PreviewKeys.digest();
            try(var stream=Files.newInputStream(input)) {
                byte[] buffer=new byte[32768];int n;
                while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);
            }
            if(!record.sha256().equals(HexFormat.of().formatHex(digest.digest())))
                throw new EditException(EditException.Code.EDIT_CONFLICT);
            Path root=PrivateFiles.root(properties.storage().convertedPath().resolve("crop-work-v1"));
            work=Files.createTempDirectory(root,"crop-");
            if(!work.toRealPath().equals(work)||!work.startsWith(root))throw PrivateFiles.invalid();
            output=work.resolve("crop.png");
            CropRaster.render(input,page,geometry,area,properties.crop().maxSourcePixels(),
                    properties.crop().maxOutputBytes(),output,image->edit==null?image:EditRaster.compose(image,area,
                            edit.brightness(),edit.rotation(),snapshot==null?List.of():snapshot.items(),
                            item->stamps.raster(record.tenantId(),item.stampOwner(),item.stampId())));
            long size=Files.size(output);
            if(size<1||size>properties.crop().maxOutputBytes())throw new EditException(EditException.Code.CROP_TOO_LARGE);
            if(!record.equals(media.require(id,action)))throw new EditException(EditException.Code.EDIT_CONFLICT);
            if(edit!=null&&partial)media.require(id,MediaAction.CROP_AND_SAVE);
            if(snapshot!=null)annotations.exportSnapshot(id,page,request.mediaVersion(),request.coordinateSpaceId(),snapshot.revision());
            int width=area.width(),height=area.height();
            if(edit!=null&&edit.rotation()%180!=0){width=area.height();height=area.width();}
            try(var stream=Files.newInputStream(output)) {
                if(!record.equals(media.require(id,action)))throw new EditException(EditException.Code.EDIT_CONFLICT);
                if(edit!=null&&partial)media.require(id,MediaAction.CROP_AND_SAVE);
                // Keep admission slot until the synchronous HTTP copy finishes, including slow clients.
                writer.write(new CropDownload(stream,size,width,height));
            }
        } catch(EditException e){throw e;}
        catch(IOException failure){throw new EditException(EditException.Code.EDIT_STORAGE_UNAVAILABLE,failure);}
        finally {
            try {if(output!=null)Files.deleteIfExists(output);if(work!=null)Files.deleteIfExists(work);}
            catch(IOException ignored){/* Stopped-service orphan cleanup is documented; never expose this directory. */}
            slots.release();
        }
    }
}
