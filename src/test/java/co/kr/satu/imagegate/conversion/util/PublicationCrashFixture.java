package co.kr.satu.imagegate.conversion.util;

import co.kr.satu.imagegate.conversion.repository.PreviewCache;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.conversion.util.process.ProcessExecutor;
import co.kr.satu.imagegate.playback.domain.PlaybackArtifact;
import co.kr.satu.imagegate.playback.repository.PlaybackCache;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;

/** Test-only child JVM halts after result staging and before ready metadata; no shutdown hooks run. */
public final class PublicationCrashFixture {
    public static ProcessExecutor.Result crash(String mode,Path root,String key)throws Exception {
        String cp=Path.of(PublicationCrashFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                +File.pathSeparator+Path.of(PreviewCache.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toRealPath();
        return new BoundedProcessExecutor(1).execute(List.of(java.toString(),"-Djava.awt.headless=true","-cp",cp,
                PublicationCrashFixture.class.getName(),mode,root.toAbsolutePath().toString(),key),root.getParent().toRealPath(),Duration.ofSeconds(20));
    }
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[1]);String key=args[2];
        if(args[0].equals("preview")){
            var cache=new PreviewCache(root);cache.claimWriter();cache.state(key,1,"running",null);
            Path work=Files.createDirectory(cache.entry(key).resolve("work-"+UUID.randomUUID()));
            Path png=work.resolve("preview.png"),small=work.resolve("thumbnail.png");
            ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_ARGB),"png",png.toFile());
            ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_ARGB),"png",small.toFile());
            cache.publish(key,1,png,16,16,null,small,8,8,()->Runtime.getRuntime().halt(73));
        }else if(args[0].equals("playback")){
            var cache=new PlaybackCache(root,100000);cache.state(key,"running",null);
            Path work=Files.createDirectory(cache.entry(key,true).resolve("work-"+UUID.randomUUID()));
            Path file=Files.write(work.resolve("playback.mp3"),new byte[128]);
            cache.publish(key,new PlaybackArtifact(file,3,"audio/mpeg",0,0,null,"mp3"),()->Runtime.getRuntime().halt(73));
        }else throw new IllegalArgumentException("Unknown fixture mode");
        throw new AssertionError("Crash fixture returned instead of halting");
    }
}
