package co.kr.satu.imagegate.editing.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Bound JSON before Jackson materializes user text. Multipart stamp uploads use their stream limit. */
@Component
public final class EditingRequestSizeFilter extends OncePerRequestFilter {
    private static final int MAX=16384;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !java.util.Set.of("POST","PUT","DELETE").contains(request.getMethod())
                ||!request.getRequestURI().substring(request.getContextPath().length()).matches("/api/v1/media/[^/]+/pages/[^/]+/(?:annotations(?:/(?:history/[^/]+|[^/]+))?|crop|export)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        if(request.getContentLengthLong()>MAX){reject(response);return;}
        byte[] body=request.getInputStream().readNBytes(MAX+1);
        if(body.length>MAX){reject(response);return;}
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public int getContentLength(){return body.length;}
            @Override public long getContentLengthLong(){return body.length;}
            @Override public ServletInputStream getInputStream(){
                var stream=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read(){return stream.read();}
                    public int read(byte[] b,int off,int len){return stream.read(b,off,len);}
                    public boolean isFinished(){return stream.available()==0;}
                    public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous JSON input");}
                };
            }
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
        },response);
    }
    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);response.setHeader("Cache-Control","no-store");response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":413,\"code\":\"EDIT_REQUEST_TOO_LARGE\",\"title\":\"Request too large\"}");
    }
}
