package co.kr.satu.imagegate.editing.repository;

import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.system.config.ImageGateProperties.EditHistory;
import java.time.*;
import java.util.*;

/** Bounded, non-persistent, actor-scoped deltas. Caller serializes mutations with AnnotationStore. */
public final class AnnotationHistoryStore {
    public record Key(String tenant,String user,String media,String version,int page,String space) {}
    public record Step(AnnotationItem before,int beforeIndex,AnnotationItem after,int afterIndex) {
        public AnnotationItem.Type type(){return before==null?after.type():before.type();}
    }
    public record State(int undoDepth,int redoDepth,AnnotationItem.Type undoType,AnnotationItem.Type redoType) {}
    private static final State EMPTY=new State(0,0,null,null);
    private static final class Timeline {
        final ArrayList<Step> steps=new ArrayList<>();int cursor,revision;Instant expires;
    }
    private final EditHistory policy;private final Clock clock;
    private final LinkedHashMap<Key,Timeline> timelines=new LinkedHashMap<>();
    public AnnotationHistoryStore(EditHistory policy,Clock clock){this.policy=policy;this.clock=clock;}
    private void expire(){var now=clock.instant();timelines.values().removeIf(t->!t.expires.isAfter(now));}
    private boolean sameActor(Key a,Key b){return a.tenant().equals(b.tenant())&&a.user().equals(b.user());}
    private Timeline create(Key key){
        while(timelines.keySet().stream().filter(k->sameActor(k,key)).count()>=policy.maxContextsPerUser()){
            var oldest=timelines.keySet().stream().filter(k->sameActor(k,key)).findFirst().orElseThrow();timelines.remove(oldest);
        }
        while(timelines.size()>=policy.maxContexts())timelines.remove(timelines.keySet().iterator().next());
        var timeline=new Timeline();timelines.put(key,timeline);return timeline;
    }
    private void touch(Key key,Timeline timeline){timeline.expires=clock.instant().plus(policy.ttl());timelines.remove(key);timelines.put(key,timeline);}
    public synchronized void record(Key key,AnnotationDocument before,AnnotationDocument after){
        if(!policy.enabled())return;expire();
        var changed=new LinkedHashSet<String>();before.items().forEach(i->changed.add(i.id()));after.items().forEach(i->changed.add(i.id()));
        Step step=null;
        for(String id:changed){AnnotationItem previous=before.items().stream().filter(i->i.id().equals(id)).findFirst().orElse(null),
                    next=after.items().stream().filter(i->i.id().equals(id)).findFirst().orElse(null);
            if(!Objects.equals(previous,next)){
                if(previous!=null&&previous.sameContent(next)&&before.items().indexOf(previous)==after.items().indexOf(next))continue;
                if(step!=null){timelines.remove(key);return;} // A future bulk operation must define its own bounded history.
                step=new Step(previous,previous==null?-1:before.items().indexOf(previous),next,next==null?-1:after.items().indexOf(next));
            }
        }
        if(step==null){var existing=timelines.get(key);
            if(existing!=null&&existing.revision==before.revision()){existing.revision=after.revision();touch(key,existing);}
            else timelines.remove(key);return;
        }
        var timeline=timelines.get(key);
        if(timeline==null)timeline=create(key);
        if(timeline.revision!=before.revision()){timeline.steps.clear();timeline.cursor=0;}
        timeline.steps.subList(timeline.cursor,timeline.steps.size()).clear();timeline.steps.add(step);
        if(timeline.steps.size()>policy.maxSteps())timeline.steps.remove(0);
        timeline.cursor=timeline.steps.size();timeline.revision=after.revision();touch(key,timeline);
    }
    public synchronized State state(Key key,int revision){
        if(!policy.enabled())return EMPTY;expire();var timeline=timelines.get(key);
        if(timeline==null||timeline.revision!=revision)return EMPTY;
        return new State(timeline.cursor,timeline.steps.size()-timeline.cursor,
                timeline.cursor==0?null:timeline.steps.get(timeline.cursor-1).type(),
                timeline.cursor==timeline.steps.size()?null:timeline.steps.get(timeline.cursor).type());
    }
    public synchronized Step step(Key key,int revision,boolean redo){
        expire();var timeline=timelines.get(key);
        if(!policy.enabled()||timeline==null||timeline.revision!=revision||(!redo&&timeline.cursor==0)||(redo&&timeline.cursor==timeline.steps.size()))
            throw new EditException(EditException.Code.EDIT_HISTORY_UNAVAILABLE);
        return timeline.steps.get(redo?timeline.cursor:timeline.cursor-1);
    }
    public synchronized void applied(Key key,int previousRevision,int nextRevision,boolean redo){
        var timeline=timelines.get(key);
        if(timeline==null||timeline.revision!=previousRevision)throw new IllegalStateException("History mutation order");
        timeline.cursor+=redo?1:-1;timeline.revision=nextRevision;touch(key,timeline);
    }
}
