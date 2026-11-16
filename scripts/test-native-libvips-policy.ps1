param([Parameter(Mandatory=$true)][string]$RuntimeDirectory)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$verify=Join-Path $PSScriptRoot 'verify-native-libvips.ps1'
$root=Join-Path $repo ('build/reports/native-license-tests/'+[Guid]::NewGuid().ToString())
$runtime=Join-Path $root 'runtime';$report=Join-Path $root 'report'
[IO.Directory]::CreateDirectory($runtime)|Out-Null;[IO.Directory]::CreateDirectory((Join-Path $report 'texts'))|Out-Null
$source=if([IO.Path]::IsPathRooted($RuntimeDirectory)){$RuntimeDirectory}else{Join-Path $repo $RuntimeDirectory}
$policy=Get-Content (Join-Path $repo 'config/oss/native-libvips-candidate.json') -Raw|ConvertFrom-Json
foreach($file in $policy.files){Copy-Item -LiteralPath (Join-Path $source $file.name) -Destination $runtime}
foreach($term in $policy.terms){Copy-Item -LiteralPath (Join-Path $repo ('build/reports/native-libvips-licenses/texts/'+$term.id+'.txt')) -Destination (Join-Path $report 'texts')}
$script:cases=0
function Pass {
    & $verify -RuntimeDirectory $runtime -ReportDirectory $report|Out-Null
    $result=Get-Content (Join-Path $report 'verification.json') -Raw|ConvertFrom-Json
    if(!$result.technicalMatch -or $result.distributionReady -or $result.licenseReviewComplete){throw 'Candidate scope check failed'}
    $script:cases++
}
function Denied([string]$reason){
    $caught=$false
    try{& $verify -RuntimeDirectory $runtime -ReportDirectory $report|Out-Null}catch{
        if(!$_.Exception.Message.Contains($reason)){throw};$caught=$true
    }
    if(!$caught){throw 'Mutated candidate was accepted'}
    $result=Get-Content (Join-Path $report 'verification.json') -Raw|ConvertFrom-Json
    if($result.technicalMatch -or $result.distributionReady){throw 'Rejected candidate retained a green report'}
    $script:cases++
}
Pass
$dll=Join-Path $runtime 'libvips-42.dll';$original=[IO.File]::ReadAllBytes($dll);$changed=[byte[]]$original.Clone();$changed[0]=$changed[0] -bxor 1
[IO.File]::WriteAllBytes($dll,$changed);Denied 'FILE_HASH';[IO.File]::WriteAllBytes($dll,$original)
$extra=Join-Path $runtime 'should-not-ship.exe';[IO.File]::WriteAllText($extra,'test-only');Denied 'FILE_SET';[IO.File]::Delete($extra)
[IO.File]::Delete($dll);Denied 'FILE_SET';[IO.File]::WriteAllBytes($dll,$original)
$notice=Join-Path $report 'texts/worker.txt';$text=[IO.File]::ReadAllBytes($notice)
[IO.File]::WriteAllText($notice,'modified notice');Denied 'TERM_HASH';[IO.File]::WriteAllBytes($notice,$text)
Pass
[IO.File]::WriteAllText((Join-Path $root 'verification.json'),([ordered]@{cases=$script:cases;passed=$true;scope='Technical identity and primary text hash guards only';distributionReady=$false}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output ('Native candidate rules: '+$script:cases+' scenarios passed; synthetic copies only')
