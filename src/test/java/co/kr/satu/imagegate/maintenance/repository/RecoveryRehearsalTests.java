package co.kr.satu.imagegate.maintenance.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.media.domain.MediaRecord;
import co.kr.satu.imagegate.editing.repository.*;
import co.kr.satu.imagegate.editing.domain.*;
import co.kr.satu.imagegate.editing.exception.EditException;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Editing;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stopped-storage rehearsal only. Copy helper is not a production backup implementation. */
class RecoveryRehearsalTests {
    @TempDir Path temp;
    final Editing limits=new Editing(100,2000,100,1048576,4194304);
    final ViewerPrincipal owner=new ViewerPrincipal("tenant","owner");
    record Snapshot(Path path,MediaRecord media,StampAsset stamp,AnnotationDocument document,byte[] original) {}
    String space(MediaRecord m){return "original:"+m.mediaVersion()+":1:upright-v1";}
    byte[] png() throws IOException {
        var image=new BufferedImage(32,24,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(5,6,0xff42a5f5);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    Snapshot stoppedSnapshot() throws Exception {
        Path source=temp.resolve("source");byte[] original=png();
        var media=new LocalMediaStore(source.resolve("originals"),1048576)
                .register(owner.tenantId(),owner.userId(),"복구 검증.png",new ByteArrayInputStream(original));
        StampAsset stamp;AnnotationDocument doc;
        try(var stamps=new StampStore(source.resolve("stamps"),limits);
                var annotations=new AnnotationStore(source.resolve("annotations"))) {
            stamp=stamps.register(owner,"확인.png",new ByteArrayInputStream(original));
            var now=Instant.now();
            var note=new AnnotationItem(UUID.randomUUID().toString(),AnnotationItem.Type.NOTE,
                    1,2,12,10,"보존 메모",null,null,now,now);
            var mark=new AnnotationItem(UUID.randomUUID().toString(),AnnotationItem.Type.STAMP,
                    16,2,8,8,null,stamp.id(),owner.userId(),now,now);
            doc=annotations.update(media,1,space(media),0,items->List.of(note,mark));
            annotations.update(media,2,"original:"+media.mediaVersion()+":2:upright-v1",0,items->List.of());
        }
        return new Snapshot(source,media,stamp,doc,original);
    }
    Path restore(Snapshot snapshot) throws Exception {
        Path backup=temp.resolve("backup"),restored=temp.resolve("relocated");
        var expected=manifest(snapshot.path());
        copy(snapshot.path(),backup);copy(backup,restored);
        assertThat(manifest(backup)).isEqualTo(expected);
        assertThat(manifest(restored)).isEqualTo(expected);
        return restored;
    }
    void copy(Path source,Path target) throws Exception {
        // Synthetic fixtures only. Preflight the complete source before creating a destination.
        if(target.startsWith(source)||source.startsWith(target)||Files.exists(target,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Restore destination must be new and isolated");
        manifest(source);
        try(var paths=Files.walk(source)) {
            for(Path p:paths.toList()) {
                assertThat(Files.isSymbolicLink(p)).isFalse();
                Path dest=target.resolve(source.relativize(p));
                if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(dest);
                else Files.copy(p,dest);
            }
        }
    }
    @Test void relocatedBackupRetainsOriginalIdentityNotesStampReferencesAndRevisions() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        var protectedSource=manifest(saved.path());
        var protectedBackup=manifest(temp.resolve("backup"));
        assertCleanInspection(restored);
        var originals=new LocalMediaStore(restored.resolve("originals"),1048576);
        var media=originals.find(owner.tenantId(),saved.media().mediaId()).orElseThrow();
        assertThat(media).isEqualTo(saved.media());
        Path blob=originals.resolveOriginal(media);
        assertThat(Files.readAllBytes(blob)).isEqualTo(saved.original());
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(blob))))
                .isEqualTo(media.sha256());
        assertThat(originals.find("Tenant",media.mediaId())).isEmpty();
        try(var annotations=new AnnotationStore(restored.resolve("annotations"));
                var stamps=new StampStore(restored.resolve("stamps"),limits)) {
            assertThat(annotations.read(media,1,space(media))).isEqualTo(saved.document());
            assertThat(stamps.list(owner)).containsExactly(saved.stamp());
            assertThat(stamps.list(new ViewerPrincipal("tenant","other"))).isEmpty();
            var item=annotations.read(media,1,space(media)).items().get(1);
            assertThat(stamps.raster(media.tenantId(),item.stampOwner(),item.stampId()).getRGB(5,6))
                    .isEqualTo(0xff42a5f5);
            assertThatThrownBy(()->annotations.update(media,1,space(media),0,items->items))
                    .isInstanceOf(EditException.class);
            var empty=annotations.read(media,2,"original:"+media.mediaVersion()+":2:upright-v1");
            assertThat(empty.items()).isEmpty();assertThat(empty.revision()).isEqualTo(1);
            assertThat(annotations.update(media,1,space(media),1,items->List.of()).revision()).isEqualTo(2);
        }
        // Restored writes must not mutate any source/backup bytes or directory entries.
        assertThat(manifest(saved.path())).isEqualTo(protectedSource);
        assertThat(manifest(temp.resolve("backup"))).isEqualTo(protectedBackup);
        // Restored writes must not mutate the immutable backup or original fixture.
        try(var source=new AnnotationStore(saved.path().resolve("annotations"))) {
            assertThat(source.read(saved.media(),1,space(saved.media()))).isEqualTo(saved.document());
        }
    }
    @Test void missingStampBlobPreventsReferencedRasterRecovery() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        Path blob;
        try(var paths=Files.walk(restored.resolve("stamps"))) {
            blob=paths.filter(p->p.getFileName().toString().equals("stamp.png")).findFirst().orElseThrow();
        }
        Files.delete(blob);
        var damaged=manifest(restored);
        assertThat(references(restored,100).issues()).isGreaterThan(0);
        assertThat(manifest(restored)).isEqualTo(damaged);
        try(var annotations=new AnnotationStore(restored.resolve("annotations"));
                var stamps=new StampStore(restored.resolve("stamps"),limits)) {
            var mark=annotations.read(saved.media(),1,space(saved.media())).items().get(1);
            assertThatThrownBy(()->stamps.raster(owner.tenantId(),mark.stampOwner(),mark.stampId()))
                    .isInstanceOf(IOException.class);
        }
    }
    @Test void missingOriginalMetadataIsNotRecoveredAsAValidCatalogRecord() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        var originals=new LocalMediaStore(restored.resolve("originals"),1048576);
        Path metadata=originals.resolveOriginal(saved.media()).resolveSibling("metadata.properties");
        Files.delete(metadata);
        var damaged=manifest(restored);
        assertThat(originals(restored,1048576).issues()).isGreaterThan(0);
        assertThat(references(restored,100).items()).extracting(EditingReferenceReport.Item::outcome)
                .contains("MISSING_ORIGINAL_CATALOG");
        assertThat(manifest(restored)).isEqualTo(damaged);
        assertThatThrownBy(()->originals.find(owner.tenantId(),saved.media().mediaId()))
                .isInstanceOf(IOException.class);
    }

    // Test-only manifest kept in memory outside the copied set. No production backup/restore API.
    Map<String,String> manifest(Path directory) throws Exception {
        var result=new TreeMap<String,String>();
        try(var paths=Files.walk(directory)) {
            for(Path path:paths.toList()) {
                if(Files.isSymbolicLink(path)||!path.toRealPath().equals(path))
                    throw new IOException("Unsafe synthetic snapshot boundary");
                String key=directory.relativize(path).toString();
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))result.put(key,"DIRECTORY");
                else if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
                    result.put(key,Files.size(path)+":"+HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
                else throw new IOException("Unexpected synthetic entry");
            }
        }
        return result;
    }
    OriginalIntegrityReport originals(Path restored,long maxBytes) throws IOException {
        return new OriginalIntegrityRepository().inspect(new OriginalIntegrityOptions(
                restored.resolve("originals"),true,100,1000,maxBytes),Instant.now());
    }
    EditingReferenceReport references(Path restored,int maxItems) throws IOException {
        return new EditingReferenceRepository().inspect(new EditingReferenceOptions(
                restored.resolve("originals"),restored.resolve("annotations"),restored.resolve("stamps"),
                true,maxItems,10000,1048576),Instant.now());
    }
    void assertCleanInspection(Path restored) throws Exception {
        var before=manifest(restored);
        var integrity=originals(restored,1048576);
        var editing=references(restored,100);
        assertThat(integrity.issues()).isZero();assertThat(integrity.limitReached()).isFalse();
        assertThat(integrity.verified()).isEqualTo(1);
        assertThat(editing.issues()).isZero();assertThat(editing.limitReached()).isFalse();
        assertThat(editing.validPages()).isEqualTo(2);assertThat(editing.validStamps()).isEqualTo(1);
        assertThat(manifest(restored)).isEqualTo(before);
    }
    Path find(Path restored,String name) throws IOException {
        try(var paths=Files.walk(restored)) {
            return paths.filter(p->p.getFileName().toString().equals(name)).findFirst().orElseThrow();
        }
    }
    void changeProperty(Path file,String key,String value) throws IOException {
        var properties=new Properties();
        try(var in=Files.newInputStream(file)){properties.load(in);}
        properties.setProperty(key,value);
        try(var out=Files.newOutputStream(file)){properties.store(out,null);}
    }
    @Test void sameSizeOriginalTamperRequiresHashInspectionDespiteValidReferences() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        var before=manifest(temp.resolve("backup"));
        Path blob=find(restored,"original.png");byte[] bytes=Files.readAllBytes(blob);
        bytes[bytes.length-1]^=1;Files.write(blob,bytes);
        var damaged=manifest(restored);
        assertThat(originals(restored,1048576).items()).extracting(OriginalIntegrityReport.Item::outcome)
                .containsExactly("CHECKSUM_MISMATCH");
        assertThat(references(restored,100).issues()).isZero();
        assertThat(damaged).isNotEqualTo(before);
        assertThat(manifest(restored)).isEqualTo(damaged);
        assertThat(manifest(saved.path())).isEqualTo(before);
        assertThat(manifest(temp.resolve("backup"))).isEqualTo(before);
    }
    @Test void missingEmptyAnnotationSnapshotRequiresExternalFileInventory() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        var expected=manifest(temp.resolve("backup"));
        Files.delete(find(restored,"page-2.properties"));
        var report=references(restored,100);
        assertThat(report.issues()).isZero();assertThat(report.validPages()).isEqualTo(1);
        assertThat(originals(restored,1048576).issues()).isZero();
        assertThat(manifest(restored)).isNotEqualTo(expected);
        assertThat(manifest(saved.path())).isEqualTo(expected);
    }
    @Test void stampPayloadTamperRequiresExternalHashInventory() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        var expected=manifest(temp.resolve("backup"));
        Path stamp=find(restored,"stamp.png");byte[] bytes=Files.readAllBytes(stamp);
        bytes[bytes.length-1]^=1;Files.write(stamp,bytes);
        var damaged=manifest(restored);
        assertThat(references(restored,100).issues()).isZero();
        assertThat(originals(restored,1048576).issues()).isZero();
        assertThat(damaged).isNotEqualTo(expected);
        assertThat(manifest(restored)).isEqualTo(damaged);
        assertThat(manifest(temp.resolve("backup"))).isEqualTo(expected);
    }
    @Test void mixedOriginalVersionFailsReferenceInspectionWithoutRewritingAnnotations() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        Path metadata=find(restored.resolve("originals"),"metadata.properties");
        changeProperty(metadata,"version",UUID.randomUUID().toString());
        var damaged=manifest(restored);
        assertThat(originals(restored,1048576).issues()).isZero();
        assertThat(references(restored,100).items()).extracting(EditingReferenceReport.Item::outcome)
                .contains("ORIGINAL_VERSION_MISMATCH");
        assertThat(manifest(restored)).isEqualTo(damaged);
        assertThat(manifest(temp.resolve("backup"))).isEqualTo(manifest(saved.path()));
    }
    @Test void partialInspectionCannotSatisfyRestoredSetAcceptance() throws Exception {
        var saved=stoppedSnapshot();Path restored=restore(saved);
        var before=manifest(restored);
        var integrity=originals(restored,1);var editing=references(restored,1);
        assertThat(integrity.limitReached()).isTrue();assertThat(integrity.verified()).isZero();
        assertThat(editing.limitReached()).isTrue();
        assertThat(manifest(restored)).isEqualTo(before);
    }
    @Test void restoreCopyRefusesExistingAndOverlappingDestinationsBeforeWriting() throws Exception {
        var saved=stoppedSnapshot();var before=manifest(saved.path());
        Path existing=Files.createDirectory(temp.resolve("existing"));
        Path marker=Files.writeString(existing.resolve("keep.txt"),"keep");
        assertThatThrownBy(()->copy(saved.path(),existing)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->copy(saved.path(),saved.path())).isInstanceOf(IOException.class);
        assertThatThrownBy(()->copy(saved.path(),saved.path().resolve("nested"))).isInstanceOf(IOException.class);
        assertThatThrownBy(()->copy(saved.path(),temp)).isInstanceOf(IOException.class);
        assertThat(Files.readString(marker)).isEqualTo("keep");
        assertThat(manifest(saved.path())).isEqualTo(before);
    }
}
