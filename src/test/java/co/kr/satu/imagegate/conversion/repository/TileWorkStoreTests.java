package co.kr.satu.imagegate.conversion.repository;
import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
class TileWorkStoreTests {
    @TempDir Path temp;
    @Test void skipsActiveAndRecoversOnlyDurablyOwnedWorkAfterRestart()throws Exception{
        var store=new TileWorkStore(temp,100);var work=store.begin("a".repeat(64));Files.createDirectories(work.path().resolve("output/mipmap_files/0"));Files.writeString(work.path().resolve("output/mipmap_files/0/0_0.png"),"partial native data");
        long now=System.currentTimeMillis()+Duration.ofHours(2).toMillis();assertThat(store.maintain(32,1000,now,true).removed()).isZero();
        var restarted=new TileWorkStore(temp,100);var baseline=restarted.maintain(32,1000,now,false);assertThat(baseline.retainedBytes()).isPositive();assertThat(baseline.removed()).isZero();
        assertThat(restarted.maintain(32,1000,now,true).removed()).isEqualTo(1);assertThat(work.path()).doesNotExist();
    }
    @Test void preservesLegacyUnknownAndMalformedOwnership()throws Exception{
        var store=new TileWorkStore(temp,100);Path legacy=Files.createDirectory(temp.resolve("tile-work-v1/job-"+UUID.randomUUID()));Files.writeString(legacy.resolve("retain.txt"),"retain");
        var work=store.begin("a".repeat(64));Files.createDirectory(work.path());Files.writeString(work.path().resolve("unknown.txt"),"private");assertThatThrownBy(()->store.finish(work)).isInstanceOf(IOException.class);
        Path malformed=temp.resolve("tile-work-v1/job-"+UUID.randomUUID()+".owner");Files.writeString(malformed,"invalid");
        var report=new TileWorkStore(temp,100).maintain(32,1,System.currentTimeMillis()+100000,true);assertThat(report.removed()).isZero();assertThat(report.skipped()).isGreaterThanOrEqualTo(3);
        assertThat(Files.readString(legacy.resolve("retain.txt"))).isEqualTo("retain");assertThat(Files.readString(work.path().resolve("unknown.txt"))).isEqualTo("private");
    }
    @Test void ownerOnlyCrashAndNormalCompletionRemoveOnlyOwnRecords()throws Exception{
        var store=new TileWorkStore(temp,100);var work=store.begin("a".repeat(64));store.finish(work);
        try(var files=Files.list(temp.resolve("tile-work-v1"))){assertThat(files.toList()).isEmpty();}
        store.begin("b".repeat(64));assertThat(new TileWorkStore(temp,100).maintain(32,1,System.currentTimeMillis()+1000,true).removed()).isEqualTo(1);
    }
    @Test void refusesJunctionWithoutDeletingOutsideContent()throws Exception{
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));var store=new TileWorkStore(temp,100);var work=store.begin("a".repeat(64));
        Path outside=Files.createDirectory(temp.resolve("outside"));Files.writeString(outside.resolve("retain.txt"),"retain");
        var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",work.path().toString(),outside.toString()).redirectErrorStream(true).start();assertThat(process.waitFor()).isZero();
        try{assertThat(new TileWorkStore(temp,100).maintain(32,1,System.currentTimeMillis()+10000,true).removed()).isZero();assertThat(Files.readString(outside.resolve("retain.txt"))).isEqualTo("retain");}
        finally{Files.delete(work.path());}
    }
}
