param(
    [Parameter(Mandatory=$true)][string]$CompilerRoot,
    [Parameter(Mandatory=$true)][string]$TiffSource,
    [string]$PythonTools='.tools/native-python',
    [switch]$Download
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Full([string]$path){if([IO.Path]::IsPathRooted($path)){[IO.Path]::GetFullPath($path)}else{[IO.Path]::GetFullPath((Join-Path $repo $path))}}
$CompilerRoot=Full $CompilerRoot;$TiffSource=Full $TiffSource;$PythonTools=Full $PythonTools
$headers=Full '.tools/native-build/codec-headers';$configure=Full '.tools/native-build/tiff-headers'
[IO.Directory]::CreateDirectory($headers)|Out-Null
$inputs=Get-Content -LiteralPath (Join-Path $repo 'native/imagegate-converter/build-inputs.json') -Raw|ConvertFrom-Json
foreach($source in $inputs.codecHeaders){
    if($source.name -notmatch '^[a-zA-Z0-9_.-]+$' -or $source.url -notmatch '^https://raw\.githubusercontent\.com/' -or $source.sha256 -notmatch '^[0-9a-f]{64}$'){throw 'Invalid header source manifest'}
    $file=Join-Path $headers $source.name
    if(!(Test-Path -LiteralPath $file)){
        if(!$Download){throw ('Missing header; use explicit -Download after reviewing build-inputs.json: '+$source.name)}
        Invoke-WebRequest -Uri $source.url -OutFile $file -TimeoutSec 30
    }
    if((Get-FileHash $file -Algorithm SHA256).Hash.ToLowerInvariant() -ne $source.sha256){throw ('Header SHA mismatch: '+$source.name)}
}
$cc=Join-Path $CompilerRoot 'bin/x86_64-w64-mingw32-clang.exe';$cxx=Join-Path $CompilerRoot 'bin/x86_64-w64-mingw32-clang++.exe'
$cmake=Join-Path $PythonTools 'cmake/data/bin/cmake.exe';$ninja=Join-Path $PythonTools 'bin/ninja.exe'
& $cmake -S $TiffSource -B $configure -G Ninja "-DCMAKE_C_COMPILER=$cc" "-DCMAKE_CXX_COMPILER=$cxx" "-DCMAKE_MAKE_PROGRAM=$ninja" -Dtiff-tools=OFF -Dtiff-tests=OFF -Dtiff-contrib=OFF -Dtiff-docs=OFF -Dzlib=OFF -Djpeg=OFF -Dlibdeflate=OFF -Djbig=OFF -Dlerc=OFF -Dlzma=OFF -Dzstd=OFF -Dwebp=OFF
if($LASTEXITCODE -ne 0){throw 'TIFF public header configuration failed'}
foreach($name in @('tiff.h','tiffio.h','tiffio.hxx')){Copy-Item -LiteralPath (Join-Path $TiffSource ('libtiff/'+$name)) -Destination $headers}
foreach($name in @('tiffconf.h','tiffvers.h')){Copy-Item -LiteralPath (Join-Path $configure ('libtiff/'+$name)) -Destination $headers}
# This exact public ABI is recorded separately from the unchanged SDK DLL, not a new JPEG build.
$jconfig=[IO.File]::ReadAllText((Join-Path $headers 'jconfig.h.in')).Replace('@JPEG_LIB_VERSION@','62').Replace('@VERSION@','5.0.0').Replace('@LIBJPEG_TURBO_VERSION_NUMBER@','5000000')
$jconfig=[regex]::Replace($jconfig,'(?m)^#cmakedefine (C_ARITH_CODING_SUPPORTED|D_ARITH_CODING_SUPPORTED|WITH_SIMD) 1\r?$','#define $1 1')
$jconfig=$jconfig.Replace('#cmakedefine RIGHT_SHIFT_IS_UNSIGNED 1','/* #undef RIGHT_SHIFT_IS_UNSIGNED */')
if($jconfig -match '@[A-Z_]+@|#cmakedefine'){throw 'Unresolved JPEG header template'}
[IO.File]::WriteAllText((Join-Path $headers 'jconfig.h'),$jconfig,[Text.UTF8Encoding]::new($false))
Write-Output 'Verified codec headers and generated Windows public ABI headers'
