param([switch]$Download, [switch]$VerifyOnly)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$root=Join-Path $repo '.tools/modules'
$cache=Join-Path $repo '.tools/module-downloads'
$report=Join-Path $repo 'build/reports/native-installation'
$utf8=[Text.UTF8Encoding]::new($false)
function JsonFile($path,$value){[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 12),$utf8)}
function Hash($path){
    $stream=[IO.File]::OpenRead($path);$digest=[Security.Cryptography.SHA256]::Create()
    try{([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$digest.Dispose()}
}
function ReceiveArchive($url,$path,$length){
    Add-Type -AssemblyName System.Net.Http
    $handler=[Net.Http.HttpClientHandler]::new();$handler.UseProxy=$false
    $client=[Net.Http.HttpClient]::new($handler);$deadline=[Threading.CancellationTokenSource]::new()
    $deadline.CancelAfter(180000);$response=$null;$stream=$null;$file=$null
    try{
        $client.DefaultRequestHeaders.UserAgent.ParseAdd('SimpleImgViewer-native-setup/1')
        $client.DefaultRequestHeaders.AcceptEncoding.ParseAdd('identity')
        $response=$client.GetAsync($url,[Net.Http.HttpCompletionOption]::ResponseHeadersRead,$deadline.Token).GetAwaiter().GetResult()
        if([int]$response.StatusCode -ne 200 -or $response.RequestMessage.RequestUri.Scheme -ne 'https' -or ($response.Content.Headers.ContentLength -and $response.Content.Headers.ContentLength -ne $length) -or @($response.Content.Headers.ContentEncoding|Where-Object{$_ -ne 'identity'}).Count){throw 'Unexpected native download response'}
        $stream=$response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $file=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
        $buffer=[byte[]]::new(65536);$total=0L
        while(($read=$stream.ReadAsync($buffer,0,$buffer.Length,$deadline.Token).GetAwaiter().GetResult()) -gt 0){
            $total+=$read;if($total -gt $length){throw 'Native download exceeded pinned size'}
            $file.Write($buffer,0,$read)
        }
        if($total -ne $length){throw 'Incomplete native download'}
    }finally{
        if($file){$file.Dispose()};if($stream){$stream.Dispose()};if($response){$response.Dispose()}
        $client.Dispose();$deadline.Dispose()
    }
}
function PlainPath($path){
    $item=Get-Item -LiteralPath $path -Force
    while($item){
        if($item.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Native installation rejects reparse paths'}
        $item=if($item.PSIsContainer){$item.Parent}else{$item.Directory}
    }
}
function Inventory($folder){
    @(Get-ChildItem -LiteralPath $folder -Recurse -File -Force|Where-Object FullName -ne (Join-Path $folder 'installation.json')|Sort-Object FullName|ForEach-Object{
        PlainPath $_.FullName
        [ordered]@{path=$_.FullName.Substring($folder.Length+1).Replace('\','/');bytes=$_.Length;sha256=(Hash $_.FullName)}
    })
}
function ReceiptMatch($expected,$actual){
    # Windows PowerShell and pwsh can sort punctuation differently. Compare both
    # inventories in the current process using the same fields and ordering.
    $left=@($expected|ForEach-Object{[pscustomobject][ordered]@{path=[string]$_.path;bytes=[long]$_.bytes;sha256=[string]$_.sha256}}|Sort-Object path)|ConvertTo-Json -Depth 4 -Compress
    $right=@($actual|ForEach-Object{[pscustomobject][ordered]@{path=[string]$_.path;bytes=[long]$_.bytes;sha256=[string]$_.sha256}}|Sort-Object path)|ConvertTo-Json -Depth 4 -Compress
    if($left -cne $right){throw 'Installed native files changed'}
}
function ArchiveMatch($archive,$top,$files){
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $actual=@{};foreach($file in $files){$actual[$file.path]=$file}
    $zip=[IO.Compression.ZipFile]::OpenRead($archive);$count=0
    try{
        foreach($entry in $zip.Entries){
            if($entry.FullName.EndsWith('/')){continue}
            $name=$entry.FullName.Substring($top.Length+1)
            if(!$actual.ContainsKey($name) -or $actual[$name].bytes -ne $entry.Length){throw 'Installed archive file set changed'}
            $stream=$entry.Open();$digest=[Security.Cryptography.SHA256]::Create()
            try{$expected=([BitConverter]::ToString($digest.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$stream.Dispose();$digest.Dispose()}
            if($actual[$name].sha256 -ne $expected){throw 'Installed archive file hash changed'}
            $count++
        }
        if($count -ne $files.Count){throw 'Unrecognized installed files'}
    }finally{$zip.Dispose()}
}
function RunTool($exe,$arguments){
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$exe;$info.Arguments=$arguments;$info.WorkingDirectory=[IO.Path]::GetDirectoryName($exe)
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    try{
        if(!$process.Start()){throw 'Native inspection failed'}
        $stdout=$process.StandardOutput.ReadToEndAsync();$stderr=$process.StandardError.ReadToEndAsync()
        if(!$process.WaitForExit(15000)){$process.Kill();$process.WaitForExit();throw 'Native inspection timed out'}
        $text=$stdout.Result+$stderr.Result
        if($process.ExitCode -ne 0 -or $text.Length -gt 1048576){throw 'Native inspection failed'}
        $text
    }finally{$process.Dispose()}
}
function Extract($archive,$target,$top){
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip=[IO.Compression.ZipFile]::OpenRead($archive)
    try{
        if($zip.Entries.Count -gt 12000){throw 'Too many native archive entries'}
        $size=0L;$seen=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        # Validate the complete archive before creating/extracting any entry.
        foreach($entry in $zip.Entries){
            $name=$entry.FullName
            if(!$name.StartsWith($top+'/',[StringComparison]::Ordinal) -or $name.Contains('\') -or $name -match '[:\x00-\x1f]' -or ($name.TrimEnd('/').Split('/')|Where-Object{$_ -in @('','.','..') -or $_ -match '[. ]$|^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)'}) -or !$seen.Add($name.TrimEnd('/'))){throw 'Unsafe native archive path'}
            if((($entry.ExternalAttributes -shr 16) -band 0xf000) -eq 0xa000){throw 'Native archive link rejected'}
            $size+=$entry.Length
            if($entry.Length -gt 268435456 -or $size -gt 1073741824){throw 'Native archive size exceeded'}
            if($name -match '(?i)(?:magick|ghostscript)[^/]*\.(dll|exe)$'){throw 'Excluded native module found'}
        }
        [IO.Directory]::CreateDirectory($target)|Out-Null
        foreach($entry in $zip.Entries){
            $name=$entry.FullName.Substring($top.Length+1)
            if(!$name){continue}
            $path=Join-Path $target $name
            if($entry.FullName.EndsWith('/')){[IO.Directory]::CreateDirectory($path)|Out-Null;continue}
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))|Out-Null
            $archiveInput=$entry.Open();$archiveOutput=[IO.File]::Open($path,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
            try{$archiveInput.CopyTo($archiveOutput)}finally{$archiveOutput.Dispose();$archiveInput.Dispose()}
        }
    }finally{$zip.Dispose()}
}
if($env:OS -ne 'Windows_NT' -or ![Environment]::Is64BitProcess){throw 'Windows x64 PowerShell is required'}
foreach($folder in @($root,$cache,$report)){[IO.Directory]::CreateDirectory($folder)|Out-Null;PlainPath $folder}
$gate=[IO.File]::Open((Join-Path $root '.installation.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
JsonFile (Join-Path $report 'verification.json') ([ordered]@{technicalMatch=$false;distributionReady=$false;status='CHECKING'})
try{
    $manifest=Get-Content -LiteralPath (Join-Path $repo 'config/oss/native-downloads.windows-x64.json') -Raw|ConvertFrom-Json
    if($manifest.schemaVersion -ne 1 -or $manifest.distributionReady -ne $false -or $manifest.platform -ne 'windows-x64'){throw 'Unsupported native policy'}
    $results=@();$ids=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($module in $manifest.modules){
        if($module.id -notmatch '^[a-z0-9.-]+$' -or !$ids.Add($module.id) -or $module.archive -notmatch '^[a-zA-Z0-9.-]+\.zip$' -or $module.root -notmatch '^[a-zA-Z0-9.-]+$' -or $module.sha256 -notmatch '^[a-f0-9]{64}$' -or $module.bytes -lt 1 -or $module.bytes -gt 268435456 -or $module.kind -notin @('ffmpeg','libvips')){throw 'Invalid native policy'}
        $uri=[Uri]$module.url
        if($uri.Scheme -ne 'https' -or $uri.Host -ne 'github.com' -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -notmatch '^/(FFmpeg-Builds/FFmpeg-Builds|libvips/build-win64-mxe)/releases/download/' -or $uri.AbsolutePath -match '/latest/'){throw 'Unpinned native source'}
        $archive=Join-Path $cache $module.archive
        if(!(Test-Path -LiteralPath $archive)){
            if(!$Download -or $VerifyOnly){throw ('Archive missing: '+$module.archive+'; use -Download')}
            $partial=$archive+'.'+[Guid]::NewGuid().ToString('N')+'.part'
            ReceiveArchive $module.url $partial $module.bytes
            if((Get-Item -LiteralPath $partial).Length -ne $module.bytes -or (Hash $partial) -ne $module.sha256){throw 'Native archive digest mismatch; partial preserved'}
            [IO.File]::Move($partial,$archive)
        }
        PlainPath $archive
        if((Get-Item -LiteralPath $archive).Length -ne $module.bytes -or (Hash $archive) -ne $module.sha256){throw 'Native archive digest mismatch'}
        $installed=Join-Path $root $module.id
        if(!(Test-Path -LiteralPath $installed)){
            if($VerifyOnly){throw 'Native module not installed'}
            $stage=Join-Path $root ('.stage-'+[Guid]::NewGuid().ToString('N'))
            Extract $archive $stage $module.root
            JsonFile (Join-Path $stage 'installation.json') ([ordered]@{id=$module.id;archiveSha256=$module.sha256;distributionReady=$false;files=@(Inventory $stage)})
            # Retain failed staging for inspection. Never overwrite or recursively remove another installation.
            [IO.Directory]::Move($stage,$installed)
        }
        PlainPath $installed
        $receipt=Get-Content -LiteralPath (Join-Path $installed 'installation.json') -Raw|ConvertFrom-Json
        $files=@(Inventory $installed)
        if($receipt.id -ne $module.id -or $receipt.archiveSha256 -ne $module.sha256){throw 'Installed native receipt identity changed'}
        ReceiptMatch @($receipt.files) $files
        # The local receipt is not a trust anchor: compare every file to the pinned publisher archive.
        ArchiveMatch $archive $module.root $files
        $license=Join-Path $installed $module.licenseFile
        if(!(Test-Path -LiteralPath $license -PathType Leaf) -or (Get-Item -LiteralPath $license).Length -lt 100){throw 'Native license text missing'}
        if($module.kind -eq 'ffmpeg'){
            $ffmpeg=Join-Path $installed 'bin/ffmpeg.exe';$ffprobe=Join-Path $installed 'bin/ffprobe.exe'
            $version=RunTool $ffmpeg '-version';$probeVersion=RunTool $ffprobe '-version';$terms=RunTool $ffmpeg '-L'
            if($version -match '--enable-(gpl|nonfree)' -or $probeVersion -match '--enable-(gpl|nonfree)' -or $version -notmatch '--enable-shared' -or $probeVersion -notmatch '--enable-shared' -or $terms -notmatch 'GNU Lesser General Public License'){throw 'LGPL shared build required'}
            foreach($encoder in @('libvpx','libopus','libmp3lame')){if($version -notmatch ('--enable-'+$encoder+'(?:\s|$)')){throw 'Playback encoder missing'}}
            $results+= [ordered]@{id=$module.id;kind='ffmpeg';directory=$installed;version=$version.Split([char]10)[0].Trim();ffmpeg=$ffmpeg;ffprobe=$ffprobe;ffmpegSha256=(Hash $ffmpeg);ffprobeSha256=(Hash $ffprobe);files=$files.Count;licenseTextSha256=(Hash $license);distributionReady=$false}
        }else{
            $vips=Join-Path $installed 'bin/vips.exe';$version=RunTool $vips '--version';$loaders=RunTool $vips '-l foreign'
            if($version -notmatch '^vips-8\.18\.7' -or $loaders -match '(?i)magick|ghostscript'){throw 'Unexpected libvips build/loaders'}
            $results+=[ordered]@{id=$module.id;kind='libvips';directory=$installed;version=$version.Trim();executable=$vips;executableSha256=(Hash $vips);files=$files.Count;licenseTextSha256=(Hash $license);distributionReady=$false;serverEnabled=$false}
        }
    }
    $ff=$results|Where-Object kind -eq 'ffmpeg'
    $config=Join-Path $repo 'build/native-modules/imagegate.native.yml';[IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($config))|Out-Null
    $yaml=@"
# Development-only compatibility file consumed by Node settings; restart required.
# The separate libvips CLI is inspection-only; Node uses the pinned sharp dependency.
imagegate:
  playback:
    enabled: true
    ffmpeg: '$($ff.ffmpeg.Replace('\','/'))'
    ffprobe: '$($ff.ffprobe.Replace('\','/'))'
    ffmpeg-sha256: $($ff.ffmpegSha256)
    ffprobe-sha256: $($ff.ffprobeSha256)
"@
    [IO.File]::WriteAllText($config,$yaml,$utf8)
    JsonFile (Join-Path $report 'verification.json') ([ordered]@{technicalMatch=$true;scope=$manifest.scope;distributionReady=$false;modules=$results;configuration=$config;systemPathChanged=$false;existingInstallationChanged=$false;installationZipCreated=$false})
    Write-Output ('Verified development installation: '+$results.Count+' modules; distributionReady=false')
    Write-Output ('Optional development configuration: '+$config)
}catch{
    JsonFile (Join-Path $report 'verification.json') ([ordered]@{technicalMatch=$false;distributionReady=$false;status='REJECTED'})
    throw
}finally{$gate.Dispose()}
