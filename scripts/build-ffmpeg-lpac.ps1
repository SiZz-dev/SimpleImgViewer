$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$compiler=Join-Path $repo '.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64/bin/x86_64-w64-mingw32-clang++.exe'
if((Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'd05fee2c3f79b15d998d3c3413c76aabd416ec789edff89c8f698a8be44a3970'){throw 'Pinned compiler hash mismatch'}
$include=Join-Path $repo 'native/imagegate-converter/src'
$sources=@((Join-Path $repo 'native/process-job/tests/ffmpeg-lpac-launcher.cpp'),(Join-Path $include 'sandbox_runner.cpp'),(Join-Path $include 'sandbox_lifecycle.cpp'))
$inputs=@($sources)+@((Join-Path $include 'sandbox_runner.h'),(Join-Path $include 'sandbox_lifecycle.h'),(Join-Path $include 'contained_token.h'),$PSCommandPath)
$fingerprints=@($inputs|ForEach-Object{@{path=$_;sha256=(Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant()}})
$hashes=($fingerprints|ForEach-Object{$_.sha256}) -join '-'
$algorithm=[Security.Cryptography.SHA256]::Create()
try{$id=([BitConverter]::ToString($algorithm.ComputeHash([Text.Encoding]::UTF8.GetBytes($hashes)))).Replace('-','').ToLowerInvariant()}finally{$algorithm.Dispose()}
$directory=Join-Path $repo ('.tools/fjl-build/'+$id)
if(Test-Path -LiteralPath $directory){throw 'Probe build exists; preserve it. Use its generated manifest.'}
[IO.Directory]::CreateDirectory($directory)|Out-Null
$programs=@{}
foreach($mode in @('zero-capability','registry-read','production-guard')){
    $program=Join-Path $directory ($mode+'.exe')
    $flags=@('-std=c++17','-O2','-static','-municode','-D_WIN32_WINNT=0x0A00','-DWINVER=0x0A00')
    if($mode -ne 'production-guard'){$flags+='-DIMAGEGATE_FFMPEG_LPAC_PROBE=1'}
    if($mode -eq 'registry-read'){$flags+='-DIMAGEGATE_FFMPEG_LPAC_REGISTRY_PROBE=1'}
    & $compiler @flags '-I' $include @sources '-luserenv' '-ladvapi32' '-lole32' '-lshell32' '-luuid' '-o' $program
    if($LASTEXITCODE -ne 0){throw 'LPAC FFmpeg probe build failed'}
    $programs[$mode]=@{executable=$program;sha256=(Get-FileHash -LiteralPath $program -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$report=Join-Path $repo 'build/ffmpeg-lpac';[IO.Directory]::CreateDirectory($report)|Out-Null
$manifest=@{testOnly=$true;sourceId=$id;programs=$programs;inputs=$fingerprints;compilerSha256=(Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant();distributionReady=$false}
[IO.File]::WriteAllText((Join-Path $directory 'manifest.json'),($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
[IO.File]::WriteAllText((Join-Path $report 'launcher.json'),($manifest|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
Write-Output ($manifest|ConvertTo-Json -Depth 5)
