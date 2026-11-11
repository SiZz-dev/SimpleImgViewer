package co.kr.satu.imagegate.viewer.repository;

import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.viewer.domain.*;
import co.kr.satu.imagegate.viewer.exception.ViewerException;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Viewer;
import java.time.Clock;
import java.util.*;

/** Bounded ephemeral state, fixed expiry. File authorization is never stored here. */
public final class ViewerSessionStore {
    private final Map<String,ViewerSession> entries=new HashMap<>();
    private final Clock clock;
    private final Viewer settings;
    public ViewerSessionStore(Viewer settings,Clock clock) { this.settings=settings;this.clock=clock; }

    public synchronized ViewerSession create(ViewerPrincipal owner,ViewerOpenOptions options,Map<String,String> versions) {
        expire();
        long owned=entries.values().stream().filter(s->s.owner().equals(owner)).count();
        if(entries.size()>=settings.maxSessions() || owned>=settings.maxSessionsPerUser())
            throw new ViewerException(ViewerException.Code.SESSION_LIMIT_REACHED);
        var now=clock.instant();
        var session=new ViewerSession(UUID.randomUUID().toString(),owner,options.mediaIds(),versions,
                options.selectedMediaId(),options.page(),options.timeSeconds(),options.theme(),options.leftPanelOpen(),
                now,now.plus(settings.sessionTtl()));
        entries.put(session.id(),session);
        return session;
    }
    public synchronized ViewerSession require(String id,ViewerPrincipal owner) {
        expire();
        var session=entries.get(id);
        if(session==null || !session.owner().equals(owner))
            throw new ViewerException(ViewerException.Code.SESSION_NOT_FOUND);
        return session;
    }
    public synchronized void delete(String id,ViewerPrincipal owner) { require(id,owner);entries.remove(id); }
    private void expire() {
        var now=clock.instant();
        entries.values().removeIf(session->!session.expiresAt().isAfter(now));
    }
}
