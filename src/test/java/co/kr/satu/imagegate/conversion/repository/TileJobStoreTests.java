package co.kr.satu.imagegate.conversion.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.domain.TileJobStatus;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class TileJobStoreTests {
    @TempDir Path temp;
    @Test void persistsBoundsAndRejectsUnknownInventory()throws Exception{
        var store=new TileJobStore(temp,1);String key="a".repeat(64);assertThat(store.read(key,1).state()).isEqualTo("not_requested");
        var state=new TileJobStatus(key,1,"running",null);store.write(state);assertThat(new TileJobStore(temp,1).read(key,1)).isEqualTo(state);
        assertThatThrownBy(()->store.write(new TileJobStatus("b".repeat(64),1,"queued",null))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->store.read(key,2)).isInstanceOf(IOException.class);
        Files.writeString(temp.resolve("tile-jobs-v1/unknown.txt"),"preserve");assertThatThrownBy(()->new TileJobStore(temp,1)).isInstanceOf(IOException.class);
    }
    @Test void rejectsCorruptionTraversalAndInvalidStates()throws Exception{
        var store=new TileJobStore(temp,2);String key="a".repeat(64);
        assertThatThrownBy(()->store.read("../bad",1)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->store.write(new TileJobStatus(key,1,"ready","TILE_FAILED"))).isInstanceOf(IOException.class);
        Files.writeString(temp.resolve("tile-jobs-v1/"+key+".properties"),"page=bad\nkey="+key+"\nstate=running\n");
        assertThatThrownBy(()->store.read(key,1)).isInstanceOf(IOException.class);
    }
    @Test void nativeBusySurvivesRestartAndIsNotAutomaticallyRecovered()throws Exception{
        String key="a".repeat(64);var state=new TileJobStatus(key,1,"failed","CONVERSION_BUSY");var store=new TileJobStore(temp,2);store.write(state);
        var reopened=new TileJobStore(temp,2);assertThat(reopened.recoverInterrupted()).isZero();assertThat(reopened.read(key,1)).isEqualTo(state);
        assertThatThrownBy(()->store.write(new TileJobStatus(key,1,"failed","CONVERSION_private-path"))).isInstanceOf(IOException.class);
    }
    @Test void refusesUnknownOrDuplicatePropertiesBeforeHistoryCanAdoptThem()throws Exception{
        var store=new TileJobStore(temp,2);String key="a".repeat(64);Path file=temp.resolve("tile-jobs-v1/"+key+".properties");
        for(String extra:new String[]{"private=preserve\n","state=failed\n"}){
            String bytes="key="+key+"\npage=1\nstate=ready\n"+extra;Files.writeString(file,bytes);
            assertThatThrownBy(store::snapshots).isInstanceOf(IOException.class);assertThat(Files.readString(file)).isEqualTo(bytes);
        }
    }
}
