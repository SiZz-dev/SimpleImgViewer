$ErrorActionPreference='Stop'
$installRoot=Split-Path -Parent $PSScriptRoot
$nodeRuntime=Join-Path $installRoot '.tools/runtimes/node-24.22.1/node.exe'
if(-not (Test-Path -LiteralPath $nodeRuntime -PathType Leaf)){$nodeRuntime=(Get-Command node -CommandType Application -ErrorAction Stop).Source}
Push-Location $installRoot
try { & $nodeRuntime (Join-Path $installRoot 'scripts/start.mjs') @args; $applicationExitCode=$LASTEXITCODE }
finally { Pop-Location }
exit $applicationExitCode
