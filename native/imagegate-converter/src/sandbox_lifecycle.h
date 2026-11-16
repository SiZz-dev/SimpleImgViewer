// SPDX-License-Identifier: BSD-3-Clause
#pragma once
#include "sandbox_runner.h"
#include <memory>
namespace imagegate {
struct RecoveryResult { unsigned recovered=0, busy=0; };
RecoveryResult recover_profiles(const fs::path& state);
void inspect_residue(const fs::path& root,const std::string& kind,unsigned stamps);
bool inspect_residue_command(int argc,wchar_t** argv);
void remove_profile(const std::wstring& name); // Bounded no-reparse preflight, then the Windows API.
fs::path profile_storage_root(const std::wstring& name);
class SandboxLease {
    struct Impl; std::unique_ptr<Impl> impl_;
public:
    explicit SandboxLease(const fs::path& state);
    ~SandboxLease();
    const std::wstring& profile_name() const;
    std::wstring job_name() const;
    void finish(); // Only after DeleteAppContainerProfile succeeded.
};
void storage_admission(const fs::path& parent,StorageLimits limits);
class StorageWatch {
    struct Impl; std::unique_ptr<Impl> impl_;
public:
    StorageWatch(const fs::path& out,const fs::path& profile,StorageLimits limits);
    ~StorageWatch();
    void inspect(); // Bounded periodic detection, not a filesystem hard quota.
    void release(); // Close profile handles before Windows profile removal.
};
}
