package co.kr.satu.imagegate.maintenance.controller;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
class OriginalIntegrityCommandTests {
    @TempDir Path temp;
    final ByteArrayOutputStream out=new ByteArrayOutputStream(),error=new ByteArrayOutputStream();
    int run(String... args){out.reset();error.reset();return MaintenanceCommand.run(args,new PrintStream(out),new PrintStream(error));}
    @Test void helpIsReadOnlyAndRoutedWithoutStartingSpring() {
        assertThat(run("--verify-originals","--help")).isZero();assertThat(out.toString()).contains("read-only").doesNotContain("Spring Boot");
        assertThat(temp.toFile().list()).isEmpty();
    }
    @Test void invalidOptionsIncludingApplyAndMissingStopAcknowledgementAreRejected() {
        for(var args:List.of(new String[]{"--verify-originals","--originals-root",temp.toString()},
                new String[]{"--verify-originals","--originals-root","relative","--workers-stopped"},
                new String[]{"--verify-originals","--originals-root",temp.toString(),"--workers-stopped","--apply"},
                new String[]{"--verify-originals","--originals-root",temp.toString(),"--workers-stopped","--max-bytes","0"},
                new String[]{"--verify-originals","--originals-root",temp.toString(),"--workers-stopped","--max-files","10001"},
                new String[]{"--verify-originals","--workers-stopped","--workers-stopped"})) {
            assertThat(run(args)).isEqualTo(2);assertThat(error.toString()).doesNotContain(temp.toString());assertThat(out.toString()).isEmpty();
        }
    }
    @Test void successfulAndDamagedReportsHaveDifferentStatusesAndNoBusinessDetails() throws Exception {
        Path root=temp.resolve("private-originals");var store=new LocalMediaStore(root,1024);
        byte[] wav=new byte[24];System.arraycopy("RIFF".getBytes(),0,wav,0,4);System.arraycopy("WAVE".getBytes(),0,wav,8,4);
        var media=store.register("privateTenant","privateOwner","confidential-name.wav",new ByteArrayInputStream(wav));
        String[] args={"--verify-originals","--originals-root",root.toString(),"--workers-stopped"};
        assertThat(run(args)).isZero();var report=JsonMapper.builder().build().readTree(out.toString());assertThat(report.get("verified").asInt()).isEqualTo(1);
        assertThat(out.toString()).doesNotContain(root.toString(),"privateTenant","privateOwner","confidential",media.sha256());
        wav[23]=42;Files.write(store.resolveOriginal(media),wav);assertThat(run(args)).isEqualTo(3);assertThat(out.toString()).contains("CHECKSUM_MISMATCH");
        assertThat(run("--verify-originals","--originals-root",root.toString(),"--workers-stopped","--max-bytes","1")).isEqualTo(3);assertThat(out.toString()).contains("\"limitReached\" : true");
    }
    @Test void missingRootHasSanitizedUnavailableStatusAndIsNotCreated() {
        Path missing=temp.resolve("missing");assertThat(run("--verify-originals","--originals-root",missing.toString(),"--workers-stopped")).isEqualTo(5);
        assertThat(error.toString()).contains("INTEGRITY_UNAVAILABLE").doesNotContain(missing.toString());assertThat(Files.exists(missing)).isFalse();
    }

    @Test void liveQuotaWriterHasExplicitBusyStatusAndReleasesForNextInspection() throws Exception {
        Path root=temp.resolve("private-originals");
        try(var writer=new LocalMediaStore(root,1024,0,1024)){
            assertThat(run("--verify-originals","--originals-root",root.toString(),"--workers-stopped")).isEqualTo(4);
            assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("STORAGE_IN_USE").doesNotContain(root.toString(),"Exception");
        }
        assertThat(run("--verify-originals","--originals-root",root.toString(),"--workers-stopped")).isZero();
        assertThat(Files.size(root.resolve(".quota-writer.lock"))).isZero();
    }
    @Test void invalidMarkerFailsClosedWithNoJsonOrPrivatePath() throws Exception {
        Path root=Files.createDirectory(temp.resolve("private-originals"));Path marker=Files.writeString(root.resolve(".quota-writer.lock"),"private marker content");
        assertThat(run("--verify-originals","--originals-root",root.toString(),"--workers-stopped")).isEqualTo(5);
        assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("INTEGRITY_UNAVAILABLE").doesNotContain(root.toString(),"private marker","Exception");
        assertThat(Files.readString(marker)).isEqualTo("private marker content");
    }

    @Test void separateJvmWriterProducesBusyStatusAndInspectionRecoversAfterExit() throws Exception {
        Path root=Files.createDirectory(temp.resolve("private-originals"));Path marker=Files.createFile(root.resolve(".quota-writer.lock"));
        Class<?> fixture=co.kr.satu.imagegate.maintenance.util.OriginalLockProcessFixture.class;
        String classpath=Path.of(fixture.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Process process=new ProcessBuilder(executable,"-cp",classpath,fixture.getName(),marker.toString()).redirectErrorStream(true).start();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        try{
            var reader=new BufferedReader(new InputStreamReader(process.getInputStream(),java.nio.charset.StandardCharsets.UTF_8));
            assertThat(executor.submit((java.util.concurrent.Callable<String>)reader::readLine).get(10,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("LOCKED");
            assertThat(run("--verify-originals","--originals-root",root.toString(),"--workers-stopped")).isEqualTo(4);
            assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("STORAGE_IN_USE").doesNotContain(root.toString());
            process.getOutputStream().close();assertThat(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }finally{
            try{process.getOutputStream().close();}
            finally{
                if(process.isAlive()){process.destroy();if(!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS);}}
                executor.shutdownNow();
            }
        }
        assertThat(run("--verify-originals","--originals-root",root.toString(),"--workers-stopped")).isZero();
        try(var files=Files.list(root)){assertThat(files.map(path->path.getFileName().toString()).toList()).containsExactly(".quota-writer.lock");}
    }
}
