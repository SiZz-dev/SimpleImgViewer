package co.kr.satu.imagegate.audit.config;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.audit.service.AuditEvents;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
class AuditRequestFilterTests {
    final String id="12345678-1234-1234-1234-123456789abc";
    final Logger logger=(Logger)LoggerFactory.getLogger("imagegate.audit");
    ListAppender<ILoggingEvent> appender;
    @BeforeEach void setup(){appender=new ListAppender<>();appender.start();logger.addAppender(appender);SecurityContextHolder.clearContext();}
    @AfterEach void clear(){logger.detachAppender(appender);appender.stop();SecurityContextHolder.clearContext();}
    void actor(){SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(new ViewerPrincipal("tenant","editor"),"secret",List.of()));}
    String event(){assertThat(appender.list).hasSize(1);return appender.list.getFirst().getFormattedMessage();}
    @Test void recordsTrustedIdentityButNeverBodyHeadersQueryOrUnknownIds() throws Exception {
        actor();var r=new MockHttpServletRequest("POST","/api/v1/media/secret-file/pages/1/annotations");
        r.setQueryString("token=secret-query");r.addHeader("Authorization","Bearer secret-auth");r.addHeader("X-User","forged-user");
        r.setContent("private-note secret-path".getBytes());
        new AuditRequestFilter(true).doFilter(r,new MockHttpServletResponse(),(q,s)->((jakarta.servlet.http.HttpServletResponse)s).setStatus(201));
        assertThat(event()).contains("action=ANNOTATION_CREATE tenant=tenant actor=editor media=- page=1 status=201 outcome=HTTP_RESPONSE")
            .doesNotContain("secret","private-note","forged-user");assertThat(r.getInputStream().readAllBytes()).isNotEmpty();
    }
    @Test void propagatesTransferFailureAndMarksInterruptedInsteadOfSuccess() {
        var r=new MockHttpServletRequest("POST","/api/v1/media/"+id+"/pages/1/export");
        assertThatThrownBy(()->new AuditRequestFilter(true).doFilter(r,new MockHttpServletResponse(),(q,s)->{throw new IOException("private-disk-path");})).isInstanceOf(IOException.class);
        assertThat(event()).contains("outcome=INTERRUPTED").doesNotContain("private-disk-path");
    }
    @Test void skipsReadsAndDisabledRequestsWithoutTouchingInput() throws Exception {
        for(String path:List.of("/api/v1/media/"+id,"/api/v1/media/"+id+"/playback/sources/abc/content","/api/v1/media/"+id+"/sources/abc/thumbnail"))
            new AuditRequestFilter(true).doFilter(new MockHttpServletRequest("GET",path),new MockHttpServletResponse(),(q,s)->{});
        new AuditRequestFilter(false).doFilter(new MockHttpServletRequest("POST","/api/v1/media"),new MockHttpServletResponse(),(q,s)->{});
        assertThat(appender.list).isEmpty();
    }
    @Test void supportsContextPathAndHistoryActions() throws Exception {
        for(String direction:List.of("undo","redo")) {
            appender.list.clear();var r=new MockHttpServletRequest("POST","/gate/api/v1/media/"+id+"/pages/2/annotations/history/"+direction);r.setContextPath("/gate");
            new AuditRequestFilter(true).doFilter(r,new MockHttpServletResponse(),(q,s)->{});
            assertThat(event()).contains("action=ANNOTATION_"+direction.toUpperCase(),"media="+id+" page=2");
        }
    }
    @Test void capturesOnlyAllowlistedRoutesAndMethods() throws Exception {
        for(String[] row:List.of(new String[]{"POST","/stamps","STAMP_UPLOAD"},new String[]{"POST","/previews","PREVIEW_REQUEST"},new String[]{"POST","/playback","PLAYBACK_REQUEST"},
            new String[]{"POST","/pages/1/pyramids","TILE_REQUEST"},new String[]{"POST","/pages/1/crop","CROP_EXPORT"},new String[]{"PUT","/pages/1/annotations/x","ANNOTATION_UPDATE"},new String[]{"DELETE","/pages/1/annotations/x","ANNOTATION_DELETE"})) {
            appender.list.clear();new AuditRequestFilter(true).doFilter(new MockHttpServletRequest(row[0],"/api/v1/media/"+id+row[1]),new MockHttpServletResponse(),(q,s)->{});
            assertThat(event()).contains("action="+row[2]);
        }
    }
    @Test void backgroundFailuresUseSanitizedFixedCodesAndNoThrowable() {
        AuditEvents.failure(AuditEvents.Action.PLAYBACK_FAILED,new ViewerPrincipal("t","u"),id,0,"secret-path\nforged=record");
        assertThat(event()).contains("action=PLAYBACK_FAILED","page=- status=0 outcome=JOB_FAILED code=UNKNOWN").doesNotContain("secret","forged");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }
    @Test void tileFailuresUseTheSameFixedPrivateAuditContract(){
        AuditEvents.failure(AuditEvents.Action.TILE_FAILED,new ViewerPrincipal("tenant","owner"),id,1,"TILE_FAILED");
        assertThat(event()).contains("action=TILE_FAILED","media="+id,"page=1 status=0 outcome=JOB_FAILED code=TILE_FAILED");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
    }
}
