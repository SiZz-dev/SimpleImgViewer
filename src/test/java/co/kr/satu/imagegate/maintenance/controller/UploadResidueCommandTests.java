package co.kr.satu.imagegate.maintenance.controller;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
class UploadResidueCommandTests {
    @TempDir Path temp;
    final ByteArrayOutputStream out=new ByteArrayOutputStream(),error=new ByteArrayOutputStream();
    int run(String...args){out.reset();error.reset();return MaintenanceCommand.run(args,new PrintStream(out),new PrintStream(error));}
    String[] args(Path root){return new String[]{"--inspect-upload-residue","--originals-root",root.toString(),"--workers-stopped"};}
    @Test void helpRoutesWithoutStartingSpringOrCreatingStorage() {
        assertThat(run("--inspect-upload-residue","--help")).isZero();assertThat(out.toString()).contains("read-only","not deletion approval").doesNotContain("Spring Boot");
        assertThat(temp.toFile().list()).isEmpty();assertThat(run("--help")).isZero();assertThat(out.toString()).contains("--inspect-upload-residue");
    }
    @Test void missingStopApplyDuplicatesRelativePathsAndInvalidLimitsAreRejectedWithoutEcho() {
        for(String[] bad:List.of(
            new String[]{"--inspect-upload-residue","--originals-root",temp.toString()},
            new String[]{"--inspect-upload-residue","--originals-root","relative","--workers-stopped"},
            new String[]{"--inspect-upload-residue","--originals-root",temp.toString(),"--workers-stopped","--apply"},
            new String[]{"--inspect-upload-residue","--originals-root",temp.toString(),"--workers-stopped","--max-bytes","0"},
            new String[]{"--inspect-upload-residue","--originals-root",temp.toString(),"--workers-stopped","--max-bytes","1125899906842625"},
            new String[]{"--inspect-upload-residue","--originals-root",temp.toString(),"--workers-stopped","--max-items","10001"},
            new String[]{"--inspect-upload-residue","--originals-root",temp.toString(),"--workers-stopped","--max-entries","100001"},
            new String[]{"--inspect-upload-residue","--originals-root",temp.resolve("x/../y").toString(),"--workers-stopped"},
            new String[]{"--inspect-upload-residue","--workers-stopped","--workers-stopped"})){
            assertThat(run(bad)).isEqualTo(2);assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("INVALID_OPTIONS").doesNotContain(temp.toString());
        }
    }
    @Test void cleanFindingsAndPartialReportsHaveExplicitStatusesWithoutBusinessContent() throws Exception {
        Path root=Files.createDirectory(temp.resolve("secret-path"));assertThat(run(args(root))).isZero();
        try(var store=new LocalMediaStore(root,1024)){store.register("privateTenant","privateOwner","confidential.png",new ByteArrayInputStream(new byte[]{(byte)137,80,78,71,13,10,26,10}));}
        assertThat(run(args(root))).isZero();
        Path stage=Files.createDirectories(root.resolve("tenant-"+"a".repeat(64)).resolve(".upload-"+UUID.randomUUID()));
        Files.writeString(stage.resolve("pending"),"private payload");
        assertThat(run(args(root))).isEqualTo(3);var json=JsonMapper.builder().build().readTree(out.toString());
        assertThat(json.get("recognizedResidues").asInt()).isEqualTo(1);assertThat(json.get("limitReached").asBoolean()).isFalse();
        assertThat(out.toString()).doesNotContain(root.toString(),"secret-path","privateTenant","privateOwner","confidential","private payload","metadata.properties");
        assertThat(error.toString()).isEmpty();
        var limited=new ArrayList<String>(List.of(args(root)));limited.addAll(List.of("--max-bytes","1"));
        assertThat(run(limited.toArray(String[]::new))).isEqualTo(3);assertThat(out.toString()).contains("\"limitReached\" : true");
    }
    @Test void liveQuotaWriterIsRefusedWithSanitizedBusyStatus() throws Exception {
        Path root=temp.resolve("private-root");
        try(var store=new LocalMediaStore(root,1024,0,1024)){
            assertThat(run(args(root))).isEqualTo(4);assertThat(out.toString()).isEmpty();
            assertThat(error.toString()).contains("STORAGE_IN_USE").doesNotContain(root.toString());
        }
        assertThat(run(args(root))).isZero();
    }
    @Test void missingRootDoesNotStartServerOrCreateDirectoryAndHasSanitizedError() {
        Path missing=temp.resolve("private-missing");assertThat(run(args(missing))).isEqualTo(5);assertThat(out.toString()).isEmpty();
        assertThat(error.toString()).contains("RESIDUE_INSPECTION_UNAVAILABLE").doesNotContain(missing.toString(),"Exception");assertThat(Files.exists(missing)).isFalse();
    }
}
