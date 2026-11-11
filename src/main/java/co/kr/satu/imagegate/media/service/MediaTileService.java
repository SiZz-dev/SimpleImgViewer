package co.kr.satu.imagegate.media.service;
import co.kr.satu.imagegate.conversion.domain.*;
import co.kr.satu.imagegate.conversion.exception.*;
import co.kr.satu.imagegate.conversion.service.TileQueue;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
/** Current file authorization on every call, including HEAD and fully cached results. */
@Service
public final class MediaTileService {
    private final MediaService media;private final CurrentActor actors;private final ObjectProvider<TileQueue> queues;private final ImageGateProperties properties;
    public MediaTileService(MediaService media,CurrentActor actors,ObjectProvider<TileQueue> queues,ImageGateProperties properties){this.media=media;this.actors=actors;this.queues=queues;this.properties=properties;}
    private void page(int page){if(page<1||page>10000)throw new MediaException(MediaException.Code.INVALID_REQUEST);}
    private TileView inactive(MediaRecord record,int page){
        if(!properties.tiles().enabled())return new TileView(new TileJobStatus(null,page,"disabled","PROCESSING_DISABLED"),null);
        if(record.mediaType()!=MediaType.IMAGE||!"image/tiff".equals(record.mimeType()))return new TileView(new TileJobStatus(null,page,"unsupported","TILE_PROFILE_UNAVAILABLE"),null);
        return null;
    }
    private TileQueue queue(){var queue=queues.getIfAvailable();if(queue==null)throw new MediaException(MediaException.Code.STORAGE_UNAVAILABLE);return queue;}
    private void same(MediaRecord record){if(!record.equals(media.require(record.mediaId(),MediaAction.VIEW)))throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);}
    public MediaTileView view(String id,int page,boolean prepare,boolean retry){
        var record=media.require(id,MediaAction.VIEW);page(page);var tiles=inactive(record,page);
        if(tiles==null)try{
            var queue=queue();var actor=actors.require();
            if(prepare){var status=queue.submit(record,actor,page,retry);tiles=status.state().equals("ready")?queue.view(record,actor,page):new TileView(status,null);}
            else tiles=queue.view(record,actor,page);
        }catch(TileAdmissionException e){same(record);throw admission(e);}catch(IOException e){same(record);throw new MediaException(MediaException.Code.STORAGE_UNAVAILABLE,e);}
        same(record);return new MediaTileView(record,tiles);
    }
    public void content(String id,int page,String generation,int level,int column,int row,TileQueue.ContentWriter writer)throws IOException{
        var record=media.require(id,MediaAction.VIEW);page(page);
        if(inactive(record,page)!=null)throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
        try{if(!UUID.fromString(generation).toString().equals(generation)||level<0||column<0||row<0)throw new IllegalArgumentException();}
        catch(IllegalArgumentException invalid){throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);}
        boolean[] delivering={false};
        try{queue().content(record,actors.require(),page,generation,level,column,row,(input,tile)->{
            same(record);delivering[0]=true;writer.write(input,tile);
        });}
        catch(TileUnavailableException e){same(record);throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);}
        catch(TileAdmissionException e){same(record);throw admission(e);}
        catch(IOException e){if(delivering[0])throw e;same(record);throw new MediaException(MediaException.Code.STORAGE_UNAVAILABLE,e);}
    }
    private MediaException admission(TileAdmissionException e){return new MediaException(switch(e.code()){
        case CAPACITY->MediaException.Code.TILE_QUEUE_FULL;case SPACE->MediaException.Code.INSUFFICIENT_STORAGE;
        case LIMIT->MediaException.Code.TILE_LIMIT_EXCEEDED;case CLOSED->MediaException.Code.STORAGE_UNAVAILABLE;
    },e);}
}
