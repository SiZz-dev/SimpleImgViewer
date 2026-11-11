package co.kr.satu.imagegate.viewer.service;

import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import co.kr.satu.imagegate.system.service.FeaturePolicy.Feature;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public final class ViewerCapabilitiesService {
    private final CurrentActor actors;
    private final FeaturePolicy policy;
    private final co.kr.satu.imagegate.system.config.ImageGateProperties properties;
    public ViewerCapabilitiesService(CurrentActor actors,FeaturePolicy policy,co.kr.satu.imagegate.system.config.ImageGateProperties properties) { this.actors=actors;this.policy=policy;this.properties=properties; }
    public Map<String,Boolean> capabilities() {
        actors.require();
        return Map.ofEntries(
                Map.entry("image",policy.isEnabled(Feature.IMAGE_VIEW)),
                Map.entry("tiles",properties.tiles().enabled()&&policy.isEnabled(Feature.IMAGE_VIEW)),
                Map.entry("video",properties.playback().enabled()&&policy.isEnabled(Feature.VIDEO_PLAYBACK)),
                Map.entry("audio",properties.playback().enabled()&&policy.isEnabled(Feature.AUDIO_PLAYBACK)),
                Map.entry("videoSeek",policy.isEnabled(Feature.VIDEO_SEEK)),
                Map.entry("audioSeek",policy.isEnabled(Feature.AUDIO_SEEK)),
                Map.entry("videoPlaybackSpeed",policy.isEnabled(Feature.VIDEO_SPEED)),
                Map.entry("audioPlaybackSpeed",policy.isEnabled(Feature.AUDIO_SPEED)),
                Map.entry("zoom",policy.isEnabled(Feature.ZOOM)),
                Map.entry("rotation",policy.isEnabled(Feature.ROTATION)),
                Map.entry("brightness",policy.isEnabled(Feature.BRIGHTNESS)),
                Map.entry("magnifier",policy.isEnabled(Feature.MAGNIFIER)),
                // Installation flags do not turn unimplemented endpoints into available UI actions.
                Map.entry("annotations",policy.isEnabled(Feature.ANNOTATIONS)),
                Map.entry("stamps",policy.isEnabled(Feature.STAMPS)),Map.entry("stampUpload",policy.isEnabled(Feature.STAMP_UPLOAD)),
                Map.entry("cropAndSave",policy.isEnabled(Feature.CROP_AND_SAVE)),Map.entry("exportEdited",policy.isEnabled(Feature.EXPORT_EDITED)));
    }
}
