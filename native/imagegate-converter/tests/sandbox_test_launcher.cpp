// SPDX-License-Identifier: BSD-3-Clause
#include "sandbox_runner.h"
#include <cstdio>
int wmain(int argc,wchar_t** argv) {
    if(argc!=7)return 20;
    using namespace imagegate;
    try {
        SandboxSpec spec;spec.runtime=argv[1];spec.input=argv[2];spec.root=argv[3];
        spec.program=L"imagegate-access-probe.exe";spec.outputs={L"probe.json"};
        std::wstring forbidden=argv[4],port=argv[5],mode=argv[6];
        if(mode==L"timeout")spec.wall_ms=500;
        if(mode==L"network")spec.program=L"imagegate-network-probe.exe";
        if(mode==L"grow-output"){spec.storage.output_bytes=1024*1024;spec.outputs.push_back(L"bulk.bin");}
        if(mode==L"grow-profile")spec.storage.profile_bytes=1024*1024;
        if(mode==L"free-space")spec.storage.free_floor_bytes=(1ULL<<62);
        spec.arguments=[&](const fs::path& input,const fs::path& out,unsigned long parent,const fs::path& profile) {
            return std::vector<std::wstring>{input.native(),out.native(),forbidden,std::to_wstring(parent),port,mode,profile.native()};
        };
        auto result=run_sandbox(spec);
        std::fwrite(result.errors.data(),1,result.errors.size(),stderr);
        std::printf("exit=%lu truncated=%u diagnostics=%s\n",result.exit_code,result.truncated,result.diagnostics.c_str());
        return result.exit_code==0?0:22;
    }catch(const SandboxFailure& failure){std::printf("stage=%s win32=%lu\n",failure.stage,failure.error);return 23;}
    catch(const std::exception& e){std::printf("fixture-error=%s\n",e.what());return 24;}
}
