param(
    [Parameter(Mandatory=$true)][string]$RuntimeDirectory,
    [string]$ReportDirectory='build/reports/native-libvips-licenses',
    [switch]$Collect
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
function Full([string]$path){if([IO.Path]::IsPathRooted($path)){[IO.Path]::GetFullPath($path)}else{[IO.Path]::GetFullPath((Join-Path $repo $path))}}
function Reject([string]$code){throw ('Native candidate verification rejected: '+$code)}
function WriteReport($value){[IO.File]::WriteAllText((Join-Path $report 'verification.json'),($value|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))}
$report=Full $ReportDirectory
$allowed=(Full 'build')+[IO.Path]::DirectorySeparatorChar
if(!$report.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)){Reject 'REPORT_PATH'}
[IO.Directory]::CreateDirectory($report)|Out-Null
# Clear a prior green result before examining mutable candidate files.
WriteReport ([ordered]@{technicalMatch=$false;licenseReviewComplete=$false;distributionReady=$false;status='CHECKING'})
try{
    $policy=Get-Content -LiteralPath (Join-Path $repo 'config/oss/native-libvips-candidate.json') -Raw|ConvertFrom-Json
    if($policy.schemaVersion -ne 1 -or $policy.licenseReviewComplete -ne $false -or $policy.distributionReady -ne $false){Reject 'POLICY_SCOPE'}
    $components=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $terms=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach($term in $policy.terms){if($term.id -notmatch '^[a-z0-9-]+$' -or !$terms.Add($term.id) -or $term.sha256 -notmatch '^[0-9a-f]{64}$'){Reject 'TERM_SCHEMA'}}
    foreach($component in $policy.components){
        if(!$components.Add($component.id) -or !$component.version -or !$component.representativeLicense -or !@($component.terms).Count){Reject 'COMPONENT_SCHEMA'}
        foreach($id in $component.terms){if(!$terms.Contains($id)){Reject 'MISSING_TERM_MAPPING'}}
    }
    $runtime=Full $RuntimeDirectory
    if(!(Test-Path -LiteralPath $runtime -PathType Container) -or ((Get-Item -LiteralPath $runtime).Attributes -band [IO.FileAttributes]::ReparsePoint)){Reject 'RUNTIME_PATH'}
    $entries=@(Get-ChildItem -LiteralPath $runtime -Force)
    $names=[Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach($file in $policy.files){if($file.name -notmatch '^[A-Za-z0-9+_.-]+\.(dll|exe)$' -or !$names.Add($file.name) -or !$components.Contains($file.component) -or $file.sha256 -notmatch '^[0-9a-f]{64}$'){Reject 'FILE_SCHEMA'}}
    if($entries.Count -ne $names.Count){Reject 'FILE_SET'}
    foreach($entry in $entries){if($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or !$names.Contains($entry.Name)){Reject 'FILE_SET'}}
    foreach($file in $policy.files){
        $actual=Join-Path $runtime $file.name
        if((Get-Item -LiteralPath $actual).Length -ne [long]$file.bytes -or (Get-FileHash -LiteralPath $actual -Algorithm SHA256).Hash.ToLowerInvariant() -ne $file.sha256){Reject 'FILE_HASH'}
    }
    $textRoot=Join-Path $report 'texts';[IO.Directory]::CreateDirectory($textRoot)|Out-Null
    foreach($term in $policy.terms){
        $target=Join-Path $textRoot ($term.id+'.txt')
        if(!(Test-Path -LiteralPath $target)){
            if(!$Collect){Reject 'TERM_NOT_COLLECTED'}
            if($term.sourceKind -eq 'repository-local'){
                $source=Full $term.source
                if(!$source.StartsWith(($repo+[IO.Path]::DirectorySeparatorChar),[StringComparison]::OrdinalIgnoreCase)){Reject 'TERM_SOURCE_PATH'}
                Copy-Item -LiteralPath $source -Destination $target
            }elseif($term.sourceKind -eq 'https-primary' -and $term.source -match '^https://raw\.githubusercontent\.com/'){
                Invoke-WebRequest -Uri $term.source -OutFile $target -TimeoutSec 20
            }else{Reject 'TERM_SOURCE'}
        }
        $info=Get-Item -LiteralPath $target
        if($info.PSIsContainer -or ($info.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $info.Length -lt 1 -or $info.Length -gt 1048576 -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $term.sha256){Reject 'TERM_HASH'}
    }
    WriteReport ([ordered]@{technicalMatch=$true;status=$policy.status;components=$components.Count;files=$names.Count;primaryTexts=$terms.Count;
        licenseReviewComplete=$false;distributionReady=$false;remaining=$policy.remaining})
    Write-Output ('Native candidate identity: '+$names.Count+' files / '+$components.Count+' components / '+$terms.Count+' primary texts matched; distributionReady=false')
}catch{
    WriteReport ([ordered]@{technicalMatch=$false;status='REJECTED';licenseReviewComplete=$false;distributionReady=$false})
    throw
}
