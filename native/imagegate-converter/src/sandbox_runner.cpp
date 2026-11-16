// SPDX-License-Identifier: BSD-3-Clause
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <userenv.h>
#include <sddl.h>
#include <aclapi.h>
#include <objbase.h>
#include <algorithm>
#include <array>
#include <set>
#include <fstream>
#include <cstdio>
#if defined(IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE) || defined(IMAGEGATE_SHARED_NODE_LPAC_PROBE)
#include <bcrypt.h>
#endif
#include "sandbox_runner.h"
#include "contained_token.h"
#include "sandbox_lifecycle.h"

namespace imagegate {
namespace {
struct Handle {
    HANDLE value = nullptr;
    explicit Handle(HANDLE h = nullptr): value(h) {}
    ~Handle() { if (value && value != INVALID_HANDLE_VALUE) CloseHandle(value); }
    Handle(const Handle&) = delete; Handle& operator=(const Handle&) = delete;
};
struct Local { void* p = nullptr; ~Local() { if(p) LocalFree(p); } };
struct Sid { PSID p = nullptr; ~Sid() { if(p) FreeSid(p); } };
struct Profile {
    std::wstring name;
    SandboxLease* lease=nullptr;
    bool cleanup_ready=true;
    ~Profile() {
        if(!name.empty()) {
            if(!cleanup_ready){std::fputs("imagegate: active child cleanup deferred to recovery\n",stderr);return;}
            try {remove_profile(name);if(lease)lease->finish();}
            catch(...){std::fputs("imagegate: temporary profile cleanup deferred to recovery\n",stderr);}
        }
    }
    void remove() {
        if(!cleanup_ready)throw SandboxFailure{"profile-child-active",ERROR_BUSY};
        remove_profile(name);
        name.clear();
        if(lease)lease->finish();
    }
};
void check(bool ok, const char* stage) { if(!ok) throw SandboxFailure{stage, GetLastError()}; }
void reject(bool ok, const char* stage) { if(!ok) throw SandboxFailure{stage, ERROR_INVALID_PARAMETER}; }
std::wstring sid_text(PSID sid) {
    LPWSTR text = nullptr; check(ConvertSidToStringSidW(sid, &text), "sid-text");
    Local own; own.p = text; return text;
}
// Only canonical local drive paths. Every existing component is checked without following reparses.
void local_path(const fs::path& path, bool exists) {
    auto text = path.native();
    reject(text.size() > 3 && text.size() < 230 && text[1] == L':' && text[2] == L'\\'
        && ((text[0] >= L'A' && text[0] <= L'Z') || (text[0] >= L'a' && text[0] <= L'z'))
        && text.find(L':', 2) == std::wstring::npos && path.lexically_normal() == path, "local-path");
    reject(GetDriveTypeW(path.root_path().c_str()) == DRIVE_FIXED, "local-fixed-drive");
    wchar_t filesystem[32]{};
    check(GetVolumeInformationW(path.root_path().c_str(), nullptr, 0, nullptr, nullptr, nullptr, filesystem, 32), "volume-info");
    reject(std::wstring(filesystem) == L"NTFS", "ntfs-required");
    fs::path part = path.root_path();
    for (const auto& component : path.relative_path()) {
        part /= component;
        DWORD attr = GetFileAttributesW(part.c_str());
        if (attr == INVALID_FILE_ATTRIBUTES && !exists && part == path
            && GetLastError() == ERROR_FILE_NOT_FOUND) return;
        check(attr != INVALID_FILE_ATTRIBUTES, "path-attributes");
        reject(!(attr & FILE_ATTRIBUTE_REPARSE_POINT), "reparse-path");
    }
    reject(fs::canonical(path) == path, "canonical-path");
}
void regular(HANDLE h, unsigned long long limit) {
    BY_HANDLE_FILE_INFORMATION info{};
    check(GetFileInformationByHandle(h, &info), "file-info");
    reject(GetFileType(h) == FILE_TYPE_DISK && !(info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT))
        && info.nNumberOfLinks == 1 && info.nFileSizeHigh == 0 && info.nFileSizeLow > 0 && info.nFileSizeLow <= limit,
        "regular-file");
}
void copy_regular(const fs::path& from, const fs::path& to, unsigned long long limit) {
    local_path(from, true);
    Handle source(CreateFileW(from.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_SEQUENTIAL_SCAN, nullptr));
    check(source.value != INVALID_HANDLE_VALUE, "source-open"); regular(source.value, limit);
    Handle target(CreateFileW(to.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW, FILE_ATTRIBUTE_NORMAL, nullptr));
    check(target.value != INVALID_HANDLE_VALUE, "copy-create");
    std::array<char, 65536> bytes{}; DWORD read = 0, written = 0; unsigned long long total = 0;
    for (;;) {
        check(ReadFile(source.value, bytes.data(), static_cast<DWORD>(bytes.size()), &read, nullptr), "copy-read");
        if (!read) break;
        total += read; reject(total <= limit, "copy-limit");
        check(WriteFile(target.value, bytes.data(), read, &written, nullptr) && written == read, "copy-write");
    }
}
void protect(const fs::path& path, const std::wstring& owner, const std::wstring& app, const wchar_t* access,const wchar_t* integrity=L"LW") {
    std::wstring sddl = L"D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;" + owner
        + L")(A;OICI;" + access + L";;;" + app + L")S:(ML;OICI;NW;;;"+integrity+L")";
    Local descriptor;
    check(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1,
        reinterpret_cast<PSECURITY_DESCRIPTOR*>(&descriptor.p), nullptr), "acl-parse");
    PACL dacl = nullptr, sacl = nullptr; BOOL present = FALSE, defaults = FALSE;
    check(GetSecurityDescriptorDacl(descriptor.p, &present, &dacl, &defaults) && present, "acl-dacl");
    check(GetSecurityDescriptorSacl(descriptor.p, &present, &sacl, &defaults) && present, "acl-label");
    // Ownership implies WRITE_DAC, not WRITE_OWNER (required for a label). Establish
    // the parent's explicit rights before lowering integrity; never request SeSecurityPrivilege.
    DWORD result = SetNamedSecurityInfoW(const_cast<LPWSTR>(path.c_str()), SE_FILE_OBJECT,
        DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION, nullptr, nullptr, dacl, nullptr);
    if(result != ERROR_SUCCESS) throw SandboxFailure{"acl-dacl-apply", result};
    result = SetNamedSecurityInfoW(const_cast<LPWSTR>(path.c_str()), SE_FILE_OBJECT,
        LABEL_SECURITY_INFORMATION, nullptr, nullptr, nullptr, sacl);
    if(result != ERROR_SUCCESS) throw SandboxFailure{"acl-label-apply", result};
}
std::wstring quote(const std::wstring& arg) {
    std::wstring out = L"\""; unsigned slashes = 0;
    for(wchar_t c : arg) {
        if(c == L'\\') { ++slashes; continue; }
        if(c == L'\"') { out.append(slashes * 2 + 1, L'\\'); out += c; }
        else { out.append(slashes, L'\\'); out += c; }
        slashes = 0;
    }
    out.append(slashes * 2, L'\\'); return out + L"\"";
}
struct Attributes {
    std::vector<unsigned char> buffer; LPPROC_THREAD_ATTRIBUTE_LIST list = nullptr;
    Attributes() {
        SIZE_T size = 0; InitializeProcThreadAttributeList(nullptr, 5, 0, &size);
        buffer.resize(size); list = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(buffer.data());
        check(InitializeProcThreadAttributeList(list, 5, 0, &size), "attributes-init");
    }
    ~Attributes() { if(list) DeleteProcThreadAttributeList(list); }
    void put(DWORD_PTR key, void* value, SIZE_T size) {
        check(UpdateProcThreadAttribute(list, 0, key, value, size, nullptr, nullptr), "attribute-update");
    }
};
}
#if defined(IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE) || defined(IMAGEGATE_SHARED_NODE_LPAC_PROBE)
namespace {
const std::set<std::wstring> shared_media_names{L"ffmpeg.exe",L"ffprobe.exe",L"avcodec-62.dll",L"avdevice-62.dll",
    L"avfilter-11.dll",L"avformat-62.dll",L"avutil-60.dll",L"swresample-6.dll",L"swscale-9.dll",L"LICENSE.txt"};
struct Sha256 {
    BCRYPT_ALG_HANDLE algorithm=nullptr;BCRYPT_HASH_HANDLE hash=nullptr;
    Sha256(){check(BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,nullptr,0)==0,"sha-provider");
        if(BCryptCreateHash(algorithm,&hash,nullptr,0,nullptr,0,0)!=0){BCryptCloseAlgorithmProvider(algorithm,0);throw SandboxFailure{"sha-create",ERROR_INVALID_DATA};}}
    ~Sha256(){if(hash)BCryptDestroyHash(hash);if(algorithm)BCryptCloseAlgorithmProvider(algorithm,0);}
    void add(const char* bytes,ULONG count){check(BCryptHashData(hash,reinterpret_cast<PUCHAR>(const_cast<char*>(bytes)),count,0)==0,"sha-update");}
    std::string finish(){unsigned char bytes[32]{};check(BCryptFinishHash(hash,bytes,sizeof(bytes),0)==0,"sha-finish");
        std::string result;const char* digits="0123456789abcdef";for(auto c:bytes){result+=digits[c>>4];result+=digits[c&15];}return result;}
};
std::string hash_handle(HANDLE handle){Sha256 hash;std::array<char,65536> bytes{};DWORD got=0;
    while(true){check(ReadFile(handle,bytes.data(),static_cast<DWORD>(bytes.size()),&got,nullptr),"shared-hash-read");if(!got)break;hash.add(bytes.data(),got);}return hash.finish();}
}
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
struct SharedMediaLease::Impl {
    std::vector<std::unique_ptr<Handle>> files;
    std::string digest;
    explicit Impl(const fs::path& root){
        local_path(root,true);
        auto directory=std::make_unique<Handle>(CreateFileW(root.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
        check(directory->value!=INVALID_HANDLE_VALUE,"shared-directory-pin");files.push_back(std::move(directory));
        unsigned count=0;for(const auto& entry:fs::directory_iterator(root))reject(++count<=10&&shared_media_names.count(entry.path().filename().native()),"shared-runtime-inventory");
        reject(count==10,"shared-runtime-inventory");
        Sha256 inventory;unsigned long long total=0;
        for(const auto& name:shared_media_names){auto path=root/name;local_path(path,true);
            auto file=std::make_unique<Handle>(CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_SEQUENTIAL_SCAN,nullptr));
            check(file->value!=INVALID_HANDLE_VALUE,"shared-file-pin");regular(file->value,134217728);
            LARGE_INTEGER size{};check(GetFileSizeEx(file->value,&size),"shared-size");total+=size.QuadPart;reject(total<=268435456,"shared-runtime-total");
            std::string line=path.filename().u8string()+"\t"+std::to_string(size.QuadPart)+"\t"+hash_handle(file->value)+"\n";
            inventory.add(line.data(),static_cast<ULONG>(line.size()));files.push_back(std::move(file));
        }digest=inventory.finish();
    }
};
SharedMediaLease::SharedMediaLease(const fs::path& root,const std::string& expected):impl(std::make_unique<Impl>(root)){
    reject(expected.size()==64&&expected==impl->digest,"shared-runtime-digest");
}
SharedMediaLease::~SharedMediaLease()=default;
std::string prepare_shared_media_runtime(const fs::path& source,const fs::path& target){
    local_path(source,true);local_path(target,false);reject(!fs::exists(target),"shared-new-root");
    unsigned count=0;for(const auto& entry:fs::directory_iterator(source))reject(++count<=10&&shared_media_names.count(entry.path().filename().native()),"shared-source-inventory");
    reject(count==10,"shared-source-inventory");
    Handle token;check(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token.value),"shared-owner-token");DWORD size=0;GetTokenInformation(token.value,TokenUser,nullptr,0,&size);
    std::vector<unsigned char> user(size);check(GetTokenInformation(token.value,TokenUser,user.data(),size,&size),"shared-owner-user");
    auto owner=sid_text(reinterpret_cast<TOKEN_USER*>(user.data())->User.Sid);
    check(CreateDirectoryW(target.c_str(),nullptr),"shared-create");
    unsigned long long total=0;
    for(const auto& name:shared_media_names){copy_regular(source/name,target/name,134217728);total+=fs::file_size(target/name);reject(total<=268435456,"shared-runtime-total");
        protect(target/name,owner,L"S-1-15-2-2",L"FRFX",L"ME");}
    protect(target,owner,L"S-1-15-2-2",L"FRFX",L"ME");
    SharedMediaLease::Impl snapshot(target);
    return snapshot.digest;
}
#endif
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
namespace {
std::vector<fs::path> node_tree(const fs::path& root){
    local_path(root,true);std::vector<fs::path> paths;
    const std::set<fs::path> font_paths{L"fonts",L"fonts/NotoSansCJKkr-Regular.otf",L"fonts/fonts.conf",L"fonts/LICENSE",L"fonts/NOTICE.txt",L"fonts/profile.json"};
    for(const auto& entry:fs::recursive_directory_iterator(root)){
        reject(paths.size()<512,"shared-node-count");local_path(entry.path(),true);
        auto relative=entry.path().lexically_relative(root);auto first=relative.begin()->native();
        reject(first==L"node_modules"||first==L"backend"||relative==L"node.exe"||relative==L"LICENSE"||relative==L"package.json"||relative==L"image-job-entry.mjs"||relative==L"sharp-worker.cjs"||font_paths.count(relative),"shared-node-inventory");
        auto text=relative.generic_u8string();reject(text.find_first_of("\t\r\n")==std::string::npos,"shared-node-name");
        if(!entry.is_directory()){auto ext=entry.path().extension().native(),name=entry.path().filename().native();
            reject(relative==L"node.exe"||ext==L".mjs"||ext==L".cjs"||ext==L".js"||ext==L".json"||ext==L".node"||ext==L".dll"||ext==L".md"||name.find(L"LICENSE")==0||name.find(L"NOTICE")==0||font_paths.count(relative),"shared-node-extension");}
        paths.push_back(relative);
    }
    std::sort(paths.begin(),paths.end(),[](const fs::path& a,const fs::path& b){return a.generic_u8string()<b.generic_u8string();});
    for(const auto& required:{L"node.exe",L"LICENSE",L"package.json",L"image-job-entry.mjs",L"backend/conversion/util/image-worker.mjs"})reject(fs::is_regular_file(root/required),"shared-node-missing");
    if(fs::exists(root/L"fonts"))for(const auto& relative:font_paths)reject(relative==L"fonts"?fs::is_directory(root/relative):fs::is_regular_file(root/relative),"shared-font-missing");
    return paths;
}
std::unique_ptr<Handle> pin_directory(const fs::path& path){
    auto handle=std::make_unique<Handle>(CreateFileW(path.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
    check(handle->value!=INVALID_HANDLE_VALUE,"shared-node-directory-pin");return handle;
}
}
struct SharedNodeLease::Impl {
    std::vector<std::unique_ptr<Handle>> handles;std::string digest;
    explicit Impl(const fs::path& root){
        local_path(root,true);handles.push_back(pin_directory(root));auto paths=node_tree(root);Sha256 inventory;unsigned long long total=0;
        for(const auto& relative:paths){auto file=root/relative;std::string line;
            if(fs::is_directory(file)){handles.push_back(pin_directory(file));line="D\t"+relative.generic_u8string()+"\n";}
            else{
                auto handle=std::make_unique<Handle>(CreateFileW(file.c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_SEQUENTIAL_SCAN,nullptr));
                check(handle->value!=INVALID_HANDLE_VALUE,"shared-node-file-pin");regular(handle->value,relative==L"node.exe"?268435456:33554432);
                LARGE_INTEGER size{};check(GetFileSizeEx(handle->value,&size),"shared-node-size");total+=size.QuadPart;reject(total<=268435456,"shared-node-total");
                line="F\t"+relative.generic_u8string()+"\t"+std::to_string(size.QuadPart)+"\t"+hash_handle(handle->value)+"\n";handles.push_back(std::move(handle));
            }inventory.add(line.data(),static_cast<ULONG>(line.size()));
        }digest=inventory.finish();
    }
};
SharedNodeLease::SharedNodeLease(const fs::path& root,const std::string& expected):impl(std::make_unique<Impl>(root)){reject(expected.size()==64&&expected==impl->digest,"shared-node-digest");}
SharedNodeLease::~SharedNodeLease()=default;
std::string prepare_shared_node_runtime(const fs::path& source,const fs::path& target){
    auto paths=node_tree(source);local_path(target,false);reject(!fs::exists(target),"shared-node-new-root");
    Handle token;check(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token.value),"shared-node-owner-token");DWORD size=0;GetTokenInformation(token.value,TokenUser,nullptr,0,&size);
    std::vector<unsigned char> user(size);check(GetTokenInformation(token.value,TokenUser,user.data(),size,&size),"shared-node-owner-user");auto owner=sid_text(reinterpret_cast<TOKEN_USER*>(user.data())->User.Sid);
    check(CreateDirectoryW(target.c_str(),nullptr),"shared-node-create");unsigned long long total=0;
    for(const auto& relative:paths){auto destination=target/relative;
        if(fs::is_directory(source/relative))check(CreateDirectoryW(destination.c_str(),nullptr),"shared-node-directory");
        else{copy_regular(source/relative,destination,relative==L"node.exe"?268435456:33554432);total+=fs::file_size(destination);reject(total<=268435456,"shared-node-total");}
        protect(destination,owner,L"S-1-15-2-2",L"FRFX",L"ME");
    }protect(target,owner,L"S-1-15-2-2",L"FRFX",L"ME");SharedNodeLease::Impl snapshot(target);return snapshot.digest;
}
#endif
#endif
SandboxResult run_sandbox(const SandboxSpec& spec) {
#ifdef IMAGEGATE_BOUND_LPAC
    reject(spec.wall_ms>=100&&spec.wall_ms<=120000,"wall-budget");
#ifdef IMAGEGATE_BOUND_IMAGE_LPAC
    reject(spec.program==L"node.exe"&&spec.reuse_runtime&&!spec.bound_document.empty()&&spec.bound_document.size()<=1048576&&spec.bound_stamps.size()<=200,"bound-profile");
#else
    reject((spec.program==L"ffmpeg.exe"||spec.program==L"ffprobe.exe")&&spec.reuse_runtime,"bound-media-profile");
#endif
    reject(spec.memory_mib>=64&&spec.memory_mib<=4096&&spec.cpu_seconds>=1&&spec.cpu_seconds<=600&&spec.cpu_rate>=1&&spec.cpu_rate<=100,"bound-resources");
    const HANDLE control=GetStdHandle(STD_INPUT_HANDLE);
    const auto parent_alive=[&](){DWORD available=0;check(PeekNamedPipe(control,nullptr,0,nullptr,&available,nullptr)!=FALSE,"parent-pipe");};
    parent_alive();
    local_path(spec.host_work,true);local_path(spec.runtime_use,true);
    reject(spec.runtime_use.filename()==L"runtime.use"&&spec.runtime==spec.runtime_use.parent_path()/L"payload","runtime-use-binding");
    SECURITY_ATTRIBUTES lease_security{sizeof(SECURITY_ATTRIBUTES),nullptr,TRUE};
    Handle host_lease(CreateFileW((spec.host_work/L"lease.sqlite").c_str(),GENERIC_READ,0,&lease_security,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
    check(host_lease.value!=INVALID_HANDLE_VALUE,"host-work-lease");regular(host_lease.value,262144);
    auto active_work=[&](){auto attr=GetFileAttributesW((spec.host_work/L"retired").c_str());reject(attr==INVALID_FILE_ATTRIBUTES&&GetLastError()==ERROR_FILE_NOT_FOUND,"retired-work");};
    active_work();
    Handle runtime_use(CreateFileW(spec.runtime_use.c_str(),GENERIC_READ,FILE_SHARE_READ,&lease_security,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
    check(runtime_use.value!=INVALID_HANDLE_VALUE,"runtime-use-open");regular(runtime_use.value,1);
    char marker=0;DWORD got=0;check(ReadFile(runtime_use.value,&marker,1,&got,nullptr)&&got==1&&marker=='1',"runtime-use-marker");
#else
    reject(spec.wall_ms >= 100 && spec.wall_ms <= 30000, "wall-budget");
#endif
    local_path(spec.runtime, true);
#ifdef IMAGEGATE_BOUND_LPAC
    if(!spec.no_input)
#endif
    local_path(spec.input, true); local_path(spec.root, false);
    reject(!fs::exists(spec.root) && fs::is_directory(spec.runtime), "new-root");
    reject(spec.program == L"imagegate-contained-worker.exe" || spec.program == L"imagegate-access-probe.exe"
        || spec.program == L"imagegate-network-probe.exe"
#ifdef IMAGEGATE_NODE_LPAC_PROBE
        || spec.program == L"node.exe"
#endif
#ifdef IMAGEGATE_FFMPEG_LPAC_PROBE
        || spec.program == L"ffmpeg.exe" || spec.program == L"ffprobe.exe"
#endif
#ifdef IMAGEGATE_STREAM_LPAC_PROBE
        || spec.program == L"imagegate-stream-probe.exe"
#endif
        , "program");
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
    std::unique_ptr<SharedMediaLease> runtime_lease;
    if(spec.reuse_runtime){reject(spec.program==L"ffmpeg.exe"||spec.program==L"ffprobe.exe","shared-program");runtime_lease=std::make_unique<SharedMediaLease>(spec.runtime,spec.runtime_digest);}
#endif
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
    std::unique_ptr<SharedNodeLease> node_runtime_lease;
    if(spec.reuse_runtime){reject(spec.program==L"node.exe","shared-node-program");node_runtime_lease=std::make_unique<SharedNodeLease>(spec.runtime,spec.runtime_digest);}
#endif
    storage_admission(spec.root.parent_path(),spec.storage);
    SandboxLease lease(spec.root.parent_path()/L".imagegate-lpac-state");
    std::wstring name=lease.profile_name();
    Profile profile;
    Sid app; HRESULT hr = CreateAppContainerProfile(name.c_str(), name.c_str(), L"ImageGate synthetic conversion job",
        nullptr, 0, &app.p);
    if(FAILED(hr)) {
        if(hr==HRESULT_FROM_WIN32(ERROR_ALREADY_EXISTS))lease.finish();
        throw SandboxFailure{"app-sid", static_cast<DWORD>(hr)};
    }
    profile.name = name;profile.lease=&lease;
    Handle token; check(OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token.value), "parent-token");
    DWORD size = 0; GetTokenInformation(token.value, TokenUser, nullptr, 0, &size);
    std::vector<unsigned char> user(size);
    check(GetTokenInformation(token.value, TokenUser, user.data(), size, &size), "parent-user");
    auto owner = sid_text(reinterpret_cast<TOKEN_USER*>(user.data())->User.Sid), app_sid = sid_text(app.p);
    check(CreateDirectoryW(spec.root.c_str(), nullptr), "root-create");
    protect(spec.root, owner, app_sid, L"0x1200a9");
    fs::path bin = spec.root / L"bin", input = spec.root / L"input", out = spec.root / L"out";
    for(const auto& dir : {bin, input, out}) check(CreateDirectoryW(dir.c_str(), nullptr), "stage-directory");
    const fs::path private_bin=bin;
#if defined(IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE) || defined(IMAGEGATE_SHARED_NODE_LPAC_PROBE)
    if(spec.reuse_runtime)bin=spec.runtime;
#endif
    PWSTR profile_folder=nullptr;
    hr=GetAppContainerFolderPath(app_sid.c_str(),&profile_folder);
    if(FAILED(hr))throw SandboxFailure{"profile-folder",static_cast<DWORD>(hr)};
    fs::path api_profile(profile_folder);CoTaskMemFree(profile_folder);
    fs::path profile_path=profile_storage_root(name),expected_profile=profile_path/L"AC";
    api_profile.make_preferred();expected_profile.make_preferred();
    reject(_wcsicmp(api_profile.c_str(),expected_profile.c_str())==0,"profile-layout");
    StorageWatch storage(out,profile_path,spec.storage);
    const std::set<std::wstring> dlls{L"libc++.dll",L"libexpat-1.dll",L"libffi-8.dll",L"libgio-2.0-0.dll",
        L"libglib-2.0-0.dll",L"libgmodule-2.0-0.dll",L"libgobject-2.0-0.dll",L"libjpeg-62.dll",L"libpng16-16.dll",
        L"libsharpyuv-0.dll",L"libtiff-6.dll",L"libunwind.dll",L"libvips-42.dll",L"libwebp-7.dll",L"libz1.dll"};
#ifdef IMAGEGATE_FFMPEG_LPAC_PROBE
    const bool ffmpeg_probe=spec.program==L"ffmpeg.exe"||spec.program==L"ffprobe.exe";
    const std::set<std::wstring> media_files{L"ffmpeg.exe",L"ffprobe.exe",L"avcodec-62.dll",L"avdevice-62.dll",
        L"avfilter-11.dll",L"avformat-62.dll",L"avutil-60.dll",L"swresample-6.dll",L"swscale-9.dll",L"LICENSE.txt"};
    unsigned media_count=0;unsigned long long media_bytes=0;
#endif
#ifdef IMAGEGATE_NODE_SHARP_LPAC_PROBE
    // Test-only hierarchical Node/sharp closure. Production keeps the fixed flat inventory.
    if(spec.program==L"node.exe") {
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
        if(!spec.reuse_runtime){
#endif
        unsigned count=0; unsigned long long total=0;
        for(const auto& entry : fs::recursive_directory_iterator(spec.runtime)) {
            reject(++count<=512,"runtime-count");
            local_path(entry.path(),true);
            auto relative=entry.path().lexically_relative(spec.runtime);
            auto target=bin/relative;
            const auto first=relative.begin()->native();
            reject(first==L"node_modules" || first==L"backend" || relative==L"image-job-entry.mjs" || relative==L"node.exe" || relative==L"LICENSE"
                || relative==L"sharp-worker.cjs" || relative==L"package.json","runtime-inventory");
            if(entry.is_directory()) {
                check(CreateDirectoryW(target.c_str(),nullptr),"runtime-directory");
                protect(target,owner,app_sid,L"FRFX");
            } else {
                auto ext=entry.path().extension().native();
                auto name=entry.path().filename().native();
                reject(relative==L"node.exe" || relative==L"LICENSE" || ext==L".cjs" || ext==L".js"
                    || ext==L".mjs" || ext==L".json" || ext==L".node" || ext==L".dll" || ext==L".md"
                    || name.find(L"LICENSE")==0 || name.find(L"NOTICE")==0,"runtime-extension");
                total+=entry.file_size();reject(total<=268435456,"runtime-total");
                copy_regular(entry.path(),target,relative==L"node.exe"?268435456:33554432);
                protect(target,owner,app_sid,L"FRFX");
            }
        }
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
        }
#endif
    } else
#endif
    for(const auto& entry : fs::directory_iterator(spec.runtime)) {
        auto filename = entry.path().filename().native();
#ifdef IMAGEGATE_FFMPEG_LPAC_PROBE
        if(ffmpeg_probe){
            reject(media_files.count(filename)&&++media_count<=media_files.size(),"media-runtime-inventory");
            media_bytes+=entry.file_size();reject(media_bytes<=268435456,"media-runtime-total");
        }else
#endif
        reject(filename == spec.program || dlls.count(filename)
#ifdef IMAGEGATE_NODE_LPAC_PROBE
            || (spec.program==L"node.exe"&&filename==L"LICENSE")
#endif
            , "runtime-inventory");
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
        if(spec.reuse_runtime)continue; // The held snapshot was hashed before profile creation.
#endif
        copy_regular(entry.path(), bin / filename,
#ifdef IMAGEGATE_FFMPEG_LPAC_PROBE
            ffmpeg_probe?134217728:
#endif
#ifdef IMAGEGATE_NODE_LPAC_PROBE
            spec.program==L"node.exe"?268435456:
#endif
            33554432);
        protect(bin / filename, owner, app_sid, L"FRFX");
    }
#ifdef IMAGEGATE_FFMPEG_LPAC_PROBE
    if(ffmpeg_probe)for(const auto& name:media_files)reject(fs::is_regular_file(bin/name),"media-runtime-missing");
#endif
    reject(fs::is_regular_file(bin / spec.program), "program-missing");
    if(spec.program == L"imagegate-contained-worker.exe")
        for(const auto& dll : dlls) reject(fs::is_regular_file(bin / dll), "dll-missing");
    fs::path staged_input = input /
#ifdef IMAGEGATE_NODE_LPAC_PROBE
        (spec.program==L"node.exe"?L"source.cjs":L"source.tif");
#else
        L"source.tif";
#endif
#ifdef IMAGEGATE_FFMPEG_LPAC_PROBE
    if(ffmpeg_probe)staged_input=input/L"source.media";
#endif
#ifdef IMAGEGATE_BOUND_LPAC
    if(!spec.no_input){copy_regular(spec.input,staged_input,spec.input_limit);protect(staged_input,owner,app_sid,L"FR");}
#else
    copy_regular(spec.input, staged_input, 104857600);
    protect(staged_input, owner, app_sid, L"FR");
#endif
    protect(input, owner, app_sid, L"FRFX"); protect(private_bin, owner, app_sid, L"FRFX");
    for(const auto& canary : {std::make_pair(L"all-app.check", L"S-1-15-2-1"),
            std::make_pair(L"restricted-app.check", L"S-1-15-2-2")}) {
        fs::path file=private_bin/canary.first;
        {std::ofstream marker(file,std::ios::binary);marker<<"ImageGate LPAC policy canary";marker.close();reject(marker.good(),"canary-create");}
        protect(file,owner,canary.second,L"FR");
    }
    protect(out, owner, app_sid, L"0x1301bf"); // Modify only; no WRITE_DAC / WRITE_OWNER.
#ifdef IMAGEGATE_BOUND_IMAGE_LPAC
    parent_alive();
    unsigned stamp_index=0;unsigned long long stamp_bytes=0;
    for(const auto& source:spec.bound_stamps){
        parent_alive();wchar_t name[32]{};std::swprintf(name,32,L"stamp-%03u.png",stamp_index++);
        auto target=input/name;copy_regular(source,target,2097152);stamp_bytes+=fs::file_size(target);reject(stamp_bytes<=64ULL*1024*1024,"stamp-total");
        protect(target,owner,app_sid,L"FR");
    }
    auto bound_manifest=out/L"job.json";
    {std::ofstream stream(bound_manifest,std::ios::binary);stream.write(spec.bound_document.data(),spec.bound_document.size());stream.close();reject(stream.good(),"bound-document-write");}
    protect(bound_manifest,owner,app_sid,L"FR");
#endif
#ifdef IMAGEGATE_NODE_SHARP_LPAC_PROBE
    if(!spec.job_template.empty()) {
        auto staged_template=input/L"job-template.json";
        copy_regular(spec.job_template,staged_template,1048576);
        protect(staged_template,owner,app_sid,L"FR");
        if(!spec.stamp.empty()) {
            copy_regular(spec.stamp,input/L"stamp.png",10485760);
            protect(input/L"stamp.png",owner,app_sid,L"FR");
        }
        std::ifstream stream(staged_template,std::ios::binary);
        std::string document((std::istreambuf_iterator<char>(stream)),std::istreambuf_iterator<char>());
        auto json_path=[](const fs::path& path){std::string text="\"";for(char c:path.u8string()){if(c=='\\'||c=='\"')text+='\\';text+=c;}return text+'\"';};
        for(const auto& binding:std::vector<std::pair<std::string,fs::path>>{
            {"@INPUT@",staged_input},{"@OUTPUT@",out/L"export.png"},{"@PREVIEW@",out/L"preview.png"},
            {"@THUMBNAIL@",out/L"thumbnail.png"},{"@STAMP@",input/L"stamp.png"}}) {
            const std::string token="\""+binding.first+"\"",value=json_path(binding.second);
            std::size_t offset=0;while((offset=document.find(token,offset))!=std::string::npos){document.replace(offset,token.size(),value);offset+=value.size();}
        }
        auto manifest=out/L"job.json";std::ofstream job(manifest,std::ios::binary);job<<document;job.close();reject(job.good(),"job-write");
        protect(manifest,owner,app_sid,L"FR");
    }
#endif

    Handle job(CreateJobObjectW(nullptr, lease.job_name().c_str())); check(job.value != nullptr, "job-create");
    reject(GetLastError()!=ERROR_ALREADY_EXISTS,"job-collision");
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_PROCESS_MEMORY | JOB_OBJECT_LIMIT_JOB_MEMORY
        | JOB_OBJECT_LIMIT_ACTIVE_PROCESS | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        | JOB_OBJECT_LIMIT_PROCESS_TIME | JOB_OBJECT_LIMIT_JOB_TIME;
    limits.BasicLimitInformation.ActiveProcessLimit = 1;
    limits.BasicLimitInformation.PerProcessUserTimeLimit.QuadPart = 300000000;
    limits.BasicLimitInformation.PerJobUserTimeLimit.QuadPart = 300000000;
    limits.ProcessMemoryLimit = limits.JobMemoryLimit = static_cast<SIZE_T>(512) * 1024 * 1024;
#ifdef IMAGEGATE_BOUND_LPAC
    limits.ProcessMemoryLimit=limits.JobMemoryLimit=static_cast<SIZE_T>(spec.memory_mib)*1024*1024;
    limits.BasicLimitInformation.PerProcessUserTimeLimit.QuadPart=static_cast<LONGLONG>(spec.cpu_seconds)*10000000;
    limits.BasicLimitInformation.PerJobUserTimeLimit=limits.BasicLimitInformation.PerProcessUserTimeLimit;
#endif
    check(SetInformationJobObject(job.value, JobObjectExtendedLimitInformation, &limits, sizeof(limits)), "job-limits");
#ifdef IMAGEGATE_BOUND_LPAC
    JOBOBJECT_CPU_RATE_CONTROL_INFORMATION rate{};rate.ControlFlags=JOB_OBJECT_CPU_RATE_CONTROL_ENABLE|JOB_OBJECT_CPU_RATE_CONTROL_HARD_CAP;rate.CpuRate=spec.cpu_rate*100;
    check(SetInformationJobObject(job.value,JobObjectCpuRateControlInformation,&rate,sizeof(rate)),"job-cpu-rate");
#endif
    SECURITY_ATTRIBUTES inheritable{sizeof(SECURITY_ATTRIBUTES), nullptr, TRUE};
    Handle pipe_read, pipe_write, error_read, error_write;
    check(CreatePipe(&pipe_read.value, &pipe_write.value, &inheritable, 0), "pipe");
    check(SetHandleInformation(pipe_read.value, HANDLE_FLAG_INHERIT, 0), "pipe-private");
    check(CreatePipe(&error_read.value, &error_write.value, &inheritable, 0), "stderr-pipe");
    check(SetHandleInformation(error_read.value, HANDLE_FLAG_INHERIT, 0), "stderr-pipe-private");
    Handle nul(CreateFileW(L"NUL", GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, &inheritable, OPEN_EXISTING, 0, nullptr));
    check(nul.value != INVALID_HANDLE_VALUE, "stdin");
    Attributes attributes;
    SECURITY_CAPABILITIES capabilities{}; capabilities.AppContainerSid = app.p;
    Local registry_sid; SID_AND_ATTRIBUTES registry_cap{};
    // Compiled test-only program, never selectable by the production launcher's CLI.
    if(spec.program == L"imagegate-network-probe.exe"
#ifdef IMAGEGATE_NODE_LPAC_REGISTRY_PROBE
        || spec.program==L"node.exe"
#endif
#ifdef IMAGEGATE_FFMPEG_LPAC_REGISTRY_PROBE
        || spec.program==L"ffmpeg.exe" || spec.program==L"ffprobe.exe"
#endif
        ) {
        check(ConvertStringSidToSidW(registry_read_sid,&registry_sid.p),"test-registry-capability");
        registry_cap={registry_sid.p,SE_GROUP_ENABLED};capabilities.Capabilities=&registry_cap;capabilities.CapabilityCount=1;
    }
    DWORD lpac = PROCESS_CREATION_ALL_APPLICATION_PACKAGES_OPT_OUT, child = PROCESS_CREATION_CHILD_PROCESS_RESTRICTED;
    std::vector<HANDLE> handles{pipe_write.value, error_write.value, nul.value};
#ifdef IMAGEGATE_BOUND_LPAC
    handles.push_back(host_lease.value);handles.push_back(runtime_use.value);
#endif
    attributes.put(PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES, &capabilities, sizeof(capabilities));
    attributes.put(PROC_THREAD_ATTRIBUTE_ALL_APPLICATION_PACKAGES_POLICY, &lpac, sizeof(lpac));
    attributes.put(PROC_THREAD_ATTRIBUTE_CHILD_PROCESS_POLICY, &child, sizeof(child));
    attributes.put(PROC_THREAD_ATTRIBUTE_HANDLE_LIST, handles.data(), handles.size()*sizeof(HANDLE));
    attributes.put(PROC_THREAD_ATTRIBUTE_JOB_LIST, &job.value, sizeof(job.value));
    STARTUPINFOEXW startup{}; startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = nul.value; startup.StartupInfo.hStdOutput = pipe_write.value;
    startup.StartupInfo.hStdError = error_write.value;
    startup.lpAttributeList = attributes.list;
    std::wstring command = quote((bin / spec.program).native());
    for(const auto& arg : spec.arguments(staged_input, out, GetCurrentProcessId(),profile_path)) command += L" " + quote(arg);
    reject(command.size() < 16000, "command-limit");
    wchar_t windows[MAX_PATH]{}; check(GetWindowsDirectoryW(windows, MAX_PATH) > 0, "windows-directory");
    std::vector<std::wstring> env{L"APPDATA="+out.native(),L"HOME="+out.native(),L"LOCALAPPDATA="+out.native(),
        L"PATH="+bin.native()+L";"+windows+L"\\System32",L"SYSTEMROOT="+std::wstring(windows),
        L"TEMP="+out.native(),L"TMP="+out.native(),L"USERPROFILE="+out.native(),L"WINDIR="+std::wstring(windows)};
    std::vector<wchar_t> environment;
#ifdef IMAGEGATE_BOUND_IMAGE_LPAC
    env.push_back(L"SIV_LPAC=1");
    if(fs::is_regular_file(bin/L"fonts/fonts.conf")){
        env.push_back(L"FONTCONFIG_FILE="+(bin/L"fonts/fonts.conf").native());
        env.push_back(L"FONTCONFIG_PATH="+(bin/L"fonts").native());
        env.push_back(L"XDG_CACHE_HOME="+api_profile.native());
        env.push_back(L"SIV_NOTE_FONT=noto-sans-cjk-kr-2.004");
    }
#endif
    std::sort(env.begin(),env.end());
    for(const auto& value : env) { environment.insert(environment.end(), value.begin(), value.end()); environment.push_back(0); }
    environment.push_back(0);
    PROCESS_INFORMATION process{};
    check(CreateProcessW((bin / spec.program).c_str(), command.data(), nullptr, nullptr, TRUE,
        EXTENDED_STARTUPINFO_PRESENT | CREATE_SUSPENDED | DETACHED_PROCESS | CREATE_UNICODE_ENVIRONMENT,
        environment.data(), out.c_str(), &startup.StartupInfo, &process), "create-contained");
    Handle child_process(process.hProcess), child_thread(process.hThread);
    profile.cleanup_ready=false;
    struct ChildExit {
        HANDLE job,process;Profile& profile;
        ~ChildExit(){TerminateJobObject(job,24);profile.cleanup_ready=WaitForSingleObject(process,5000)==WAIT_OBJECT_0;}
    } child_exit{job.value,child_process.value,profile};
    BOOL in_job=FALSE;
    check(IsProcessInJob(child_process.value,job.value,&in_job)&&in_job,"create-job-membership");
#ifdef IMAGEGATE_BOUND_LPAC
    parent_alive();active_work();
#endif
    check(ResumeThread(child_thread.value) != static_cast<DWORD>(-1), "resume");
    CloseHandle(pipe_write.value); pipe_write.value = nullptr;
    CloseHandle(error_write.value); error_write.value = nullptr;
    ULONGLONG deadline = GetTickCount64() + spec.wall_ms;
    ULONGLONG next_storage_check=0;
    SandboxResult result{23, {}, false};
    auto pending=[](HANDLE pipe){DWORD count=0;if(!PeekNamedPipe(pipe,nullptr,0,nullptr,&count,nullptr)){
        check(GetLastError()==ERROR_BROKEN_PIPE,"pipe-peek");return DWORD{0};}return count;};
    auto drain=[&](HANDLE pipe,std::string& output,bool& truncated){
        DWORD available=pending(pipe);if(!available)return;
        char buffer[4096];DWORD got=0;
        check(ReadFile(pipe,buffer,std::min<DWORD>(available,sizeof(buffer)),&got,nullptr),"channel-read");
        auto keep=std::min<std::size_t>(
#ifdef IMAGEGATE_BOUND_LPAC
            spec.channel_limit
#else
            8192
#endif
            -output.size(),got);
        output.append(buffer,keep);truncated|=keep!=got;
    };
    for(;;) {
#ifdef IMAGEGATE_BOUND_LPAC
        parent_alive();
#endif
        if(GetTickCount64()>=next_storage_check){storage.inspect();next_storage_check=GetTickCount64()+100;}
        // One bounded read per channel prevents a busy writer starving its peer.
        drain(pipe_read.value,result.diagnostics,result.stdout_truncated);
        drain(error_read.value,result.errors,result.stderr_truncated);
        result.truncated=result.stdout_truncated||result.stderr_truncated;
        if(result.truncated){TerminateJobObject(job.value,24);check(WaitForSingleObject(child_process.value,5000)==WAIT_OBJECT_0,"channel-limit-kill");}
        DWORD state = WaitForSingleObject(child_process.value, 10);
        if(state == WAIT_OBJECT_0) {
            // The child can write and exit between PeekNamedPipe and Wait.
            // Recheck after exit so its final diagnostic bytes are not dropped.
            if(pending(pipe_read.value)||pending(error_read.value))continue;
            check(GetExitCodeProcess(child_process.value, &result.exit_code), "exit-code");
            if(result.truncated)result.exit_code=24;
            break;
        }
        check(state == WAIT_TIMEOUT, "wait");
        if(GetTickCount64() >= deadline) {
            TerminateJobObject(job.value, 24); WaitForSingleObject(child_process.value, 5000);
            throw SandboxFailure{"wall-timeout", WAIT_TIMEOUT};
        }
    }
    storage.inspect();
#ifdef IMAGEGATE_BOUND_LPAC
    if(result.exit_code==0)for(const auto& name:spec.outputs)reject(fs::is_regular_file(out/name),"bound-output-missing");
#endif
    // Untrusted files are never followed or recursively deleted by the trusted parent.
    local_path(out, true);
    for(const auto& entry : fs::directory_iterator(out)) {
        auto name = entry.path().filename().native();
        reject(std::find(spec.outputs.begin(), spec.outputs.end(), name) != spec.outputs.end(), "output-inventory");
        local_path(entry.path(), true);
        Handle file(CreateFileW(entry.path().c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
            FILE_FLAG_OPEN_REPARSE_POINT, nullptr));
        check(file.value != INVALID_HANDLE_VALUE, "output-open"); regular(file.value,
#ifdef IMAGEGATE_BOUND_LPAC
            spec.output_limit
#else
            33554432
#endif
        );
    }
    profile.cleanup_ready=true; // The loop observed WAIT_OBJECT_0 before validating output.
    storage.release();
    profile.remove();
    return result;
}
}
