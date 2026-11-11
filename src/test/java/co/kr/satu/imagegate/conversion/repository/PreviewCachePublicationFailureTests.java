package co.kr.satu.imagegate.conversion.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.conversion.service.PreviewQueue;
import co.kr.satu.imagegate.conversion.service.PreviewRenderer;
import co.kr.satu.imagegate.conversion.util.PublicationCrashFixture;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.security.domain.ViewerPrincipal;
import co.kr.satu.imagegate.system.config.ImageGateProperties.Preview;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PreviewCachePolicy;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PreviewCachePublicationFailureTests {
    @TempDir Path root;
    final String key="a".repeat(64);
    Path png(Path file,int edge)throws IOException{
        var image=new BufferedImage(edge,edge,BufferedImage.TYPE_INT_ARGB);
        try{assertThat(ImageIO.write(image,"png",file.toFile())).isTrue();}finally{image.flush();}return file;
    }
    Set<Path> results(Path entry)throws IOException{
        try(var files=Files.list(entry)){return files.filter(p->p.getFileName().toString().startsWith("result-")).collect(Collectors.toSet());}
    }
    @ParameterizedTest @ValueSource(strings={"io","runtime","metadata"})
    void failedReplacementPreservesPreviousGenerationAndActiveLease(String fault)throws Exception{
        try(var cache=new PreviewCache(root)){
            cache.claimWriter();Path entry=cache.entry(key);
            cache.publish(key,1,png(entry.resolve("old.png"),16),16,16,null,png(entry.resolve("old-thumb.png"),8),8,8);
            var previous=results(entry);Path old=cache.content(key,1);byte[] bytes=Files.readAllBytes(old);
            Path state=entry.resolve("state.properties"),backup=entry.resolve("saved-state");byte[] metadata=Files.readAllBytes(state);
            Throwable primary=fault.equals("runtime")?new IllegalStateException("fixture publication rejection"):new IOException("fixture publication rejection");
            try(var lease=cache.lease(key)){
                var thrown=catchThrowable(()->cache.publish(key,1,png(entry.resolve("new.png"),16),16,16,null,
                        png(entry.resolve("new-thumb.png"),8),8,8,()->{
                            assertThat(results(entry)).hasSize(4);
                            if(fault.equals("metadata")){
                                Files.move(state,backup,StandardCopyOption.ATOMIC_MOVE);Files.createDirectory(state);Files.writeString(state.resolve("blocker"),"keep");
                            }else if(primary instanceof IOException io)throw io;
                            else throw (RuntimeException)primary;
                        }));
                try{
                    if(fault.equals("metadata")){
                        assertThat(thrown).isInstanceOf(IOException.class);
                        assertThatThrownBy(()->cache.read(key,1)).isInstanceOf(IOException.class);
                        assertThat(Files.readString(state.resolve("blocker"))).isEqualTo("keep");
                    }else assertThat(thrown).isSameAs(primary);
                    assertThat(results(entry)).containsExactlyInAnyOrderElementsOf(previous);
                    assertThat(Files.readAllBytes(old)).containsExactly(bytes);
                    try(var files=Files.list(entry)){assertThat(files.noneMatch(p->p.getFileName().toString().endsWith(".tmp"))).isTrue();}
                }finally{
                    if(fault.equals("metadata")&&Files.exists(backup)){
                        Files.delete(state.resolve("blocker"));Files.delete(state);Files.move(backup,state,StandardCopyOption.ATOMIC_MOVE);
                    }
                }
                assertThat(Files.readAllBytes(state)).containsExactly(metadata);assertThat(cache.read(key,1).ready()).isTrue();
                var policy=new PreviewCachePolicy(true,Duration.ofHours(1),Duration.ofMinutes(1),1048576);
                assertThat(cache.maintain(policy,System.currentTimeMillis()+Duration.ofDays(8).toMillis()).removed()).isZero();
                assertThat(old).isRegularFile();
                cache.publish(key,1,png(entry.resolve("retry.png"),16),16,16);
                assertThat(cache.read(key,1).ready()).isTrue();assertThat(cache.content(key,1)).isNotEqualTo(old);
                assertThat(Files.readAllBytes(old)).containsExactly(bytes);
            }
        }
    }
    @Test void cleanupFailurePreservesPrimaryExceptionAndUnknownDirectory()throws Exception{
        try(var cache=new PreviewCache(root)){
            cache.claimWriter();cache.state(key,1,"running",null);Path entry=cache.entry(key);
            var primary=new IOException("primary fixture rejection");var blocker=new java.util.concurrent.atomic.AtomicReference<Path>();
            var thrown=catchThrowable(()->cache.publish(key,1,png(entry.resolve("new.png"),16),16,16,null,
                    png(entry.resolve("thumb.png"),8),8,8,()->{
                        Path replaced=results(entry).iterator().next();Files.delete(replaced);Files.createDirectory(replaced);
                        Files.writeString(replaced.resolve("unknown"),"preserve");blocker.set(replaced);throw primary;
                    }));
            assertThat(thrown).isSameAs(primary);assertThat(thrown.getSuppressed()).hasSize(1);
            assertThat(results(entry)).containsExactly(blocker.get());
            assertThat(Files.readString(blocker.get().resolve("unknown"))).isEqualTo("preserve");
            assertThat(cache.read(key,1).ready()).isFalse();
        }
    }
    @Test void haltedPublisherRemainsInterruptedAcrossRestartUntilExplicitRetry()throws Exception{
        Path original=Files.write(root.resolve("original-proof"),new byte[]{1,2,3});
        var profile=new Preview(true,1,2,32,32,100000,"crash-v1");var actor=new ViewerPrincipal("tenant","alice");
        var media=new MediaRecord("id","tenant","alice","version","synthetic.png",MediaType.IMAGE,"image/png",3,"digest","synthetic.png",Instant.EPOCH);
        String crashKey=co.kr.satu.imagegate.conversion.util.PreviewKeys.key(media,actor,1,profile,"fixture");
        var crash=PublicationCrashFixture.crash("preview",root,crashKey);
        assertThat(crash.exitCode()).as("Child output: %s",crash.output()).isEqualTo(73);
        Path entry=root.resolve("previews-v1").resolve(crashKey);var remnants=results(entry);assertThat(remnants).hasSize(2);
        var calls=new AtomicInteger();
        try(var queue=new PreviewQueue(profile,"fixture",new PreviewCache(root),(m,a,p,work)->{
            calls.incrementAndGet();Files.createDirectory(work);return new PreviewRenderer.Result(png(work.resolve("preview.png"),16),16,16);
        })){
            assertThat(queue.status(media,actor,1).errorCode()).isEqualTo("INTERRUPTED");
            assertThat(queue.submit(media,actor,1,false).state()).isEqualTo("failed");assertThat(calls).hasValue(0);
            assertThatThrownBy(()->queue.content(media,actor,1,crashKey,p->fail("No ready artifact")))
                    .isInstanceOf(co.kr.satu.imagegate.media.exception.MediaException.class);
            queue.submit(media,actor,1,true);long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!queue.status(media,actor,1).ready()&&System.nanoTime()<deadline)Thread.sleep(10);
            assertThat(queue.status(media,actor,1).ready()).isTrue();assertThat(calls).hasValue(1);
        }
        try(var reopened=new PreviewCache(root)){
            reopened.claimWriter();var policy=new PreviewCachePolicy(true,Duration.ofHours(1),Duration.ofMinutes(1),1048576);
            var maintenance=reopened.maintain(policy,System.currentTimeMillis()+Duration.ofDays(8).toMillis());
            assertThat(maintenance.removed()).isZero();assertThat(maintenance.skipped()).isGreaterThanOrEqualTo(1);
            for(var remnant:remnants)assertThat(remnant).isRegularFile();
            assertThat(reopened.read(crashKey,1).ready()).isTrue();
        }
        assertThat(Files.readAllBytes(original)).containsExactly(1,2,3);
    }
}
