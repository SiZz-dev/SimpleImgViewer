// SPDX-License-Identifier: BSD-3-Clause
#include "sandbox_runner.h"
#include <cstdio>
std::string hex(const std::string& value){const char* digits="0123456789abcdef";std::string result;for(unsigned char c:value){result+=digits[c>>4];result+=digits[c&15];}return result;}
int wmain(int argc,wchar_t** argv){
    if(argc!=5)return 20;using namespace imagegate;
    try{
        SandboxSpec spec;spec.runtime=argv[1];spec.input=argv[2];spec.root=argv[3];spec.program=L"imagegate-stream-probe.exe";
        spec.arguments=[&](const fs::path&,const fs::path&,unsigned long,const fs::path&){return std::vector<std::wstring>{argv[4]};};
        auto result=run_sandbox(spec);
        std::printf("{\"exit\":%lu,\"truncated\":%s,\"stdoutTruncated\":%s,\"stderrTruncated\":%s,\"stdoutHex\":\"%s\",\"stderrHex\":\"%s\"}\n",
            result.exit_code,result.truncated?"true":"false",result.stdout_truncated?"true":"false",result.stderr_truncated?"true":"false",hex(result.diagnostics).c_str(),hex(result.errors).c_str());return 0;
    }catch(const SandboxFailure& failure){std::printf("stage=%s win32=%lu\n",failure.stage,failure.error);return 23;}
    catch(...){return 24;}
}
