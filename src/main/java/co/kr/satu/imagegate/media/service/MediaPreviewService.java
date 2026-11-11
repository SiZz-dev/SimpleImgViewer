package co.kr.satu.imagegate.media.service;

import co.kr.satu.imagegate.conversion.domain.PreviewStatus;
import co.kr.satu.imagegate.conversion.service.PreviewQueue;
import co.kr.satu.imagegate.conversion.service.TileQueue;
import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.security.service.CurrentActor;
import java.nio.file.Path;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/** Every public operation resolves current file authorization, including warm cache hits. */
@Service
public final class MediaPreviewService {
    private final MediaService media;
    private final CurrentActor actors;
    private final PreviewQueue queue;
    private final ObjectProvider<TileQueue> tiles;
    private final ImageGateProperties properties;
    public MediaPreviewService(MediaService media,CurrentActor actors,PreviewQueue queue) {
        this(media,actors,queue,null,null);
    }
    @Autowired
    public MediaPreviewService(MediaService media,CurrentActor actors,PreviewQueue queue,
            ObjectProvider<TileQueue> tiles,ImageGateProperties properties) {
        this.media=media;this.actors=actors;this.queue=queue;this.tiles=tiles;this.properties=properties;
    }
    public PreviewStatus afterRegistration(MediaRecord record) {
        if(record.mediaType()!=MediaType.IMAGE) return PreviewStatus.empty(1,"not_requested","IMAGE_PREVIEW_ONLY");
        try { return request(record.mediaId(),1,false); }
        catch(MediaException failure) {
            // The original has already been committed; return its ID even when admission fails.
            if(failure.code()!=MediaException.Code.PREVIEW_QUEUE_FULL
                    && failure.code()!=MediaException.Code.STORAGE_UNAVAILABLE) throw failure;
            return PreviewStatus.empty(1,"not_requested",failure.code().name());
        }
    }
    public PreviewStatus status(String id,int page) {
        var record=media.require(id,MediaAction.VIEW);
        if(page<1 || page>10000) throw new MediaException(MediaException.Code.INVALID_REQUEST);
        return record.mediaType()==MediaType.IMAGE ? queue.status(record,actors.require(),page)
                : PreviewStatus.empty(page,"not_requested","IMAGE_PREVIEW_ONLY");
    }
    public PreviewStatus request(String id,int page,boolean retry) {
        var record=media.require(id,MediaAction.VIEW);
        if(record.mediaType()!=MediaType.IMAGE) throw new MediaException(MediaException.Code.UNSUPPORTED_MEDIA);
        return queue.submit(record,actors.require(),page,retry);
    }
    public co.kr.satu.imagegate.media.domain.MediaPage page(String id,int page) {
        var record=media.require(id,MediaAction.VIEW);
        if(record.mediaType()!=MediaType.IMAGE) throw new MediaException(MediaException.Code.UNSUPPORTED_MEDIA);
        if(page<1 || page>10000) throw new MediaException(MediaException.Code.INVALID_REQUEST);
        var actor=actors.require();
        var preview=queue.status(record,actor,page);
        if(preview.ready()&&preview.geometry()==null&&"image/tiff".equals(record.mimeType())
                &&properties!=null&&properties.tiles().enabled()) {
            var tileQueue=tiles.getIfAvailable();
            if(tileQueue!=null)try {
                // Read-only lookup: never start native work from a page or editing request.
                var result=tileQueue.view(record,actor,page);
                var manifest=result.manifest();
                if("ready".equals(result.status().state())&&manifest!=null&&manifest.page()==page) {
                    var layout=manifest.layout();
                    // Publication approves only upright orientation-1 TIFF. Allow one preview pixel of rounding.
                    if(preview.width()>0&&preview.height()>0&&layout.overlap()==0
                            &&Math.abs((long)layout.width()*preview.height()-(long)layout.height()*preview.width())
                                <=Math.max(layout.width(),layout.height())) {
                        preview=new PreviewStatus(preview.sourceId(),preview.page(),preview.state(),preview.errorCode(),
                                preview.width(),preview.height(),new ImageGeometry(layout.width(),layout.height(),1),preview.thumbnail());
                    }
                }
            }catch(java.io.IOException failure) {
                if(!record.equals(media.require(id,MediaAction.VIEW)))throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
                throw new MediaException(MediaException.Code.STORAGE_UNAVAILABLE,failure);
            }
        }
        if(!record.equals(media.require(id,MediaAction.VIEW)))throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
        return new co.kr.satu.imagegate.media.domain.MediaPage(record,preview);
    }
    public void content(String id,int page,String sourceId,PreviewQueue.ContentWriter writer) throws java.io.IOException {
        content(id,page,sourceId,false,writer);
    }
    public void content(String id,int page,String sourceId,boolean thumbnail,PreviewQueue.ContentWriter writer) throws java.io.IOException {
        var record=media.require(id,MediaAction.VIEW);
        if(record.mediaType()!=MediaType.IMAGE) throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
        queue.content(record,actors.require(),page,sourceId,thumbnail,file->{
            if(!record.equals(media.require(id,MediaAction.VIEW)))throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
            writer.write(file);
        });
    }
}
