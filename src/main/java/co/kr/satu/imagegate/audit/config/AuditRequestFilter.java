package co.kr.satu.imagegate.audit.config;
import co.kr.satu.imagegate.audit.service.AuditEvents;
import co.kr.satu.imagegate.audit.service.AuditEvents.Action;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
/** Inside the security context boundary, before CSRF/authorization. Never reads bodies/headers. */
public final class AuditRequestFilter extends OncePerRequestFilter {
    private final boolean enabled;
    public AuditRequestFilter(boolean enabled){this.enabled=enabled;}
    private record Target(Action action,String media,String page) {}
    private static final java.util.regex.Pattern ROUTE=java.util.regex.Pattern.compile(
        "/api/v1/media/([^/]+)/(?:pages/([^/]+)/(annotations(?:/[^/]+(?:/[^/]+)?)?|crop|export|pyramids)|stamps|playback|previews)");
    private Target target(HttpServletRequest r) {
        if(!enabled)return null;
        String method=r.getMethod();
        if(!java.util.Set.of("POST","PUT","DELETE").contains(method))return null;
        String path=r.getRequestURI().substring(r.getContextPath().length());
        if(method.equals("POST")&&path.equals("/api/v1/media"))return new Target(Action.MEDIA_REGISTER,null,null);
        var match=ROUTE.matcher(path);if(!match.matches())return null;
        String part=match.group(3);Action action=null;
        if(method.equals("POST")) {
            if(part==null&&path.endsWith("/stamps"))action=Action.STAMP_UPLOAD;
            else if(part==null&&path.endsWith("/playback"))action=Action.PLAYBACK_REQUEST;
            else if("crop".equals(part))action=Action.CROP_EXPORT;
            else if("export".equals(part))action=Action.EDITED_EXPORT;
            else if("pyramids".equals(part))action=Action.TILE_REQUEST;
            else if(part==null&&path.endsWith("/previews"))action=Action.PREVIEW_REQUEST;
            else if("annotations".equals(part))action=Action.ANNOTATION_CREATE;
            else if("annotations/history/undo".equals(part))action=Action.ANNOTATION_UNDO;
            else if("annotations/history/redo".equals(part))action=Action.ANNOTATION_REDO;
        } else if(part!=null&&part.matches("annotations/[^/]+")) {
            if(method.equals("PUT"))action=Action.ANNOTATION_UPDATE;
            if(method.equals("DELETE"))action=Action.ANNOTATION_DELETE;
        }
        return action==null?null:new Target(action,match.group(1),match.group(2));
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest r){return target(r)==null;}
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        Target target=target(r);long start=System.nanoTime();boolean interrupted=true;
        try {chain.doFilter(r,response);interrupted=false;}
        finally {
            var auth=SecurityContextHolder.getContext().getAuthentication();
            ViewerPrincipal actor=auth!=null&&auth.isAuthenticated()&&!(auth instanceof AnonymousAuthenticationToken)
                &&auth.getPrincipal() instanceof ViewerPrincipal p?p:null;
            String media=target.media();
            if(target.action()==Action.MEDIA_REGISTER&&response.getStatus()==201) {
                String location=response.getHeader("Location");
                if(location!=null&&location.startsWith("/api/v1/media/"))media=location.substring(14);
            }
            AuditEvents.response(target.action(),actor,media,target.page(),response.getStatus(),interrupted,System.nanoTime()-start);
        }
    }
}
