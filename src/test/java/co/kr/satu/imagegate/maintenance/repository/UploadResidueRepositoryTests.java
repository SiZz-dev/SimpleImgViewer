package co.kr.satu.imagegate.maintenance.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.service.UploadResidueService;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class UploadResidueRepositoryTests {
    @TempDir Path temp;
    static final String TENANT="tenant-"+"a".repeat(64);
    static final byte[] PNG={(byte)137,80,78,71,13,10,26,10};
    Path root(){return temp.resolve("originals");}
    Path upload() throws Exception {return Files.createDirectory(Files.createDirectories(root().resolve(TENANT)).resolve(".upload-"+UUID.randomUUID()));}
    UploadResidueOptions options(){return new UploadResidueOptions(root(),true,100,1000,1048576);}
    UploadResidueReport inspect() throws Exception {return new UploadResidueService(new UploadResidueRepository(),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).run(options());}
    Map<Path,byte[]> contents() throws Exception {
        var result=new HashMap<Path,byte[]>();try(var paths=Files.walk(root())){for(Path p:paths.filter(Files::isRegularFile).toList())result.put(root().relativize(p),Files.readAllBytes(p));}return result;
    }
    void assertPreserved(Map<Path,byte[]> before) throws Exception {var after=contents();assertThat(after.keySet()).isEqualTo(before.keySet());for(Path p:before.keySet())assertThat(after.get(p)).isEqualTo(before.get(p));}
    @Test void recognizesFourLayoutsWithoutReadingCatalogOrChangingAnyBytes() throws Exception {
        try(var store=new LocalMediaStore(root(),1024)){store.register("privateTenant","privateOwner","secret.png",new ByteArrayInputStream(PNG));}
        upload();Files.write(upload().resolve("pending"),PNG);Files.write(upload().resolve("original.png"),PNG);
        Path ready=upload();Files.write(ready.resolve("original.png"),PNG);Files.write(ready.resolve("metadata.properties"),new byte[]{(byte)255,0,1});
        var before=contents();var report=inspect();
        assertThat(report.generatedAt()).isEqualTo(Instant.EPOCH);assertThat(report.recognizedResidues()).isEqualTo(4);
        assertThat(report.publishedNameEntriesSkipped()).isEqualTo(1);assertThat(report.limitReached()).isFalse();
        assertThat(report.observedBytes()).isEqualTo(27);assertThat(report.items()).extracting(UploadResidueReport.Item::layout).containsExactlyInAnyOrder("EMPTY","PENDING","RENAMED","READY_TO_PUBLISH");
        assertThat(report.items()).allMatch(i->i.relativePath().matches("tenant-[a-f0-9]{64}/[.]upload-[a-f0-9-]{36}")&&i.lastModifiedAt()!=null);
        assertPreserved(before);assertThat(Files.exists(root().resolve(".quota-writer.lock"))).isFalse();
    }
    @Test void emptyInventoryDoesNotCreateLockOrDirectories() throws Exception {
        Files.createDirectory(root());assertThat(inspect().items()).isEmpty();try(var files=Files.list(root())){assertThat(files.toList()).isEmpty();}
    }
    @Test void absentRootIsNotCreated() {
        assertThatThrownBy(this::inspect).isInstanceOf(IOException.class);assertThat(Files.exists(root())).isFalse();
    }
    @Test void unknownNestedDirectoryIsReportedAndNeverTraversed() throws Exception {
        Path nested=Files.createDirectory(upload().resolve("secret-folder"));Path secret=Files.writeString(nested.resolve("secret-file"),"private bytes");
        var report=inspect();assertThat(report.recognizedResidues()).isZero();assertThat(report.items()).extracting(UploadResidueReport.Item::outcome).containsExactly("UNEXPECTED_LAYOUT");
        assertThat(Files.readString(secret)).isEqualTo("private bytes");assertThat(report.observedBytes()).isZero();
    }
    @Test void pendingWithMetadataOrMultipleOriginalsIsNotARecognizedLayout() throws Exception {
        Path a=upload();Files.write(a.resolve("pending"),PNG);Files.writeString(a.resolve("metadata.properties"),"private");
        Path b=upload();Files.write(b.resolve("original.png"),PNG);Files.write(b.resolve("original.jpg"),PNG);
        Path c=upload();Files.write(c.resolve("original.png"),PNG);Files.write(c.resolve("metadata.properties"),new byte[16385]);
        var report=inspect();assertThat(report.recognizedResidues()).isZero();assertThat(report.items()).hasSize(3).allMatch(i->i.outcome().equals("UNEXPECTED_LAYOUT"));
    }
    @Test void publishedIdCollisionIsFlaggedWithoutOpeningPublishedCatalog() throws Exception {
        Path stage=upload();String id=stage.getFileName().toString().substring(8);Files.createDirectory(stage.getParent().resolve(id));Files.write(stage.resolve("pending"),PNG);
        var report=inspect();assertThat(report.publishedNameEntriesSkipped()).isEqualTo(1);assertThat(report.recognizedResidues()).isZero();
        assertThat(report.items().getFirst().outcome()).isEqualTo("PUBLISHED_ID_COLLISION");assertThat(report.observedBytes()).isZero();
    }
    @Test void itemEnumerationAndLogicalByteBudgetsReturnExplicitPartialReports() throws Exception {
        Files.write(upload().resolve("pending"),PNG);Files.write(upload().resolve("pending"),PNG);
        var repo=new UploadResidueRepository();
        var items=repo.inspect(new UploadResidueOptions(root(),true,1,1000,1048576),Instant.EPOCH);
        assertThat(items.limitReached()).isTrue();assertThat(items.items()).hasSize(1);
        var entries=repo.inspect(new UploadResidueOptions(root(),true,10,1,1048576),Instant.EPOCH);
        assertThat(entries.limitReached()).isTrue();assertThat(entries.scanned()).isEqualTo(1);
        var bytes=repo.inspect(new UploadResidueOptions(root(),true,10,1000,1),Instant.EPOCH);
        assertThat(bytes.limitReached()).isTrue();assertThat(bytes.observedBytes()).isZero();assertThat(bytes.items()).isEmpty();
    }
    @Test void inPlaceSizeOrTimestampChangeIsNeverRecognizedAsStable() throws Exception {
        Path pending=Files.write(upload().resolve("pending"),PNG);FileTime before=Files.getLastModifiedTime(pending);
        var repo=new UploadResidueRepository(()->{try{Files.write(pending,new byte[9]);Files.setLastModifiedTime(pending,FileTime.fromMillis(before.toMillis()+10000));}catch(IOException e){throw new UncheckedIOException(e);}});
        var report=repo.inspect(options(),Instant.EPOCH);assertThat(report.recognizedResidues()).isZero();
        assertThat(report.items().getFirst().outcome()).isEqualTo("CHANGED_DURING_SCAN");assertThat(report.items().getFirst().payloadBytes()).isNull();
    }
    @Test void replacedUploadDirectoryIsNotRecognizedAsTheSelectedEntry() throws Exception {
        Path stage=upload();Files.write(stage.resolve("pending"),PNG);
        var repo=new UploadResidueRepository(()->{try{Files.move(stage,temp.resolve("replaced"));Files.createDirectory(stage);Files.write(stage.resolve("pending"),PNG);}catch(IOException e){throw new UncheckedIOException(e);}});
        var report=repo.inspect(options(),Instant.EPOCH);assertThat(report.recognizedResidues()).isZero();
        assertThat(report.items()).anyMatch(i->i.outcome().equals("CHANGED_DURING_SCAN"));
        assertThat(Files.readAllBytes(temp.resolve("replaced/pending"))).isEqualTo(PNG);
    }
    @Test void quotaMarkerAppearingDuringInspectionInvalidatesWholeResult() throws Exception {
        Files.write(upload().resolve("pending"),PNG);
        var repo=new UploadResidueRepository(()->{try{Files.createFile(root().resolve(".quota-writer.lock"));}catch(IOException e){throw new UncheckedIOException(e);}});
        assertThatThrownBy(()->repo.inspect(options(),Instant.EPOCH)).isInstanceOf(IOException.class);
        assertThat(Files.size(root().resolve(".quota-writer.lock"))).isZero();
    }
    @Test void quotaWriterBlocksInventoryAndReadOnlyGuardReleasesAfterward() throws Exception {
        try(var store=new LocalMediaStore(root(),1024,0,1024)){
            store.register("t","u","x.png",new ByteArrayInputStream(PNG));
            assertThatThrownBy(this::inspect).isInstanceOf(UploadResidueRepository.StorageInUseException.class);
        }
        assertThat(inspect().items()).isEmpty();assertThat(Files.size(root().resolve(".quota-writer.lock"))).isZero();
        try(var store=new LocalMediaStore(root(),1024,0,1024)){store.register("t","u","x.png",new ByteArrayInputStream(PNG));}
    }
    @Test void nonemptyQuotaMarkerIsRejectedAndPreserved() throws Exception {
        Files.createDirectory(root());Path marker=Files.writeString(root().resolve(".quota-writer.lock"),"do not overwrite");
        assertThatThrownBy(this::inspect).isInstanceOf(IOException.class);assertThat(Files.readString(marker)).isEqualTo("do not overwrite");
    }
    void link(Path link,Path target) throws Exception {
        if(System.getProperty("os.name").startsWith("Windows")){
            var p=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            assertThat(p.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero();
        }else Files.createSymbolicLink(link,target);
    }
    @Test void junctionOrSymlinkAtTenantUploadAndPayloadIsNeverTraversed() throws Exception {
        Path stage=upload();Path tenant=stage.getParent();Path outside=Files.createDirectory(temp.resolve("outside"));Path sentinel=Files.writeString(outside.resolve("private"),"unchanged");
        Path tenantLink=root().resolve("tenant-"+"b".repeat(64));Path uploadLink=tenant.resolve(".upload-"+UUID.randomUUID());Path payloadLink=stage.resolve("pending");
        var links=new ArrayList<Path>();
        try{
            for(Path path:List.of(tenantLink,uploadLink,payloadLink)){link(path,outside);links.add(path);}
            var report=inspect();assertThat(report.recognizedResidues()).isZero();assertThat(report.items()).hasSize(3);
            assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
        }finally{for(Path path:links.reversed())Files.delete(path);}
    }
    @Test void rootAliasIsRejectedWithoutTouchingTarget() throws Exception {
        Files.createDirectory(root());Path alias=temp.resolve("alias");link(alias,root());
        try{assertThatThrownBy(()->new UploadResidueRepository().inspect(new UploadResidueOptions(alias,true,10,100,1024),Instant.EPOCH)).isInstanceOf(IOException.class);}
        finally{Files.delete(alias);}
    }
}
