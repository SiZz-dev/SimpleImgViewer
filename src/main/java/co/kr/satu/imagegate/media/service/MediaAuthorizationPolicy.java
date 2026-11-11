package co.kr.satu.imagegate.media.service;

import co.kr.satu.imagegate.media.domain.MediaAction;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;

/** Replace with the business system's trusted ACL policy when integrating shared records. */
@FunctionalInterface
public interface MediaAuthorizationPolicy {
    boolean permits(ViewerPrincipal actor, MediaRecord media, MediaAction action);
}
