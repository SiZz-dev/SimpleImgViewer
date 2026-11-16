// SPDX-License-Identifier: BSD-3-Clause
// Own wrapper code; libvips and its dependencies retain their respective licenses.
#include <vips/vips.h>
#include <algorithm>
#include <charconv>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <regex>
#include <stdexcept>
#include <string>
#include <vector>
#include <utility>
#include <cstdio>
#include "windows_job.h"
#ifdef IMAGEGATE_CONTAINED_WORKER
#include "contained_token.h"
#endif
#ifdef _WIN32
#define NOMINMAX
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

namespace fs = std::filesystem;
namespace {
#ifdef IMAGEGATE_CONTAINED_WORKER
constexpr const char* build = "imagegate-libvips-worker-contained-v3";
#else
constexpr const char* build = "imagegate-libvips-worker-winjob-v2";
#endif
constexpr const char* profile = "tiff-rgb8-o1-v1";
struct Failure { int code; };
void require(bool ok, int code) { if (!ok) throw Failure{code}; }
int integer(const std::string& text, int upper) {
    int value = 0;
    auto result = std::from_chars(text.data(), text.data() + text.size(), value);
    require(!text.empty() && text.front() != '0' && result.ec == std::errc{}
        && result.ptr == text.data() + text.size() && value > 0 && value <= upper, 20);
    return value;
}
struct Unref { void operator()(VipsImage* image) const { if (image) g_object_unref(image); } };
using Image = std::unique_ptr<VipsImage, Unref>;

int run(const std::vector<std::string>& args) {
    require(args.size() == 19, 20);
    std::map<std::string, std::string> options;
    for (std::size_t i = 1; i < args.size(); i += 2)
        require(options.emplace(args[i], args[i + 1]).second, 20);
    for (const auto* key : {"--protocol", "--operation", "--input", "--work", "--page",
            "--job", "--max-width", "--max-height", "--format"}) require(options.count(key) == 1, 20);
    require(options.at("--protocol") == "1", 20);
    const auto operation = options.at("--operation"), format = options.at("--format"), job = options.at("--job");
    require(operation == "probe" || operation == "preview", 20);
    require(std::regex_match(job, std::regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")), 20);
    int page = integer(options.at("--page"), 10000), maxw = integer(options.at("--max-width"), 4096), maxh = integer(options.at("--max-height"), 4096);
    require(operation == "probe" ? format == "none" : (format == "png" || format == "jpeg"), 20);
    fs::path input = fs::u8path(options.at("--input")), work = fs::u8path(options.at("--work"));
    require(input.is_absolute() && work.is_absolute() && fs::canonical(input) == input
        && fs::canonical(work) == work && fs::is_regular_file(input) && fs::is_directory(work)
        && !fs::is_symlink(input) && !fs::is_symlink(work) && fs::canonical(fs::current_path()) == work
        && fs::is_empty(work) && fs::file_size(input) >= 8 && fs::file_size(input) <= 104857600, 20);
    { std::ifstream stream(input, std::ios::binary); unsigned char h[4]{}; stream.read(reinterpret_cast<char*>(h), 4);
      require(stream.good() && ((h[0] == 73 && h[1] == 73 && h[2] == 42 && h[3] == 0)
        || (h[0] == 77 && h[1] == 77 && h[2] == 0 && h[3] == 42)), 20); }
    require(std::string(vips_version_string()) == "8.18.7", 23);
    // A supplement to a minimal audited build, not a replacement for its DLL/loader inventory.
    for (const auto* name : {"magickload", "magickload_buffer", "pdfload", "pdfload_buffer",
            "rsvgload", "heifload", "jp2kload", "openslideload"})
        require(vips_type_find("VipsOperation", name) == 0, 23);
    vips_error_clear(); vips_concurrency_set(1); vips_cache_set_max(0);
    VipsImage* loaded = nullptr;
    require(vips_tiffload(options.at("--input").c_str(), &loaded, "page", page - 1, "n", 1,
        "access", VIPS_ACCESS_SEQUENTIAL, "fail_on", VIPS_FAIL_ON_WARNING, nullptr) == 0, 20);
    Image image(loaded);
    int w = vips_image_get_width(image.get()), h = vips_image_get_height(image.get());
    int count = vips_image_get_n_pages(image.get());
    require(w > 0 && h > 0 && static_cast<long long>(w) * h <= 40000000 && count >= page && count <= 10000, 20);
    require(vips_image_get_bands(image.get()) == 3 && vips_image_get_format(image.get()) == VIPS_FORMAT_UCHAR
        && vips_image_get_interpretation(image.get()) == VIPS_INTERPRETATION_sRGB
        && vips_image_get_orientation(image.get()) == 1
        && vips_image_get_typeof(image.get(), VIPS_META_ICC_NAME) == 0, 21);
    int ow = 0, oh = 0; std::string output, outformat = "none";
    if (operation == "preview") {
        double scale = std::min({1.0, static_cast<double>(maxw) / w, static_cast<double>(maxh) / h});
        VipsImage* scaled = nullptr;
        require(vips_resize(image.get(), &scaled, scale, "kernel", VIPS_KERNEL_LANCZOS3, nullptr) == 0, 22);
        Image resized(scaled); ow = vips_image_get_width(resized.get()); oh = vips_image_get_height(resized.get());
        require(ow > 0 && oh > 0 && ow <= maxw && oh <= maxh && ow <= w && oh <= h, 22);
        output = format == "png" ? "preview.png" : "preview.jpg"; outformat = format;
        // Direct saver API: no filename option parsing or user-controlled operation selection.
        int status = format == "png"
            ? vips_pngsave(resized.get(), output.c_str(), "keep", VIPS_FOREIGN_KEEP_NONE, "compression", 6, nullptr)
            : vips_jpegsave(resized.get(), output.c_str(), "keep", VIPS_FOREIGN_KEEP_NONE, "Q", 90, nullptr);
        require(status == 0 && fs::is_regular_file(work / output) && !fs::is_symlink(work / output)
            && fs::file_size(work / output) > 0 && fs::file_size(work / output) <= 33554432, 22);
    }
    // Only fixed ASCII values, bounded integers and a validated UUID are serialized.
    std::ofstream result(work / "result.json", std::ios::binary | std::ios::out);
    result << "{\"protocolVersion\":1,\"jobId\":\"" << job << "\",\"operation\":\"" << operation
        << "\",\"engineBuildId\":\"" << build << "\",\"profileId\":\"" << profile
        << "\",\"libvipsVersion\":\"8.18.7\",\"pageCount\":" << count << ",\"page\":" << page
        << ",\"sourceWidth\":" << w << ",\"sourceHeight\":" << h << ",\"outputWidth\":" << ow
        << ",\"outputHeight\":" << oh << ",\"outputFile\":\"" << output << "\",\"outputFormat\":\"" << outformat << "\"}";
    result.close(); require(result.good(), 22); return 0;
}
int entry(const std::vector<std::string>& args) {
#ifdef _WIN32
#ifdef IMAGEGATE_CONTAINED_WORKER
    if (!imagegate::contained_token()) return 23;
#else
    imagegate::WindowsJob resource_job;
    if (!resource_job.enter()) return 23;
#endif
    SetErrorMode(SEM_FAILCRITICALERRORS | SEM_NOGPFAULTERRORBOX | SEM_NOOPENFILEERRORBOX);
#else
    // This build identity promises the Windows resource policy; never silently run without it.
    return 23;
#endif
    if (args.size() == 2 && args[1] == "--capabilities") {
#ifdef IMAGEGATE_CONTAINED_WORKER
        std::printf("{\"protocolVersion\":1,\"engineBuildId\":\"%s\",\"resourcePolicy\":\"lpac-parent-job-512m-30cpu-1proc\"}\n", build);
#else
        std::printf("{\"protocolVersion\":1,\"engineBuildId\":\"%s\",\"resourcePolicy\":\"windows-job-512m-30cpu-1proc\"}\n", build);
#endif
        return 0;
    }
    if (VIPS_INIT("imagegate-converter")) return 23;
    int code = 0;
    try { code = run(args); } catch (const Failure& failure) { code = failure.code; }
    catch (...) { code = 22; }
    // Do not echo arguments; library diagnostics remain private bounded process output.
    vips_error_clear(); vips_shutdown(); return code;
}
}
#ifdef _WIN32
int wmain(int argc, wchar_t** argv) {
    std::vector<std::string> args;
    for (int i = 0; i < argc; ++i) {
        int size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[i], -1, nullptr, 0, nullptr, nullptr);
        if (size < 1) return 20;
        std::string value(static_cast<std::size_t>(size), '\0');
        if (!WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, argv[i], -1, value.data(), size, nullptr, nullptr)) return 20;
        value.pop_back(); args.push_back(std::move(value));
    }
    return entry(args);
}
#else
int main(int argc, char** argv) { return entry(std::vector<std::string>(argv, argv + argc)); }
#endif
