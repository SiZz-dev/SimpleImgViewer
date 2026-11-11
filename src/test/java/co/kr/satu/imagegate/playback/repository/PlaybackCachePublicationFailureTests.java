package co.kr.satu.imagegate.playback.repository;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.playback.domain.PlaybackArtifact;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PlaybackCachePolicy;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PlaybackCachePublicationFailureTests {
    @TempDir Path root;
    final String key="b".repeat(64);
    PlaybackArtifact artifact(Path entry,String name,byte value)throws IOException{
        return new PlaybackArtifact(Files.write(entry.resolve(name),new byte[]{value,value,value}),3,"audio/mpeg",0,0,null,"mp3");
    }
    Set<Path> results(Path entry)throws IOException{
        try(var files=Files.list(entry)){return files.filter(p->p.getFileName().toString().startsWith("result-")).collect(Collectors.toSet());}
    }
    @ParameterizedTest @ValueSource(strings={"io","runtime","metadata"})
    void failedReplacementPreservesPreviousGenerationAndActiveLease(String fault)throws Exception{
        try(var cache=new PlaybackCache(root,100000)){
            Path entry=cache.entry(key,true);cache.publish(key,artifact(entry,"old.mp3",(byte)1));
            var previous=results(entry);Path old=cache.content(key);byte[] bytes=Files.readAllBytes(old);
            Path state=entry.resolve("state.properties"),backup=entry.resolve("saved-state");byte[] metadata=Files.readAllBytes(state);
            Throwable primary=fault.equals("runtime")?new IllegalStateException("fixture rejection"):new IOException("fixture rejection");
            try(var lease=cache.lease(key)){
                var thrown=catchThrowable(()->cache.publish(key,artifact(entry,"new.mp3",(byte)2),()->{
                    assertThat(results(entry)).hasSize(2);
                    if(fault.equals("metadata")){
                        Files.move(state,backup,StandardCopyOption.ATOMIC_MOVE);Files.createDirectory(state);Files.writeString(state.resolve("blocker"),"keep");
                    }else if(primary instanceof IOException io)throw io;
                    else throw (RuntimeException)primary;
                }));
                try{
                    if(fault.equals("metadata")){
                        assertThat(thrown).isInstanceOf(IOException.class);
                        assertThatThrownBy(()->cache.read(key)).isInstanceOf(IOException.class);
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
                assertThat(Files.readAllBytes(state)).containsExactly(metadata);assertThat(cache.read(key).ready()).isTrue();
                var policy=new PlaybackCachePolicy(true,Duration.ofHours(1),Duration.ofMinutes(1),1048576);
                assertThat(cache.maintain(policy,System.currentTimeMillis()+Duration.ofDays(8).toMillis()).removed()).isZero();
                cache.publish(key,artifact(entry,"retry.mp3",(byte)3));
                assertThat(cache.content(key)).isNotEqualTo(old);assertThat(Files.readAllBytes(old)).containsExactly(bytes);
            }
        }
    }
    @Test void cleanupFailurePreservesPrimaryExceptionAndUnknownDirectory()throws Exception{
        try(var cache=new PlaybackCache(root,100000)){
            cache.state(key,"running",null);Path entry=cache.entry(key,true);
            var primary=new IOException("primary fixture rejection");var blocker=new java.util.concurrent.atomic.AtomicReference<Path>();
            var thrown=catchThrowable(()->cache.publish(key,artifact(entry,"new.mp3",(byte)2),()->{
                Path replaced=results(entry).iterator().next();Files.delete(replaced);Files.createDirectory(replaced);
                Files.writeString(replaced.resolve("unknown"),"preserve");blocker.set(replaced);throw primary;
            }));
            assertThat(thrown).isSameAs(primary);assertThat(thrown.getSuppressed()).hasSize(1);
            assertThat(results(entry)).containsExactly(blocker.get());
            assertThat(Files.readString(blocker.get().resolve("unknown"))).isEqualTo("preserve");
            assertThat(cache.read(key).ready()).isFalse();
        }
    }
}
