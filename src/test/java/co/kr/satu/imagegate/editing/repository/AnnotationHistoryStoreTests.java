package co.kr.satu.imagegate.editing.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.system.config.ImageGateProperties.EditHistory;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnnotationHistoryStoreTests {
    final Instant now=Instant.parse("2026-10-22T00:00:00Z");
    final AnnotationHistoryStore.Key key=new AnnotationHistoryStore.Key("tenant","alice","media","version",1,"space");
    static final class MutableClock extends Clock {
        Instant value;MutableClock(Instant value){this.value=value;}
        public Instant instant(){return value;}public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}
    }
    AnnotationItem item(String text){return new AnnotationItem("id",AnnotationItem.Type.NOTE,1,2,3,4,text,null,null,now,now);}
    AnnotationDocument doc(int revision,AnnotationItem... items){return new AnnotationDocument("version",1,"space",revision,List.of(items));}
    AnnotationHistoryStore store(int steps,int contexts,int perUser,Clock clock){return new AnnotationHistoryStore(new EditHistory(true,steps,contexts,perUser,Duration.ofMinutes(30)),clock);}
    @Test void maintainsUndoRedoAndMonotonicallyAdvancedRevisions(){
        var store=store(20,10,3,Clock.fixed(now,ZoneOffset.UTC));
        store.record(key,doc(0),doc(1,item("first")));store.record(key,doc(1,item("first")),doc(2,item("second")));
        assertThat(store.step(key,2,false).before().text()).isEqualTo("first");store.applied(key,2,3,false);
        assertThat(store.state(key,3).undoDepth()).isEqualTo(1);assertThat(store.state(key,3).redoDepth()).isEqualTo(1);
        store.applied(key,3,4,false);assertThat(store.state(key,4).undoDepth()).isZero();
        assertThat(store.step(key,4,true).after().text()).isEqualTo("first");store.applied(key,4,5,true);
        assertThat(store.state(key,5).undoDepth()).isEqualTo(1);
    }
    @Test void boundsStepsAndDropsRedoAfterNewMutation(){
        var store=store(2,10,3,Clock.fixed(now,ZoneOffset.UTC));
        store.record(key,doc(0),doc(1,item("a")));store.record(key,doc(1,item("a")),doc(2,item("b")));store.record(key,doc(2,item("b")),doc(3,item("c")));
        assertThat(store.state(key,3).undoDepth()).isEqualTo(2);store.applied(key,3,4,false);
        store.record(key,doc(4,item("b")),doc(5,item("new")));
        assertThat(store.state(key,5).undoDepth()).isEqualTo(2);assertThat(store.state(key,5).redoDepth()).isZero();
    }
    @Test void isolatesActorPageAndVersionAndDoesNotEraseOnStaleRead(){
        var store=store(20,10,3,Clock.fixed(now,ZoneOffset.UTC));store.record(key,doc(0),doc(1,item("a")));
        for(var other:List.of(new AnnotationHistoryStore.Key("other","alice","media","version",1,"space"),
                new AnnotationHistoryStore.Key("tenant","bob","media","version",1,"space"),new AnnotationHistoryStore.Key("tenant","alice","media","other",1,"space"),new AnnotationHistoryStore.Key("tenant","alice","other","version",1,"space"),new AnnotationHistoryStore.Key("tenant","alice","media","version",1,"other"),
                new AnnotationHistoryStore.Key("tenant","alice","media","version",2,"space"))){
            assertThat(store.state(other,1).undoDepth()).isZero();assertThatThrownBy(()->store.step(other,1,false)).isInstanceOf(EditException.class);
        }
        assertThat(store.state(key,0).undoDepth()).isZero();assertThat(store.state(key,1).undoDepth()).isEqualTo(1);
        store.record(key,doc(3,item("external")),doc(4,item("mine")));
        assertThat(store.state(key,4).undoDepth()).isEqualTo(1);assertThat(store.step(key,4,false).before().text()).isEqualTo("external");
    }
    @Test void expiresWithoutExtendingOnGetAndBoundsGlobalAndActorContexts(){
        var clock=new MutableClock(now);var store=store(20,2,1,clock);
        store.record(key,doc(0),doc(1,item("a")));
        var page2=new AnnotationHistoryStore.Key("tenant","alice","media","version",2,"space");store.record(page2,doc(0),doc(1,item("b")));
        assertThat(store.state(key,1).undoDepth()).isZero();
        var bob=new AnnotationHistoryStore.Key("tenant","bob","media","version",1,"space");store.record(bob,doc(0),doc(1,item("c")));
        var carol=new AnnotationHistoryStore.Key("tenant","carol","media","version",1,"space");store.record(carol,doc(0),doc(1,item("d")));
        assertThat(store.state(page2,1).undoDepth()).isZero();assertThat(store.state(bob,1).undoDepth()).isEqualTo(1);
        clock.value=now.plus(Duration.ofMinutes(29));assertThat(store.state(bob,1).undoDepth()).isEqualTo(1);
        clock.value=now.plus(Duration.ofMinutes(30));assertThat(store.state(bob,1).undoDepth()).isZero();
    }
    @Test void disabledAndRestartedHistoryHaveNoCommands(){
        var store=new AnnotationHistoryStore(new EditHistory(false,20,128,8,Duration.ofMinutes(30)),Clock.fixed(now,ZoneOffset.UTC));
        store.record(key,doc(0),doc(1,item("a")));assertThat(store.state(key,1).undoDepth()).isZero();
        assertThatThrownBy(()->store.step(key,1,false)).isInstanceOf(EditException.class);
        assertThat(store(20,128,8,Clock.fixed(now,ZoneOffset.UTC)).state(key,1).undoDepth()).isZero();
    }
    @Test void unchangedSaveDoesNotConsumeAHistoryStepOrDiscardRedo(){
        var store=store(20,10,3,Clock.fixed(now,ZoneOffset.UTC));store.record(key,doc(0),doc(1,item("a")));
        var unchanged=new AnnotationItem("id",AnnotationItem.Type.NOTE,1,2,3,4,"a",null,null,now,now.plusSeconds(1));
        store.record(key,doc(1,item("a")),doc(2,unchanged));assertThat(store.state(key,2).undoDepth()).isEqualTo(1);
        store.applied(key,2,3,false);store.record(key,doc(3),doc(4));
        assertThat(store.state(key,4).undoDepth()).isZero();assertThat(store.state(key,4).redoDepth()).isEqualTo(1);
    }
}
