// SPDX-License-Identifier: BSD-3-Clause
// Test-only: no production server entry point.
#include "sandbox_runner.h"
#include <cstdio>
int wmain(int argc,wchar_t** argv){
    if(argc!=5)return 20;
    using namespace imagegate;
    try{
        SandboxSpec spec;spec.runtime=argv[1];spec.input=argv[2];spec.root=argv[3];
        spec.program=L"node.exe";spec.outputs={L"preview.png",L"thumbnail.png",L"crop.png",L"edited.png",L"probe.json"};
        std::wstring forbidden=argv[4];
        spec.arguments=[&](const fs::path& input,const fs::path& out,unsigned long,const fs::path&){
            return std::vector<std::wstring>{L"--no-warnings",L"--preserve-symlinks",L"--preserve-symlinks-main",
                (spec.root/L"bin"/L"sharp-worker.cjs").native(),input.native(),out.native(),forbidden};
        };
        auto result=run_sandbox(spec);
        std::fwrite(result.errors.data(),1,result.errors.size(),stderr);
        std::printf("exit=%lu truncated=%u diagnostics=%s\n",result.exit_code,result.truncated,result.diagnostics.c_str());
        return result.exit_code==0?0:22;
    }catch(const SandboxFailure& failure){std::printf("stage=%s win32=%lu\n",failure.stage,failure.error);return 23;}
    catch(const std::exception&){std::puts("fixture-error");return 24;}
}
