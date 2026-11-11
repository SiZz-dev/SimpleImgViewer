package co.kr.satu.imagegate.editing.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.media.domain.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnnotationStoreTests {
    @TempDir Path root;
    final String id=UUID.randomUUID().toString(),version=UUID.randomUUID().toString();
    MediaRecord media(String tenant,String version) {
        return new MediaRecord(id,tenant,"owner",version,"image.png",MediaType.IMAGE,"image/png",10,"a".repeat(64),"original.png",Instant.now());
    }
    String space(String version,int page){return "original:"+version+":"+page+":upright-v1";}
    AnnotationItem note() {
        var now=Instant.now();
        return new AnnotationItem(UUID.randomUUID().toString(),AnnotationItem.Type.NOTE,1,2,30,20,"저장 메모",null,null,now,now);
    }
    @Test void restartRetainsDataAndEmptyDeletionRevision() throws Exception {
        var media=media("tenant",version);AnnotationDocument written;
        try(var store=new AnnotationStore(root)) {
            written=store.update(media,1,space(version,1),0,items->{items.add(note());return items;});
        }
        try(var store=new AnnotationStore(root)) {
            assertThat(store.read(media,1,space(version,1))).isEqualTo(written);
            store.update(media,1,space(version,1),1,items->List.of());
        }
        try(var store=new AnnotationStore(root)) {
            var empty=store.read(media,1,space(version,1));
            assertThat(empty.items()).isEmpty();assertThat(empty.revision()).isEqualTo(2);
            assertThatThrownBy(()->store.update(media,1,space(version,1),0,items->items)).isInstanceOf(EditException.class);
        }
    }
    @Test void exactlyOneConcurrentWriterCanCommitTheSameRevision() throws Exception {
        var media=media("tenant",version);
        try(var store=new AnnotationStore(root);var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);
            Callable<Boolean> task=()->{start.await();try {
                store.update(media,1,space(version,1),0,items->{items.add(note());return items;});return true;
            } catch(EditException conflict){return false;}};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            assertThat(store.read(media,1,space(version,1)).revision()).isEqualTo(1);
            assertThat(store.read(media,1,space(version,1)).items()).hasSize(1);
        }
    }
    @Test void tenantsPagesAndMediaVersionsHaveIndependentSnapshots() throws Exception {
        try(var store=new AnnotationStore(root)) {
            store.update(media("tenant",version),1,space(version,1),0,items->{items.add(note());return items;});
            String otherVersion=UUID.randomUUID().toString();
            assertThat(store.read(media("Tenant",version),1,space(version,1)).items()).isEmpty();
            assertThat(store.read(media("tenant",version),2,space(version,2)).items()).isEmpty();
            assertThat(store.read(media("tenant",otherVersion),1,space(otherVersion,1)).items()).isEmpty();
            assertThatThrownBy(()->store.read(media("tenant",version),1,"wrong-space")).isInstanceOf(IOException.class);
        }
    }
    @Test void corruptedSnapshotCannotBeReturnedAsAValidDocument() throws Exception {
        var media=media("tenant",version);
        try(var store=new AnnotationStore(root)) {
            store.update(media,1,space(version,1),0,items->{items.add(note());return items;});
            Path file;
            try(var paths=Files.walk(root)){file=paths.filter(p->p.getFileName().toString().equals("page-1.properties")).findFirst().orElseThrow();}
            Files.writeString(file,"revision=1\ncount=999999\n");
            assertThatThrownBy(()->store.read(media,1,space(version,1))).isInstanceOf(IOException.class);
        }
    }
    @Test void refusesASecondStorageWriterUntilTheFirstCloses() throws Exception {
        try(var store=new AnnotationStore(root)) {
            assertThatThrownBy(()->new AnnotationStore(root)).isInstanceOf(Exception.class);
        }
        try(var reopened=new AnnotationStore(root)) {
            assertThat(reopened.read(media("tenant",version),1,space(version,1)).revision()).isZero();
        }
    }
}
