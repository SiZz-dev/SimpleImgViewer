// SPDX-License-Identifier: BSD-3-Clause
// Fixed acceptance operations, never an arbitrary command or production entry point.
#include "sandbox_runner.h"
#include "sandbox_lifecycle.h"
#include <cstdio>
int wmain(int argc,wchar_t** argv){
    using namespace imagegate;
    try{
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
        if(argc==4&&std::wstring(argv[1])==L"--prepare"){
            auto digest=prepare_shared_media_runtime(argv[2],argv[3]);std::printf("%s\n",digest.c_str());return 0;
        }
#endif
        if(argc==3&&std::wstring(argv[1])==L"--recover"){
            auto result=recover_profiles(argv[2]);std::printf("recovered=%u busy=%u\n",result.recovered,result.busy);return result.busy?25:0;
        }
        if(argc!=6
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
            &&argc!=8
#endif
        )return 20;
        SandboxSpec spec;spec.runtime=argv[1];spec.input=argv[2];spec.root=argv[3];
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
        if(argc==8){if(std::wstring(argv[6])!=L"shared")return 20;spec.reuse_runtime=true;
            auto text=std::wstring(argv[7]);spec.runtime_digest=std::string(text.begin(),text.end());}
#endif
        std::wstring mode=argv[4],extra=argv[5];
        const bool probe=mode==L"probe-mov"||mode==L"probe-mpeg"||mode==L"probe-wav"||mode==L"probe-mp3"||mode==L"probe-webm"
            ||mode==L"outside-read"||mode==L"network"||mode==L"network-whitelist";
        const bool video=mode==L"video-mov"||mode==L"video-mpeg";
        if(!probe&&!video&&mode!=L"audio-wav"&&mode!=L"audio-mp3"&&mode!=L"outside-write"&&mode!=L"runtime-write"&&mode!=L"hold"&&mode!=L"timeout")return 20;
        spec.program=probe?L"ffprobe.exe":L"ffmpeg.exe";spec.outputs={L"playback"};
        if(mode==L"timeout")spec.wall_ms=1000;
        spec.arguments=[&](const fs::path& input,const fs::path& out,unsigned long,const fs::path&){
            std::wstring format=L"wav";
            if(mode.find(L"mov")!=std::wstring::npos)format=L"mov";
            if(mode.find(L"mpeg")!=std::wstring::npos)format=L"mpeg";
            if(mode.find(L"mp3")!=std::wstring::npos)format=L"mp3";
            if(mode==L"probe-webm")format=L"matroska,webm";
            if(probe){
                std::vector<std::wstring> args{L"-v",L"error",L"-max_alloc",L"268435456"};
                if(mode!=L"network")args.insert(args.end(),{L"-protocol_whitelist",L"file"});
                if(mode==L"network"||mode==L"network-whitelist")args.insert(args.end(),{L"-rw_timeout",L"1000000"});
                else args.insert(args.end(),{L"-format_whitelist",format});
                if(format==L"mov")args.insert(args.end(),{L"-enable_drefs",L"0",L"-use_absolute_path",L"0"});
                args.insert(args.end(),{L"-show_entries",L"format=duration:stream=codec_name,codec_type,width,height,sample_rate,channels",L"-of",L"json",
                    mode==L"outside-read"||mode==L"network"||mode==L"network-whitelist"?extra:input.native()});return args;
            }
            std::vector<std::wstring> args{L"-hide_banner",L"-loglevel",L"error",L"-nostdin",L"-y",L"-max_alloc",L"268435456",L"-protocol_whitelist",L"file",L"-format_whitelist",format};
            if(format==L"mov")args.insert(args.end(),{L"-enable_drefs",L"0",L"-use_absolute_path",L"0"});
            if(mode==L"hold"||mode==L"timeout")args.insert(args.end(),{L"-re",L"-stream_loop",L"-1"});
            args.insert(args.end(),{L"-threads",L"2",L"-i",input.native(),L"-map_metadata",L"-1",L"-sn",L"-dn"});
            if(video)args.insert(args.end(),{L"-map",L"0:v:0",L"-map",L"0:a:0?",L"-vf",L"scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",L"-c:v",L"libvpx",L"-deadline",L"realtime",L"-cpu-used",L"5",L"-b:v",L"1500k",L"-c:a",L"libopus",L"-f",L"webm"});
            else args.insert(args.end(),{L"-map",L"0:a:0",L"-vn",L"-c:a",L"libmp3lame",L"-b:a",L"192k",L"-f",L"mp3"});
            fs::path runtime_folder=spec.root/L"bin";
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
            if(spec.reuse_runtime)runtime_folder=spec.runtime;
#endif
            if(mode==L"hold")args.insert(args.end(),{L"-flush_packets",L"1"}); // Visible progress in cancellation acceptance only.
            args.insert(args.end(),{L"-threads",L"2",L"-ac",L"2",L"-ar",L"48000",L"-t",L"7200",L"-fs",L"33554432",
                mode==L"outside-write"?extra:mode==L"runtime-write"?(runtime_folder/L"LICENSE.txt").native():(out/L"playback").native()});return args;
        };
        auto result=run_sandbox(spec);std::printf("exit=%lu truncated=%u diagnostics=%s\n",result.exit_code,result.truncated,result.diagnostics.c_str());
        std::fwrite(result.errors.data(),1,result.errors.size(),stderr);
        return result.exit_code==0&&!result.truncated?0:22;
    }catch(const SandboxFailure& failure){std::printf("stage=%s win32=%lu\n",failure.stage,failure.error);return 23;}
    catch(const std::exception&){std::puts("fixture-error");return 24;}
}
