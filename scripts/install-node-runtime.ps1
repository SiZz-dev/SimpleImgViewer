param([switch]$Download)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'));$utf8=[Text.UTF8Encoding]::new($false)
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'install-native-modules.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Installer helper parse failed'}
foreach($function in $ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst]},$false)){. ([ScriptBlock]::Create($function.Extent.Text))}
$runtimeRoot=Join-Path $repo '.tools/runtimes';$cache=Join-Path $repo '.tools/module-downloads'
foreach($folder in @($runtimeRoot,$cache)){[IO.Directory]::CreateDirectory($folder)|Out-Null;PlainPath $folder}
$archive=Join-Path $cache 'node-v24.22.1-win-x64.zip';$destination=Join-Path $runtimeRoot 'node-24.22.1'
$expected='d2046d28c2c3dcd211ac5145c0bf30e2047ef9dfb306693c8c6e2eccd8b95fa5'
if(!(Test-Path -LiteralPath $archive)){
    if(!$Download){throw 'Node archive missing; use -Download'}
    $partial=$archive+'.'+[Guid]::NewGuid().ToString('N')+'.part'
    ReceiveArchive 'https://nodejs.org/dist/v24.22.1/node-v24.22.1-win-x64.zip' $partial 37748064
    if((Hash $partial) -ne $expected){throw 'Official Node SHA mismatch'}
    [IO.File]::Move($partial,$archive)
}
PlainPath $archive;if((Hash $archive) -ne $expected){throw 'Official Node SHA mismatch'}
if(!(Test-Path -LiteralPath $destination)){
    $stage=Join-Path $runtimeRoot ('.node-stage-'+[Guid]::NewGuid().ToString('N'))
    Extract $archive $stage 'node-v24.22.1-win-x64';[IO.Directory]::Move($stage,$destination)
}
PlainPath $destination;ArchiveMatch $archive 'node-v24.22.1-win-x64' @(Inventory $destination)
$version=RunTool (Join-Path $destination 'node.exe') '--version';if($version.Trim() -ne 'v24.22.1'){throw 'Unexpected Node version'}
Write-Output ('Verified Node '+$version.Trim()+': '+$destination)
