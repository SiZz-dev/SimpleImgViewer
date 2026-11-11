package co.kr.satu.imagegate.media.service;

import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.media.exception.MediaException;
import co.kr.satu.imagegate.media.repository.MediaStore;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.IOException;
import java.io.BufferedInputStream;
import co.kr.satu.imagegate.media.util.MediaHeaderDetector;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.core.io.InputStreamSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public final class MediaService {
    private final CurrentActor actors;
    private final MediaStore store;
    private final MediaAuthorizationPolicy authorization;
    private final FeaturePolicy features;
    private final UploadAdmission uploads;

    @org.springframework.beans.factory.annotation.Autowired
    public MediaService(CurrentActor actors,MediaStore store,MediaAuthorizationPolicy authorization,
            FeaturePolicy features,UploadAdmission uploads) {
        this.uploads=uploads;
        this.actors=actors; this.store=store; this.authorization=authorization; this.features=features;
    }

    public MediaService(CurrentActor actors,MediaStore store,MediaAuthorizationPolicy authorization,FeaturePolicy features){
        this(actors,store,authorization,features,new UploadAdmission(co.kr.satu.imagegate.system.config.ImageGateProperties.Upload.defaults()));
    }

    public MediaRecord register(String name,InputStreamSource source) {
        var actor=actors.require();
        try (var slot=uploads.acquire(actor);var input=new BufferedInputStream(source.getInputStream())) {
            input.mark(512);
            byte[] header=input.readNBytes(512);
            input.reset();
            if(header.length==0) throw new MediaException(MediaException.Code.INVALID_MEDIA);
            var candidate=MediaHeaderDetector.detect(header);
            if(candidate==null) throw new MediaException(MediaException.Code.UNSUPPORTED_MEDIA);
            if(!features.isEnabled(viewFeature(candidate.type())))
                throw new AccessDeniedException("Media type disabled by installation policy");
            return store.register(actor.tenantId(),actor.userId(),name,input);
        } catch (IOException failure) {
            throw new MediaException(MediaException.Code.STORAGE_UNAVAILABLE,failure);
        }
    }

    public MediaRecord require(String id,MediaAction action) {
        var actor=actors.require();
        try {
            var media=store.find(actor.tenantId(),id)
                    .orElseThrow(() -> new MediaException(MediaException.Code.MEDIA_NOT_FOUND));
            if (!actor.tenantId().equals(media.tenantId())
                    || !authorization.permits(actor,media,MediaAction.VIEW)
                    || !authorization.permits(actor,media,action))
                throw new MediaException(MediaException.Code.MEDIA_NOT_FOUND);
            if (!features.isEnabled(viewFeature(media.mediaType())) || !featureAllowed(media,action))
                throw new AccessDeniedException("Feature disabled by installation policy");
            return media;
        } catch (IOException failure) {
            throw new MediaException(MediaException.Code.STORAGE_UNAVAILABLE,failure);
        }
    }

    public Set<MediaAction> permissions(MediaRecord media) {
        var actor=actors.require();
        var result=EnumSet.noneOf(MediaAction.class);
        if (!actor.tenantId().equals(media.tenantId())
                || !features.isEnabled(viewFeature(media.mediaType()))
                || !authorization.permits(actor,media,MediaAction.VIEW)) return Set.of();
        for(var action:MediaAction.values()) {
            if (featureAllowed(media,action) && authorization.permits(actor,media,action)) result.add(action);
        }
        return Set.copyOf(result);
    }

    private boolean featureAllowed(MediaRecord media,MediaAction action) {
        if(media.mediaType()!=MediaType.IMAGE && action!=MediaAction.VIEW
                && action!=MediaAction.DOWNLOAD_ORIGINAL) return false;
        return switch(action) {
            case ANNOTATE -> features.isEnabled(FeaturePolicy.Feature.ANNOTATIONS);
            case STAMP -> features.isEnabled(FeaturePolicy.Feature.STAMPS);
            case CROP_AND_SAVE -> features.isEnabled(FeaturePolicy.Feature.CROP_AND_SAVE);
            case EXPORT_EDITED -> features.isEnabled(FeaturePolicy.Feature.EXPORT_EDITED);
            default -> true;
        };
    }

    private static FeaturePolicy.Feature viewFeature(MediaType type) {
        return switch(type) {
            case IMAGE -> FeaturePolicy.Feature.IMAGE_VIEW;
            case VIDEO -> FeaturePolicy.Feature.VIDEO_PLAYBACK;
            case AUDIO -> FeaturePolicy.Feature.AUDIO_PLAYBACK;
        };
    }
}
