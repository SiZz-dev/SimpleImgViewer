# Compile the non-Node-macro LPAC code and check its existing synthetic access probe.
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$id=[Guid]::NewGuid().ToString('D')
$build=Join-Path $repo ('.tools/node-lpac-legacy/'+$id)
$root=Join-Path $repo ('build/acceptance/node-lpac-legacy-'+$id)
$runtime=Join-Path $build 'runtime'
foreach($directory in @($build,$root,$runtime)){[IO.Directory]::CreateDirectory($directory)|Out-Null}
$compiler=Join-Path $repo '.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64/bin/x86_64-w64-mingw32-clang++.exe'
$native=Join-Path $repo 'native/imagegate-converter'
$launcher=Join-Path $build 'legacy-control.exe'
& $compiler '-std=c++17' '-O2' '-static' '-municode' '-D_WIN32_WINNT=0x0A00' '-DWINVER=0x0A00' '-I' (Join-Path $native 'src') (Join-Path $native 'tests/sandbox_test_launcher.cpp') (Join-Path $native 'src/sandbox_runner.cpp') (Join-Path $native 'src/sandbox_lifecycle.cpp') '-luserenv' '-ladvapi32' '-lole32' '-lshell32' '-luuid' '-o' $launcher
if($LASTEXITCODE -ne 0){throw 'Legacy LPAC control compile failed'}
$probe=Join-Path $runtime 'imagegate-access-probe.exe'
& $compiler '-std=c++17' '-O2' '-static' '-municode' '-D_WIN32_WINNT=0x0A00' '-DWINVER=0x0A00' '-I' (Join-Path $native 'src') (Join-Path $native 'tests/access_probe.cpp') '-ladvapi32' '-lws2_32' '-o' $probe
if($LASTEXITCODE -ne 0){throw 'Legacy access probe compile failed'}
[IO.File]::WriteAllText((Join-Path $runtime 'libz1.dll'),'synthetic unloaded ACL canary')
$forbidden=Join-Path $root 'forbidden.txt';[IO.File]::WriteAllText($forbidden,'synthetic private marker')
$input=Join-Path $repo 'native/process-job/tests/node-lpac-probe.cjs'
$inputAcl=(Get-Acl -LiteralPath $input).Sddl;$outsideAcl=(Get-Acl -LiteralPath $forbidden).Sddl
$target=Join-Path $root 'job'
$diagnostics=& $launcher $runtime $input $target $forbidden '9' 'normal'
if($LASTEXITCODE -ne 0){throw ('Legacy LPAC access control failed: '+$diagnostics)}
$checks=Get-Content -LiteralPath (Join-Path $target 'out/probe.json') -Raw|ConvertFrom-Json
if(!$checks.contained -or !$checks.lpacFilePolicy -or $checks.caps -ne 0 -or $checks.hostRead -ne 5 -or $checks.hostWrite -ne 5){throw 'Unexpected legacy LPAC access result'}
if((Get-Acl -LiteralPath $input).Sddl -ne $inputAcl -or (Get-Acl -LiteralPath $forbidden).Sddl -ne $outsideAcl){throw 'Original ACL changed'}
if([IO.File]::ReadAllText($forbidden) -ne 'synthetic private marker'){throw 'Outside synthetic file changed'}
$remaining=@(Get-ChildItem -LiteralPath (Join-Path $root '.imagegate-lpac-state') -Filter '*.lease' -File)
if($remaining.Count){throw 'Profile lease cleanup incomplete'}
$result=@{passed=$true;testOnly=$true;productionEnabled=$false;checks=$checks;originalAclUnchanged=$true;remainingLeases=$remaining.Count;launcherSha256=(Get-FileHash -LiteralPath $launcher -Algorithm SHA256).Hash.ToLowerInvariant()}
$report=Join-Path $repo 'build/reports/node-lpac';[IO.Directory]::CreateDirectory($report)|Out-Null
[IO.File]::WriteAllText((Join-Path $report 'legacy-control.json'),($result|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
Write-Output ($result|ConvertTo-Json -Depth 4)
