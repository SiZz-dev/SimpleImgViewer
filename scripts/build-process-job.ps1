[CmdletBinding()]
param([string]$CompilerRoot='.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64',[switch]$TestTools)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$source=Join-Path $repo 'native/process-job/main.cpp'
$sourceHash=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
$directory=Join-Path $repo ('.tools/process-job/'+$sourceHash)
$program=Join-Path $directory 'simpleimgviewer-process-job.exe'
$compiler=Join-Path ([IO.Path]::GetFullPath((Join-Path $repo $CompilerRoot))) 'bin/x86_64-w64-mingw32-clang++.exe'
if(!(Test-Path -LiteralPath $compiler -PathType Leaf)){throw 'Pinned LLVM toolchain missing. No automatic download is performed.'}
if(Test-Path -LiteralPath $directory){
    $existing=Get-Content -LiteralPath (Join-Path $directory 'manifest.json') -Raw|ConvertFrom-Json
    if($existing.schemaVersion -ne 1 -or $existing.platform -ne 'win32-x64' -or $existing.executable -ne $program -or $existing.sourceSha256 -ne $sourceHash -or $existing.compilerSha256 -ne (Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant() -or $existing.sha256 -ne (Get-FileHash -LiteralPath $program -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'Existing build changed; never overwrite or trust its new bytes automatically.'}
    $manifest=$existing
}else{
[IO.Directory]::CreateDirectory($directory)|Out-Null
& $compiler '-std=c++17' '-O2' '-static' '-municode' '-Wall' '-Wextra' '-Werror' $source '-ladvapi32' '-o' $program
if($LASTEXITCODE -ne 0){throw 'Job launcher compilation failed'}
$readobj=Join-Path (Split-Path $compiler) 'llvm-readobj.exe'
$imports=& $readobj '--coff-imports' $program
if($LASTEXITCODE -ne 0){throw 'PE imports inspection failed'}
$names=@($imports|Select-String '^\s*Name: (.+\.dll)$'|ForEach-Object{$_.Matches[0].Groups[1].Value})
if(!$names.Count -or @($names|Where-Object{$_ -notmatch '^(KERNEL32|ADVAPI32|msvcrt|api-ms-win-crt-[a-z0-9-]+)\.dll$'}).Count){throw ('Unexpected runtime DLL import: '+($names -join ', '))}
$manifest=[ordered]@{schemaVersion=1;platform='win32-x64';executable=$program;sha256=(Get-FileHash -LiteralPath $program -Algorithm SHA256).Hash.ToLowerInvariant();sourceSha256=$sourceHash;compilerSha256=(Get-FileHash -LiteralPath $compiler -Algorithm SHA256).Hash.ToLowerInvariant();imports=$names;distributionReady=$false}
}
$json=$manifest|ConvertTo-Json -Depth 4
[IO.File]::WriteAllText((Join-Path $directory 'manifest.json'),$json,[Text.UTF8Encoding]::new($false))
$report=Join-Path $repo 'build/process-job'
[IO.Directory]::CreateDirectory($report)|Out-Null
[IO.File]::WriteAllText((Join-Path $report 'launcher.json'),$json,[Text.UTF8Encoding]::new($false))
if($TestTools){
    $probeSource=Join-Path $repo 'native/process-job/tests/probe.cpp'
    $probeHash=(Get-FileHash -LiteralPath $probeSource -Algorithm SHA256).Hash.ToLowerInvariant()
    $probeDirectory=Join-Path $repo ('.tools/process-job-tests/'+$probeHash)
    [IO.Directory]::CreateDirectory($probeDirectory)|Out-Null
    $probe=Join-Path $probeDirectory 'probe.exe'
    if(!(Test-Path -LiteralPath $probe)){
        & $compiler '-std=c++17' '-O2' '-static' '-municode' '-Wall' '-Wextra' '-Werror' $probeSource '-o' $probe
        if($LASTEXITCODE -ne 0){throw 'Job test probe compilation failed'}
    }
    $probeManifest=[ordered]@{executable=$probe;sha256=(Get-FileHash -LiteralPath $probe -Algorithm SHA256).Hash.ToLowerInvariant();sourceSha256=$probeHash}
    [IO.File]::WriteAllText((Join-Path $report 'probe.json'),($probeManifest|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
}
Write-Output $json
