package co.kr.satu.imagegate.editing.service;

import co.kr.satu.imagegate.editing.domain.StampAsset;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.editing.repository.StampStore;
import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.service.MediaService;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.core.io.InputStreamSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public final class StampService {
    private final StampStore store;private final CurrentActor actors;private final MediaService media;private final FeaturePolicy features;
    public StampService(StampStore store,CurrentActor actors,MediaService media,FeaturePolicy features) {
        this.store=store;this.actors=actors;this.media=media;this.features=features;
    }
    private void enabled(boolean upload) {
        actors.require();
        if(!features.isEnabled(upload?FeaturePolicy.Feature.STAMP_UPLOAD:FeaturePolicy.Feature.STAMPS))
            throw new AccessDeniedException("Stamp feature disabled");
    }
    public List<StampAsset> list() {
        enabled(false);try{return store.list(actors.require());}catch(IOException e){throw storage(e);}
    }
    public StampAsset upload(String mediaId,String name,InputStreamSource source) {
        enabled(true);media.require(mediaId,MediaAction.STAMP);
        try(var input=source.getInputStream()){return store.register(actors.require(),name,input);}
        catch(IOException e){throw storage(e);}
    }
    public StampAsset owned(String id) {
        enabled(false);var actor=actors.require();
        try{return store.find(actor.tenantId(),actor.userId(),id);}catch(IOException e){throw storage(e);}
    }
    public Path ownedContent(String id) {
        owned(id);var actor=actors.require();
        try{return store.preview(actor.tenantId(),actor.userId(),id);}catch(IOException e){throw storage(e);}
    }
    private static EditException storage(IOException e){return new EditException(EditException.Code.EDIT_STORAGE_UNAVAILABLE,e);}
}
