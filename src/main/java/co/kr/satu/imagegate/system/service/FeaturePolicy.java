package co.kr.satu.imagegate.system.service;

import co.kr.satu.imagegate.system.config.ImageGateProperties;
import org.springframework.stereotype.Component;

/**
 * Installation policy, separate from user authorization. Future API handlers and
 * UI capability responses must apply both this policy and per-media permissions.
 */
@Component
public final class FeaturePolicy {
    public enum Feature {
        IMAGE_VIEW, ROTATION, ZOOM, CROP_AND_SAVE, BRIGHTNESS, ANNOTATIONS,
        MAGNIFIER, STAMPS, STAMP_UPLOAD, EXPORT_EDITED,
        VIDEO_PLAYBACK, VIDEO_SEEK, VIDEO_SPEED,
        AUDIO_PLAYBACK, AUDIO_SEEK, AUDIO_SPEED
    }

    private final ImageGateProperties.Features features;

    public FeaturePolicy(ImageGateProperties properties) {
        this.features = properties.features();
    }

    public boolean isEnabled(Feature feature) {
        var image = features.image();
        var video = features.video();
        var audio = features.audio();
        return switch (feature) {
            case IMAGE_VIEW -> image.enabled();
            case ROTATION -> image.enabled() && image.rotation();
            case ZOOM -> image.enabled() && image.zoom();
            case CROP_AND_SAVE -> image.enabled() && image.cropAndSave();
            case BRIGHTNESS -> image.enabled() && image.brightness();
            case ANNOTATIONS -> image.enabled() && image.annotations();
            case MAGNIFIER -> image.enabled() && image.magnifier();
            case STAMPS -> image.enabled() && image.stamps();
            case STAMP_UPLOAD -> image.enabled() && image.stamps() && image.stampUpload();
            case EXPORT_EDITED -> image.enabled() && image.exportEdited();
            case VIDEO_PLAYBACK -> video.enabled();
            case VIDEO_SEEK -> video.enabled() && video.seek();
            case VIDEO_SPEED -> video.enabled() && video.playbackSpeed();
            case AUDIO_PLAYBACK -> audio.enabled();
            case AUDIO_SEEK -> audio.enabled() && audio.seek();
            case AUDIO_SPEED -> audio.enabled() && audio.playbackSpeed();
        };
    }

    public void requireEnabled(Feature feature) {
        if (!isEnabled(feature)) {
            throw new IllegalStateException("Feature disabled by installation policy: " + feature);
        }
    }
}
