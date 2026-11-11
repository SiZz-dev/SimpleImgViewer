package co.kr.satu.imagegate.viewer.service;

import co.kr.satu.imagegate.media.domain.MediaType;
import co.kr.satu.imagegate.viewer.controller.dto.ViewerSessionRequest;

/** Checks position only after authorized metadata for the selected version is loaded. */
public final class MediaPositionValidator {

    private MediaPositionValidator() {}

    public static void validateType(Integer page,Double time,MediaType type) {
        if(type==null || (page!=null && (page<1 || page>10000))
                || (time!=null && (!Double.isFinite(time) || time<0)))
            throw new IllegalArgumentException("Invalid viewer position");
        if((type==MediaType.IMAGE && time!=null) || (type!=MediaType.IMAGE && page!=null))
            throw new IllegalArgumentException("Position does not match media type");
    }

    public static void validate(ViewerSessionRequest request, MediaType type,
            Integer pageCount, Double durationSeconds) {
        if (request == null || type == null) {
            throw new IllegalArgumentException("Request and verified media type are required");
        }
        validateType(request.initialPage(),request.initialTimeSeconds(),type);
        if (type == MediaType.IMAGE) {
            if (request.initialTimeSeconds() != null) {
                throw new IllegalArgumentException("Playback time cannot be supplied for images");
            }
            if (pageCount == null || pageCount < 1) {
                throw new IllegalArgumentException("Verified page count is required");
            }
            int page = request.initialPage() == null ? 1 : request.initialPage();
            if (page < 1 || page > pageCount) {
                throw new IllegalArgumentException("Requested page is outside the image");
            }
        } else {
            if (request.initialPage() != null) {
                throw new IllegalArgumentException("Page cannot be supplied for video or audio");
            }
            if (durationSeconds == null || !Double.isFinite(durationSeconds) || durationSeconds <= 0) {
                throw new IllegalArgumentException("Verified positive playback duration is required");
            }
            double time = request.initialTimeSeconds() == null ? 0 : request.initialTimeSeconds();
            if (!Double.isFinite(time) || time < 0 || time >= durationSeconds) {
                throw new IllegalArgumentException("Playback start is outside the media");
            }
        }
    }
}
