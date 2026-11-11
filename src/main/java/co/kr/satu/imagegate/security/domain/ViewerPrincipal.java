package co.kr.satu.imagegate.security.domain;

import java.security.Principal;

/** Created only by a trusted authentication provider, never from request parameters or headers. */
public record ViewerPrincipal(String tenantId, String userId) implements Principal {
    public ViewerPrincipal {
        if (!valid(tenantId) || !valid(userId)) throw new IllegalArgumentException("Invalid identity");
    }
    private static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    }
    @Override public String getName() { return userId; }
}
