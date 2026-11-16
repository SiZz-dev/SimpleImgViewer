$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$utf8=[Text.UTF8Encoding]::new($false)
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'install-native-modules.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Installer parse failed'}
# Load only the trusted local helper definitions, never the installer entry point.
foreach($function in $ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)){
    . ([ScriptBlock]::Create($function.Extent.Text))
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$work=Join-Path $repo ('build/native-installation-tests/'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($work)|Out-Null
$passed=0
function Fixture($name,$entries){
    $file=Join-Path $work ($name+'.zip');$zip=[IO.Compression.ZipFile]::Open($file,[IO.Compression.ZipArchiveMode]::Create)
    try{
        foreach($entry in $entries){
            $item=$zip.CreateEntry($entry.name)
            if($entry.link){$item.ExternalAttributes=(0xa000 -shl 16)}
            $stream=$item.Open();$bytes=$utf8.GetBytes('synthetic bytes')
            try{$stream.Write($bytes,0,$bytes.Length)}finally{$stream.Dispose()}
        }
    }finally{$zip.Dispose()}
    $file
}
function Rejects($action){
    $rejected=$false
    try{& $action}catch{$rejected=$true}
    if(!$rejected){throw 'Unsafe native fixture was accepted'}
    $script:passed++
}
$valid=Fixture 'valid' @(@{name='package/bin/example.dll'})
$target=Join-Path $work 'valid';Extract $valid $target 'package'
$original=@(Inventory $target);ArchiveMatch $valid 'package' $original;$passed++
$one=[pscustomobject]@{path='doc/ffmpeg.html';bytes=10;sha256=('a'*64)}
$two=[ordered]@{path='doc/ffmpeg-all.html';bytes=20;sha256=('b'*64)}
ReceiptMatch @($one,$two) @($two,$one);$passed++
Rejects {ReceiptMatch @($one,$two) @($one)}
Rejects {ReceiptMatch @($one) @($one,$two)}
Rejects {ReceiptMatch @($one) @([pscustomobject]@{path=$one.path;bytes=$one.bytes;sha256=('c'*64)})}
$cases=@(
    @{id='parent';names=@('package/../outside.dll')},
    @{id='absolute';names=@('C:/outside.dll')},
    @{id='backslash';names=@('package/bin\outside.dll')},
    @{id='case-collision';names=@('package/bin/a.dll','package/bin/A.dll')},
    @{id='device';names=@('package/bin/CON.dll')},
    @{id='trailing-dot';names=@('package/bin/a.dll.')},
    @{id='magick';names=@('package/bin/libMagickCore.dll')}
)
foreach($case in $cases){
    $archive=Fixture $case.id @($case.names|ForEach-Object{@{name=$_}})
    $destination=Join-Path $work $case.id
    Rejects {Extract $archive $destination 'package'}
    if(Test-Path -LiteralPath $destination){throw 'Rejected archive created output'}
}
$linked=Fixture 'link' @(@{name='package/bin/link.dll';link=$true})
Rejects {Extract $linked (Join-Path $work 'linked') 'package'}
[IO.File]::WriteAllText((Join-Path $target 'extra.dll'),'unrecognized',$utf8)
Rejects {ArchiveMatch $valid 'package' @(Inventory $target)}
[IO.File]::Delete((Join-Path $target 'extra.dll'))
[IO.File]::WriteAllText((Join-Path $target 'bin/example.dll'),'tampered bytes',$utf8)
# Even a newly generated/forged receipt cannot approve content different from the publisher ZIP.
Rejects {ArchiveMatch $valid 'package' @(Inventory $target)}
[IO.File]::WriteAllText((Join-Path $work 'result.json'),(@{passed=$true;scenarios=$passed;scope='synthetic archive boundary and publisher-content identity'}|ConvertTo-Json),$utf8)
Write-Output ('Native installation rules: '+$passed+' scenarios passed; synthetic fixtures only')
