$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$compiler=Join-Path $repo '.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64/bin/x86_64-w64-mingw32-clang++.exe'
if((Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'd05fee2c3f79b15d998d3c3413c76aabd416ec789edff89c8f698a8be44a3970'){throw 'Pinned compiler hash mismatch'}
$include=Join-Path $repo 'native/imagegate-converter/src'
$sources=@((Join-Path $repo 'native/process-job/tests/stream-lpac-launcher.cpp'),(Join-Path $include 'sandbox_runner.cpp'),(Join-Path $include 'sandbox_lifecycle.cpp'))
$probe=Join-Path $repo 'native/process-job/tests/stream-probe.cpp'
$inputs=@($sources)+@($probe,(Join-Path $include 'sandbox_runner.h'),(Join-Path $include 'sandbox_lifecycle.h'),(Join-Path $include 'contained_token.h'),$PSCommandPath)
$fingerprints=@($inputs|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
$algorithm=[Security.Cryptography.SHA256]::Create()
try{$id=([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes(($fingerprints.sha256 -join '-'))))).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose()}
$directory=Join-Path $repo ('.tools/stream-lpac/'+$id)
if(Test-Path -LiteralPath $directory){throw 'Build exists; use its manifest'}
$runtime=Join-Path $directory 'runtime';[IO.Directory]::CreateDirectory($runtime)|Out-Null
$flags=@('-std=c++17','-O2','-static','-municode','-D_WIN32_WINNT=0x0A00','-DWINVER=0x0A00')
$worker=Join-Path $runtime 'imagegate-stream-probe.exe'
& $compiler @flags $probe '-o' $worker
if($LASTEXITCODE -ne 0){throw 'Stream worker build failed'}
$launcher=Join-Path $directory 'launcher.exe'
& $compiler @flags '-DIMAGEGATE_STREAM_LPAC_PROBE=1' '-I' $include @sources '-luserenv' '-ladvapi32' '-lole32' '-lshell32' '-luuid' '-o' $launcher
if($LASTEXITCODE -ne 0){throw 'Stream launcher build failed'}
$manifest=@{testOnly=$true;productionEnabled=$false;inputs=$fingerprints;runtime=$runtime;worker=$worker;workerSha256=(Get-FileHash $worker -Algorithm SHA256).Hash.ToLowerInvariant();executable=$launcher;sha256=(Get-FileHash $launcher -Algorithm SHA256).Hash.ToLowerInvariant()}
$report=Join-Path $repo 'build/stream-lpac';[IO.Directory]::CreateDirectory($report)|Out-Null
[IO.File]::WriteAllText((Join-Path $report 'launcher.json'),($manifest|ConvertTo-Json -Depth 6),[Text.UTF8Encoding]::new($false))
Write-Output 'LPAC stream probe built'
