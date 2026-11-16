// SPDX-License-Identifier: BSD-3-Clause
// Windows 10 / Server 2016+: lifetime/resource containment, not a filesystem sandbox.
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#define _WIN32_WINNT 0x0A00
#include <windows.h>
#include <string>
#include <vector>
#include <cstdio>
#include <filesystem>

struct Handle {
    HANDLE value;
    explicit Handle(HANDLE h=nullptr):value(h){}
    ~Handle(){if(value&&value!=INVALID_HANDLE_VALUE)CloseHandle(value);}
    Handle(const Handle&)=delete;
    Handle& operator=(const Handle&)=delete;
};
std::wstring quote(const std::wstring& text){
    std::wstring result=L"\"";size_t slashes=0;
    for(wchar_t c:text){
        if(c==L'\\'){slashes++;continue;}
        if(c==L'"')result.append(slashes*2+1,L'\\');else result.append(slashes,L'\\');
        result+=c;slashes=0;
    }
    result.append(slashes*2,L'\\');result+=L'"';return result;
}
bool parent_alive(HANDLE control){
    DWORD available=0;
    // Only the Node parent owns the write end. No PID lookup or PID-based kill.
    return PeekNamedPipe(control,nullptr,0,nullptr,&available,nullptr)!=FALSE;
}
int fail(){std::fputs("SimpleImgViewer process containment failed\n",stderr);return 125;}
bool number(const wchar_t* text,unsigned low,unsigned high,unsigned& value){
    value=0;if(!*text)return false;
    for(;*text;text++){if(*text<L'0'||*text>L'9')return false;value=value*10+static_cast<unsigned>(*text-L'0');if(value>high)return false;}
    return value>=low;
}
HANDLE duplicate_std(DWORD which){
    HANDLE result=nullptr;
    if(!DuplicateHandle(GetCurrentProcess(),GetStdHandle(which),GetCurrentProcess(),&result,0,TRUE,DUPLICATE_SAME_ACCESS))return nullptr;
    return result;
}
bool medium_or_higher(HANDLE process){
    Handle token;if(!OpenProcessToken(process,TOKEN_QUERY,&token.value))return false;
    DWORD size=0;GetTokenInformation(token.value,TokenIntegrityLevel,nullptr,0,&size);if(!size)return false;
    std::vector<unsigned char> bytes(size);if(!GetTokenInformation(token.value,TokenIntegrityLevel,bytes.data(),size,&size))return false;
    auto sid=reinterpret_cast<TOKEN_MANDATORY_LABEL*>(bytes.data())->Label.Sid;
    return IsValidSid(sid)&&*GetSidSubAuthorityCount(sid)>0&&*GetSidSubAuthority(sid,*GetSidSubAuthorityCount(sid)-1)>=SECURITY_MANDATORY_MEDIUM_RID;
}
int wmain(int argc,wchar_t** argv){
    unsigned memoryMiB=0,cpuSeconds=0,cpuRate=0;
    if(argc<9||std::wstring(argv[1])!=L"--memory-mib"||!number(argv[2],64,4096,memoryMiB)
        ||std::wstring(argv[3])!=L"--cpu-seconds"||!number(argv[4],1,600,cpuSeconds)
        ||std::wstring(argv[5])!=L"--cpu-rate"||!number(argv[6],1,100,cpuRate))return fail();
    int cursor=7;std::wstring work,runtimeUse;
    while(cursor<argc&&std::wstring(argv[cursor])!=L"--"){
        if(cursor+1>=argc||!argv[cursor+1][0])return fail();const std::wstring option=argv[cursor];
        if(option==L"--work"&&work.empty())work=argv[cursor+1];
        else if(option==L"--runtime-use"&&runtimeUse.empty())runtimeUse=argv[cursor+1];else return fail();
        cursor+=2;
    }
    const int first=cursor+1;if(first>=argc)return fail();
    const std::wstring executable=argv[first];
    if(executable.size()<4||executable[1]!=L':'||(executable[2]!=L'\\'&&executable[2]!=L'/'))return fail();
    HANDLE control=GetStdHandle(STD_INPUT_HANDLE);
    if(!parent_alive(control))return fail();
    Handle job(CreateJobObjectW(nullptr,nullptr));if(!job.value)return fail();
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE|JOB_OBJECT_LIMIT_ACTIVE_PROCESS
        |JOB_OBJECT_LIMIT_PROCESS_MEMORY|JOB_OBJECT_LIMIT_JOB_MEMORY|JOB_OBJECT_LIMIT_PROCESS_TIME|JOB_OBJECT_LIMIT_JOB_TIME;
    limits.BasicLimitInformation.ActiveProcessLimit=1;
    limits.ProcessMemoryLimit=static_cast<SIZE_T>(memoryMiB)*1024*1024;limits.JobMemoryLimit=limits.ProcessMemoryLimit;
    limits.BasicLimitInformation.PerProcessUserTimeLimit.QuadPart=static_cast<LONGLONG>(cpuSeconds)*10000000;
    limits.BasicLimitInformation.PerJobUserTimeLimit=limits.BasicLimitInformation.PerProcessUserTimeLimit;
    if(!SetInformationJobObject(job.value,JobObjectExtendedLimitInformation,&limits,sizeof(limits)))return fail();
    JOBOBJECT_CPU_RATE_CONTROL_INFORMATION rate{};rate.ControlFlags=JOB_OBJECT_CPU_RATE_CONTROL_ENABLE|JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;rate.CpuRate=cpuRate*100;
    if(!SetInformationJobObject(job.value,JobObjectCpuRateControlInformation,&rate,sizeof(rate)))return fail();
    SECURITY_ATTRIBUTES security{sizeof(security),nullptr,TRUE};
    Handle workLease;
    if(!work.empty()){
        if(work.size()<4||work[1]!=L':'||work[2]!=L'\\')return fail();
        workLease.value=CreateFileW((work+L"\\lease.sqlite").c_str(),GENERIC_READ|GENERIC_WRITE,0,&security,
            OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        BY_HANDLE_FILE_INFORMATION info{};
        if(workLease.value==INVALID_HANDLE_VALUE||!GetFileInformationByHandle(workLease.value,&info)
            ||GetFileType(workLease.value)!=FILE_TYPE_DISK||info.nNumberOfLinks!=1
            ||(info.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)))return fail();
        DWORD retired=GetFileAttributesW((work+L"\\retired").c_str());
        if(retired!=INVALID_FILE_ATTRIBUTES||GetLastError()!=ERROR_FILE_NOT_FOUND)return fail();
    }
    Handle runtimeLease;
    if(!runtimeUse.empty()){
        namespace fs=std::filesystem;
        try{
            fs::path file(runtimeUse);if(runtimeUse.size()<4||runtimeUse.size()>=260||runtimeUse[1]!=L':'||runtimeUse[2]!=L'\\'||file.filename()!=L"runtime.use"||fs::canonical(file)!=file||fs::path(executable).parent_path()!=file.parent_path()/L"payload")return fail();
            auto part=file.root_path();for(const auto& component:file.relative_path()){part/=component;const DWORD attr=GetFileAttributesW(part.c_str());if(attr==INVALID_FILE_ATTRIBUTES||(attr&FILE_ATTRIBUTE_REPARSE_POINT))return fail();}
        }catch(...){return fail();}
        runtimeLease.value=CreateFileW(runtimeUse.c_str(),GENERIC_READ,FILE_SHARE_READ,&security,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        BY_HANDLE_FILE_INFORMATION info{};char marker=0;DWORD got=0;
        if(runtimeLease.value==INVALID_HANDLE_VALUE||!GetFileInformationByHandle(runtimeLease.value,&info)||GetFileType(runtimeLease.value)!=FILE_TYPE_DISK||info.nNumberOfLinks!=1||(info.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))||info.nFileSizeHigh||info.nFileSizeLow!=1||!ReadFile(runtimeLease.value,&marker,1,&got,nullptr)||got!=1||marker!='1')return fail();
    }
    Handle input(CreateFileW(L"NUL",GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,&security,OPEN_EXISTING,0,nullptr));
    Handle output(duplicate_std(STD_OUTPUT_HANDLE)),error(duplicate_std(STD_ERROR_HANDLE));
    if(input.value==INVALID_HANDLE_VALUE||!output.value||!error.value)return fail();
    std::vector<HANDLE> inherited{input.value,output.value,error.value};
    if(workLease.value)inherited.push_back(workLease.value); // Child retains the lease until actual process exit.
    if(runtimeLease.value)inherited.push_back(runtimeLease.value);
    SIZE_T bytes=0;InitializeProcThreadAttributeList(nullptr,2,0,&bytes);
    std::vector<unsigned char> memory(bytes);
    auto attributes=reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(memory.data());
    if(!InitializeProcThreadAttributeList(attributes,2,0,&bytes))return fail();
    struct AttributeOwner {LPPROC_THREAD_ATTRIBUTE_LIST p;~AttributeOwner(){DeleteProcThreadAttributeList(p);}} owner{attributes};
    if(!UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_HANDLE_LIST,inherited.data(),inherited.size()*sizeof(HANDLE),nullptr,nullptr)
        ||!UpdateProcThreadAttribute(attributes,0,PROC_THREAD_ATTRIBUTE_JOB_LIST,&job.value,sizeof(job.value),nullptr,nullptr))return fail();
    std::wstring command;
    for(int i=first;i<argc;i++){if(i>first)command+=L' ';command+=quote(argv[i]);}
    if(command.size()>=32767)return fail();
    STARTUPINFOEXW startup{};startup.StartupInfo.cb=sizeof(startup);startup.lpAttributeList=attributes;
    startup.StartupInfo.dwFlags=STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput=input.value;startup.StartupInfo.hStdOutput=output.value;startup.StartupInfo.hStdError=error.value;
    PROCESS_INFORMATION child{};
    // Kernel assigns the job at creation; never start first and assign later.
    if(!CreateProcessW(executable.c_str(),command.data(),nullptr,nullptr,TRUE,
        EXTENDED_STARTUPINFO_PRESENT|CREATE_SUSPENDED|CREATE_NO_WINDOW,nullptr,nullptr,&startup.StartupInfo,&child))return fail();
    Handle process(child.hProcess),thread(child.hThread);
    if(!parent_alive(control)||(!runtimeUse.empty()&&!medium_or_higher(process.value))||ResumeThread(thread.value)==static_cast<DWORD>(-1)){
        TerminateJobObject(job.value,125);WaitForSingleObject(process.value,INFINITE);return fail();
    }
    for(;;){
        DWORD wait=WaitForSingleObject(process.value,25);
        if(wait==WAIT_OBJECT_0){DWORD code=125;if(!GetExitCodeProcess(process.value,&code))return fail();return static_cast<int>(code);}
        if(wait==WAIT_FAILED||!parent_alive(control)){
            TerminateJobObject(job.value,125);WaitForSingleObject(process.value,INFINITE);return 125;
        }
    }
}
