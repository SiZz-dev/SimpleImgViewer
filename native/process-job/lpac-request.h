// SPDX-License-Identifier: BSD-3-Clause
#pragma once
#include <windows.h>
#include <cstdint>
#include "sandbox_runner.h"
namespace {
using namespace imagegate;
struct Request {
    HANDLE handle=INVALID_HANDLE_VALUE;std::string bytes;std::size_t cursor=8;
    ~Request(){if(handle!=INVALID_HANDLE_VALUE)CloseHandle(handle);}
    void need(bool value){if(!value)throw SandboxFailure{"request",ERROR_INVALID_DATA};}
    explicit Request(const fs::path& file){
        need(fs::canonical(file)==file&&file.extension()==L".request");
        fs::path part=file.root_path();for(const auto& component:file.relative_path()){part/=component;auto attr=GetFileAttributesW(part.c_str());need(attr!=INVALID_FILE_ATTRIBUTES&&!(attr&FILE_ATTRIBUTE_REPARSE_POINT));}
        handle=CreateFileW(file.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr);
        need(handle!=INVALID_HANDLE_VALUE);BY_HANDLE_FILE_INFORMATION info{};
        need(GetFileInformationByHandle(handle,&info)&&GetFileType(handle)==FILE_TYPE_DISK&&info.nNumberOfLinks==1&&!(info.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))&&!info.nFileSizeHigh&&info.nFileSizeLow>=8&&info.nFileSizeLow<=1048576);
        bytes.resize(info.nFileSizeLow);DWORD got=0;need(ReadFile(handle,bytes.data(),bytes.size(),&got,nullptr)&&got==bytes.size());need(bytes.compare(0,8,"SIVLPAC1")==0);
    }
    std::uint32_t number(){need(bytes.size()-cursor>=4);std::uint32_t value=0;for(unsigned i=0;i<4;i++)value|=static_cast<std::uint32_t>(static_cast<unsigned char>(bytes[cursor++]))<<(8*i);return value;}
    std::string text(){auto length=number();need(length>0&&length<=1048576&&length<=bytes.size()-cursor);auto value=bytes.substr(cursor,length);cursor+=length;need(value.find('\0')==std::string::npos);need(MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),value.size(),nullptr,0)>0);return value;}
    fs::path path(){auto value=text();int count=MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),value.size(),nullptr,0);std::wstring wide(count,L'\0');need(MultiByteToWideChar(CP_UTF8,MB_ERR_INVALID_CHARS,value.data(),value.size(),wide.data(),count)==count);return fs::path(wide);}
};
}
