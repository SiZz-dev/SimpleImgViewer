// SPDX-License-Identifier: BSD-3-Clause
// Acceptance only; never selected by the server.
#include "sandbox_runner.h"
#include "sandbox_lifecycle.h"
#include <cstdio>
int wmain(int argc,wchar_t** argv){
    using namespace imagegate;
    try{
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
        if(argc==4&&std::wstring(argv[1])==L"--prepare"){
            auto digest=prepare_shared_node_runtime(argv[2],argv[3]);std::printf("%s\n",digest.c_str());return 0;
        }
#endif
        if(argc==3&&std::wstring(argv[1])==L"--recover"){
            auto result=recover_profiles(argv[2]);std::printf("recovered=%u busy=%u\n",result.recovered,result.busy);return result.busy?25:0;
        }
        if(argc!=8
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
            &&argc!=10
#endif
        )return 20;
        SandboxSpec spec;spec.runtime=argv[1];spec.input=argv[2];spec.root=argv[3];spec.job_template=argv[4];
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
        if(argc==10){if(std::wstring(argv[8])!=L"shared")return 20;spec.reuse_runtime=true;std::wstring text=argv[9];spec.runtime_digest=std::string(text.begin(),text.end());}
#endif
        if(std::wstring(argv[5])!=L"-")spec.stamp=argv[5];
        std::wstring mode=argv[6];
        if(mode!=L"normal"&&mode!=L"hold"&&mode!=L"timeout")return 20;
        if(std::wstring(argv[7])!=L"acceptance")return 20;
        if(mode==L"timeout")spec.wall_ms=1000;
        spec.program=L"node.exe";spec.outputs={L"job.json",L"preview.png",L"thumbnail.png",L"export.png",L"ready.json"};
        spec.arguments=[&](const fs::path&,const fs::path& out,unsigned long,const fs::path&){
            fs::path runtime=spec.root/L"bin";
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
            if(spec.reuse_runtime)runtime=spec.runtime;
#endif
            return std::vector<std::wstring>{L"--max-old-space-size=128",L"--no-warnings",L"--preserve-symlinks",L"--preserve-symlinks-main",
                (runtime/L"image-job-entry.mjs").native(),(out/L"job.json").native(),mode==L"normal"?L"normal":L"hold"};
        };
        auto result=run_sandbox(spec);std::printf("exit=%lu truncated=%u diagnostics=%s\n",result.exit_code,result.truncated,result.diagnostics.c_str());
        std::fwrite(result.errors.data(),1,result.errors.size(),stderr);
        return result.exit_code==0?0:22;
    }catch(const SandboxFailure& failure){std::printf("stage=%s win32=%lu\n",failure.stage,failure.error);return 23;}
    catch(const std::exception&){std::puts("fixture-error");return 24;}
}
