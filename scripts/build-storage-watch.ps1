$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$compiler=Join-Path $repo '.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64/bin/x86_64-w64-mingw32-clang++.exe'
$compilerSha='d05fee2c3f79b15d998d3c3413c76aabd416ec789edff89c8f698a8be44a3970'
if((Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant() -ne $compilerSha){throw 'Pinned compiler hash mismatch'}
$include=Join-Path $repo 'native/imagegate-converter/src'
$sources=@((Join-Path $repo 'native/process-job/tests/storage-watch.cpp'),(Join-Path $include 'sandbox_runner.cpp'),(Join-Path $include 'sandbox_lifecycle.cpp'))
$inputs=@($sources)+@((Join-Path $include 'sandbox_runner.h'),(Join-Path $include 'sandbox_lifecycle.h'),(Join-Path $include 'contained_token.h'),(Join-Path $repo 'native/process-job/lpac-request.h'),$PSCommandPath)
$fingerprints=@($inputs|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
$hashes=($fingerprints|ForEach-Object{$_.sha256}) -join '-'
$algorithm=[Security.Cryptography.SHA256]::Create()
try{$id=([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($hashes)))).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose()}
$directory=Join-Path $repo ('.tools/storage-watch-build/'+$id)
if(Test-Path -LiteralPath $directory){throw 'Build exists; use its preserved manifest'}
[IO.Directory]::CreateDirectory($directory)|Out-Null
$program=Join-Path $directory 'storage-watch.exe'
# Shared core guards retain compatibility with existing probes. This separate
# entry point exposes only the fixed storage-watch request and profile recovery.
$flags=@('-std=c++17','-O2','-static','-municode','-D_WIN32_WINNT=0x0A00','-DWINVER=0x0A00')
& $compiler @flags '-I' $include @sources '-luserenv' '-ladvapi32' '-lole32' '-lshell32' '-luuid' '-lbcrypt' '-o' $program
if($LASTEXITCODE -ne 0){throw 'Bound image LPAC build failed'}
$manifest=@{schemaVersion=1;profile='storage-watch-v1';serverSelectable=$false;sourceId=$id;executable=$program;sha256=(Get-FileHash -LiteralPath $program -Algorithm SHA256).Hash.ToLowerInvariant();inputs=$fingerprints;compilerSha256=$compilerSha;distributionReady=$false}
$report=Join-Path $repo 'build/storage-watch';[IO.Directory]::CreateDirectory($report)|Out-Null
foreach($target in @((Join-Path $directory 'manifest.json'),(Join-Path $report 'launcher.json'))){[IO.File]::WriteAllText($target,($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
Write-Output ($manifest|ConvertTo-Json -Depth 5)
