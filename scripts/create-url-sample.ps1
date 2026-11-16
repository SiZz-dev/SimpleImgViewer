# Synthetic development sample only; existing files are preserved.
[CmdletBinding()]
param([string]$OutputPath='.runtime/development/url-files/TestFiles/sample.jpg')
$ErrorActionPreference='Stop'
$repoRoot=Split-Path -Parent $PSScriptRoot
$nodeRuntime=Join-Path $repoRoot '.tools/runtimes/node-24.22.1/node.exe'
if(-not (Test-Path -LiteralPath $nodeRuntime -PathType Leaf)){$nodeRuntime=(Get-Command node -CommandType Application -ErrorAction Stop).Source}
Push-Location $repoRoot
try { & $nodeRuntime (Join-Path $repoRoot 'scripts/create-url-sample.mjs') $OutputPath; if($LASTEXITCODE -ne 0){throw 'Synthetic sample creation failed'} }
finally { Pop-Location }
