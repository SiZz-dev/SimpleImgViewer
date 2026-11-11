package co.kr.satu.imagegate.media.config;
import co.kr.satu.imagegate.media.service.MultipartAdmission;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;
/** Installed once inside security, before CSRF can consult multipart parameters. Never reads the body. */
public final class MultipartIngressFilter extends OncePerRequestFilter {
    public static final String ADMITTED_MULTIPART_ATTRIBUTE="co.kr.satu.imagegate.media.admittedMultipart";
    private final MultipartAdmission admission;
    public MultipartIngressFilter(MultipartAdmission admission){this.admission=admission;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String type=request.getContentType();return type==null||!type.regionMatches(true,0,"multipart/",0,10);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        var lease=admission.tryAcquire();
        if(lease==null){
            response.setStatus(429);response.setHeader("Cache-Control","no-store");response.setContentType("application/problem+json");
            response.getWriter().write("{\"status\":429,\"code\":\"UPLOAD_INGRESS_BUSY\",\"title\":\"Upload requests busy\"}");return;
        }
        request.setAttribute(ADMITTED_MULTIPART_ATTRIBUTE,Boolean.TRUE);
        try{chain.doFilter(request,response);}
        finally {
            try{if(request.isAsyncStarted())track(request.getAsyncContext(),lease);else lease.close();}
            catch(IllegalStateException completed){lease.close();}
        }
    }
    private static void track(AsyncContext context,MultipartAdmission.Lease lease){
        var listener=new AsyncListener(){
            public void onComplete(AsyncEvent event){lease.close();}
            public void onTimeout(AsyncEvent event){/* Retain until actual completion, not just timeout notification. */}
            public void onError(AsyncEvent event){/* Container completion releases the slot. */}
            public void onStartAsync(AsyncEvent event){try{event.getAsyncContext().addListener(this);}catch(IllegalStateException completed){lease.close();}}
        };
        try{context.addListener(listener);}catch(IllegalStateException completed){lease.close();}
    }
}
