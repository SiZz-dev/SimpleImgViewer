package co.kr.satu.imagegate.audit.service;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import java.util.UUID;
import org.slf4j.LoggerFactory;
/** Fixed schema only: never accept exceptions, URLs, bodies or filenames. */
public final class AuditEvents {
    private AuditEvents() {}
    public enum Action { MEDIA_REGISTER, ANNOTATION_CREATE, ANNOTATION_UPDATE, ANNOTATION_DELETE,
        ANNOTATION_UNDO, ANNOTATION_REDO, STAMP_UPLOAD, CROP_EXPORT, EDITED_EXPORT,
        PREVIEW_REQUEST, PLAYBACK_REQUEST, PREVIEW_FAILED, PLAYBACK_FAILED, TILE_REQUEST, TILE_FAILED }
    public static void response(Action action,ViewerPrincipal actor,String media,String page,int status,boolean interrupted,long elapsedNanos) {
        emit(action,actor,media,page,status,interrupted?"INTERRUPTED":"HTTP_RESPONSE","-",elapsedNanos);
    }
    public static void failure(Action action,ViewerPrincipal actor,String media,int page,String code) {
        if(action!=Action.PREVIEW_FAILED&&action!=Action.PLAYBACK_FAILED&&action!=Action.TILE_FAILED)throw new IllegalArgumentException("Failure action required");
        emit(action,actor,media,Integer.toString(page),0,"JOB_FAILED",token(code),0);
    }
    private static void emit(Action action,ViewerPrincipal actor,String media,String page,int status,String outcome,String code,long nanos) {
        LoggerFactory.getLogger("imagegate.audit").info(
            "schema=1 event={} action={} tenant={} actor={} media={} page={} status={} outcome={} code={} durationMs={}",
            UUID.randomUUID(),action,actor==null?"-":actor.tenantId(),actor==null?"-":actor.userId(),
            media!=null&&media.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")?media:"-",
            page!=null&&page.matches("[1-9][0-9]{0,3}|10000")?page:"-",Math.max(0,Math.min(599,status)),outcome,code,
            Math.max(0,nanos/1_000_000));
    }
    private static String token(String value){return value!=null&&value.matches("[A-Z_]{1,64}")?value:"UNKNOWN";}
}
