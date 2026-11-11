package co.kr.satu.imagegate.media.config;
import co.kr.satu.imagegate.media.util.UploadReadTimeouts;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
/** Constant transport error only. Never forwards to a controller or changes authorization rules. */
public final class UploadTimeoutErrorFilter implements Filter {
    @Override public void doFilter(ServletRequest raw,ServletResponse output,FilterChain chain)throws IOException,ServletException {
        if(raw instanceof HttpServletRequest request&&output instanceof HttpServletResponse response
                &&request.getDispatcherType()==DispatcherType.ERROR
                &&Boolean.TRUE.equals(request.getAttribute(MultipartIngressFilter.ADMITTED_MULTIPART_ATTRIBUTE))
                &&Integer.valueOf(408).equals(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE))
                &&request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) instanceof Throwable failure
                &&UploadReadTimeouts.causedBySocketTimeout(failure)){
            response.setStatus(408);response.setHeader("Cache-Control","no-store");response.setHeader("Connection","close");
            response.setHeader("X-Content-Type-Options","nosniff");response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":408,\"code\":\"UPLOAD_READ_TIMEOUT\",\"title\":\"Request Timeout\"}");return;
        }
        chain.doFilter(raw,output);
    }
}
