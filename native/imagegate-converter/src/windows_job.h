// SPDX-License-Identifier: BSD-3-Clause
#pragma once
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <cstdint>

namespace imagegate {
/** Resource limits only. Does not restrict filesystem, network, token privileges or DLL loading. */
class WindowsJob final {
    HANDLE job_ = nullptr;
public:
    WindowsJob() = default;
    WindowsJob(const WindowsJob&) = delete;
    WindowsJob& operator=(const WindowsJob&) = delete;
    bool enter(std::uint32_t memory_mib = 512, std::uint32_t cpu_seconds = 30) {
        if (job_ || memory_mib < 32 || memory_mib > 2048 || cpu_seconds < 1 || cpu_seconds > 120) return false;
        HANDLE candidate = CreateJobObjectW(nullptr, nullptr);
        if (!candidate) return false;
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits{};
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_PROCESS_MEMORY | JOB_OBJECT_LIMIT_JOB_MEMORY
            | JOB_OBJECT_LIMIT_ACTIVE_PROCESS | JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            | JOB_OBJECT_LIMIT_PROCESS_TIME | JOB_OBJECT_LIMIT_JOB_TIME;
        limits.BasicLimitInformation.ActiveProcessLimit = 1;
        limits.BasicLimitInformation.PerProcessUserTimeLimit.QuadPart = static_cast<LONGLONG>(cpu_seconds) * 10000000;
        limits.BasicLimitInformation.PerJobUserTimeLimit.QuadPart = static_cast<LONGLONG>(cpu_seconds) * 10000000;
        limits.ProcessMemoryLimit = static_cast<SIZE_T>(memory_mib) * 1024 * 1024;
        limits.JobMemoryLimit = limits.ProcessMemoryLimit;
        if (!SetInformationJobObject(candidate, JobObjectExtendedLimitInformation, &limits, sizeof(limits))
            || !AssignProcessToJobObject(candidate, GetCurrentProcess())) {
            CloseHandle(candidate); return false;
        }
        job_ = candidate;
        return true;
    }
    HANDLE handle() const { return job_; }
    // Closing the last handle terminates members, including self. Keep the handle until OS process exit.
    // It is not inheritable and the worker never creates children. Do not CloseHandle on a normal stack unwind.
};
}
#endif
