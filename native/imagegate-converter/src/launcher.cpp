// SPDX-License-Identifier: BSD-3-Clause
#include "sandbox_runner.h"
#include "sandbox_lifecycle.h"
#include <cstdio>
#include <map>
int wmain(int argc, wchar_t** argv) {
    using namespace imagegate;
    try {
        if(argc==2&&std::wstring(argv[1])==L"--capabilities") {
            // No input path, profile creation or decoder startup in this metadata-only contract.
            std::puts("{\"launcherBuild\":\"imagegate-lpac-launcher-v2\",\"workerBuild\":\"imagegate-libvips-worker-contained-v3\",\"resourcePolicy\":\"windows-job-512m-30cpu-1proc\",\"isolationPolicy\":\"lpac-capabilities-0\",\"storagePolicy\":\"watch-64m-output-16m-profile\",\"deadlineMs\":20000}");
            return 0;
        }
        if(argc==3&&std::wstring(argv[1])==L"--recover-profiles") {
            auto result=recover_profiles(fs::path(argv[2]));
            std::printf("{\"launcherBuild\":\"imagegate-lpac-launcher-v2\",\"recovered\":%u,\"busy\":%u}\n",result.recovered,result.busy);return 0;
        }
        // Private trusted caller only. No arbitrary command/program/shell option.
        if(argc != 23) return 20;
        std::map<std::wstring, std::wstring> args;
        for(int i=1; i<argc; i+=2) if(!args.emplace(argv[i], argv[i+1]).second) return 20;
        for(const auto* key : {L"--runtime",L"--sandbox",L"--input",L"--protocol",L"--operation",L"--page",
                L"--job",L"--max-width",L"--max-height",L"--format",L"--deadline-ms"}) if(!args.count(key)) return 20;
        if(args[L"--deadline-ms"] != L"20000") return 20;
        SandboxSpec spec;
        spec.runtime=args[L"--runtime"]; spec.input=args[L"--input"]; spec.root=args[L"--sandbox"];
        spec.program=L"imagegate-contained-worker.exe";
        spec.outputs={L"result.json",L"preview.png",L"preview.jpg"};
        spec.arguments=[&](const fs::path& input,const fs::path& out,unsigned long,const fs::path&) {
            std::vector<std::wstring> values{L"--input",input.native(),L"--work",out.native()};
            for(const auto* key : {L"--protocol",L"--operation",L"--page",L"--job",L"--max-width",L"--max-height",L"--format"}) {
                values.push_back(key); values.push_back(args.at(key));
            }
            return values;
        };
        auto result=run_sandbox(spec);
        // Decoder diagnostics are not forwarded as public API output.
        std::printf("{\"launcherBuild\":\"imagegate-lpac-launcher-v2\",\"childExit\":%lu,\"diagnosticsTruncated\":%s}\n",
            result.exit_code,result.truncated?"true":"false");
        return result.exit_code == 0 ? 0 : 22;
    } catch(const SandboxFailure& failure) {
        std::printf("{\"stage\":\"%s\",\"win32\":%lu}\n",failure.stage,failure.error); return 23;
    } catch(...) { return 23; }
}
