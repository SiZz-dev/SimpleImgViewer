$ErrorActionPreference = 'Stop'
$installRoot = Split-Path -Parent $PSScriptRoot
$configPath = Join-Path $installRoot 'config/imagegate.yml'
$jars = @(Get-ChildItem -LiteralPath (Join-Path $installRoot 'lib') -Filter '*.jar' -File)
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw 'Required installation config is missing: config/imagegate.yml'
}
if ($jars.Count -ne 1) { throw 'Expected exactly one application JAR in lib/' }
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
Push-Location $installRoot
try {
    & $javaBin '-jar' $jars[0].FullName '--spring.profiles.active=prod' ("--spring.config.additional-location=file:" + $configPath) @args
    $applicationExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}
exit $applicationExitCode
