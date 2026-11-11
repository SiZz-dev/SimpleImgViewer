package co.kr.satu.imagegate.editing.service;

import co.kr.satu.imagegate.conversion.domain.ImageGeometry;
import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.editing.repository.*;
import co.kr.satu.imagegate.editing.util.PrivateFiles;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.media.service.*;
import co.kr.satu.imagegate.security.service.CurrentActor;
import co.kr.satu.imagegate.system.config.ImageGateProperties;
import co.kr.satu.imagegate.system.service.FeaturePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

@Service
public final class AnnotationService {
    private record Context(MediaRecord media,int page,ImageGeometry geometry,String space) {}
    private final MediaService media;private final MediaPreviewService previews;private final AnnotationStore store;
    private final StampService stamps;private final StampStore stampStore;private final CurrentActor actors;
    private final AnnotationHistoryStore history;
    private final FeaturePolicy features;private final ImageGateProperties.Editing limits;
    public AnnotationService(MediaService media,MediaPreviewService previews,AnnotationStore store,StampService stamps,
            StampStore stampStore,CurrentActor actors,FeaturePolicy features,ImageGateProperties properties,AnnotationHistoryStore history) {
        this.media=media;this.previews=previews;this.store=store;this.stamps=stamps;this.stampStore=stampStore;
        this.actors=actors;this.features=features;limits=properties.editing();this.history=history;
    }
    private Context context(String id,int page,MediaAction action) {
        var record=media.require(id,action);var view=previews.page(id,page);
        if(!record.mediaVersion().equals(view.media().mediaVersion()))throw failure(EditException.Code.EDIT_CONFLICT);
        var geometry=view.preview().geometry();
        if(!view.preview().ready()||geometry==null)throw failure(EditException.Code.EDIT_NOT_READY);
        return new Context(record,page,geometry,geometry.coordinateSpaceId(record.mediaVersion(),page));
    }
    private boolean visible(AnnotationItem.Type type) {
        return features.isEnabled(type==AnnotationItem.Type.NOTE?FeaturePolicy.Feature.ANNOTATIONS:FeaturePolicy.Feature.STAMPS);
    }
    private MediaAction action(AnnotationItem.Type type){return type==AnnotationItem.Type.NOTE?MediaAction.ANNOTATE:MediaAction.STAMP;}
    private void identity(Context ctx,String version,String space,int revision) {
        if(!ctx.media.mediaVersion().equals(version)||!ctx.space.equals(space))throw failure(EditException.Code.EDIT_CONFLICT);
        if(revision<0)throw failure(EditException.Code.INVALID_EDIT);
    }
    private AnnotationDocument read(Context ctx) throws IOException {
        var document=store.read(ctx.media,ctx.page,ctx.space);
        for(var item:document.items()) {
            if(!bounds(ctx,item.x(),item.y(),item.width(),item.height()))throw PrivateFiles.invalid();
        }
        return document;
    }
    public AnnotationDocument get(String id,int page) {
        var ctx=context(id,page,MediaAction.VIEW);
        if(!visible(AnnotationItem.Type.NOTE)&&!visible(AnnotationItem.Type.STAMP))throw new AccessDeniedException("Editing disabled");
        try{return filtered(read(ctx));}catch(IOException e){throw storage(e);}
    }
    public AnnotationDocument exportSnapshot(String id,int page,String version,String space,int revision) {
        var ctx=context(id,page,MediaAction.EXPORT_EDITED);identity(ctx,version,space,revision);
        if(!visible(AnnotationItem.Type.NOTE)&&!visible(AnnotationItem.Type.STAMP))
            throw new AccessDeniedException("Annotations disabled");
        try {
            var snapshot=read(ctx);
            if(snapshot.revision()!=revision)throw failure(EditException.Code.EDIT_CONFLICT);
            return filtered(snapshot);
        } catch(IOException e){throw storage(e);}
    }
    private AnnotationDocument filtered(AnnotationDocument document) {
        return new AnnotationDocument(document.mediaVersion(),document.page(),document.coordinateSpaceId(),document.revision(),
                document.items().stream().filter(item->visible(item.type())).toList());
    }
    public AnnotationDocument create(String id,int page,AnnotationChange change) {return write(id,page,null,change);}
    public AnnotationDocument replace(String id,int page,String annotationId,AnnotationChange change) {
        if(!PrivateFiles.uuid(annotationId))throw failure(EditException.Code.ANNOTATION_NOT_FOUND);
        return write(id,page,annotationId,change);
    }
    private synchronized AnnotationDocument write(String id,int page,String annotationId,AnnotationChange change) {
        var ctx=context(id,page,action(change.type()));identity(ctx,change.mediaVersion(),change.coordinateSpaceId(),change.expectedRevision());
        if(!bounds(ctx,change.x(),change.y(),change.width(),change.height()))throw failure(EditException.Code.INVALID_EDIT);
        String text=change.text();
        if(change.type()==AnnotationItem.Type.NOTE) {
            if(text==null||text.isBlank()||text.length()>limits.maxNoteLength()||change.stampId()!=null
                    ||text.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))
                throw failure(EditException.Code.INVALID_EDIT);
            text=text.replace("\r\n","\n").replace('\r','\n');
        } else if(text!=null||!PrivateFiles.uuid(change.stampId()))throw failure(EditException.Code.INVALID_EDIT);
        final String note=text;
        try {
            var before=read(ctx);
            var next=store.update(ctx.media,page,ctx.space,change.expectedRevision(),items->{
                recheck(ctx,action(change.type()));
                AnnotationItem previous=null;
                if(annotationId!=null) {
                    previous=items.stream().filter(i->i.id().equals(annotationId)).findFirst()
                            .orElseThrow(()->failure(EditException.Code.ANNOTATION_NOT_FOUND));
                    if(previous.type()!=change.type())throw failure(EditException.Code.INVALID_EDIT);
                } else if(items.size()>=limits.maxAnnotationsPerPage())throw failure(EditException.Code.EDIT_LIMIT_REACHED);
                String stampOwner=null;
                if(change.type()==AnnotationItem.Type.STAMP) {
                    if(previous!=null&&change.stampId().equals(previous.stampId())) {
                        stampOwner=previous.stampOwner();
                        try {stampStore.find(ctx.media.tenantId(),stampOwner,change.stampId());}
                        catch(IOException e){throw storage(e);}
                    } else {stamps.owned(change.stampId());stampOwner=actors.require().userId();}
                }
                var now=Instant.now();
                var item=new AnnotationItem(previous==null?UUID.randomUUID().toString():previous.id(),change.type(),
                        change.x(),change.y(),change.width(),change.height(),note,change.stampId(),stampOwner,
                        previous==null?now:previous.createdAt(),now);
                if(previous!=null)items.remove(previous);items.add(item);return items;
            });
            history.record(historyKey(ctx.media,ctx.page,ctx.space),before,next);
            return filtered(next);
        } catch(IOException e){throw storage(e);}
    }
    public synchronized AnnotationDocument delete(String id,int page,String annotationId,String version,String space,int revision) {
        if(!PrivateFiles.uuid(annotationId))throw failure(EditException.Code.ANNOTATION_NOT_FOUND);
        var ctx=context(id,page,MediaAction.VIEW);identity(ctx,version,space,revision);
        try {
            var before=read(ctx);
            var next=store.update(ctx.media,page,ctx.space,revision,items->{
                var item=items.stream().filter(i->i.id().equals(annotationId)).findFirst()
                        .orElseThrow(()->failure(EditException.Code.ANNOTATION_NOT_FOUND));
                recheck(ctx,action(item.type()));items.remove(item);return items;
            });
            history.record(historyKey(ctx.media,page,ctx.space),before,next);return filtered(next);
        } catch(IOException e){throw storage(e);}
    }
    private AnnotationHistoryStore.Key historyKey(MediaRecord record,int page,String space){
        var actor=actors.require();return new AnnotationHistoryStore.Key(actor.tenantId(),actor.userId(),record.mediaId(),record.mediaVersion(),page,space);
    }
    public record History(boolean enabled,boolean canUndo,boolean canRedo,int undoDepth,int redoDepth,String undoType,String redoType) {}
    public synchronized History historyStatus(String id,AnnotationDocument document){
        var record=media.require(id,MediaAction.VIEW);
        if(!limits.history().enabled()||!record.mediaVersion().equals(document.mediaVersion()))return new History(limits.history().enabled(),false,false,0,0,null,null);
        var state=history.state(historyKey(record,document.page(),document.coordinateSpaceId()),document.revision());var permitted=media.permissions(record);
        boolean undo=state.undoType()!=null&&permitted.contains(action(state.undoType())),redo=state.redoType()!=null&&permitted.contains(action(state.redoType()));
        return new History(true,undo,redo,undo?state.undoDepth():0,redo?state.redoDepth():0,
                undo?state.undoType().name().toLowerCase(Locale.ROOT):null,redo?state.redoType().name().toLowerCase(Locale.ROOT):null);
    }
    public synchronized AnnotationDocument history(String id,int page,String version,String space,int revision,boolean redo){
        var ctx=context(id,page,MediaAction.VIEW);identity(ctx,version,space,revision);
        if(!limits.history().enabled())throw new AccessDeniedException("Edit history disabled");
        try{
            var current=read(ctx);if(current.revision()!=revision)throw failure(EditException.Code.EDIT_CONFLICT);
            var key=historyKey(ctx.media,page,space);var step=history.step(key,revision,redo);
            recheck(ctx,action(step.type()));var expected=redo?step.before():step.after();var target=redo?step.after():step.before();
            String itemId=step.before()==null?step.after().id():step.before().id();int index=redo?step.afterIndex():step.beforeIndex();
            var next=store.update(ctx.media,page,space,revision,items->{
                recheck(ctx,action(step.type()));var item=items.stream().filter(i->i.id().equals(itemId)).findFirst().orElse(null);
                if(!sameContent(expected,item))throw failure(EditException.Code.EDIT_CONFLICT);
                if(item!=null)items.remove(item);
                if(target!=null){
                    if(!bounds(ctx,target.x(),target.y(),target.width(),target.height()))throw failure(EditException.Code.INVALID_EDIT);
                    if(items.size()>=limits.maxAnnotationsPerPage())throw failure(EditException.Code.EDIT_LIMIT_REACHED);
                    if(target.type()==AnnotationItem.Type.STAMP){try{stampStore.find(ctx.media.tenantId(),target.stampOwner(),target.stampId());}catch(IOException e){throw storage(e);}}
                    else if(target.text().isBlank()||target.text().length()>limits.maxNoteLength()
                            ||target.text().codePoints().anyMatch(c->Character.isISOControl(c)&&c!=10&&c!=13&&c!=9))throw failure(EditException.Code.INVALID_EDIT);
                    var restored=new AnnotationItem(target.id(),target.type(),target.x(),target.y(),target.width(),target.height(),target.text(),target.stampId(),target.stampOwner(),target.createdAt(),Instant.now());
                    items.add(Math.min(index,items.size()),restored);
                }
                return items;
            });
            history.applied(key,revision,next.revision(),redo);return filtered(next);
        }catch(IOException e){throw storage(e);}
    }
    private static boolean sameContent(AnnotationItem a,AnnotationItem b){
        if(a==null||b==null)return a==b;
        return a.sameContent(b);
    }
    public Path stampContent(String id,int page,String annotationId) {
        var ctx=context(id,page,MediaAction.VIEW);
        if(!visible(AnnotationItem.Type.STAMP))throw new AccessDeniedException("Stamps disabled");
        try {
            var item=read(ctx).items().stream().filter(i->i.id().equals(annotationId)&&i.type()==AnnotationItem.Type.STAMP)
                    .findFirst().orElseThrow(()->failure(EditException.Code.ANNOTATION_NOT_FOUND));
            return stampStore.preview(ctx.media.tenantId(),item.stampOwner(),item.stampId());
        } catch(IOException e){throw storage(e);}
    }
    private void recheck(Context ctx,MediaAction action) {
        var current=media.require(ctx.media.mediaId(),action);
        if(!current.mediaVersion().equals(ctx.media.mediaVersion()))throw failure(EditException.Code.EDIT_CONFLICT);
    }
    private static boolean bounds(Context ctx,double x,double y,double w,double h) {
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(w)&&Double.isFinite(h)
                &&x>=0&&y>=0&&w>=1&&h>=1&&x<=ctx.geometry.coordinateWidth()&&y<=ctx.geometry.coordinateHeight()
                &&w<=ctx.geometry.coordinateWidth()-x&&h<=ctx.geometry.coordinateHeight()-y;
    }
    private static EditException failure(EditException.Code code){return new EditException(code);}
    private static EditException storage(IOException e){return new EditException(EditException.Code.EDIT_STORAGE_UNAVAILABLE,e);}
}
