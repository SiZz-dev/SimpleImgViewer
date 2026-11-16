// SPDX-License-Identifier: BSD-3-Clause
#pragma once
#if defined(IMAGEGATE_BOUND_IMAGE_LPAC) || defined(IMAGEGATE_BOUND_MEDIA_LPAC)
#define IMAGEGATE_BOUND_LPAC 1
#endif
#include <filesystem>
#include <functional>
#include <string>
#include <vector>
#if defined(IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE) || defined(IMAGEGATE_SHARED_NODE_LPAC_PROBE)
#include <memory>
#endif
namespace imagegate {
namespace fs = std::filesystem;
struct SandboxFailure { const char* stage; unsigned long error; };
struct StorageLimits {
    unsigned long long output_bytes=64ULL*1024*1024;
    unsigned long long profile_bytes=16ULL*1024*1024;
    unsigned long long free_floor_bytes=512ULL*1024*1024;
};
struct SandboxSpec {
    fs::path runtime, input, root;
#if defined(IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE) || defined(IMAGEGATE_SHARED_NODE_LPAC_PROBE)
    bool reuse_runtime=false;
    std::string runtime_digest;
#endif
#ifdef IMAGEGATE_NODE_SHARP_LPAC_PROBE
    fs::path job_template, stamp; // Test-only production-worker binding acceptance.
#endif
#ifdef IMAGEGATE_BOUND_LPAC
    // Trusted parent-bound JSON, copied verbatim. No placeholder substitution.
    std::string bound_document;
    std::vector<fs::path> bound_stamps;
    fs::path host_work, runtime_use;
    unsigned memory_mib=512, cpu_seconds=30, cpu_rate=100;
    bool no_input=false;
    unsigned long long input_limit=104857600, output_limit=33554432;
    unsigned channel_limit=8192;
#endif
    std::wstring program;
    std::function<std::vector<std::wstring>(const fs::path&, const fs::path&, unsigned long,const fs::path&)> arguments;
    std::vector<std::wstring> outputs;
    unsigned long wall_ms = 20000;
    StorageLimits storage;
};
struct SandboxResult {
    unsigned long exit_code;
    std::string diagnostics; // Child stdout only; retained name for existing callers.
    bool truncated;
    std::string errors; // Child stderr only; never parsed as a facts document.
    bool stdout_truncated=false, stderr_truncated=false;
};
// Experimental trusted parent. Uses a unique temporary Windows profile, removed after exit.
// No firewall, original ACL or service mutations. Not yet connected to server publication.
SandboxResult run_sandbox(const SandboxSpec& spec);
#ifdef IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE
// Test-only snapshot: no source ACL changes and no writable shared work data.
std::string prepare_shared_media_runtime(const fs::path& source,const fs::path& target);
class SharedMediaLease {
    struct Impl;std::unique_ptr<Impl> impl;
    friend std::string prepare_shared_media_runtime(const fs::path&,const fs::path&);
public:
    SharedMediaLease(const fs::path& root,const std::string& expected);
    ~SharedMediaLease();
};
#endif
#ifdef IMAGEGATE_SHARED_NODE_LPAC_PROBE
std::string prepare_shared_node_runtime(const fs::path& source,const fs::path& target);
class SharedNodeLease {
    struct Impl;std::unique_ptr<Impl> impl;
    friend std::string prepare_shared_node_runtime(const fs::path&,const fs::path&);
public:
    SharedNodeLease(const fs::path& root,const std::string& expected);
    ~SharedNodeLease();
};
#endif
}
