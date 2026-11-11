package co.kr.satu.imagegate.maintenance.repository;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.maintenance.domain.*;
import co.kr.satu.imagegate.maintenance.service.EditingReferenceService;
import co.kr.satu.imagegate.maintenance.util.*;
import co.kr.satu.imagegate.editing.repository.*;
import co.kr.satu.imagegate.media.repository.LocalMediaStore;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class EditingReferenceRepositoryTests {
    @TempDir Path temp;
    EditingReferenceReport inspect(EditingReferenceFixture f) throws Exception {return new EditingReferenceService(new EditingReferenceRepository(),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).run(f.options());}
    List<String> pageOutcomes(EditingReferenceReport r){return r.items().stream().filter(i->i.area().equals("annotations")).map(EditingReferenceReport.Item::outcome).toList();}
    Map<Path,byte[]> snapshot() throws Exception {var result=new HashMap<Path,byte[]>();try(var paths=Files.walk(temp)){for(Path p:paths.filter(Files::isRegularFile).toList())result.put(temp.relativize(p),Files.readAllBytes(p));}return result;}
    @Test void sharedOwnerStampAndEmptyPageValidateWithoutMutatingAnyBytes() throws Exception {
        var f=new EditingReferenceFixture(temp);var before=snapshot();var r=inspect(f);
        assertThat(r.validPages()).isEqualTo(2);assertThat(r.validStamps()).isEqualTo(1);assertThat(r.issues()).isZero();assertThat(r.generatedAt()).isEqualTo(Instant.EPOCH);
        assertThat(r.limitReached()).isFalse();assertThat(r.metadataBytesRead()).isPositive();
        assertThat(r.items()).filteredOn(i->i.area().equals("annotations")).extracting(EditingReferenceReport.Item::stampReferences).containsExactlyInAnyOrder(1,0);
        var after=snapshot();assertThat(after.keySet()).isEqualTo(before.keySet());for(Path p:before.keySet())assertThat(after.get(p)).isEqualTo(before.get(p));
    }
    @Test void missingOriginalCatalogIsNotAValidReference() throws Exception {
        var f=new EditingReferenceFixture(temp);Files.delete(f.originalEntry.resolve("metadata.properties"));
        assertThat(pageOutcomes(inspect(f))).containsOnly("MISSING_ORIGINAL_CATALOG");
    }
    @Test void missingOriginalDirectoryAndMissingStampAreDistinctFindings() throws Exception {
        var f=new EditingReferenceFixture(temp);Path moved=temp.resolve("saved-original");Files.move(f.originalEntry,moved);
        assertThat(pageOutcomes(inspect(f))).containsOnly("MISSING_ORIGINAL");Files.move(moved,f.originalEntry);
        Files.move(f.stampEntry,temp.resolve("saved-stamp"));
        assertThat(pageOutcomes(inspect(f))).contains("MISSING_STAMP","ANNOTATION_REFERENCES_VALID");
    }
    @Test void differentCurrentOriginalVersionDoesNotValidateOldAnnotations() throws Exception {
        var f=new EditingReferenceFixture(temp);f.property(f.originalEntry.resolve("metadata.properties"),"version",UUID.randomUUID().toString());
        assertThat(pageOutcomes(inspect(f))).containsOnly("ORIGINAL_VERSION_MISMATCH");
    }
    @Test void wrongTenantCatalogAndEscapingOriginalFileAreRejected() throws Exception {
        var f=new EditingReferenceFixture(temp);Path meta=f.originalEntry.resolve("metadata.properties");f.property(meta,"tenantId","anotherTenant");
        assertThat(pageOutcomes(inspect(f))).containsOnly("INVALID_ORIGINAL");f.property(meta,"tenantId",EditingReferenceFixture.TENANT);
        f.property(meta,"storedFileName","../../private");assertThat(pageOutcomes(inspect(f))).containsOnly("INVALID_ORIGINAL");
    }
    @Test void missingStampImageOrWrongMetadataIdFailsReferencedAndLibraryChecks() throws Exception {
        var f=new EditingReferenceFixture(temp);Files.delete(f.stampEntry.resolve("preview.png"));var r=inspect(f);
        assertThat(r.validStamps()).isZero();assertThat(pageOutcomes(r)).contains("INVALID_STAMP_REFERENCE");
        f.property(f.stampEntry.resolve("metadata.properties"),"id",UUID.randomUUID().toString());assertThat(inspect(f).issues()).isPositive();
    }
    @Test void stampFromAnotherOwnerIsNotSilentlySubstituted() throws Exception {
        var f=new EditingReferenceFixture(temp);f.property(f.page,"item.1.stampOwner",EditingReferenceFixture.OWNER);
        assertThat(pageOutcomes(inspect(f))).contains("MISSING_STAMP");assertThat(inspect(f).validStamps()).isEqualTo(1);
    }
    @Test void malformedAnnotationScalarsUnknownFieldsAndCoordinateIdentityAreRejected() throws Exception {
        int n=0;
        for(String[] change:List.of(new String[]{"count","101"},new String[]{"item.0.x","NaN"},new String[]{"version",UUID.randomUUID().toString()},
                new String[]{"unknown","private"},new String[]{"item.1.stampOwner","../escape"},new String[]{"item.0.created",null},new String[]{"space","unknown"})){
            var f=new EditingReferenceFixture(temp.resolve("case-"+n++));f.property(f.page,change[0],change[1]);
            assertThat(pageOutcomes(inspect(f))).containsAnyOf("INVALID_ANNOTATION","COORDINATE_SPACE_MISMATCH");
        }
    }
    @Test void malformedUnicodeAndOversizedSnapshotsDoNotEscapeAsRawErrors() throws Exception {
        var f=new EditingReferenceFixture(temp);Files.writeString(f.page,"private="+((char)92)+"uXXXX");assertThat(pageOutcomes(inspect(f))).contains("INVALID_ANNOTATION");
        Files.writeString(f.page,java.util.stream.IntStream.range(0,3000).mapToObj(i->"private-key-"+i+"=value").collect(java.util.stream.Collectors.joining("\n")));
        assertThat(pageOutcomes(inspect(f))).contains("INVALID_ANNOTATION");
        Files.write(f.page,new byte[2097153]);assertThat(pageOutcomes(inspect(f))).contains("INVALID_ANNOTATION");
    }
    @Test void unusedStampLibraryEntryIsValidRatherThanAnOrphan() throws Exception {
        var f=new EditingReferenceFixture(temp);Path extra=f.stampEntry.resolveSibling(UUID.randomUUID().toString());Files.createDirectory(extra);
        for(String name:List.of("metadata.properties","stamp.png","preview.png"))Files.copy(f.stampEntry.resolve(name),extra.resolve(name));
        f.property(extra.resolve("metadata.properties"),"id",extra.getFileName().toString());
        var r=inspect(f);assertThat(r.validStamps()).isEqualTo(2);assertThat(r.issues()).isZero();
    }
    @Test void unknownStampFilesAreReportedAndPreserved() throws Exception {
        var f=new EditingReferenceFixture(temp);Path unknown=Files.writeString(f.stampEntry.resolve("private-unknown"),"preserve");
        var r=inspect(f);assertThat(r.items()).anyMatch(i->i.outcome().equals("UNEXPECTED_STAMP_CONTENT"));assertThat(Files.readString(unknown)).isEqualTo("preserve");
    }
    @Test void limitsProducePartialInventoryInsteadOfFalseValidity() throws Exception {
        var f=new EditingReferenceFixture(temp);var repo=new EditingReferenceRepository();
        for(var o:List.of(new EditingReferenceOptions(f.originals,f.annotations,f.stamps,true,1,10000,10485760),
                new EditingReferenceOptions(f.originals,f.annotations,f.stamps,true,100,1,10485760),
                new EditingReferenceOptions(f.originals,f.annotations,f.stamps,true,100,10000,1))){
            var r=repo.inspect(o,Instant.EPOCH);assertThat(r.limitReached()).isTrue();assertThat(r.metadataBytesRead()).isLessThanOrEqualTo(o.maxBytes());
        }
    }
    @Test void changedSnapshotIsNeverReportedAsValid() throws Exception {
        var f=new EditingReferenceFixture(temp);var n=new AtomicInteger();
        var repo=new EditingReferenceRepository(()->{try{f.property(f.page,"revision",""+n.incrementAndGet());}catch(Exception e){throw new RuntimeException(e);}});
        var r=repo.inspect(f.options(),Instant.EPOCH);assertThat(r.items()).anyMatch(i->i.outcome().equals("CHANGED_DURING_SCAN"));assertThat(r.issues()).isPositive();
    }
    @Test void runningWritersOfAllThreeStoresAreRefusedAndFailedAcquisitionReleasesLocks() throws Exception {
        var f=new EditingReferenceFixture(temp);
        try(var writer=new AnnotationStore(f.annotations)){assertThatThrownBy(()->inspect(f)).isInstanceOf(ReadOnlyStorageLock.StorageInUseException.class);}
        try(var writer=new StampStore(f.stamps,EditingReferenceFixture.LIMITS)){assertThatThrownBy(()->inspect(f)).isInstanceOf(ReadOnlyStorageLock.StorageInUseException.class);}
        try(var writer=new LocalMediaStore(f.originals,1048576,0,1048576)){assertThatThrownBy(()->inspect(f)).isInstanceOf(ReadOnlyStorageLock.StorageInUseException.class);}
        assertThat(inspect(f).issues()).isZero();
    }
    @Test void missingRootIsNotCreatedAndNewLockInvalidatesInspection() throws Exception {
        var f=new EditingReferenceFixture(temp);Path missing=temp.resolve("missing");
        assertThatThrownBy(()->new EditingReferenceRepository().inspect(new EditingReferenceOptions(missing,f.annotations,f.stamps,true,100,1000,1048576),Instant.EPOCH)).isInstanceOf(IOException.class);
        assertThat(Files.exists(missing)).isFalse();var once=new AtomicBoolean();
        var repo=new EditingReferenceRepository(()->{try{if(once.compareAndSet(false,true))Files.createFile(f.originals.resolve(".quota-writer.lock"));}catch(IOException e){throw new UncheckedIOException(e);}});
        assertThatThrownBy(()->repo.inspect(f.options(),Instant.EPOCH)).isInstanceOf(IOException.class);
    }
    void link(Path path,Path target) throws Exception {
        if(System.getProperty("os.name").startsWith("Windows")){
            String prefix=""+((char)92)+((char)92)+"?"+((char)92);
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",prefix+path,prefix+target).redirectErrorStream(true).start();
            assertThat(process.waitFor(10,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero();
        }else Files.createSymbolicLink(path,target);
    }
    @Test void junctionsAtAnnotationTenantAndStampImageDoNotExposeOutsideData() throws Exception {
        var f=new EditingReferenceFixture(temp);Path outside=Files.createDirectory(temp.resolve("outside"));Path sentinel=Files.writeString(outside.resolve("private"),"preserve");
        Path tenant=f.annotations.resolve("tenant-"+"b".repeat(64)),image=f.stampEntry.resolve("stamp.png");Files.move(image,temp.resolve("saved.png"));
        var links=new ArrayList<Path>();try{
            for(Path p:List.of(tenant,image)){link(p,outside);links.add(p);}var r=inspect(f);assertThat(r.issues()).isPositive();assertThat(r.validStamps()).isZero();
            assertThat(Files.readString(sentinel)).isEqualTo("preserve");
        }finally{for(Path p:links.reversed())Files.delete(p);}
    }
    @Test void rootAliasAndNonemptyWriterMarkerAreRejected() throws Exception {
        var f=new EditingReferenceFixture(temp);Path alias=temp.resolve("alias");link(alias,f.annotations);
        try{assertThatThrownBy(()->new EditingReferenceRepository().inspect(new EditingReferenceOptions(f.originals,alias,f.stamps,true,100,10000,10485760),Instant.EPOCH)).isInstanceOf(IOException.class);}
        finally{Files.delete(alias);}
        Files.writeString(f.annotations.resolve("writer.lock"),"private unexpected");assertThatThrownBy(()->inspect(f)).isInstanceOf(IOException.class);
    }
}
