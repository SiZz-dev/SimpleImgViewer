// SPDX-License-Identifier: BSD-3-Clause
// Test-only executable, not copied to the production worker runtime.
#include "windows_job.h"
#include <string>
#include <vector>
#include <fstream>
#include <filesystem>

int wmain(int argc, wchar_t** argv) {
    if (argc != 3) return 10;
    std::wstring mode(argv[1]); std::filesystem::path marker(argv[2]);
    if (mode == L"child") { std::ofstream(marker) << "child-started"; return 0; }
    if (mode == L"memory-control") {
        void* block = VirtualAlloc(nullptr, 64ULL * 1024 * 1024, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        if (!block) return 16;
        VirtualFree(block, 0, MEM_RELEASE); std::ofstream(marker) << "commit-allowed"; return 0;
    }
    imagegate::WindowsJob job;
    if (!job.enter(32, 1)) return 23;
    if (mode == L"memory") {
        void* block = VirtualAlloc(nullptr, 64ULL * 1024 * 1024, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        if (block) { VirtualFree(block, 0, MEM_RELEASE); return 11; }
        std::ofstream(marker) << "commit-denied"; return 0;
    }
    if (mode == L"cpu") {
        { std::ofstream(marker) << "cpu-loop-entered"; }
        volatile unsigned long long value = 1;
        for (;;) value = value * 1664525ULL + 1013904223ULL;
    }
    if (mode == L"children") {
        std::vector<wchar_t> filename(32768);
        DWORD size = GetModuleFileNameW(nullptr, filename.data(), static_cast<DWORD>(filename.size()));
        if (size == 0 || size >= filename.size()) return 12;
        std::wstring command = L"\"" + std::wstring(filename.data(), size) + L"\" child \"" + marker.wstring() + L"\"";
        std::vector<wchar_t> mutable_command(command.begin(), command.end()); mutable_command.push_back(0);
        STARTUPINFOW startup{}; startup.cb = sizeof(startup); PROCESS_INFORMATION process{};
        BOOL started = CreateProcessW(filename.data(), mutable_command.data(), nullptr, nullptr, FALSE,
            CREATE_NO_WINDOW, nullptr, nullptr, &startup, &process);
        if (started) {
            DWORD wait = WaitForSingleObject(process.hProcess, 2000);
            if (wait != WAIT_OBJECT_0) { TerminateProcess(process.hProcess, 99); CloseHandle(process.hThread); CloseHandle(process.hProcess); return 13; }
            CloseHandle(process.hThread); CloseHandle(process.hProcess);
        }
        if (std::filesystem::exists(marker)) return 14;
        JOBOBJECT_BASIC_ACCOUNTING_INFORMATION accounting{};
        if (!QueryInformationJobObject(job.handle(), JobObjectBasicAccountingInformation, &accounting, sizeof(accounting), nullptr)
            || accounting.ActiveProcesses != 1) return 15;
        return 0;
    }
    return 10;
}
