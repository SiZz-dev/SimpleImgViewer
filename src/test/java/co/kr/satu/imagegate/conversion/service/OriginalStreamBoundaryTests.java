package co.kr.satu.imagegate.conversion.service;
import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class OriginalStreamBoundaryTests {
    @TempDir Path root;
    @ParameterizedTest @EnumSource(OriginalStreamComparisonTests.Method.class)
    void allReadOnlyMethodsPreserveBytesAndMatchDigest(OriginalStreamComparisonTests.Method method) throws Exception {
        byte[] bytes={1,2,3,4,5};Path file=Files.write(root.resolve("original.bin"),bytes);
        var result=OriginalStreamComparisonTests.verify(file,method,bytes.length,PreviewKeys.sha256(bytes));
        assertThat(result.bytes()).isEqualTo(bytes.length);assertThat(result.reads()).isEqualTo(result.updates()+1);
        assertThat(Files.readAllBytes(file)).containsExactly(bytes);
    }
    @ParameterizedTest @EnumSource(OriginalStreamComparisonTests.Method.class)
    void digestMismatchStillRejectsSameSizeFile(OriginalStreamComparisonTests.Method method) throws Exception {
        Path file=Files.write(root.resolve("original.bin"),new byte[]{9,9,9});
        assertThatThrownBy(()->OriginalStreamComparisonTests.verify(file,method,3,PreviewKeys.sha256(new byte[]{1,2,3})))
                .isInstanceOf(IOException.class).hasMessage("Original changed");
    }
    @ParameterizedTest @EnumSource(OriginalStreamComparisonTests.Method.class)
    void rejectsNoncanonicalDirectoryAndSizeMismatch(OriginalStreamComparisonTests.Method method) throws Exception {
        Path file=Files.write(root.resolve("original.bin"),new byte[]{1,2,3});String sha=PreviewKeys.sha256(new byte[]{1,2,3});
        assertThatThrownBy(()->OriginalStreamComparisonTests.verify(root,method,3,sha)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->OriginalStreamComparisonTests.verify(file,method,4,sha)).isInstanceOf(IOException.class);
        assertThatThrownBy(()->OriginalStreamComparisonTests.verify(root.resolve("child/../original.bin"),method,3,sha)).isInstanceOf(IOException.class);
    }
    @ParameterizedTest @EnumSource(OriginalStreamComparisonTests.Method.class)
    void preexistingInterruptStopsBeforeMissingFileIsOpened(OriginalStreamComparisonTests.Method method) {
        try{
            Thread.currentThread().interrupt();
            assertThatThrownBy(()->OriginalStreamComparisonTests.verify(root.resolve("absent.bin"),method,1,"0".repeat(64)))
                    .isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        }finally{Thread.interrupted();}
    }

    @ParameterizedTest @EnumSource(OriginalStreamComparisonTests.Method.class)
    void openMarkerAlwaysExitsWhenOpenFails(OriginalStreamComparisonTests.Method method) throws Exception {
        Path file=Files.write(root.resolve("removed-at-open.bin"),new byte[]{1,2,3});
        var enters=new java.util.concurrent.atomic.AtomicInteger();var exits=new java.util.concurrent.atomic.AtomicInteger();
        var marker=new co.kr.satu.imagegate.conversion.util.OpenStackSampler.Marker(){
            public void entering(){
                enters.incrementAndGet();
                try{Files.delete(file);}catch(IOException e){throw new UncheckedIOException(e);}
            }
            public void exited(){exits.incrementAndGet();}
        };
        assertThatThrownBy(()->OriginalStreamComparisonTests.verify(file,method,3,PreviewKeys.sha256(new byte[]{1,2,3}),marker))
                .isInstanceOf(IOException.class);
        assertThat(enters).hasValue(1);assertThat(exits).hasValue(1);
    }
}
