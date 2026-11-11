package co.kr.satu.imagegate.maintenance.controller;
import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
class MaintenanceCommandTests {
    @TempDir Path root;
    final ByteArrayOutputStream output=new ByteArrayOutputStream(),errors=new ByteArrayOutputStream();
    int run(String...args){output.reset();errors.reset();return MaintenanceCommand.run(args,new PrintStream(output),new PrintStream(errors));}
    @Test void helpDoesNotStartSpringOrTouchStorage(){assertThat(run("--help")).isZero();assertThat(output.toString()).contains("default: dry-run").doesNotContain("Spring Boot");}
    @Test void malformedOrUnacknowledgedApplyOptionsAreRejectedBeforeStorageAccess(){
        for(String[] args:List.of(new String[]{"--converted-root",root.toString(),"--apply"},new String[]{"--converted-root","relative"},new String[]{"--converted-root",root.toString(),"--min-age-hours","0"},
            new String[]{"--converted-root",root.toString(),"--max-candidates","1001"},new String[]{"--converted-root",root.toString(),"--max-entries","100001"},new String[]{"--converted-root",root.toString(),"--apply","--apply","--workers-stopped"},new String[]{"--unknown","secret"})){
            assertThat(run(args)).isEqualTo(2);assertThat(errors.toString()).doesNotContain(root.toString(),"secret");assertThat(output.toString()).isEmpty();
        }
    }
    Path fixture() throws Exception {
        var preview=Files.createDirectory(root.resolve("previews-v1"));Files.createFile(preview.resolve("worker.lock"));var key=Files.createDirectory(preview.resolve("a".repeat(64)));
        Files.writeString(key.resolve("state.properties"),"key="+"a".repeat(64)+"\npage=1\nstate=failed\n");
        var tmp=Files.writeString(key.resolve("state-"+UUID.randomUUID()+".tmp"),"fixture");Files.setLastModifiedTime(tmp,FileTime.from(Instant.now().minus(Duration.ofHours(48))));return tmp;
    }
    @Test void dryRunThenExplicitApplyReturnPrivateBoundedJson() throws Exception {
        var tmp=fixture();assertThat(run("--converted-root",root.toString())).isZero();var json=JsonMapper.builder().build();var report=json.readTree(output.toString());
        assertThat(report.get("apply").asBoolean()).isFalse();assertThat(report.get("items").size()).isEqualTo(1);assertThat(Files.exists(tmp)).isTrue();assertThat(output.toString()).doesNotContain(root.toString());
        assertThat(run("--converted-root",root.toString(),"--apply","--workers-stopped")).isZero();assertThat(Files.exists(tmp)).isFalse();assertThat(json.readTree(output.toString()).get("deleted").asInt()).isEqualTo(1);
    }
    @Test void busyCacheAndMissingStorageHaveDistinctSanitizedExitCodes() throws Exception {
        fixture();try(var channel=FileChannel.open(root.resolve("previews-v1/worker.lock"),StandardOpenOption.WRITE);var lock=channel.lock()){
            assertThat(run("--converted-root",root.toString())).isEqualTo(4);assertThat(errors.toString()).contains("STORAGE_IN_USE").doesNotContain(root.toString());
        }
        assertThat(run("--converted-root",root.resolve("absent").toString())).isEqualTo(5);assertThat(errors.toString()).contains("MAINTENANCE_UNAVAILABLE").doesNotContain(root.toString());
    }
}
