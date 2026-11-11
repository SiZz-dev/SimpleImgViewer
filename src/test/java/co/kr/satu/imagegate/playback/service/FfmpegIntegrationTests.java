package co.kr.satu.imagegate.playback.service;

import static org.assertj.core.api.Assertions.*;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.playback.exception.PlaybackException;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.process.BoundedProcessExecutor;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PlaybackProcessing;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@Tag("ffmpeg")
class FfmpegIntegrationTests {
    @TempDir Path root;
    final Path ffmpeg=Path.of(System.getenv().getOrDefault("FFMPEG_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffmpeg.exe")).toAbsolutePath();
    final Path ffprobe=Path.of(System.getenv().getOrDefault("FFPROBE_EXECUTABLE","D:/DG_module/ffmpeg/bin/ffprobe.exe")).toAbsolutePath();
    String digest(Path p) throws IOException {
        var d=PreviewKeys.digest();try(var in=Files.newInputStream(p)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}
        return HexFormat.of().formatHex(d.digest());
    }
    PlaybackProcessing profile() throws IOException {
        return new PlaybackProcessing(true,ffmpeg.toString(),ffprobe.toString(),digest(ffmpeg),digest(ffprobe),
                Duration.ofSeconds(60),1,8,7200,8294400,268435456,32,"test-v1");
    }
    @Test void preparesAllFourFormatsWithRealApprovedToolsAndPublishesBrowserFixtures() throws Exception {
        var processes=new BoundedProcessExecutor(1);var engine=new FfmpegEngine(profile(),processes,root);
        Path fixtures=Path.of("build/playback-fixtures").toAbsolutePath();Files.createDirectories(fixtures);
        for(String format:List.of("mp4","mpeg","mp3","wav")) {
            boolean video=format.equals("mp4")||format.equals("mpeg");
            Path input=root.resolve("input."+format),work=Files.createDirectory(root.resolve("work-"+format));
            var command=new ArrayList<>(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-nostdin","-y"));
            if(video)command.addAll(List.of("-f","lavfi","-i","testsrc=size=320x180:rate=25"));
            command.addAll(List.of("-f","lavfi","-i","sine=frequency=440:sample_rate=48000","-t","3"));
            switch(format) {
                case "mp4"->command.addAll(List.of("-c:v","mpeg4","-q:v","5","-c:a","aac","-movflags","+faststart"));
                case "mpeg"->command.addAll(List.of("-c:v","mpeg2video","-c:a","mp2","-f","mpeg"));
                case "mp3"->command.addAll(List.of("-c:a","libmp3lame","-b:a","128k"));
                case "wav"->command.addAll(List.of("-c:a","pcm_s16le"));
            }
            command.add(input.toString());var generated=processes.execute(command,root,Duration.ofSeconds(30));
            assertThat(generated.exitCode()).as("Synthetic "+format+" generation").isZero();
            String hash=digest(input);
            var record=new MediaRecord(UUID.randomUUID().toString(),"tenant","alice",UUID.randomUUID().toString(),
                    "sample."+format,video?co.kr.satu.imagegate.media.domain.MediaType.VIDEO:co.kr.satu.imagegate.media.domain.MediaType.AUDIO,
                    video?(format.equals("mp4")?"video/mp4":"video/mpeg"):(format.equals("mp3")?"audio/mpeg":"audio/wav"),
                    Files.size(input),hash,"original."+format,Instant.now());
            var output=engine.prepare(record,input,work);
            assertThat(output.durationSeconds()).isBetween(2.5,3.5);assertThat(Files.size(output.file())).isPositive();
            assertThat(output.mimeType()).isEqualTo(video?"video/webm":"audio/mpeg");
            assertThat(video?output.videoCodec():output.audioCodec()).isEqualTo(video?"vp8":"mp3");
            assertThat(digest(input)).isEqualTo(hash);
            Files.copy(input,fixtures.resolve("input."+format),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(output.file(),fixtures.resolve(video?"video.webm":"audio.mp3"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    @Test void rejectsWrongHashAndMalformedInputUsingRealTools() throws Exception {
        var p=profile();var wrong=new PlaybackProcessing(true,p.ffmpeg(),p.ffprobe(),"0".repeat(64),p.ffprobeSha256(),
                p.timeout(),1,8,7200,p.maxSourcePixels(),p.maxOutputBytes(),32,"test-v1");
        assertThatThrownBy(()->new FfmpegEngine(wrong,new BoundedProcessExecutor(1),root)).isInstanceOf(IOException.class);
        var engine=new FfmpegEngine(p,new BoundedProcessExecutor(1),root);
        Path invalid=Files.write(root.resolve("invalid.wav"),new byte[512]);
        var record=new MediaRecord(UUID.randomUUID().toString(),"tenant","alice",UUID.randomUUID().toString(),"invalid.wav",
                co.kr.satu.imagegate.media.domain.MediaType.AUDIO,"audio/wav",512,digest(invalid),"original.wav",Instant.now());
        assertThatThrownBy(()->engine.prepare(record,invalid,Files.createDirectory(root.resolve("invalid-work"))))
                .isInstanceOf(PlaybackException.class);
    }
}
