package co.kr.satu.imagegate.playback.service;

import co.kr.satu.imagegate.playback.domain.*;
import co.kr.satu.imagegate.playback.exception.PlaybackException;
import co.kr.satu.imagegate.playback.util.PlaybackFiles;
import co.kr.satu.imagegate.playback.util.PlaybackDeadline;
import java.util.function.LongSupplier;
import co.kr.satu.imagegate.media.domain.*;
import co.kr.satu.imagegate.conversion.util.process.*;
import co.kr.satu.imagegate.system.config.ImageGateProperties.PlaybackProcessing;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fixed local-file recipe. No client options, URLs, shell or external playlist demuxers. */
public final class FfmpegEngine implements PlaybackEngine {
    private final PlaybackProcessing profile;private final Path ffmpeg,ffprobe;
    private final ProcessExecutor processes;private final String identity;
    private final NativeModuleSnapshot module;
    private final LongSupplier clock;
    private final JsonMapper json=JsonMapper.builder().build();
    private record Probe(double duration,int width,int height,double fps,int video,int audio,String videoCodec,String audioCodec,String format) {}
    public FfmpegEngine(PlaybackProcessing profile,ProcessExecutor processes,Path checkDirectory) throws IOException {
        this(profile,processes,checkDirectory,System::nanoTime);
    }
    FfmpegEngine(PlaybackProcessing profile,ProcessExecutor processes,Path checkDirectory,LongSupplier clock) throws IOException {
        this.profile=profile;this.processes=processes;this.clock=Objects.requireNonNull(clock);
        ffmpeg=toolPath(profile.ffmpeg());ffprobe=toolPath(profile.ffprobe());
        module=NativeModuleSnapshot.capture(List.of(ffmpeg,ffprobe),false);
        module.requirePinnedDigest(ffmpeg,profile.ffmpegSha256());module.requirePinnedDigest(ffprobe,profile.ffprobeSha256());
        String a=build(ffmpeg,checkDirectory),b=build(ffprobe,checkDirectory);
        if(!a.equals(b))throw new IOException("Mismatched FFmpeg tool versions");
        module.verifyContent();
        identity=profile.cacheVersion()+":"+profile.toString()+":"+a+":"+module.fingerprint();
    }
    private static Path toolPath(String executable) throws IOException {
        Path requested=Path.of(executable);
        if(!requested.isAbsolute()||!requested.equals(requested.normalize()))throw new IOException("Canonical absolute tool path required");
        PlaybackFiles.regular(requested,256L*1024*1024);return requested;
    }
    private String build(Path executable,Path work) throws IOException {
        String version=run(List.of(executable.toString(),"-version"),work,Duration.ofSeconds(10));
        String license=run(List.of(executable.toString(),"-L"),work,Duration.ofSeconds(10)).toLowerCase(Locale.ROOT);
        if(version.contains("--enable-gpl")||version.contains("--enable-nonfree")||!version.contains("--enable-shared")
                ||!license.contains("gnu lesser general public license"))
            throw new IOException("An approved LGPL shared build is required");
        if(!version.contains("--enable-libvpx")||!version.contains("--enable-libopus")||!version.contains("--enable-libmp3lame"))
            throw new IOException("Required LGPL-compatible encoders unavailable");
        int start=version.indexOf("version ")+8,end=version.indexOf(' ',start);
        if(start<8||end<=start)throw new IOException("Invalid tool version");
        return version.substring(start,end);
    }
    private String run(List<String> command,Path work,Duration timeout) throws IOException {
        module.verifyAttributes();ProcessExecutor.Result result;
        try{result=processes.execute(command,work,timeout);}finally{module.verifyAttributes();}
        if(result.exitCode()!=0||result.outputTruncated())throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);
        return result.output();
    }
    private String run(List<String> command,Path work,PlaybackDeadline deadline) throws IOException {
        deadline.check();
        module.verifyAttributes();
        ProcessExecutor.Result result;
        try{result=processes.execute(command,work,deadline.remaining());}
        finally{module.verifyAttributes();}
        deadline.check();
        if(result.exitCode()!=0||result.outputTruncated())throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);
        return result.output();
    }
    @Override public String identity(){return identity;}
    private Probe probe(Path input,Path work,String formats,PlaybackDeadline deadline) throws IOException {
        var command=new ArrayList<>(List.of(ffprobe.toString(),"-v","error","-max_alloc","268435456",
                "-protocol_whitelist","file","-format_whitelist",formats,"-threads","2","-probesize","10000000","-analyzeduration","10000000",
                "-show_entries","format=duration,format_name:stream=index,codec_type,codec_name,width,height,avg_frame_rate,sample_rate,channels:stream_disposition=attached_pic:stream_side_data=rotation",
                "-of","json","-i",input.toString()));
        if(formats.equals("mov"))command.addAll(command.indexOf("-i"),List.of("-enable_drefs","0","-use_absolute_path","0"));
        JsonNode tree;
        try{tree=json.readTree(run(command,work,deadline));}
        catch(tools.jackson.core.JacksonException invalid){throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);}
        deadline.check();
        var streams=tree.get("streams");var format=tree.get("format");
        if(streams==null||!streams.isArray()||streams.isEmpty()||streams.size()>8||format==null)
            throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);
        double duration=number(format.path("duration").asText());
        if(duration<=0||duration>profile.maxDurationSeconds())throw new PlaybackException(PlaybackException.Code.LIMIT_EXCEEDED);
        int video=-1,audio=-1,width=0,height=0;double fps=0;String vc=null,ac=null;
        for(var stream:streams) {
            String type=stream.path("codec_type").asText();int index=stream.path("index").asInt(-1);
            if(index<0||index>128)throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);
            if(type.equals("video")&&stream.path("disposition").path("attached_pic").asInt(0)==0&&video<0) {
                video=index;vc=stream.path("codec_name").asText();
                width=stream.path("width").asInt();height=stream.path("height").asInt();
                if(width<2||height<2||(long)width*height>profile.maxSourcePixels())throw new PlaybackException(PlaybackException.Code.LIMIT_EXCEEDED);
                String[] rate=stream.path("avg_frame_rate").asText().split("/");
                fps=rate.length==2?number(rate[0])/number(rate[1]):0;
                if(!Double.isFinite(fps)||fps<=0||fps>60)throw new PlaybackException(PlaybackException.Code.LIMIT_EXCEEDED);
                var side=stream.path("side_data_list");
                if(side.isArray())for(var data:side)if(data.has("rotation")) {
                    double angle=number(data.get("rotation").asText());
                    if(angle%90!=0)throw new PlaybackException(PlaybackException.Code.UNSUPPORTED);
                    if(Math.abs(angle)%180==90){int swap=width;width=height;height=swap;}
                }
            } else if(type.equals("audio")&&audio<0) {
                audio=index;ac=stream.path("codec_name").asText();
                double sample=number(stream.path("sample_rate").asText());int channels=stream.path("channels").asInt();
                if(sample<8000||sample>192000||channels<1||channels>8)throw new PlaybackException(PlaybackException.Code.LIMIT_EXCEEDED);
            }
        }
        deadline.check();
        return new Probe(duration,width,height,fps,video,audio,vc,ac,format.path("format_name").asText());
    }
    private static double number(String text) {
        try {double n=Double.parseDouble(text);if(!Double.isFinite(n))throw new NumberFormatException();return n;}
        catch(NumberFormatException invalid){throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);}
    }
    @Override public PlaybackArtifact prepare(MediaRecord record,Path original,Path work) throws IOException {
        var deadline=new PlaybackDeadline(profile.timeout(),clock);
        deadline.check();
        String formats=switch(record.mimeType()){
            case "video/mp4"->"mov";
            case "video/mpeg"->"mpeg,mpegvideo,mpegts";
            case "audio/mpeg"->"mp3";
            case "audio/wav"->"wav";
            default->throw new PlaybackException(PlaybackException.Code.UNSUPPORTED);
        };
        module.verifyContent();
        deadline.check();
        var source=probe(original,work,formats,deadline);
        boolean video=record.mediaType()==MediaType.VIDEO;
        if((video&&source.video<0)||(!video&&source.audio<0))throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);
        Path output=work.resolve(video?"playback.webm":"playback.mp3");
        var command=new ArrayList<>(List.of(ffmpeg.toString(),"-hide_banner","-loglevel","error","-nostdin","-n",
                "-max_alloc","268435456","-protocol_whitelist","file","-format_whitelist",formats,
                "-threads","2","-probesize","10000000","-analyzeduration","10000000","-xerror","-i",original.toString(),
                "-map_metadata","-1","-map_chapters","-1","-sn","-dn"));
        if(formats.equals("mov"))command.addAll(command.indexOf("-i"),List.of("-enable_drefs","0","-use_absolute_path","0"));
        if(video) {
            double scale=Math.min(1,Math.min(1920.0/source.width,1080.0/source.height));
            int w=Math.max(2,(int)(source.width*scale)/2*2),h=Math.max(2,(int)(source.height*scale)/2*2);
            command.addAll(List.of("-map","0:"+source.video,"-c:v","libvpx","-threads","2",
                    "-deadline","realtime","-cpu-used","5","-b:v","1500k","-crf","10","-vf","scale="+w+":"+h));
            if(source.audio>=0)command.addAll(List.of("-map","0:"+source.audio,"-c:a","libopus","-b:a","96k","-ar","48000","-ac","2"));
            else command.add("-an");
            command.addAll(List.of("-f","webm"));
        } else command.addAll(List.of("-map","0:"+source.audio,"-vn","-c:a","libmp3lame","-b:a","128k",
                "-ar","48000","-ac","2","-id3v2_version","0","-write_id3v1","0","-f","mp3"));
        command.addAll(List.of("-t",""+profile.maxDurationSeconds(),"-fs",""+profile.maxOutputBytes(),output.toString()));
        run(command,work,deadline);PlaybackFiles.regular(output,profile.maxOutputBytes());
        if(Files.size(output)<1||Files.size(output)>=profile.maxOutputBytes())throw new PlaybackException(PlaybackException.Code.LIMIT_EXCEEDED);
        var result=probe(output,work,video?"matroska,webm":"mp3",deadline);
        if(Math.abs(result.duration-source.duration)>.75||(video&&(!"vp8".equals(result.videoCodec)
                ||(source.audio>=0&&!"opus".equals(result.audioCodec))))||(!video&&!"mp3".equals(result.audioCodec)))
            throw new PlaybackException(PlaybackException.Code.INVALID_MEDIA);
        module.verifyContent();
        deadline.check();
        return new PlaybackArtifact(output,result.duration,video?"video/webm":"audio/mpeg",
                result.width,result.height,result.videoCodec,result.audioCodec);
    }
}
