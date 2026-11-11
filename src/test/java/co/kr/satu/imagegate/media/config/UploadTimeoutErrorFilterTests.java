package co.kr.satu.imagegate.media.config;
import static org.assertj.core.api.Assertions.*;
import jakarta.servlet.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
class UploadTimeoutErrorFilterTests {
    MockHttpServletRequest error(){var r=new MockHttpServletRequest("POST","/error");r.setDispatcherType(DispatcherType.ERROR);r.setAttribute(MultipartIngressFilter.ADMITTED_MULTIPART_ATTRIBUTE,Boolean.TRUE);r.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,408);r.setAttribute(RequestDispatcher.ERROR_EXCEPTION,new IOException("private path",new SocketTimeoutException("private socket")));return r;}
    @Test void confirmedContainerTimeoutReturnsConstant408WithoutCallingAnyEndpoint()throws Exception{
        var response=new MockHttpServletResponse();new UploadTimeoutErrorFilter().doFilter(error(),response,(r,s)->{throw new AssertionError("Must not dispatch a controller");});
        assertThat(response.getStatus()).isEqualTo(408);assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");assertThat(response.getHeader("Connection")).isEqualTo("close");assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");assertThat(response.getContentAsString()).contains("UPLOAD_READ_TIMEOUT").doesNotContain("private","exception","/error");
    }
    @Test void normalRequestsAndOtherErrorsAlwaysContinueThroughExistingSecurity()throws Exception{
        for(int variant=0;variant<4;variant++){
            var request=error();if(variant==0)request.setDispatcherType(DispatcherType.REQUEST);if(variant==1)request.removeAttribute(MultipartIngressFilter.ADMITTED_MULTIPART_ATTRIBUTE);if(variant==2)request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE,500);if(variant==3)request.setAttribute(RequestDispatcher.ERROR_EXCEPTION,new IOException("not a timeout"));
            var called=new AtomicBoolean();var response=new MockHttpServletResponse();new UploadTimeoutErrorFilter().doFilter(request,response,(r,s)->called.set(true));assertThat(called).isTrue();assertThat(response.getContentAsString()).isEmpty();
        }
    }
}
