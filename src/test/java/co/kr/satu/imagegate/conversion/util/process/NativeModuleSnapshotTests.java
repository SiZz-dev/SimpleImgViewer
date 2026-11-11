package co.kr.satu.imagegate.conversion.util.process;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.exception.ConversionException;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class NativeModuleSnapshotTests {
    @TempDir Path temp;
    Path module(String name) throws Exception {
        Path root=Files.createDirectory(temp.resolve(name));Files.write(root.resolve("tool.exe"),new byte[]{1,2,3});
        Files.write(root.resolve("codec.dll"),new byte[]{4,5,6});Path resources=Files.createDirectory(root.resolve("resource"));
        Files.write(resources.resolve("cmyk.icc"),new byte[]{7,8,9});return root;
    }
    NativeModuleSnapshot capture(Path root) throws Exception {return NativeModuleSnapshot.capture(List.of(root.resolve("tool.exe")),true);}
    @Test void contentFingerprintIsStableAcrossRelocationAndIgnoresUnrelatedOutput() throws Exception {
        Path a=module("a"),b=module("b");var one=capture(a);var two=capture(b);
        assertThat(one.fingerprint()).isEqualTo(two.fingerprint());Files.writeString(a.resolve("notes.txt"),"unrelated");Files.createDirectory(a.resolve("jobs"));
        one.verifyAttributes();one.verifyContent();assertThat(one.fingerprint()).isEqualTo(two.fingerprint());
    }
    @Test void sameSizeExecutableLibraryAndResourceTamperWithRestoredMtimeIsCaughtAndLatched() throws Exception {
        int n=0;
        for(String relative:List.of("tool.exe","codec.dll","resource/cmyk.icc")){
            Path root=module("case"+(n++));var snapshot=capture(root);String identity=snapshot.fingerprint();Path file=root.resolve(relative);
            byte[] original=Files.readAllBytes(file);var mtime=Files.getLastModifiedTime(file);byte[] changed=original.clone();changed[0]^=1;
            Files.write(file,changed);Files.setLastModifiedTime(file,mtime);snapshot.verifyAttributes();
            assertThatThrownBy(snapshot::verifyContent).isInstanceOfSatisfying(ConversionException.class,e->{assertThat(e.code()).isEqualTo(ConversionException.Code.UNAVAILABLE);assertThat(e.getMessage()).doesNotContain(root.toString());});
            Files.write(file,original);Files.setLastModifiedTime(file,mtime);
            assertThatThrownBy(snapshot::verifyAttributes).isInstanceOf(ConversionException.class);
            assertThatThrownBy(snapshot::verifyContent).isInstanceOf(ConversionException.class);
            assertThat(capture(root).fingerprint()).isEqualTo(identity);
        }
    }
    @Test void addingAndRemovingLibrariesInvalidatesTheFrozenSet() throws Exception {
        Path a=module("add");var added=capture(a);Files.write(a.resolve("new.dll"),new byte[]{1});
        assertThatThrownBy(added::verifyAttributes).isInstanceOf(ConversionException.class);
        Path b=module("remove");var removed=capture(b);Files.delete(b.resolve("codec.dll"));
        assertThatThrownBy(removed::verifyAttributes).isInstanceOf(ConversionException.class);
        assertThat(capture(a).fingerprint()).isNotEqualTo(capture(b).fingerprint());
    }
    @Test void changedInstalledContentProducesDifferentIdentityOnlyForANewInstance() throws Exception {
        Path root=module("change");var old=capture(root);Files.write(root.resolve("codec.dll"),new byte[]{9,9,9,9});
        assertThatThrownBy(old::verifyContent).isInstanceOf(ConversionException.class);var replacement=capture(root);
        assertThat(replacement.fingerprint()).isNotEqualTo(old.fingerprint());replacement.verifyContent();
    }
    @Test void configuredExecutableDigestMustMatchTheCapturedBytes() throws Exception {
        Path root=module("pin");var snapshot=capture(root);Path exe=root.resolve("tool.exe");
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(exe)));
        snapshot.requirePinnedDigest(exe,sha);
        assertThatThrownBy(()->snapshot.requirePinnedDigest(exe,"0".repeat(64))).isInstanceOf(ConversionException.class);
        assertThatThrownBy(snapshot::verifyContent).isInstanceOf(ConversionException.class);
    }
    @Test void stagedResourceMustHaveTheBaselineBytesBeforeUse() throws Exception {
        Path root=module("copy");var snapshot=capture(root);Path source=root.resolve("resource/cmyk.icc");Path copy=Files.copy(source,temp.resolve("copy.icc"));
        snapshot.verifyCopiedResource(source,copy);Files.write(copy,new byte[]{0,0,0});
        assertThatThrownBy(()->snapshot.verifyCopiedResource(source,copy)).isInstanceOf(ConversionException.class);
        assertThatThrownBy(snapshot::verifyContent).isInstanceOf(ConversionException.class);
    }
    @Test void unsupportedNestedResourcesAndExcessiveFileSetsFailBeforeUse() throws Exception {
        Path a=module("nested");Files.createDirectory(a.resolve("resource/subdirectory"));
        assertThatThrownBy(()->capture(a)).isInstanceOf(ConversionException.class);
        Path b=module("large");for(int i=0;i<256;i++)Files.createFile(b.resolve("extra-"+i+".dll"));
        assertThatThrownBy(()->capture(b)).isInstanceOf(ConversionException.class);
    }
    @Test void interruptionDoesNotRevokeAnUnchangedModule() throws Exception {
        Path root=module("interrupt");var snapshot=capture(root);var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread thread=Thread.ofPlatform().start(()->{Thread.currentThread().interrupt();try{snapshot.verifyContent();failure.set(new AssertionError("Expected interrupt"));}
            catch(InterruptedIOException expected){if(!Thread.currentThread().isInterrupted())failure.set(new AssertionError("Interrupt lost"));}
            catch(Throwable unexpected){failure.set(unexpected);}});
        thread.join(5000);assertThat(thread.isAlive()).isFalse();assertThat(failure.get()).isNull();snapshot.verifyContent();
    }
    @Test void junctionModuleAliasIsNotFollowed() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root=module("real"),alias=temp.resolve("alias");String prefix=""+((char)92)+((char)92)+"?"+((char)92);
        var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",prefix+alias,prefix+root).redirectErrorStream(true).start();process.getInputStream().readAllBytes();assertThat(process.waitFor()).isZero();
        try{assertThatThrownBy(()->capture(alias)).isInstanceOf(ConversionException.class);assertThat(Files.readAllBytes(root.resolve("tool.exe"))).containsExactly(1,2,3);}
        finally{Files.delete(alias);}
    }
}
