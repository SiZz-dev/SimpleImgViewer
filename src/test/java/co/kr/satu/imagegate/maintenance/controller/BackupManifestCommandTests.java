package co.kr.satu.imagegate.maintenance.controller;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.util.*;
import co.kr.satu.imagegate.editing.repository.AnnotationStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class BackupManifestCommandTests {
    @TempDir Path temp;
    final ByteArrayOutputStream out=new ByteArrayOutputStream(),error=new ByteArrayOutputStream();
    int run(String... args){out.reset();error.reset();return MaintenanceCommand.run(args,new PrintStream(out),new PrintStream(error));}
    String[] args(BackupManifestFixture f) throws Exception {return new String[]{"--verify-backup-manifest",
        "--originals-root",f.stores.originals.toString(),"--annotations-root",f.stores.annotations.toString(),
        "--stamps-root",f.stores.stamps.toString(),"--manifest",f.manifest.toString(),"--manifest-sha256",f.options().manifestSha256(),"--workers-stopped"};}
    @Test void helpIsReadOnlyAndDoesNotStartSpring(){
        assertThat(run("--verify-backup-manifest","--help")).isZero();assertThat(out.toString()).contains("read-only","--manifest-sha256").doesNotContain("Spring Boot");
        assertThat(run("--help")).isZero();assertThat(out.toString()).contains("--verify-backup-manifest");assertThat(temp.toFile().list()).isEmpty();
    }
    @Test void invalidMissingDuplicateAndMutationArgumentsAreRejected() throws Exception {
        var f=new BackupManifestFixture(temp);
        for(String[] suffix:List.of(new String[]{"--apply"},new String[]{"--delete"},new String[]{"--workers-stopped"},
                new String[]{"--max-entries","0"},new String[]{"--max-bytes","1099511627777"})){
            var bad=new ArrayList<>(List.of(args(f)));bad.addAll(List.of(suffix));assertThat(run(bad.toArray(String[]::new))).isEqualTo(2);
            assertThat(out.toString()).isEmpty();assertThat(error.toString()).doesNotContain(temp.toString());
        }
        assertThat(run(Arrays.copyOf(args(f),11))).isEqualTo(2);
        var bad=args(f);bad[2]="relative";assertThat(run(bad)).isEqualTo(2);
        bad=args(f);bad[10]="bad";assertThat(run(bad)).isEqualTo(2);
    }
    @Test void cleanMismatchAndPartialExitStatusesHaveNoPrivateMetadata() throws Exception {
        var f=new BackupManifestFixture(temp);assertThat(run(args(f))).isZero();
        assertThat(out.toString()).doesNotContain(temp.toString(),"privateTenant","privateOwner","sharedOwner","private note text","private-original.png",f.stores.media.sha256(),f.options().manifestSha256(),f.stores.media.mediaId());
        Files.delete(f.stores.page);assertThat(run(args(f))).isEqualTo(3);assertThat(out.toString()).contains("MISSING_ENTRY");
        var partial=new ArrayList<>(List.of(args(f)));partial.addAll(List.of("--max-entries","1"));
        assertThat(run(partial.toArray(String[]::new))).isEqualTo(3);assertThat(out.toString()).contains("\"limitReached\" : true");
    }
    @Test void busyWritersHaveExplicitPrivateStatus() throws Exception {
        var f=new BackupManifestFixture(temp);
        try(var writer=new AnnotationStore(f.stores.annotations)){assertThat(run(args(f))).isEqualTo(4);assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("STORAGE_IN_USE").doesNotContain(temp.toString());}
        assertThat(run(args(f))).isZero();
    }
    @Test void untrustedManifestDigestAndUnavailableStorageHaveFixedDiagnostics() throws Exception {
        var f=new BackupManifestFixture(temp);var options=args(f);Files.writeString(f.manifest,"tampered private content");
        assertThat(run(options)).isEqualTo(5);assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("MANIFEST_VERIFICATION_UNAVAILABLE").doesNotContain(temp.toString(),"private content","Exception");
        options=args(f);options[2]=temp.resolve("absent").toString();assertThat(run(options)).isEqualTo(5);
        assertThat(Files.exists(temp.resolve("absent"))).isFalse();
    }
}
