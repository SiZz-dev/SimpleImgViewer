package co.kr.satu.imagegate.playback.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PlaybackProcessing;
import co.kr.satu.imagegate.conversion.util.PreviewKeys;
import co.kr.satu.imagegate.conversion.util.process.ProcessExecutor;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegApprovalTests {
    @TempDir Path root;
    PlaybackProcessing profile() throws Exception {
        Path a=Files.write(root.resolve("ffmpeg.exe"),new byte[]{1,2,3}),b=Files.write(root.resolve("ffprobe.exe"),new byte[]{4,5,6});
        return new PlaybackProcessing(true,a.toString(),b.toString(),HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(a))),
                HexFormat.of().formatHex(PreviewKeys.digest().digest(Files.readAllBytes(b))),Duration.ofSeconds(30),1,8,7200,8294400,268435456,32,"v1");
    }
    @Test void rejectsGplNonfreeStaticAndMissingRequiredEncoders() throws Exception {
        var p=profile();
        for(String flags:List.of("--enable-shared --enable-gpl --enable-libvpx --enable-libopus --enable-libmp3lame",
                "--enable-shared --enable-nonfree --enable-libvpx --enable-libopus --enable-libmp3lame",
                "--enable-libvpx --enable-libopus --enable-libmp3lame","--enable-shared")) {
            var process=mock(ProcessExecutor.class);
            when(process.execute(anyList(),any(),any())).thenAnswer(call->{
                List<String> cmd=call.getArgument(0);
                return new ProcessExecutor.Result(0,cmd.contains("-L")?"GNU Lesser General Public License":
                        "ffmpeg version 8.0 configuration: "+flags,false);
            });
            assertThatThrownBy(()->new FfmpegEngine(p,process,root)).isInstanceOf(java.io.IOException.class);
        }
    }
    @Test void acceptsExplicitLgplSharedBuildAndRejectsMismatchedTools() throws Exception {
        var p=profile();var process=mock(ProcessExecutor.class);
        when(process.execute(anyList(),any(),any())).thenAnswer(call->{
            List<String> cmd=call.getArgument(0);
            return new ProcessExecutor.Result(0,cmd.contains("-L")?"GNU Lesser General Public License":
                    "ffmpeg version 8.0 configuration: --enable-shared --enable-libvpx --enable-libopus --enable-libmp3lame",false);
        });
        assertThat(new FfmpegEngine(p,process,root).identity()).contains("v1");
        when(process.execute(anyList(),any(),any())).thenAnswer(call->{
            List<String> cmd=call.getArgument(0);
            return new ProcessExecutor.Result(0,cmd.contains("-L")?"GNU Lesser General Public License":
                    "ffmpeg version "+(cmd.getFirst().endsWith("ffprobe.exe")?"9.0":"8.0")+" configuration: --enable-shared --enable-libvpx --enable-libopus --enable-libmp3lame",false);
        });
        assertThatThrownBy(()->new FfmpegEngine(p,process,root)).isInstanceOf(java.io.IOException.class);
    }

    ProcessExecutor approvedProcess() throws Exception {
        var process=mock(ProcessExecutor.class);
        when(process.execute(anyList(),any(),any())).thenAnswer(call->{
            List<String> cmd=call.getArgument(0);java.nio.file.Path work=call.getArgument(1);
            if(cmd.contains("-L"))return new ProcessExecutor.Result(0,"GNU Lesser General Public License",false);
            if(cmd.contains("-version"))return new ProcessExecutor.Result(0,"ffmpeg version 8.0 configuration: --enable-shared --enable-libvpx --enable-libopus --enable-libmp3lame",false);
            if(cmd.contains("-show_entries")){
                String codec=cmd.getLast().endsWith("playback.mp3")?"mp3":"pcm_s16le";
                return new ProcessExecutor.Result(0,"{\"format\":{\"duration\":\"1\",\"format_name\":\"wav\"},\"streams\":[{\"index\":0,\"codec_type\":\"audio\",\"codec_name\":\""+codec+"\",\"sample_rate\":\"48000\",\"channels\":2}]}",false);
            }
            Files.write(work.resolve("playback.mp3"),new byte[]{1,2,3});return new ProcessExecutor.Result(0,"",false);
        });return process;
    }
    co.kr.satu.imagegate.media.domain.MediaRecord audio() {
        return new co.kr.satu.imagegate.media.domain.MediaRecord(UUID.randomUUID().toString(),"tenant","owner",UUID.randomUUID().toString(),"sample.wav",
                co.kr.satu.imagegate.media.domain.MediaType.AUDIO,"audio/wav",1,"0".repeat(64),"original.wav",java.time.Instant.EPOCH);
    }
    @Test void changedExecutableIsRejectedBeforeAnyFurtherNativeCall() throws Exception {
        var p=profile();var process=approvedProcess();var engine=new FfmpegEngine(p,process,root);clearInvocations(process);
        Path exe=Path.of(p.ffprobe());var modified=Files.getLastModifiedTime(exe);Files.write(exe,new byte[]{7,8,9});Files.setLastModifiedTime(exe,modified);
        assertThatThrownBy(()->engine.prepare(audio(),root.resolve("input.wav"),root)).isInstanceOf(java.io.IOException.class);verifyNoInteractions(process);
    }
    @Test void changedDllIsLatchedAndReviewedRestartChangesCacheIdentity() throws Exception {
        var p=profile();Path dll=Files.write(root.resolve("codec.dll"),new byte[]{1,2,3});var modified=Files.getLastModifiedTime(dll);
        var process=approvedProcess();var engine=new FfmpegEngine(p,process,root);String identity=engine.identity();clearInvocations(process);
        Files.write(dll,new byte[]{9,9,9});Files.setLastModifiedTime(dll,modified);
        assertThatThrownBy(()->engine.prepare(audio(),root.resolve("input.wav"),root)).isInstanceOf(java.io.IOException.class);verifyNoInteractions(process);
        assertThat(engine.identity()).isEqualTo(identity);
        Files.write(dll,new byte[]{1,2,3});Files.setLastModifiedTime(dll,modified);
        assertThatThrownBy(()->engine.prepare(audio(),root.resolve("input.wav"),root)).isInstanceOf(java.io.IOException.class);verifyNoInteractions(process);
        Files.write(dll,new byte[]{8,8,8});var replacement=new FfmpegEngine(p,approvedProcess(),root);
        assertThat(replacement.identity()).isNotEqualTo(identity);
    }
    @Test void libraryAdditionAfterStartupCannotSilentlyUseExistingApproval() throws Exception {
        var p=profile();var process=approvedProcess();var engine=new FfmpegEngine(p,process,root);clearInvocations(process);
        Files.write(root.resolve("added.dll"),new byte[]{1});
        assertThatThrownBy(()->engine.prepare(audio(),root.resolve("input.wav"),root)).isInstanceOf(java.io.IOException.class);verifyNoInteractions(process);
    }
    @Test void startupMutationDoesNotProduceAnApprovedEngine() throws Exception {
        var p=profile();Path dll=Files.write(root.resolve("codec.dll"),new byte[]{1,2,3});var modified=Files.getLastModifiedTime(dll);
        var process=approvedProcess();doAnswer(call->{Files.write(dll,new byte[]{9,9,9});Files.setLastModifiedTime(dll,modified);
            return new ProcessExecutor.Result(0,"ffmpeg version 8.0 configuration: --enable-shared --enable-libvpx --enable-libopus --enable-libmp3lame",false);
        }).when(process).execute(argThat(cmd->cmd.contains("-version")),any(),any());
        assertThatThrownBy(()->new FfmpegEngine(p,process,root)).isInstanceOf(java.io.IOException.class);
    }
    @Test void payloadCreatedWhileDllChangesCannotBeReturnedForPublication() throws Exception {
        var p=profile();Path dll=Files.write(root.resolve("codec.dll"),new byte[]{1,2,3});var modified=Files.getLastModifiedTime(dll);
        var delegate=approvedProcess();var engine=new FfmpegEngine(p,(cmd,work,timeout)->{
            var result=delegate.execute(cmd,work,timeout);
            if(cmd.contains("-show_entries")){Files.write(dll,new byte[]{9,9,9});Files.setLastModifiedTime(dll,modified);}return result;
        },root);
        assertThatThrownBy(()->engine.prepare(audio(),root.resolve("input.wav"),root)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.isRegularFile(root.resolve("playback.mp3"))).isTrue();
        clearInvocations(delegate);assertThatThrownBy(()->engine.prepare(audio(),root.resolve("input.wav"),root)).isInstanceOf(java.io.IOException.class);verifyNoInteractions(delegate);
    }

    @Test void executablePinIsCheckedAgainstCapturedBytesBeforeVersionProcessesRun() throws Exception {
        var p=profile();Files.write(Path.of(p.ffmpeg()),new byte[]{9,9,9});var process=approvedProcess();
        assertThatThrownBy(()->new FfmpegEngine(p,process,root)).isInstanceOf(java.io.IOException.class);verifyNoInteractions(process);
    }
}
