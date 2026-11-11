package co.kr.satu.imagegate.security.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class CurrentActorTests {
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @Test void rejectsMissingUnauthenticatedAndUntypedIdentities() {
        var actor=new CurrentActor();
        assertThatThrownBy(actor::require).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.unauthenticated(new ViewerPrincipal("tenant","alice"),"x"));
        assertThatThrownBy(actor::require).isInstanceOf(AccessDeniedException.class);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("alice","x",List.of()));
        assertThatThrownBy(actor::require).isInstanceOf(AccessDeniedException.class);
    }
    @Test void acceptsOnlyTrustedAuthenticatedPrincipal() {
        var principal=new ViewerPrincipal("tenant","alice");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal,"unused",List.of()));
        assertThat(new CurrentActor().require()).isSameAs(principal);
    }
    @Test void anonymousTokenCannotBecomeTrustedEvenWithTypedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "test-only-key",new ViewerPrincipal("tenant","alice"),
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThatThrownBy(()->new CurrentActor().require()).isInstanceOf(AccessDeniedException.class);
    }
}
