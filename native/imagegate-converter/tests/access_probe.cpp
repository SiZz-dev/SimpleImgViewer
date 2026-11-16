// SPDX-License-Identifier: BSD-3-Clause
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <ws2tcpip.h>
#include "contained_token.h"
#include <filesystem>
#include <fstream>
#include <string>
#include <cstdio>
namespace fs = std::filesystem;
DWORD access(const fs::path& path,DWORD mode) {
    HANDLE file=CreateFileW(path.c_str(),mode,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,
        nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr);
    if(file==INVALID_HANDLE_VALUE) return GetLastError();
    CloseHandle(file);return 0;
}
int network(unsigned short port) {
    WSADATA data{};if(WSAStartup(MAKEWORD(2,2),&data)) return -1;
    SOCKET s=socket(AF_INET,SOCK_STREAM,IPPROTO_TCP);
    if(s==INVALID_SOCKET) {int error=WSAGetLastError();WSACleanup();return error;}
    sockaddr_in target{};target.sin_family=AF_INET;target.sin_port=htons(port);target.sin_addr.s_addr=htonl(INADDR_LOOPBACK);
    int result=connect(s,reinterpret_cast<sockaddr*>(&target),sizeof(target));
    int error=result==0?0:WSAGetLastError();closesocket(s);WSACleanup();return error;
}
int wmain(int argc,wchar_t** argv) {
    if(argc==3 && std::wstring(argv[1])==L"network-control") return network(static_cast<unsigned short>(std::stoi(argv[2])))==0?0:30;
    if(argc!=8) return 10;
    fs::path input=argv[1],out=argv[2],forbidden=argv[3];
    auto parent=static_cast<DWORD>(std::stoul(argv[4]));auto port=static_cast<unsigned short>(std::stoi(argv[5]));
#ifdef IMAGEGATE_NETWORK_PROBE
    constexpr bool network_probe=true;
#else
    constexpr bool network_probe=false;
#endif
    bool contained=imagegate::contained_token(network_probe);
    auto mode=std::wstring(argv[6]);
    if(mode==L"timeout"||mode==L"hold"||mode==L"grow-output"||mode==L"grow-profile"||mode==L"ads") {
        if(!contained)return 12;
        {std::ofstream marker(out/L"probe.json");marker<<GetCurrentProcessId();}
        if(mode==L"grow-output"||mode==L"grow-profile"||mode==L"ads") {
            fs::path target=out/L"bulk.bin";
            if(mode==L"grow-profile")target=fs::path(argv[7])/L"AC"/L"Temp"/L"bulk.bin";
            if(mode==L"ads")target=out/L"probe.json:hidden";
            std::ofstream flood(target,std::ios::binary);
            if(!flood)return 14;
            std::string chunk(65536,'x');
            unsigned count=mode==L"ads"?1:32;
            for(unsigned i=0;i<count;i++){flood.write(chunk.data(),chunk.size());flood.flush();Sleep(10);}
            if(!flood.good())return 15;
        }
        for(;;)Sleep(10);
    }
    HANDLE token=nullptr;DWORD app=0,size=0;BOOL job=FALSE;DWORD caps=999;
    if(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token)) {
        GetTokenInformation(token,TokenIsAppContainer,&app,sizeof(app),&size);
        GetTokenInformation(token,TokenCapabilities,nullptr,0,&size);std::vector<unsigned char> data(size);
        if(GetTokenInformation(token,TokenCapabilities,data.data(),size,&size))caps=reinterpret_cast<TOKEN_GROUPS*>(data.data())->GroupCount;
        CloseHandle(token);
    }
    IsProcessInJob(GetCurrentProcess(),nullptr,&job);
    DWORD input_read=access(input,GENERIC_READ),input_write=access(input,GENERIC_WRITE);
    DWORD host_read=access(forbidden,GENERIC_READ),host_write=access(forbidden,GENERIC_WRITE);
    DWORD runtime_write=access(input.parent_path().parent_path()/L"bin"/L"libz1.dll",GENERIC_WRITE);
    DWORD input_dacl=access(input,WRITE_DAC),runtime_dacl=access(input.parent_path().parent_path()/L"bin"/L"libz1.dll",WRITE_DAC);
    HANDLE process=OpenProcess(PROCESS_VM_WRITE|PROCESS_DUP_HANDLE,FALSE,parent);
    DWORD parent_access=process?0:GetLastError();if(process)CloseHandle(process);
    int network_error=network_probe?network(port):0;
    std::ofstream output(out/L"probe.json");
    output<<"{\"contained\":"<<(contained?"true":"false")<<",\"app\":"<<app<<",\"lpacFilePolicy\":"<<(imagegate::lpac_file_policy()?"true":"false")<<",\"caps\":"<<caps<<",\"job\":"<<job<<",\"inputRead\":"<<input_read
        <<",\"inputWrite\":"<<input_write<<",\"hostRead\":"<<host_read<<",\"hostWrite\":"<<host_write
        <<",\"runtimeWrite\":"<<runtime_write<<",\"inputDacl\":"<<input_dacl<<",\"runtimeDacl\":"<<runtime_dacl<<",\"parentAccess\":"<<parent_access
        <<",\"networkAttempted\":"<<(network_probe?"true":"false")<<",\"network\":"<<network_error<<"}";output.close();
    return output.good()&&contained&&input_read==0&&input_write==ERROR_ACCESS_DENIED
        &&host_read==ERROR_ACCESS_DENIED&&host_write==ERROR_ACCESS_DENIED&&runtime_write==ERROR_ACCESS_DENIED
        &&input_dacl==ERROR_ACCESS_DENIED&&runtime_dacl==ERROR_ACCESS_DENIED
        &&parent_access==ERROR_ACCESS_DENIED&&network_error==(network_probe?WSAEACCES:0)?0:11;
}
