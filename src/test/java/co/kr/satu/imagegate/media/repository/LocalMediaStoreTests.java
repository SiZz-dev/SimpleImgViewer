package co.kr.satu.imagegate.media.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.exception.MediaException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalMediaStoreTests {
    @TempDir Path root;

    @Test void metadataSurvivesRepositoryRecreationAndTenantNamesAreCaseSafe() throws Exception {
        var store=new LocalMediaStore(root,1024*1024);
        var record=store.register("TenantA","alice","C:\\fakepath\\sample.png",new ByteArrayInputStream(png()));
        assertThat(record.fileName()).isEqualTo("sample.png");
        assertThat(record.sha256()).hasSize(64);
        assertThat(new LocalMediaStore(root,1024*1024).find("TenantA",record.mediaId())).contains(record);
        assertThat(store.find("tenanta",record.mediaId())).isEmpty();
        assertThat(store.find("TenantB",record.mediaId())).isEmpty();
    }

    @Test void rejectsTraversalIdentifiersAndDoesNotAcceptPathsAsIds() throws Exception {
        var store=new LocalMediaStore(root,100);
        assertThatThrownBy(() -> store.find("../other","anything")).isInstanceOf(MediaException.class);
        assertThatThrownBy(() -> store.find("tenant","../../outside")).isInstanceOf(MediaException.class);
    }

    @Test void rejectsOversizedStreamAndCleansUnpublishedFiles() throws Exception {
        var store=new LocalMediaStore(root,8);
        assertThatThrownBy(() -> store.register("tenant","alice","test.png",new ByteArrayInputStream(png())))
                .isInstanceOfSatisfying(MediaException.class,e ->
                        assertThat(e.code()).isEqualTo(MediaException.Code.FILE_TOO_LARGE));
        assertNoFiles();
    }

    @Test void cleansFailedPartialUploadAndRejectsUnknownFormat() throws Exception {
        var store=new LocalMediaStore(root,1024);
        InputStream failure=new InputStream() {
            int calls;
            @Override public int read() throws IOException { if(++calls>4) throw new IOException("test");return 1; }
        };
        assertThatThrownBy(() -> store.register("tenant","alice","x.bin",failure)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.register("tenant","alice","x.png",
                new ByteArrayInputStream(new byte[]{1,2,3,4}))).isInstanceOf(MediaException.class);
        assertNoFiles();
    }

    @Test void rejectsHeaderInjectionInDisplayName() throws Exception {
        assertThatThrownBy(() -> new LocalMediaStore(root,1024).register("tenant","alice","bad\r\nname.png",
                new ByteArrayInputStream(png()))).isInstanceOf(MediaException.class);
        assertNoFiles();
    }

    @Test void insufficientDiskSpaceRejectsBeforeReadingAndExistingOriginalStillWorks() throws Exception {
        var free=new java.util.concurrent.atomic.AtomicLong(10000000);
        var store=new LocalMediaStore(root,4000000,1000,path->free.get());
        var original=store.register("tenant","alice","safe.png",new ByteArrayInputStream(png()));
        free.set(999);var reads=new java.util.concurrent.atomic.AtomicInteger();
        assertThatThrownBy(()->store.register("tenant","alice","blocked.png",new InputStream(){public int read(){reads.incrementAndGet();return -1;}}))
            .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.INSUFFICIENT_STORAGE));
        assertThat(reads.get()).isZero();assertThat(store.find("tenant",original.mediaId())).contains(original);
        assertThat(Files.readAllBytes(store.resolveOriginal(original))).isEqualTo(png());
    }
    @Test void fallingFreeSpaceDuringCopyCleansPartialAndAllowsLaterRecovery() throws Exception {
        var probes=new java.util.concurrent.atomic.AtomicInteger();var recovered=new java.util.concurrent.atomic.AtomicBoolean();
        var store=new LocalMediaStore(root,4000000,1000,path->recovered.get()||probes.incrementAndGet()<3?10000000:0);
        byte[] data=java.util.Arrays.copyOf(png(),2200000);
        assertThatThrownBy(()->store.register("tenant","alice","partial.png",new ByteArrayInputStream(data)))
            .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.INSUFFICIENT_STORAGE));
        assertThat(probes.get()).isGreaterThanOrEqualTo(3);assertNoFiles();recovered.set(true);
        var saved=store.register("tenant","alice","recovered.png",new ByteArrayInputStream(png()));
        assertThat(store.find("tenant",saved.mediaId())).contains(saved);
    }
    @Test void diskProbeFailureAfterCopyCleansUnpublishedData() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var store=new LocalMediaStore(root,4000000,1000,path->{if(calls.incrementAndGet()==3)throw new IOException("private-disk");return 10000000;});
        assertThatThrownBy(()->store.register("tenant","alice","partial.png",new ByteArrayInputStream(png()))).isInstanceOf(IOException.class);
        assertNoFiles();
    }
    @Test void zeroReserveSkipsProbeButKeepsFileLimit() throws Exception {
        var store=new LocalMediaStore(root,8,0,path->{throw new AssertionError("Must not probe");});
        assertThatThrownBy(()->store.register("tenant","alice","large.png",new ByteArrayInputStream(png())))
            .isInstanceOfSatisfying(MediaException.class,e->assertThat(e.code()).isEqualTo(MediaException.Code.FILE_TOO_LARGE));
        assertNoFiles();
    }

    private void assertNoFiles() throws Exception {
        try(var files=Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile).toList()).isEmpty();
        }
    }
    private byte[] png() throws Exception {
        var output=new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",output);
        return output.toByteArray();
    }
}
