// SPDX-License-Identifier: BSD-3-Clause
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <userenv.h>
#include <sddl.h>
#include <aclapi.h>
#include <objbase.h>
#include <shlobj.h>
#include <algorithm>
#include <array>
#include <cwctype>
#include <cstddef>
#include <regex>
#include "sandbox_lifecycle.h"
namespace imagegate {
namespace {
void need(bool ok,const char* stage) {if(!ok)throw SandboxFailure{stage,GetLastError()};}
void require(bool ok,const char* stage) {if(!ok)throw SandboxFailure{stage,ERROR_INVALID_DATA};}
struct H {
    HANDLE h=INVALID_HANDLE_VALUE;
    H()=default; explicit H(HANDLE v):h(v){}
    ~H(){if(h!=INVALID_HANDLE_VALUE&&h)CloseHandle(h);}
    H(const H&)=delete;H& operator=(const H&)=delete;
    H(H&& other) noexcept:h(other.h){other.h=INVALID_HANDLE_VALUE;}
    H& operator=(H&& other) noexcept {if(h!=INVALID_HANDLE_VALUE&&h)CloseHandle(h);h=other.h;other.h=INVALID_HANDLE_VALUE;return *this;}
};
struct L {void* p=nullptr;~L(){if(p)LocalFree(p);}};
struct Find {HANDLE h=INVALID_HANDLE_VALUE;~Find(){if(h!=INVALID_HANDLE_VALUE)FindClose(h);}};
std::wstring user_sid() {
    H token;need(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token.h),"state-token");
    DWORD size=0;GetTokenInformation(token.h,TokenUser,nullptr,0,&size);std::vector<unsigned char> data(size);
    need(GetTokenInformation(token.h,TokenUser,data.data(),size,&size),"state-user");L text;
    need(ConvertSidToStringSidW(reinterpret_cast<TOKEN_USER*>(data.data())->User.Sid,reinterpret_cast<LPWSTR*>(&text.p)),"state-user-sid");
    return static_cast<wchar_t*>(text.p);
}
void local(const fs::path& p) {
    auto s=p.native();require(s.size()>3&&s.size()<230&&s[1]==L':'&&s[2]==L'\\'
        &&std::iswalpha(s[0])&&s.find(L':',2)==std::wstring::npos&&p.lexically_normal()==p,"state-path");
    wchar_t name[32]{};need(GetVolumeInformationW(p.root_path().c_str(),nullptr,0,nullptr,nullptr,nullptr,name,32),"state-volume");
    require(GetDriveTypeW(p.root_path().c_str())==DRIVE_FIXED&&std::wstring(name)==L"NTFS","state-ntfs");
}
BY_HANDLE_FILE_INFORMATION info(HANDLE h) {
    BY_HANDLE_FILE_INFORMATION i{};need(GetFileInformationByHandle(h,&i),"state-file-info");
    require(GetFileType(h)==FILE_TYPE_DISK&&!(i.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT),"state-reparse");return i;
}
void canonical_handle(HANDLE h,const fs::path& p) {
    wchar_t name[32768]{};DWORD n=GetFinalPathNameByHandleW(h,name,32768,FILE_NAME_NORMALIZED|VOLUME_NAME_DOS);
    need(n>0&&n<32768,"state-final-path");std::wstring actual=name;
    fs::path expected=p;expected.make_preferred();
    require(actual.rfind(L"\\\\?\\",0)==0&&_wcsicmp(actual.substr(4).c_str(),expected.c_str())==0,"state-canonical");
}
H open_dir(const fs::path& p) {
    H h(CreateFileW(p.c_str(),FILE_READ_ATTRIBUTES|FILE_LIST_DIRECTORY|READ_CONTROL,FILE_SHARE_READ|FILE_SHARE_WRITE,
        nullptr,OPEN_EXISTING,FILE_FLAG_BACKUP_SEMANTICS|FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
    need(h.h!=INVALID_HANDLE_VALUE,"state-directory-open");auto i=info(h.h);
    require(i.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY,"state-directory");canonical_handle(h.h,p);return h;
}
std::vector<H> lock_tree(const fs::path& p) {
    local(p);std::vector<H> handles;fs::path part=p.root_path();
    // Lock each ancestor against deletion/rename. Reparse traversal is never accepted.
    for(const auto& c:p.relative_path()){part/=c;handles.push_back(open_dir(part));}return handles;
}
void no_ads(const fs::path& p) {
    WIN32_FIND_STREAM_DATA data{};Find streams;streams.h=FindFirstStreamW(p.c_str(),FindStreamInfoStandard,&data,0);
    if(streams.h==INVALID_HANDLE_VALUE){need(GetLastError()==ERROR_HANDLE_EOF,"storage-stream-open");return;}
    do {require(std::wstring(data.cStreamName)==L"::$DATA","storage-alternate-stream");}
    while(FindNextStreamW(streams.h,&data));need(GetLastError()==ERROR_HANDLE_EOF,"storage-stream-next");
}
// Query the SAME pinned file, not a name that a writer may have atomically
// replaced or marked for deletion between metadata and stream enumeration.
void no_ads_file(HANDLE file){
    alignas(8) unsigned char bytes[65536]{};
    if(!GetFileInformationByHandleEx(file,FileStreamInfo,bytes,sizeof(bytes))){need(GetLastError()==ERROR_HANDLE_EOF,"storage-stream-handle");return;}
    std::size_t offset=0;
    for(unsigned count=0;count<256;count++){
        require(offset<=sizeof(bytes)-offsetof(FILE_STREAM_INFO,StreamName),"storage-stream-layout");
        auto stream=reinterpret_cast<FILE_STREAM_INFO*>(bytes+offset);
        require(stream->StreamNameLength%sizeof(wchar_t)==0&&stream->StreamNameLength<=sizeof(bytes)-offset-offsetof(FILE_STREAM_INFO,StreamName),"storage-stream-layout");
        require(std::wstring(stream->StreamName,stream->StreamNameLength/sizeof(wchar_t))==L"::$DATA","storage-alternate-stream");
        if(!stream->NextEntryOffset)return;
        require(stream->NextEntryOffset>=offsetof(FILE_STREAM_INFO,StreamName)&&stream->NextEntryOffset%8==0&&stream->NextEntryOffset<=sizeof(bytes)-offset,"storage-stream-layout");offset+=stream->NextEntryOffset;
    }
    require(false,"storage-stream-count");
}
void private_acl(HANDLE h,const std::wstring& user,bool directory) {
    L sd;PSID owner=nullptr;PACL dacl=nullptr;
    DWORD status=GetSecurityInfo(h,SE_FILE_OBJECT,OWNER_SECURITY_INFORMATION|DACL_SECURITY_INFORMATION,&owner,nullptr,&dacl,nullptr,&sd.p);
    if(status)throw SandboxFailure{"state-acl-read",status};
    L expected;need(ConvertStringSidToSidW(user.c_str(),&expected.p),"state-owner-sid");
    require(EqualSid(owner,expected.p)&&dacl&&dacl->AceCount==3,"state-acl-owner");
    SECURITY_DESCRIPTOR_CONTROL control{};DWORD revision=0;need(GetSecurityDescriptorControl(sd.p,&control,&revision),"state-acl-control");
    require(!directory||(control&SE_DACL_PROTECTED),"state-acl-protected");
    std::vector<std::wstring> seen;
    for(DWORD i=0;i<dacl->AceCount;i++) {
        void* raw=nullptr;need(GetAce(dacl,i,&raw),"state-ace");auto ace=static_cast<ACCESS_ALLOWED_ACE*>(raw);
        require(ace->Header.AceType==ACCESS_ALLOWED_ACE_TYPE&&ace->Mask==FILE_ALL_ACCESS
            &&!(ace->Header.AceFlags&~(OBJECT_INHERIT_ACE|CONTAINER_INHERIT_ACE|INHERITED_ACE)),"state-ace-rights");
        L text;need(ConvertSidToStringSidW(&ace->SidStart,reinterpret_cast<LPWSTR*>(&text.p)),"state-ace-sid");
        seen.emplace_back(static_cast<wchar_t*>(text.p));
    }
    std::sort(seen.begin(),seen.end());std::vector<std::wstring> wanted{user,L"S-1-5-18",L"S-1-5-32-544"};std::sort(wanted.begin(),wanted.end());
    require(seen==wanted,"state-ace-inventory");
}
void delete_handle(HANDLE h) {
    FILE_DISPOSITION_INFO disposition{TRUE};need(SetFileInformationByHandle(h,FileDispositionInfo,&disposition,sizeof(disposition)),"state-record-delete");
}
std::vector<std::wstring> records(const fs::path& state) {
    std::vector<std::wstring> result;Find find;WIN32_FIND_DATAW data{};
    find.h=FindFirstFileW((state/L"*").c_str(),&data);need(find.h!=INVALID_HANDLE_VALUE,"state-enumerate");
    do {
        std::wstring name=data.cFileName;if(name==L"."||name==L".."||name==L".gate")continue;
        require(result.size()<32&&std::regex_match(name,std::wregex(L"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.lease")),"state-record-name");
        require(!(data.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)),"state-record-type");result.push_back(name);
    }while(FindNextFileW(find.h,&data));need(GetLastError()==ERROR_NO_MORE_FILES,"state-enumerate-next");return result;
}
std::string record_text(const std::wstring& uuid) {return "IMAGEGATE-LPAC-LEASE/1\n"+std::string(uuid.begin(),uuid.end())+"\n";}
void record_info(HANDLE h,const fs::path& path,const std::wstring& user) {
    auto i=info(h);require(!(i.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY)&&i.nNumberOfLinks==1&&i.nFileSizeHigh==0&&i.nFileSizeLow<=128,"state-record-file");
    canonical_handle(h,path);private_acl(h,user,false);no_ads(path);
}
struct State {
    fs::path path;std::wstring user;std::vector<H> parents;H dir;
    State(const fs::path& p,bool create):path(p),user(user_sid()),parents(lock_tree(p.parent_path())) {
        local(path);
        if(create) {
            L sd;auto sddl=L"D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;"+user+L")S:(ML;OICI;NW;;;ME)";
            need(ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(),SDDL_REVISION_1,reinterpret_cast<PSECURITY_DESCRIPTOR*>(&sd.p),nullptr),"state-descriptor");
            SECURITY_ATTRIBUTES sa{sizeof(sa),sd.p,FALSE};
            if(!CreateDirectoryW(path.c_str(),&sa))need(GetLastError()==ERROR_ALREADY_EXISTS,"state-create");
        }
        dir=open_dir(path);private_acl(dir.h,user,true);no_ads(path);
    }
    H gate() {
        H h;
        for(unsigned i=0;i<100;i++) {
            h=H(CreateFileW((path/L".gate").c_str(),GENERIC_READ|GENERIC_WRITE|READ_CONTROL,0,nullptr,OPEN_ALWAYS,FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
            if(h.h!=INVALID_HANDLE_VALUE)break;
            if(GetLastError()!=ERROR_SHARING_VIOLATION)need(false,"state-gate-open");Sleep(10);
        }
        need(h.h!=INVALID_HANDLE_VALUE,"state-gate-busy");record_info(h.h,path/L".gate",user);return h;
    }
    RecoveryResult recover() {
        RecoveryResult result;
        for(const auto& filename:records(path)) {
            H record(CreateFileW((path/filename).c_str(),GENERIC_READ|READ_CONTROL|DELETE,0,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
            if(record.h==INVALID_HANDLE_VALUE&&GetLastError()==ERROR_SHARING_VIOLATION){++result.busy;continue;}
            if(record.h==INVALID_HANDLE_VALUE&&GetLastError()==ERROR_FILE_NOT_FOUND)continue;
            need(record.h!=INVALID_HANDLE_VALUE,"state-record-open");record_info(record.h,path/filename,user);
            auto uuid=filename.substr(0,36);char bytes[129]{};DWORD size=0;
            need(ReadFile(record.h,bytes,128,&size,nullptr),"state-record-read");require(std::string(bytes,size)==record_text(uuid),"state-record-content");
            auto job_name=L"Local\\ImageGate.v4."+uuid;
            H job(OpenJobObjectW(JOB_OBJECT_QUERY,FALSE,job_name.c_str()));
            if(job.h) {
                JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting{};
                need(QueryInformationJobObject(job.h,JobObjectBasicAccountingInformation,&accounting,sizeof(accounting),nullptr),"state-job-query");
                if(accounting.ActiveProcesses){++result.busy;continue;}
            }else need(GetLastError()==ERROR_FILE_NOT_FOUND,"state-job-open");
            remove_profile(L"ImageGate.v4."+uuid);
            delete_handle(record.h);++result.recovered;
        }return result;
    }
};
void free_space(const fs::path& path,unsigned long long floor) {
    ULARGE_INTEGER available{};need(GetDiskFreeSpaceExW(path.c_str(),&available,nullptr,nullptr),"storage-free-query");
    require(available.QuadPart>=floor,"storage-free-floor");
}
struct Scan {
    unsigned long long bytes=0,limit;unsigned entries=0;
    void walk(const fs::path& path,unsigned depth=0) {
        require(depth<=8,"storage-depth");H directory=open_dir(path);no_ads(path);
        Find find;WIN32_FIND_DATAW data{};find.h=FindFirstFileW((path/L"*").c_str(),&data);need(find.h!=INVALID_HANDLE_VALUE,"storage-enumerate");
        do {
            std::wstring name=data.cFileName;if(name==L"."||name==L"..")continue;
            require(++entries<=256,"storage-entry-limit");
            require(!(data.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT),"storage-reparse");
            fs::path child=path/name;
            if(data.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY){walk(child,depth+1);continue;}
            H file(CreateFileW(child.c_str(),FILE_READ_ATTRIBUTES,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
            // Decoder files may be exclusively open. Treat metadata/open failures as failure, never ignore them.
            need(file.h!=INVALID_HANDLE_VALUE,"storage-file-open");auto i=info(file.h);
            try{canonical_handle(file.h,child);}catch(const SandboxFailure& failure){
                if(std::string(failure.stage)=="state-canonical"&&failure.error==ERROR_INVALID_DATA)throw SandboxFailure{"storage-name-changed",ERROR_RETRY};
                throw;
            }
            require(!(i.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY)&&i.nNumberOfLinks==1,"storage-file-type");no_ads_file(file.h);
            unsigned long long size=(static_cast<unsigned long long>(i.nFileSizeHigh)<<32)|i.nFileSizeLow;
            require(size<=limit-bytes,"storage-byte-limit");bytes+=size;
        }while(FindNextFileW(find.h,&data));need(GetLastError()==ERROR_NO_MORE_FILES,"storage-enumerate-next");
    }
};
}
struct SandboxLease::Impl {
    State state;H record;std::wstring profile;
    explicit Impl(const fs::path& folder):state(folder,true) {
        H gate=state.gate();auto recovered=state.recover();require(recovered.busy<4,"state-active-limit");
        GUID id{};need(SUCCEEDED(CoCreateGuid(&id)),"state-guid");wchar_t text[40]{};need(StringFromGUID2(id,text,40)>0,"state-guid-text");
        std::wstring uuid(text+1,36);std::transform(uuid.begin(),uuid.end(),uuid.begin(),[](wchar_t c){return std::towlower(c);});
        profile=L"ImageGate.v4."+uuid;fs::path path=folder/(uuid+L".lease");
        record=H(CreateFileW(path.c_str(),GENERIC_READ|GENERIC_WRITE|READ_CONTROL|DELETE,0,nullptr,CREATE_NEW,FILE_FLAG_OPEN_REPARSE_POINT,nullptr));
        need(record.h!=INVALID_HANDLE_VALUE,"state-record-create");record_info(record.h,path,state.user);
        std::string content=record_text(uuid);DWORD written=0;
        need(WriteFile(record.h,content.data(),static_cast<DWORD>(content.size()),&written,nullptr)&&written==content.size(),"state-record-write");
        need(FlushFileBuffers(record.h),"state-record-flush");
    }
};
SandboxLease::SandboxLease(const fs::path& p):impl_(std::make_unique<Impl>(p)){}
SandboxLease::~SandboxLease()=default;
const std::wstring& SandboxLease::profile_name()const{return impl_->profile;}
std::wstring SandboxLease::job_name()const{return L"Local\\"+impl_->profile;}
void SandboxLease::finish(){delete_handle(impl_->record.h);impl_->record=H();}
RecoveryResult recover_profiles(const fs::path& p){State state(p,false);H gate=state.gate();return state.recover();}
fs::path profile_storage_root(const std::wstring& name) {
    require(std::regex_match(name,std::wregex(L"ImageGate\\.v4\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),"profile-name");
    H token;need(OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY|TOKEN_IMPERSONATE,&token.h),"profile-known-token");
    PWSTR base=nullptr;HRESULT hr=SHGetKnownFolderPath(FOLDERID_LocalAppData,0,token.h,&base);
    if(FAILED(hr)){CoTaskMemFree(base);throw SandboxFailure{"profile-known-folder",static_cast<DWORD>(hr)};}
    fs::path root=fs::path(base)/L"Packages"/name;CoTaskMemFree(base);local(root);return root;
}
void remove_profile(const std::wstring& name) {
    auto root=profile_storage_root(name);
    // Lock ancestors, then preflight the entire generated profile, not just its AC data subfolder.
    auto parents=lock_tree(root.parent_path());DWORD attributes=GetFileAttributesW(root.c_str());
    if(attributes!=INVALID_FILE_ATTRIBUTES){Scan scan{0,~0ULL};scan.walk(root);}
    else need(GetLastError()==ERROR_FILE_NOT_FOUND||GetLastError()==ERROR_PATH_NOT_FOUND,"profile-cleanup-path");
    HRESULT hr=DeleteAppContainerProfile(name.c_str());
    if(FAILED(hr))throw SandboxFailure{"profile-delete",static_cast<DWORD>(hr)};
    require(GetFileAttributesW(root.c_str())==INVALID_FILE_ATTRIBUTES,"profile-cleanup-incomplete");
    need(GetLastError()==ERROR_FILE_NOT_FOUND||GetLastError()==ERROR_PATH_NOT_FOUND,"profile-cleanup-confirm");
}
struct StorageWatch::Impl {
    fs::path out,profile;StorageLimits limits;std::vector<H> output_locks,profile_locks;
    Impl(const fs::path& o,const fs::path& p,StorageLimits l):out(o),profile(p),limits(l),output_locks(lock_tree(o)),profile_locks(lock_tree(p)){}
};
void inspect_residue(const fs::path& root,const std::string& kind,unsigned stamps){
    require(std::regex_match(root.filename().native(),std::wregex(L"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),"residue-id");
    const bool preview=kind=="image-preview",image=preview||kind=="image-export",empty=kind=="media-empty",result=kind=="media-result",convert=kind=="media-convert";
    require(image||empty||result||convert||kind=="media-source","residue-kind");require(stamps<=200&&(kind=="image-export"||stamps==0),"residue-stamps");
    auto pins=lock_tree(root);Scan scan{0,512ULL*1024*1024};scan.walk(root);
    for(const auto& directory:fs::directory_iterator(root)){
        const auto name=directory.path().filename().native();require(name==L"bin"||name==L"input"||name==L"out","residue-directory");require(directory.is_directory(),"residue-directory-type");
        for(const auto& entry:fs::directory_iterator(directory.path())){
            require(entry.is_regular_file(),"residue-entry-type");const auto file=entry.path().filename().native();unsigned long long limit=0;
            if(name==L"bin"&&(file==L"all-app.check"||file==L"restricted-app.check"))limit=1024;
            if(name==L"input"){
                if(image&&file==L"source.cjs")limit=104857600;
                if(!image&&!empty&&file==L"source.media")limit=result?268435456:104857600;
                for(unsigned i=0;i<stamps;i++){wchar_t stamp[32]{};std::swprintf(stamp,32,L"stamp-%03u.png",i);if(file==stamp)limit=2097152;}
            }
            if(name==L"out"){
                if(image&&file==L"job.json")limit=1048576;
                if(preview&&(file==L"preview.png"||file==L"thumbnail.png")||kind=="image-export"&&file==L"export.png")limit=33554432;
                if(convert&&file==L"playback")limit=268435456;
            }
            require(limit>0&&entry.file_size()<=limit,"residue-file");
        }
    }
}
bool inspect_residue_command(int argc,wchar_t** argv){
    if(argc<2||std::wstring(argv[1])!=L"--inspect-residue")return false;
    require(argc==5,"residue-arguments");std::wstring text=argv[4];require(!text.empty()&&text.size()<=3,"residue-stamps");unsigned stamps=0;for(auto c:text){require(c>=L'0'&&c<=L'9',"residue-stamps");stamps=stamps*10+unsigned(c-L'0');}
    std::wstring kind=argv[3];require(kind.find_first_not_of(L"abcdefghijklmnopqrstuvwxyz-")==std::wstring::npos,"residue-kind");inspect_residue(argv[2],std::string(kind.begin(),kind.end()),stamps);std::puts("residue-ok");return true;
}
StorageWatch::StorageWatch(const fs::path& o,const fs::path& p,StorageLimits l):impl_(std::make_unique<Impl>(o,p,l)){inspect();}
StorageWatch::~StorageWatch()=default;
void StorageWatch::release(){impl_.reset();}
void storage_admission(const fs::path& parent,StorageLimits limits){
    constexpr unsigned long long reservation=768ULL*1024*1024;
    require(limits.output_bytes>0&&limits.output_bytes<=
#ifdef IMAGEGATE_BOUND_MEDIA_LPAC
        256ULL*1024*1024
#else
        64ULL*1024*1024
#endif
        &&limits.profile_bytes>0&&limits.profile_bytes<=16ULL*1024*1024
        &&limits.free_floor_bytes>=512ULL*1024*1024&&limits.free_floor_bytes<=~0ULL-reservation,"storage-budget");
    free_space(parent,limits.free_floor_bytes+reservation);
}
void StorageWatch::inspect(){
    auto& x=*impl_;free_space(x.out,x.limits.free_floor_bytes);free_space(x.profile,x.limits.free_floor_bytes);
    for(unsigned attempt=0;;attempt++)try{
        Scan out{0,x.limits.output_bytes};out.walk(x.out);Scan profile{0,x.limits.profile_bytes};profile.walk(x.profile);return;
    }catch(const SandboxFailure& failure){
        const std::string stage=failure.stage;
        const bool missing=failure.error==ERROR_FILE_NOT_FOUND||failure.error==ERROR_PATH_NOT_FOUND;
        const bool transient=stage=="storage-file-open"||stage=="state-directory-open"||stage=="storage-enumerate"||stage=="storage-stream-open"||stage=="state-final-path"||stage=="storage-stream-handle";
        const bool renamed=stage=="storage-name-changed"&&failure.error==ERROR_RETRY;
        if(attempt>=2||!(missing&&transient||renamed))throw;
        Sleep(1); // Restart both bounded inventories; never skip an unchecked file.
    }
}
}
