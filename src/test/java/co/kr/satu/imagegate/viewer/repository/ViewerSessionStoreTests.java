package co.kr.satu.imagegate.viewer.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Viewer;
import co.kr.satu.imagegate.viewer.domain.ViewerOpenOptions;
import co.kr.satu.imagegate.viewer.exception.ViewerException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ViewerSessionStoreTests {
    static class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-21T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    final ViewerPrincipal alice=new ViewerPrincipal("tenant","alice");
    ViewerOpenOptions options() { return new ViewerOpenOptions(List.of("media"),"media",1,null,"dark",true); }
    @Test void enforcesOwnerAndExactTenantCaseWithoutLeakingExistence() {
        var store=new ViewerSessionStore(new Viewer(Duration.ofMinutes(30),3,2),new MutableClock());
        var session=store.create(alice,options(),Map.of("media","v1"));
        for(var stranger:List.of(new ViewerPrincipal("tenant","bob"),new ViewerPrincipal("Tenant","alice"))) {
            assertThatThrownBy(()->store.require(session.id(),stranger)).isInstanceOf(ViewerException.class);
            assertThatThrownBy(()->store.delete(session.id(),stranger)).isInstanceOf(ViewerException.class);
        }
        assertThat(store.require(session.id(),alice).versions()).containsEntry("media","v1");
    }
    @Test void fixedExpiryDoesNotSlideAndExpiredSessionsReleaseCapacity() {
        var clock=new MutableClock();
        var store=new ViewerSessionStore(new Viewer(Duration.ofMinutes(1),1,1),clock);
        var first=store.create(alice,options(),Map.of("media","v1"));
        clock.now=clock.now.plusSeconds(59);
        assertThat(store.require(first.id(),alice).expiresAt()).isEqualTo(first.expiresAt());
        clock.now=clock.now.plusSeconds(1);
        assertThatThrownBy(()->store.require(first.id(),alice)).isInstanceOf(ViewerException.class);
        assertThat(store.create(alice,options(),Map.of("media","v1")).id()).isNotEqualTo(first.id());
    }
    @Test void appliesTotalAndPerUserLimitsAndDeleteReleasesCapacity() {
        var store=new ViewerSessionStore(new Viewer(Duration.ofMinutes(30),2,1),new MutableClock());
        var a=store.create(alice,options(),Map.of("media","v1"));
        assertLimit(()->store.create(alice,options(),Map.of("media","v1")));
        var bob=new ViewerPrincipal("tenant","bob");
        store.create(bob,options(),Map.of("media","v1"));
        assertLimit(()->store.create(new ViewerPrincipal("tenant","carol"),options(),Map.of("media","v1")));
        store.delete(a.id(),alice);
        assertThat(store.create(alice,options(),Map.of("media","v1"))).isNotNull();
    }
    private void assertLimit(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ViewerException.class,
                e->assertThat(e.code()).isEqualTo(ViewerException.Code.SESSION_LIMIT_REACHED));
    }
}
