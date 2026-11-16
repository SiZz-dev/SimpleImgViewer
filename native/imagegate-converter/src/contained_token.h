// SPDX-License-Identifier: BSD-3-Clause
#pragma once
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <sddl.h>
#include <vector>
#include <string>
#include <filesystem>
namespace imagegate {
constexpr const wchar_t* registry_read_sid = L"S-1-15-3-1024-1065365936-1281604716-3511738428-1654721687-432734479-3232135806-4053264122-3456934681";
// Trusted-parent-created, read-only differential ACL canaries. Ordinary AppContainer
// accepts ALL_APPLICATION_PACKAGES; this policy must deny it and accept restricted packages.
inline bool lpac_file_policy() {
    wchar_t module[MAX_PATH]{};
    DWORD length=GetModuleFileNameW(nullptr,module,MAX_PATH);
    if(!length || length>=MAX_PATH)return false;
    auto bin=std::filesystem::path(module).parent_path();
    HANDLE all=CreateFileW((bin/L"all-app.check").c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,0,nullptr);
    DWORD denied=all==INVALID_HANDLE_VALUE?GetLastError():0;
    if(all!=INVALID_HANDLE_VALUE)CloseHandle(all);
    HANDLE restricted=CreateFileW((bin/L"restricted-app.check").c_str(),GENERIC_READ,FILE_SHARE_READ,nullptr,OPEN_EXISTING,0,nullptr);
    bool allowed=restricted!=INVALID_HANDLE_VALUE;
    if(allowed)CloseHandle(restricted);
    return denied==ERROR_ACCESS_DENIED && allowed;
}
// A child-side invariant check, not an attestation of its parent's Job budgets.
inline bool contained_token(bool network_test_only = false) {
    HANDLE token = nullptr;
    if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return false;
    DWORD app = 0, size = 0; BOOL job = FALSE;
    bool ok = GetTokenInformation(token, TokenIsAppContainer, &app, sizeof(app), &size) && app == 1
        && lpac_file_policy()
        && IsProcessInJob(GetCurrentProcess(), nullptr, &job) && job;
    GetTokenInformation(token, TokenCapabilities, nullptr, 0, &size);
    std::vector<unsigned char> buffer(size);
    ok = ok && size >= FIELD_OFFSET(TOKEN_GROUPS, Groups)
        && GetTokenInformation(token, TokenCapabilities, buffer.data(), size, &size);
    if(ok) {
        auto groups=reinterpret_cast<TOKEN_GROUPS*>(buffer.data());
        if(!network_test_only)ok=groups->GroupCount==0;
        else {
            PSID expected=nullptr;
            ok=ConvertStringSidToSidW(registry_read_sid,&expected) && groups->GroupCount==1 && EqualSid(groups->Groups[0].Sid,expected);
            if(expected)LocalFree(expected);
        }
    }
    CloseHandle(token); return ok;
}
}
