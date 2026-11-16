// SPDX-License-Identifier: BSD-3-Clause
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <string>
#include <thread>
#include <cstdio>
void emit(DWORD channel,unsigned size,unsigned seed){
    std::string bytes(size,'\0');for(unsigned i=0;i<size;i++)bytes[i]=static_cast<char>((i+seed)%256);
    unsigned offset=0;while(offset<size){DWORD written=0;if(!WriteFile(GetStdHandle(channel),bytes.data()+offset,size-offset,&written,nullptr)||!written)ExitProcess(90);offset+=written;}
}
int wmain(int argc,wchar_t** argv){
    if(argc!=2)return 20;std::wstring mode=argv[1];unsigned out=128,err=128;
    if(mode.rfind(L"snapshot:",0)==0||mode.rfind(L"private:",0)==0){
        const bool private_file=mode.rfind(L"private:",0)==0;auto target=mode.substr(private_file?8:9);
        auto access=[&](DWORD desired){HANDLE file=CreateFileW(target.c_str(),desired,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_BACKUP_SEMANTICS,nullptr);
            if(file==INVALID_HANDLE_VALUE)return GetLastError();CloseHandle(file);return DWORD{0};};
        auto read=access(GENERIC_READ),write=access(FILE_WRITE_DATA),remove=access(DELETE),dacl=access(WRITE_DAC),owner=access(WRITE_OWNER);
        std::printf("{\"read\":%lu,\"write\":%lu,\"delete\":%lu,\"dacl\":%lu,\"owner\":%lu}\n",read,write,remove,dacl,owner);
        return read==(private_file?5:0)&&write==5&&remove==5&&dacl==5&&owner==5?0:1;
    }
    if(mode==L"empty")out=err=0;
    else if(mode==L"boundary")out=err=8192;
    else if(mode==L"stdout-over"){out=8193;err=0;}
    else if(mode==L"stderr-over"){out=0;err=8193;}
    else if(mode==L"flood")out=err=2097152;
    else if(mode!=L"tail"&&mode!=L"nonzero")return 20;
    std::thread peer([&]{emit(STD_ERROR_HANDLE,err,37);});emit(STD_OUTPUT_HANDLE,out,11);peer.join();
    return mode==L"nonzero"?7:0;
}
