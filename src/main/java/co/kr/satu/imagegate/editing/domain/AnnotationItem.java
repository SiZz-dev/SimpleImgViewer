package co.kr.satu.imagegate.editing.domain;

import java.time.Instant;

/** stampOwner is private storage identity and must never be serialized directly. */
public record AnnotationItem(String id,Type type,double x,double y,double width,double height,
        String text,String stampId,String stampOwner,Instant createdAt,Instant updatedAt) {
    public boolean sameContent(AnnotationItem other){
        return other!=null&&id.equals(other.id)&&type==other.type&&x==other.x&&y==other.y&&width==other.width&&height==other.height
                &&java.util.Objects.equals(text,other.text)&&java.util.Objects.equals(stampId,other.stampId)
                &&java.util.Objects.equals(stampOwner,other.stampOwner)&&createdAt.equals(other.createdAt);
    }
    public enum Type { NOTE, STAMP }
}
