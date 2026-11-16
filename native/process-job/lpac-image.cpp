// SPDX-License-Identifier: BSD-3-Clause
// Fixed bound-image transport. No test modes, shell, arbitrary program or argv.
#define NOMINMAX
#include <windows.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include "sandbox_runner.h"
#include "sandbox_lifecycle.h"
#include "lpac-request.h"
int wmain(int argc,wchar_t** argv){
    using namespace imagegate;
    try{
        if(argc==2&&std::wstring(argv[1])==L"--capabilities"){std::puts("{\"protocol\":1,\"profile\":\"bound-image-v1\",\"recovery\":true,\"noteFonts\":true}");return 0;}
        if(inspect_residue_command(argc,argv))return 0;
        if(argc==3&&std::wstring(argv[1])==L"--recover"){
            auto result=recover_profiles(argv[2]);std::printf("recovered=%u busy=%u\n",result.recovered,result.busy);return result.busy?25:0;
        }
        if(argc!=2)return 125;
        Request request(argv[1]);SandboxSpec spec;const auto operation=request.number();request.need(operation==1||operation==2);
        spec.wall_ms=request.number();spec.memory_mib=request.number();spec.cpu_seconds=request.number();spec.cpu_rate=request.number();
        spec.runtime=request.path();spec.runtime_digest=request.text();request.need(spec.runtime_digest.size()==64&&spec.runtime_digest.find_first_not_of("0123456789abcdef")==std::string::npos);
        spec.runtime_use=request.path();spec.host_work=request.path();spec.root=request.path();spec.input=request.path();spec.bound_document=request.text();
        auto count=request.number();request.need(count<=200);for(unsigned i=0;i<count;i++)spec.bound_stamps.push_back(request.path());request.need(request.cursor==request.bytes.size());
        request.need(operation==2||count==0);
        spec.program=L"node.exe";spec.reuse_runtime=true;
        spec.outputs=operation==1?std::vector<std::wstring>{L"job.json",L"preview.png",L"thumbnail.png"}:std::vector<std::wstring>{L"job.json",L"export.png"};
        spec.arguments=[&](const fs::path&,const fs::path& out,unsigned long,const fs::path&){return std::vector<std::wstring>{L"--max-old-space-size=128",L"--no-warnings",L"--preserve-symlinks",L"--preserve-symlinks-main",(spec.runtime/L"backend/conversion/util/image-worker.mjs").native(),(out/L"job.json").native()};};
        const auto result=run_sandbox(spec);
        std::fwrite(result.diagnostics.data(),1,result.diagnostics.size(),stdout);std::fwrite(result.errors.data(),1,result.errors.size(),stderr);
        return result.exit_code==0&&!result.truncated?0:22;
    }catch(const SandboxFailure& failure){std::fprintf(stderr,"LPAC stage=%s win32=%lu\n",failure.stage,failure.error);return std::strcmp(failure.stage,"wall-timeout")==0?124:125;}
    catch(...){std::fputs("LPAC request rejected\n",stderr);return 125;}
}
