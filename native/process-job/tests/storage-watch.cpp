// SPDX-License-Identifier: BSD-3-Clause
#define NOMINMAX
#include <windows.h>
#include <atomic>
#include <thread>
#include <fstream>
#include <cstdio>
#include "sandbox_runner.h"
#include "sandbox_lifecycle.h"
int wmain(int argc,wchar_t** argv){
 using namespace imagegate;
 try{
  if(argc!=2)return 1;fs::path root(argv[1]),out=root/L"out",profile=root/L"profile";if(fs::exists(root))return 2;fs::create_directory(root);fs::create_directory(out);fs::create_directory(profile);
  auto write=[](const fs::path& file,std::size_t bytes){std::ofstream stream(file,std::ios::binary);stream<<std::string(bytes,'x');stream.close();if(!stream.good())throw 3;};
  write(out/L"data",16);StorageLimits limits;limits.output_bytes=4096;StorageWatch watch(out,profile,limits);unsigned cases=1;
  auto rejected=[&](const char* stage){try{watch.inspect();throw 4;}catch(const SandboxFailure& failure){if(std::string(failure.stage)!=stage)throw;cases++;}};
  write(fs::path((out/L"data").native()+L":secret"),16);rejected("storage-alternate-stream");if(!DeleteFileW(fs::path((out/L"data").native()+L":secret").c_str()))return 5;watch.inspect();
  if(!CreateHardLinkW((out/L"link").c_str(),(out/L"data").c_str(),nullptr))return 6;rejected("storage-file-type");if(!DeleteFileW((out/L"link").c_str()))return 7;
  write(out/L"large",8192);rejected("storage-byte-limit");if(!DeleteFileW((out/L"large").c_str()))return 8;
  std::atomic<bool> stop{false};std::thread writer([&](){while(!stop){write(profile/L"cache.tmp",128);MoveFileExW((profile/L"cache.tmp").c_str(),(profile/L"cache").c_str(),MOVEFILE_REPLACE_EXISTING);Sleep(2);DeleteFileW((profile/L"cache").c_str());Sleep(2);}});
  try{for(unsigned i=0;i<200;i++){watch.inspect();Sleep(1);}}catch(...){stop=true;writer.join();throw;}stop=true;writer.join();cases++;watch.inspect();
  std::printf("cases=%u churnScans=200 passed=true\n",cases);return 0;
 }catch(const SandboxFailure& failure){std::fprintf(stderr,"stage=%s error=%lu\n",failure.stage,failure.error);return 9;}catch(...){return 10;}
}
