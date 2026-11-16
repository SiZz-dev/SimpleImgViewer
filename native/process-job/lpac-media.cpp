// SPDX-License-Identifier: BSD-3-Clause
#define NOMINMAX
#include <windows.h>
#include <cstdio>
#include <cstring>
#include "sandbox_runner.h"
#include "sandbox_lifecycle.h"
#include "lpac-request.h"
int wmain(int argc,wchar_t** argv){
 using namespace imagegate;
 try{
  if(argc==2&&std::wstring(argv[1])==L"--capabilities"){std::puts("{\"protocol\":1,\"profile\":\"bound-media-v1\",\"recovery\":true}");return 0;}
  if(inspect_residue_command(argc,argv))return 0;
  if(argc==3&&std::wstring(argv[1])==L"--recover"){auto result=recover_profiles(argv[2]);std::printf("recovered=%u busy=%u\n",result.recovered,result.busy);return result.busy?25:0;}
  if(argc!=2)return 125;
  Request request(argv[1]);SandboxSpec spec;auto operation=request.number(),tool=request.number(),format=request.number();request.need(operation<6&&tool<2&&format<6);
  spec.wall_ms=request.number();spec.memory_mib=request.number();spec.cpu_seconds=request.number();spec.cpu_rate=request.number();
  spec.runtime=request.path();spec.runtime_digest=request.text();request.need(spec.runtime_digest.size()==64&&spec.runtime_digest.find_first_not_of("0123456789abcdef")==std::string::npos);
  spec.runtime_use=request.path();spec.host_work=request.path();spec.root=request.path();spec.no_input=operation<2;
  if(!spec.no_input)spec.input=request.path();request.need(request.cursor==request.bytes.size());
  spec.program=tool?L"ffprobe.exe":L"ffmpeg.exe";spec.reuse_runtime=true;spec.channel_limit=1048576;spec.output_limit=268435456;spec.input_limit=operation==3?268435456:104857600;spec.storage.output_bytes=268435456;
  if(operation<2)request.need(format==0);
  else if(operation==2)request.need(tool==1&&format>=1&&format<=4);
  else if(operation==3)request.need(tool==1&&(format==4||format==5));
  else if(operation==4)request.need(tool==0&&(format==1||format==2));
  else request.need(tool==0&&(format==3||format==4));
  if(operation>=4)spec.outputs={L"playback"};
  spec.arguments=[&](const fs::path& input,const fs::path& out,unsigned long,const fs::path&){
   if(operation<2)return std::vector<std::wstring>{operation==0?L"-version":L"-L"};
   const std::vector<std::wstring> formats{L"",L"mov",L"mpeg",L"wav",L"mp3",L"matroska,webm"};
   std::vector<std::wstring> args=operation<4?std::vector<std::wstring>{L"-v",L"error"}:std::vector<std::wstring>{L"-hide_banner",L"-loglevel",L"error",L"-nostdin"};
   auto add=[&](std::initializer_list<std::wstring> values){args.insert(args.end(),values.begin(),values.end());};
   add({L"-max_alloc",L"268435456",L"-protocol_whitelist",L"file",L"-format_whitelist",formats[format]});
   if(format==1)add({L"-enable_drefs",L"0",L"-use_absolute_path",L"0"});
   if(operation<4){add({L"-show_streams",L"-show_format",L"-of",L"json",input.native()});return args;}
   add({L"-threads",L"2",L"-i",input.native(),L"-map_metadata",L"-1",L"-sn",L"-dn"});
   if(operation==4)add({L"-map",L"0:v:0",L"-map",L"0:a:0?",L"-vf",L"scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",L"-c:v",L"libvpx",L"-deadline",L"realtime",L"-cpu-used",L"5",L"-b:v",L"1500k",L"-c:a",L"libopus",L"-f",L"webm"});
   else add({L"-map",L"0:a:0",L"-vn",L"-c:a",L"libmp3lame",L"-b:a",L"192k",L"-f",L"mp3"});
   add({L"-threads",L"2",L"-ac",L"2",L"-ar",L"48000",L"-t",L"7200",L"-fs",L"268435456",(out/L"playback").native()});return args;
  };
  auto result=run_sandbox(spec);std::fwrite(result.diagnostics.data(),1,result.diagnostics.size(),stdout);std::fwrite(result.errors.data(),1,result.errors.size(),stderr);return result.exit_code==0&&!result.truncated?0:22;
 }catch(const SandboxFailure& failure){std::fprintf(stderr,"LPAC stage=%s win32=%lu\n",failure.stage,failure.error);return std::strcmp(failure.stage,"wall-timeout")==0?124:125;}
 catch(...){std::fputs("LPAC request rejected\n",stderr);return 125;}
}
