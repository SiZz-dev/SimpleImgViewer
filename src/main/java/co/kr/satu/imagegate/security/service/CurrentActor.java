package co.kr.satu.imagegate.security.service;

import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Service;

@Service
public final class CurrentActor {
    public ViewerPrincipal require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof ViewerPrincipal actor)) {
            throw new AccessDeniedException("A trusted authenticated identity is required");
        }
        return actor;
    }
}
