// SPDX-License-Identifier: BSD-3-Clause
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <cstdio>
#include <string>

int wmain(int argc,wchar_t** argv){
    if(argc!=2&&argc!=3)return 2;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};BOOL contained=FALSE;
    if(!IsProcessInJob(GetCurrentProcess(),nullptr,&contained)||!contained
        ||!QueryInformationJobObject(nullptr,JobObjectExtendedLimitInformation,&limits,sizeof(limits),nullptr)
        ||!(limits.BasicLimitInformation.LimitFlags&JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
        ||limits.BasicLimitInformation.ActiveProcessLimit!=1)return 3;
    wchar_t nullByte=0;DWORD read=99;
    if(!ReadFile(GetStdHandle(STD_INPUT_HANDLE),&nullByte,1,&read,nullptr)||read!=0)return 4;
    JOBOBJECT_CPU_RATE_CONTROL_INFORMATION rate{};
    if(!QueryInformationJobObject(nullptr,JobObjectCpuRateControlInformation,&rate,sizeof(rate),nullptr)
        ||(rate.ControlFlags&(JOB_OBJECT_CPU_RATE_CONTROL_ENABLE|JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP))!=(JOB_OBJECT_CPU_RATE_CONTROL_ENABLE|JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP)
        ||(limits.BasicLimitInformation.LimitFlags&(JOB_OBJECT_LIMIT_PROCESS_MEMORY|JOB_OBJECT_LIMIT_JOB_MEMORY|JOB_OBJECT_LIMIT_JOB_TIME))!=(JOB_OBJECT_LIMIT_PROCESS_MEMORY|JOB_OBJECT_LIMIT_JOB_MEMORY|JOB_OBJECT_LIMIT_JOB_TIME))return 6;
    if(argc==3&&std::wstring(argv[1])==L"--memory"){
        unsigned long long allocated=0;
        for(unsigned i=0;i<2048;i++){
            void* memory=VirtualAlloc(nullptr,4*1024*1024,MEM_RESERVE|MEM_COMMIT,PAGE_READWRITE);
            if(!memory){std::printf("{\"denied\":true,\"allocatedBytes\":%llu,\"memoryMiB\":%llu,\"cpuRate\":%lu}",allocated,static_cast<unsigned long long>(limits.JobMemoryLimit/1024/1024),rate.CpuRate);return 0;}
            allocated+=4*1024*1024;*static_cast<volatile unsigned char*>(memory)=1;
        }
        return 7;
    }
    FILE* file=_wfopen(argv[argc-1],L"wb");if(!file)return 5;
    std::fprintf(file,"{\"pid\":%lu,\"jobConfirmed\":true,\"stdinIsNull\":true,\"cpuSeconds\":%lld,\"cpuRate\":%lu}",GetCurrentProcessId(),limits.BasicLimitInformation.PerJobUserTimeLimit.QuadPart/10000000,rate.CpuRate);std::fclose(file);
    if(argc==3&&std::wstring(argv[1])==L"--cpu"){volatile unsigned long long value=1;for(;;)value=value*1664525+1013904223;}
    for(;;)Sleep(25);
}
