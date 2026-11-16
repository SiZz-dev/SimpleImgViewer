param(
    [Parameter(Mandatory=$true)][string]$PythonExecutable,
    [Parameter(Mandatory=$true)][string]$CompilerRoot,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [Parameter(Mandatory=$true)][string]$VipsSource,
    [Parameter(Mandatory=$true)][string]$PkgconfSource,
    [Parameter(Mandatory=$true)][string]$EnumScript,
    [string]$MarshalScript='.tools/native-sources/glib-genmarshal-2.90.0.py',
    [string]$PythonTools='.tools/native-python',
    [string]$CodecHeaders='.tools/native-build/codec-headers',
    [string]$RuntimeDirectory='.tools/native-worker/bin',
    [switch]$ResourceTests
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Full([string]$path){if([IO.Path]::IsPathRooted($path)){[IO.Path]::GetFullPath($path)}else{[IO.Path]::GetFullPath((Join-Path $repo $path))}}
function Call([string]$program,[string[]]$arguments){
    & $program @arguments
    if($LASTEXITCODE -ne 0){throw ('Build command failed: '+[IO.Path]::GetFileName($program))}
}
function Meson([string[]]$arguments){Call $PythonExecutable (@('-m','mesonbuild.mesonmain')+$arguments)}
function WriteUtf8([string]$path,[string]$content){[IO.File]::WriteAllText($path,$content,[Text.UTF8Encoding]::new($false))}
$CompilerRoot=Full $CompilerRoot; $SdkRoot=Full $SdkRoot; $VipsSource=Full $VipsSource
$PkgconfSource=Full $PkgconfSource; $EnumScript=Full $EnumScript; $PythonTools=Full $PythonTools
$CodecHeaders=Full $CodecHeaders
$MarshalScript=Full $MarshalScript
$build=Full '.tools/native-build'; $runtime=Full $RuntimeDirectory
$runtimeRoot=(Full '.tools/native-worker')+[IO.Path]::DirectorySeparatorChar
if(!$runtime.StartsWith($runtimeRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'Runtime output must stay in the workspace native-worker directory'}
[IO.Directory]::CreateDirectory($build)|Out-Null;[IO.Directory]::CreateDirectory($runtime)|Out-Null
if(@(Get-ChildItem -LiteralPath $runtime -Force).Count){throw 'Use an empty private runtime directory; existing native modules are never overwritten'}
$compilerBin=Join-Path $CompilerRoot 'bin'; $sdkBin=Join-Path $SdkRoot 'bin'
$cc=Join-Path $compilerBin 'x86_64-w64-mingw32-clang.exe'; $cxx=Join-Path $compilerBin 'x86_64-w64-mingw32-clang++.exe'
$ninja=Join-Path $PythonTools 'bin/ninja.exe';$cmake=Join-Path $PythonTools 'cmake/data/bin/cmake.exe'
foreach($file in @($PythonExecutable,$cc,$cxx,$ninja,$cmake,$EnumScript,$MarshalScript)){if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw ('Missing build input: '+$file)}}
$saved=@{};foreach($name in @('PATH','PYTHONPATH','CC','CXX','NINJA','PKG_CONFIG','PKG_CONFIG_PATH','PKG_CONFIG_LIBDIR')){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
    $env:PATH=$compilerBin+';'+(Split-Path $ninja)+';'+(Split-Path $cmake)+';'+(Split-Path $PythonExecutable)+';'+$sdkBin+';'+$env:PATH
    $env:PYTHONPATH=$PythonTools;$env:CC=$cc;$env:CXX=$cxx;$env:NINJA=$ninja
    $pkgBuild=Join-Path $build 'pkgconf';$pkgPrefix=Join-Path $build 'pkgconf-install'
    if(!(Test-Path (Join-Path $pkgBuild 'meson-private/coredata.dat'))){
        Meson @('setup',$pkgBuild,$PkgconfSource,'--prefix',$pkgPrefix,'--buildtype=release','--wrap-mode=nodownload')
    }
    Meson @('compile','-C',$pkgBuild,'-j','2');Meson @('install','-C',$pkgBuild)
    $pkgconf=Join-Path $pkgPrefix 'bin/pkgconf.exe';$env:PKG_CONFIG=$pkgconf
    $pc=Join-Path $build 'pkgconfig';[IO.Directory]::CreateDirectory($pc)|Out-Null
    # Relocate metadata in a build-only copy, preserving the downloaded SDK bytes.
    foreach($file in Get-ChildItem (Join-Path $SdkRoot 'lib/pkgconfig') -Filter '*.pc'){
        $text=[IO.File]::ReadAllText($file.FullName)
        $text=$text.Replace('/usr/local/mxe/usr/x86_64-w64-mingw32.shared',$SdkRoot.Replace('\','/'))
        $text=[regex]::Replace($text,'(?m)^includedir=.*?/include/(libpng16|webp)\r?$',('includedir='+$CodecHeaders.Replace('\','/')))
        # This SDK embeds its no-NLS proxy in consumers and omits a separate intl library.
        # Minimal libvips also has NLS disabled; do not pull in a new dynamic gettext provider.
        $text=[regex]::Replace($text,'(?<!\S)-lintl(?=\s|$)','')
        $text=[regex]::Replace($text,'(?<!\S)-lz(?=\s|$)','-l:libz1.lib')
        $text=[regex]::Replace($text,'(?m)^Cflags: (.*)$',('Cflags: $1 -I"'+$CodecHeaders.Replace('\','/')+'"'))
        WriteUtf8 (Join-Path $pc $file.Name) $text
    }
    $env:PKG_CONFIG_PATH=$pc;$env:PKG_CONFIG_LIBDIR=$pc
    $native=Join-Path $build 'meson-native.ini'
    WriteUtf8 $native ("[binaries]`nglib-mkenums = ['"+$PythonExecutable.Replace('\','/')+"', '"+$EnumScript.Replace('\','/')+"']`nglib-genmarshal = ['"+$PythonExecutable.Replace('\','/')+"', '"+$MarshalScript.Replace('\','/')+"']`npkg-config = ['"+$pkgconf.Replace('\','/')+"', '--dont-define-prefix']`n")
    $vipsBuild=Join-Path $build 'vips';$vipsPrefix=Join-Path $build 'vips-install'
    if(!(Test-Path (Join-Path $vipsBuild 'meson-private/coredata.dat'))){
        $options=@(Get-Content (Join-Path $repo 'native/imagegate-converter/libvips-8.18.7.args')|Where-Object{$_ -and !($_.StartsWith('#'))})
        Meson (@('setup',$vipsBuild,$VipsSource,'--prefix',$vipsPrefix,'--native-file',$native,'--wrap-mode=nodownload','-Dc_link_args=-Wl,--no-insert-timestamp','-Dcpp_link_args=-Wl,--no-insert-timestamp')+$options)
    }else{
        Meson @('setup','--reconfigure','--clearcache',$vipsBuild,$VipsSource,'-Dc_link_args=-Wl,--no-insert-timestamp','-Dcpp_link_args=-Wl,--no-insert-timestamp')
    }
    # Build only the library used by our worker, not upstream CLI/fuzzer/example programs.
    Call $ninja @('-C',$vipsBuild,'-j','2','libvips/libvips-42.dll')
    foreach($sub in @('bin','lib','lib/pkgconfig','include/vips')){[IO.Directory]::CreateDirectory((Join-Path $vipsPrefix $sub))|Out-Null}
    Copy-Item -LiteralPath (Join-Path $vipsBuild 'libvips/libvips-42.dll') -Destination (Join-Path $vipsPrefix 'bin')
    Copy-Item -LiteralPath (Join-Path $vipsBuild 'libvips/libvips.dll.a') -Destination (Join-Path $vipsPrefix 'lib')
    Copy-Item -LiteralPath (Join-Path $vipsBuild 'meson-private/vips.pc') -Destination (Join-Path $vipsPrefix 'lib/pkgconfig')
    Get-ChildItem (Join-Path $VipsSource 'libvips/include/vips') -Filter '*.h'|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $vipsPrefix 'include/vips')}
    Get-ChildItem (Join-Path $vipsBuild 'libvips/include/vips') -Filter '*.h'|ForEach-Object{Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $vipsPrefix 'include/vips')}
    $env:PKG_CONFIG_PATH=(Join-Path $vipsPrefix 'lib/pkgconfig')+';'+$pc;$env:PKG_CONFIG_LIBDIR=$env:PKG_CONFIG_PATH
    $workerBuild=Join-Path $build 'worker'
    $resourceOption=if($ResourceTests){'ON'}else{'OFF'}
    Call $cmake @('-S',(Join-Path $repo 'native/imagegate-converter'),'-B',$workerBuild,'-G','Ninja','-U','*VIPS*',('-DCMAKE_CXX_COMPILER='+$cxx),('-DCMAKE_MAKE_PROGRAM='+$ninja),('-DPKG_CONFIG_EXECUTABLE='+$pkgconf),'-DPKG_CONFIG_ARGN=--dont-define-prefix','-DCMAKE_BUILD_TYPE=Release','-DIMAGEGATE_BUILD_SANDBOX=OFF',('-DIMAGEGATE_BUILD_RESOURCE_TESTS='+$resourceOption))
    Call $cmake @('--build',$workerBuild,'--parallel','2')
    $worker=Join-Path $workerBuild 'imagegate-converter.exe';Copy-Item -LiteralPath $worker -Destination $runtime
    $minimalBin=Join-Path $vipsPrefix 'bin';$vipsDll=Join-Path $minimalBin 'libvips-42.dll'
    if(!(Test-Path $vipsDll)){throw 'Minimal libvips DLL missing'}
    Copy-Item -LiteralPath $vipsDll -Destination $runtime
    # Resolve the actual PE dependency closure from audited candidate directories, not ambient PATH.
    $queue=[Collections.Generic.Queue[string]]::new();$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    $queue.Enqueue((Join-Path $runtime 'imagegate-converter.exe'));$queue.Enqueue((Join-Path $runtime 'libvips-42.dll'))
    $readobj=Join-Path $compilerBin 'llvm-readobj.exe'
    while($queue.Count -gt 0){
        $binary=$queue.Dequeue();if(!$seen.Add([IO.Path]::GetFileName($binary))){continue}
        $imports=& $readobj --coff-imports $binary;if($LASTEXITCODE -ne 0){throw 'PE dependency inspection failed'}
        foreach($match in [regex]::Matches(($imports -join "`n"),'(?mi)^\s*Name: ([^\r\n]+\.dll)\s*$')){
            $name=$match.Groups[1].Value.Trim()
            if($name -match '^(?i:api-ms-|ext-ms-)' -or (Test-Path -LiteralPath (Join-Path $env:SystemRoot ('System32/'+$name)))){continue}
            $dest=Join-Path $runtime $name
            if(!(Test-Path -LiteralPath $dest)){
                $source=@($minimalBin,$compilerBin,$sdkBin)|ForEach-Object{Join-Path $_ $name}|Where-Object{Test-Path -LiteralPath $_ -PathType Leaf}|Select-Object -First 1
                if(!$source){throw ('Unresolved native dependency: '+$name)}
                Copy-Item -LiteralPath $source -Destination $dest
            }
            $queue.Enqueue($dest)
        }
    }
    $reportDir=Full 'build/reports/libvips-native';[IO.Directory]::CreateDirectory($reportDir)|Out-Null
    $inventory=@(Get-ChildItem $runtime -File|Sort-Object Name|ForEach-Object{[ordered]@{name=$_.Name;bytes=$_.Length;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}})
    WriteUtf8 (Join-Path $reportDir 'runtime-inventory.json') ([ordered]@{libvips='8.18.7';buildOnly=$true;distributionReady=$false;files=$inventory}|ConvertTo-Json -Depth 5)
    Write-Output ('Built experimental worker: '+(Join-Path $runtime 'imagegate-converter.exe'))
}finally{
    foreach($name in $saved.Keys){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}
}
