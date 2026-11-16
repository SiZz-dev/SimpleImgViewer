# Experimental native build only; never an installation package.
[CmdletBinding()]
param(
    [string]$WorkerBuildDirectory = '.tools/native-build/worker',
    [string]$VerifiedRuntime = '.tools/native-worker/winjob-v2-verified/bin',
    [string]$OutputDirectory = '.tools/native-sandbox/lifecycle-v2-final',
    [string]$CompilerRoot = '.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64',
    [string]$Cmake = '.tools/native-python/cmake/data/bin/cmake.exe'
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$output=[IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
$allowed=[IO.Path]::GetFullPath((Join-Path $root '.tools/native-sandbox'))+[IO.Path]::DirectorySeparatorChar
if(-not $output.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){throw 'Output must be a new .tools/native-sandbox directory'}
if(Test-Path -LiteralPath $output){throw 'Output already exists; choose a new directory'}
$build=[IO.Path]::GetFullPath((Join-Path $root $WorkerBuildDirectory))
if(-not (Test-Path -LiteralPath (Join-Path $build 'CMakeCache.txt'))){throw 'First build the pinned minimal libvips worker with build-libvips-worker.ps1'}
& (Join-Path $PSScriptRoot 'verify-native-libvips.ps1') -RuntimeDirectory $VerifiedRuntime
$savedPath=$env:PATH
try {
    $env:PATH=(Join-Path $root "$CompilerRoot/bin")+';'+$savedPath
    & $Cmake -S (Join-Path $root 'native/imagegate-converter') -B $build -DIMAGEGATE_BUILD_SANDBOX=ON -DIMAGEGATE_BUILD_RESOURCE_TESTS=ON
    if($LASTEXITCODE -ne 0){throw 'Sandbox CMake configure failed'}
    & $Cmake --build $build --target imagegate-sandbox-launcher imagegate-contained-worker imagegate-access-probe imagegate-network-probe imagegate-sandbox-test-launcher
    if($LASTEXITCODE -ne 0){throw 'Sandbox compile failed'}
    foreach($name in @('worker','probe','network-probe','launcher')){New-Item -ItemType Directory -Path (Join-Path $output $name) -Force | Out-Null}
    Copy-Item -LiteralPath (Join-Path $build 'imagegate-contained-worker.exe') -Destination (Join-Path $output 'worker')
    Get-ChildItem -LiteralPath $VerifiedRuntime -Filter '*.dll' -File | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $output 'worker')
    }
    Copy-Item -LiteralPath (Join-Path $build 'imagegate-access-probe.exe') -Destination (Join-Path $output 'probe')
    # An unloaded ordinary file: denial cannot be explained by Windows locking a loaded DLL image.
    Set-Content -LiteralPath (Join-Path $output 'probe/libz1.dll') -Value 'synthetic unloaded write control' -Encoding ascii
    Copy-Item -LiteralPath (Join-Path $build 'imagegate-network-probe.exe') -Destination (Join-Path $output 'network-probe')
    Set-Content -LiteralPath (Join-Path $output 'network-probe/libz1.dll') -Value 'synthetic unloaded write control' -Encoding ascii
    foreach($name in @('imagegate-sandbox-launcher.exe','imagegate-sandbox-test-launcher.exe')) {
        Copy-Item -LiteralPath (Join-Path $build $name) -Destination (Join-Path $output 'launcher')
    }
    Write-Host "Experimental sandbox build: $output (not registered, not distribution approved)"
} finally {$env:PATH=$savedPath}
