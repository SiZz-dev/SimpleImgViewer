package co.kr.satu.imagegate.media.config;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.service.MultipartAdmission;
import jakarta.servlet.*;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
class MultipartIngressFilterTests {
    MockHttpServletRequest multipart(){var r=new MockHttpServletRequest("POST","/api/v1/media");r.setContentType("MULTIPART/form-data; boundary=fixture");return r;}
    @Test void overloadNeverReadsBodyOrInvokesSecurityAndReturnsPrivate429() throws Exception {
        var gate=new MultipartAdmission(1);var filter=new MultipartIngressFilter(gate);var response=new MockHttpServletResponse();
        var request=new MockHttpServletRequest("POST","/unknown"){
            @Override public ServletInputStream getInputStream(){throw new AssertionError("Body must remain unread");}
            @Override public String getParameter(String name){throw new AssertionError("No multipart parameter parsing");}
        };request.setContentType("multipart/form-data");
        try(var held=gate.tryAcquire()){
            filter.doFilter(request,response,(r,s)->{throw new AssertionError("Overload must not proceed");});
            assertThat(response.getStatus()).isEqualTo(429);assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(response.getContentAsString()).contains("UPLOAD_INGRESS_BUSY").doesNotContain("tenant","owner");
        }
    }
    @Test void ordinaryViewingBypassesBusyMultipartAdmission() throws Exception {
        var gate=new MultipartAdmission(1);var called=new AtomicBoolean();
        try(var held=gate.tryAcquire()){new MultipartIngressFilter(gate).doFilter(new MockHttpServletRequest("GET","/api/v1/media/id/previews/1/source"),new MockHttpServletResponse(),(r,s)->called.set(true));}
        assertThat(called).isTrue();
    }
    @Test void normalAndExceptionalChainsAlwaysReleaseSlots() throws Exception {
        var gate=new MultipartAdmission(1);var filter=new MultipartIngressFilter(gate);
        filter.doFilter(multipart(),new MockHttpServletResponse(),(r,s)->assertThat(gate.tryAcquire()).isNull());
        assertThatThrownBy(()->filter.doFilter(multipart(),new MockHttpServletResponse(),(r,s)->{throw new IOException("synthetic");})).isInstanceOf(IOException.class);
        assertThatThrownBy(()->filter.doFilter(multipart(),new MockHttpServletResponse(),(r,s)->{throw new ServletException("synthetic");})).isInstanceOf(ServletException.class);
        try(var next=gate.tryAcquire()){assertThat(next).isNotNull();}
    }
    @Test void asyncCompletionRetainsSlotThroughTimeoutAndReleasesExactlyOnce() throws Exception {
        var gate=new MultipartAdmission(1);var request=multipart();request.setAsyncSupported(true);
        new MultipartIngressFilter(gate).doFilter(request,new MockHttpServletResponse(),(r,s)->r.startAsync());
        assertThat(gate.tryAcquire()).isNull();var context=(MockAsyncContext)request.getAsyncContext();
        for(var listener:context.getListeners())listener.onTimeout(new AsyncEvent(context));assertThat(gate.tryAcquire()).isNull();
        context.complete();for(var listener:context.getListeners())listener.onComplete(new AsyncEvent(context));
        try(var next=gate.tryAcquire()){assertThat(next).isNotNull();assertThat(gate.tryAcquire()).isNull();}
    }
}
