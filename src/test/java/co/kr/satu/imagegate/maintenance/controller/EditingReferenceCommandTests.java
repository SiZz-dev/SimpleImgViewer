package co.kr.satu.imagegate.maintenance.controller;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.util.EditingReferenceFixture;
import co.kr.satu.imagegate.editing.repository.AnnotationStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
class EditingReferenceCommandTests {
    @TempDir Path temp;
    final ByteArrayOutputStream out=new ByteArrayOutputStream(),error=new ByteArrayOutputStream();
    int run(String...args){out.reset();error.reset();return MaintenanceCommand.run(args,new PrintStream(out),new PrintStream(error));}
    String[] args(EditingReferenceFixture f){return new String[]{"--verify-editing-references","--originals-root",f.originals.toString(),"--annotations-root",f.annotations.toString(),"--stamps-root",f.stamps.toString(),"--workers-stopped"};}
    @Test void helpDoesNotReadConfigurationCreateStorageOrStartSpring(){
        assertThat(run("--verify-editing-references","--help")).isZero();assertThat(out.toString()).contains("read-only").doesNotContain("Spring Boot");assertThat(temp.toFile().list()).isEmpty();
        assertThat(run("--help")).isZero();assertThat(out.toString()).contains("--verify-editing-references");
    }
    @Test void invalidRootsStopAcknowledgementMutationsAndLimitsAreRejected() throws Exception {
        var f=new EditingReferenceFixture(temp);
        var valid=new ArrayList<>(List.of(args(f)));
        for(String[] suffix:List.of(new String[]{"--apply"},new String[]{"--delete"},new String[]{"--workers-stopped"},new String[]{"--max-items","0"},new String[]{"--max-items","10001"},
                new String[]{"--max-entries","100001"},new String[]{"--max-bytes","0"},new String[]{"--max-bytes","1099511627777"})){
            var bad=new ArrayList<>(valid);bad.addAll(List.of(suffix));assertThat(run(bad.toArray(String[]::new))).isEqualTo(2);assertThat(out.toString()).isEmpty();assertThat(error.toString()).doesNotContain(temp.toString());
        }
        for(String[] bad:List.of(Arrays.copyOf(args(f),7),new String[]{"--verify-editing-references","--originals-root","relative","--annotations-root",f.annotations.toString(),"--stamps-root",f.stamps.toString(),"--workers-stopped"},
                new String[]{"--verify-editing-references","--originals-root",f.originals.toString(),"--annotations-root",f.originals.resolve("nested").toString(),"--stamps-root",f.stamps.toString(),"--workers-stopped"}))assertThat(run(bad)).isEqualTo(2);
    }
    @Test void validMissingAndPartialResultsHaveExplicitStatusesAndNeverExposePrivateFields() throws Exception {
        var f=new EditingReferenceFixture(temp);assertThat(run(args(f))).isZero();var json=JsonMapper.builder().build().readTree(out.toString());
        assertThat(json.get("validPages").asInt()).isEqualTo(2);assertThat(json.get("validStamps").asInt()).isEqualTo(1);
        assertThat(out.toString()).doesNotContain(temp.toString(),EditingReferenceFixture.TENANT,EditingReferenceFixture.OWNER,EditingReferenceFixture.STAMP_OWNER,"private note text","private-original.png","private-stamp.png",f.media.sha256());
        Files.delete(f.stampEntry.resolve("preview.png"));assertThat(run(args(f))).isEqualTo(3);assertThat(out.toString()).contains("INVALID_STAMP_REFERENCE").doesNotContain("private note text");
        var limited=new ArrayList<>(List.of(args(f)));limited.addAll(List.of("--max-entries","1"));assertThat(run(limited.toArray(String[]::new))).isEqualTo(3);assertThat(out.toString()).contains("\"limitReached\" : true");
    }
    @Test void writerBusyStatusDoesNotLeakPathsAndReleasesOtherLocks() throws Exception {
        var f=new EditingReferenceFixture(temp);
        try(var writer=new AnnotationStore(f.annotations)){assertThat(run(args(f))).isEqualTo(4);assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("STORAGE_IN_USE").doesNotContain(temp.toString());}
        assertThat(run(args(f))).isZero();
    }
    @Test void unavailableRootAndMalformedMetadataHaveSanitizedDiagnostics() throws Exception {
        var f=new EditingReferenceFixture(temp);var missing=args(f);missing[2]=temp.resolve("private-missing").toString();
        assertThat(run(missing)).isEqualTo(5);assertThat(out.toString()).isEmpty();assertThat(error.toString()).contains("EDITING_INSPECTION_UNAVAILABLE").doesNotContain(temp.toString(),"Exception");
        Files.writeString(f.page,"private="+((char)92)+"uXXXX");assertThat(run(args(f))).isEqualTo(3);assertThat(out.toString()).doesNotContain("uXXXX","private note text");
    }
}
