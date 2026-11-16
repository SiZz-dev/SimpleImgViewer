# LPAC 프로필 회수와 저장 용량 감시

최신 후속: [Java 격리 어댑터](LIBVIPS_JAVA_SANDBOX_ADAPTER.md)를 추가했습니다. 부모 capabilities/기대 모듈 지문·응답/이미지 검증을 연결했고 기존 생명주기9개를 포함한109개 회귀가 통과했습니다. 아래2026-11-11의 Java 미연결 설명은 당시 범위이며 Spring factory·운영 활성화는 지금도 하지 않았습니다.

2026-11-11. [파일/네트워크 경계](LIBVIPS_FILE_NETWORK_BOUNDARY.md)의 후속 실험 단계다. 부모 BUILD는 imagegate-lpac-launcher-v2이며 contained-v3와 생산 capability0은 유지한다. Java v2 client/Spring factory, 설치 설정, 기존 img2tile은 변경하지 않았다. 배포 승인이나 OS 하드 디스크 쿼터 완료를 뜻하지 않는다.

## 작업 원장과 프로필 회수

- 작업 부모 폴더의 .imagegate-lpac-state에 전용 원장을 둔다. 디렉터리는 생성 시부터 보호 DACL/Medium Integrity를 적용하며 현재 사용자·SYSTEM·Administrators만 Full Control을 가진다. 기존 폴더/파일의 소유자와 정확한 ACL을 대조하고, 경로의 각 상위 폴더를 reparse를 따르지 않는 handle로 열어 실행 중 rename/delete를 막는다.
- 원장 파일명은 소문자 UUID.lease, 내용은 고정 IMAGEGATE-LPAC-LEASE/1과 같은 UUID뿐이다. 경로·외부 프로필명·PID를 신뢰 데이터로 받지 않는다. 프로필명은 ImageGate.v4.UUID로만 파생한다. 프로필 생성 API 전에 CREATE_NEW로 파일을 만들고 FlushFileBuffers를 수행한다.
- 부모는 기록 handle을 비상속·공유 불가로 유지한다. 회수기는 실행 중인 기록을 sharing violation으로 건너뛴다. .gate의 짧은 배타 잠금으로 회수/접수를 직렬화하고 이 원장을 공유하는 활성 작업을4개로 제한한다. 다른 원장 디렉터리와 서버 전체의 슬롯을 합산하는 정책은 후속이다.
- 회수는 해당 원장의 UUID 기록만 열어 스키마·파일명/본문 일치·소유자/ACL·정규 파일·단일 hard link·대체 스트림 부재를 확인한다. 잘못된 기록은 삭제하거나 자동 수정하지 않는다. API 실패도 기록을 보존한다. 다른 앱 프로필을 전역 prefix 검색하여 회수하지 않는다.
- Job 이름도 Local\\ImageGate.v4.UUID로 고정한다. 잠금이 풀렸어도 해당 Job에 활성 프로세스가 있으면 건너뛴다. 프로필 정리 전에 하위 항목의 reparse/hard link/대체 스트림과 탐색 범위를 검사하고 Windows DeleteAppContainerProfile을 사용한다. 임의 경로를 재귀 삭제하지 않는다. 삭제 API 성공 후에도 생성된 프로필 폴더가 남아 있으면 기록을 지우지 않는다.
- 회수는 같은 Windows 사용자 컨텍스트에서 실행한다. 프로필 루트는 환경 변수가 아닌 명시적 현재 사용자 토큰의 SHGetKnownFolderPath(LocalAppData)와 고정 Packages/ImageGate.v4.UUID에서 얻는다. 시작 시 SID 기반 GetAppContainerFolderPath가 그 루트의 AC인지 대조한다. 다른 저장소 레이아웃/UNC/reparse는 자동 우회하지 않는다. Java 제한 실행기의 USERPROFILE=작업 폴더 환경에서도 실제 사용자 저장소를 조회한다.
- 정상 종료/예외에서는 자식 종료를 확인한 뒤 프로필을 제거하고 기록을 handle로 삭제한다. 자식 종료를 확인하지 못하거나 프로필에 위험한 링크/너무 많은 항목이 있으면 회수를 보류한다. 기록 없는 예전 실험 프로필은 이 회수기가 자동 입양하지 않는다.

새 작업 접수 때 같은 원장의 중단 기록을 먼저 회수하며 수동 실행도 가능하다.

```powershell
& '.tools/native-sandbox/lifecycle-v2-final/launcher/imagegate-sandbox-launcher.exe' `
  --recover-profiles 'D:\ImageGatePrivateJobs\.imagegate-lpac-state'
```

경로는 실제 사용했던 전용 원장으로 지정한다. 새 폴더를 임의 생성하거나 다른 업무 폴더를 지정하는 명령이 아니다. 성공 stdout은 launcherBuild/recovered/busy의 고정 JSON이다. busy 작업은 종료시키지 않으며 실패는 고정 stage/Win32 번호로 반환한다.

## 부모 종료와 자식 수명

PROC_THREAD_ATTRIBUTE_JOB_LIST로 생성 시점부터 자식을 부모 소유 Job에 배치한다. CREATE_SUSPENDED 상태에서 해당 Job 멤버인지 확인한 뒤 ResumeThread한다. CreateProcess와 후속 AssignProcess 사이의 미할당 자식 구간을 제거했다. Job handle은 자식/회수기에 상속하지 않는다. 부모 강제 종료는 KILL_ON_JOB_CLOSE로 자식을 종료하며, 원장은 다음 실행/명시적 회수에 남는다. 예외 unwind에서도 Job 종료와 자식 wait를 수행하고 종료 미확인 시 프로필 삭제를 보류한다.

프로필 생성 전 기록 flush는 강제 종료 복구를 위한 순서다. 전원 장애/장치 캐시 고장까지 모든 파일시스템 트랜잭션을 보장하는 WAL 구현은 아니다. 전원 장애·서비스 계정 변경·다른 세션의 운영 인수는 남아 있다.

## 저장 용량 정책

| 항목 | 현재 실험 기본값 |
| --- | --- |
| 출력 폴더 논리 바이트 합계 |64MiB 감시 상한 |
| 해당 프로필 전체 저장소 합계 |16MiB 감시 상한. AC만 집계하지 않음 |
| 각 트리 탐색 |최대256개 항목, 깊이8 |
| 검사 주기 |부모 루프에서 약100ms 간격, 최종 결과 검사 전 재검사 |
| 실행 전 작업 볼륨 |사용자 가용 여유512MiB + 예상 작업 여유768MiB 미만이면 접수 거절 |
| 실행 중 작업/프로필 볼륨 |각각 사용자 가용 여유512MiB 미만이면 중단 |
| 위험한 파일 |reparse/hard link/대체 데이터 스트림 또는 검사 실패 시 중단 |

용량/파일 검사는 metadata만 사용한다. 검사 중 파일/폴더를 삭제 공유 없이 열고 실제 handle 경로를 대조한다. 검사 오류를0바이트로 무시하지 않는다. 입출력·프로필의 검사 실패나 초과는 Job 종료/자식 wait를 거쳐 실패 처리한다. 원본·설치 모듈을 수정하지 않으며 업무 파일을 정리 대상으로 삼지 않는다.

이 정책은 주기적 탐지와 접수 제한이다. 검사 사이의 급격한 쓰기, 파일시스템 metadata/클러스터 사용량, 커널/드라이버 지연을 하드 상한으로 막지 않는다. 예상 작업 여유도 실제 디스크 공간 예약이 아니다. NTFS quota는 파일 소유 사용자/볼륨 기준이므로 개발 사용자 전체에 임의 적용하지 않았다. 전용 볼륨·서비스 계정 quota 또는 다른 강제 저장소 경계의 선택/운영 검증은 여전히 필요하다. 정상 결과/실패 작업 폴더의 누적 보존·게시 후 정리와 서버 전체 저장소 예산도 Java 연동 단계에서 연결한다.

현재 고정 native 정책이며 설치 YAML에 동작하지 않는 새 옵션을 추가하지 않았다. Java 실행 어댑터와 검증된 설정 범위를 연결하는 단계에서 config/imagegate.example.yml과 설정 검증을 함께 추가한다. production CLI로 한도 상향/감시 해제/네트워크 capability를 지정할 수 없다.

## 실제 시험과 재현

LibvipsLifecycleIntegrationTests의 새9개는 실제 Windows 프로세스/합성 파일로 아래를 검증한다.

1. 살아 있는 원장은 busy로 남고 자식이 계속 실행된다. 부모 강제 종료 후 자식 PID 종료·프로필 회수·원장 삭제를 확인하고 재실행은0개 회수다.
2. 동시4개 부모/자식을 실행하고5번째 접수를 거절한다.4개 강제 종료 후 모두 회수한다.
3. 실행 중 출력 파일을 늘려 시험용1MiB 상한 초과를 감지하고 자식을 종료한다.
4. capability0을 유지한 채 프로필 AC/Temp에 쓰는 합성 파일의1MiB 초과를 감지한다. 시험 프로브에만 부모가 확인한 경로를 전달하며 생산 워커 인자는 변경하지 않는다.
5. 출력 파일의 NTFS 대체 스트림을 바이트 집계에서 누락하지 않고 거절한다.
6. 실제 디스크를 채우지 않고, 가용 공간보다 큰 시험용 요구값으로 프로필/작업 폴더 생성 전 거절을 확인한다.
7. 변조된 원장 본문을 거절하고 기록/호스트 guard를 보존한다.
8. 호스트 파일과 hard link된 원장을 거절하고 호스트 파일을 보존한다.
9. 자식이 원장 gate를 읽거나 쓸 수 없음을 확인한다.

기존 LPAC6개는 실제 TIFF·원본 ACL/바이트·작업 간 SID·직접 실행 거절·벽시계 종료·미승인 runtime을 재검증했다. 입력/런타임 WRITE_DAC 거절도 추가 확인했다. 네트워크 connect10013은 기존과 같이 registryRead 시험 토큰이며 생산 capability0과 구분한다. 시험 실패 시 JUnit 임시 폴더를 보존하도록 바꿨다. 실패 진단/보존 기록을 검토한 뒤 회수하며 무조건 재귀 삭제하지 않는다.

```powershell
./scripts/build-libvips-sandbox.ps1
$env:JAVA_HOME = 'C:\Program Files\Java\openjdk\jdk-21.0.2'
$env:IMAGEGATE_SANDBOX_HOME = (Resolve-Path '.tools/native-sandbox/lifecycle-v2-final').Path
$env:IMAGEGATE_LIBVIPS_WORKER = (Resolve-Path '.tools/native-worker/winjob-v2-verified/bin/imagegate-converter.exe').Path
$env:IMAGEGATE_JOB_PROBE = (Resolve-Path '.tools/native-resource-probe/bin/imagegate-job-probe.exe').Path
./gradlew.bat test -PlibvipsSandboxIntegration -PlibvipsIntegration -Pimg2tileIntegration `
  --tests '*LibvipsLifecycleIntegrationTests' --tests '*LibvipsSandboxIntegrationTests' `
  --tests '*LibvipsWorkerClientTests' --tests '*LibvipsWorkerIntegrationTests' `
  --tests '*LibvipsResourceIntegrationTests' --tests '*TilePyramidValidatorContractTests' `
  --tests '*Img2TileIntegrationTests' --no-daemon
```

새9 + LPAC6 + 기존 집중62 =77개/7 suites가1분4초에 실패·오류·건너뜀0으로 통과했다. build -x test는19초에 성공했으며 Java 라이선스16규칙/63구성요소/53포함 JAR도 대조했다. 최종 결과·파일 해시는 build/reports/libvips-lifecycle/verification.json을 따른다. 이전 패키지 변경 단계의892개 전체/실제FFmpeg·HTTP/Chrome은 이번 단계에서 재실행하지 않았다. 새 EXE는 기존 v2 native 후보16파일 승인 목록에 자동 포함되지 않는다. 전체 native 고지/내장 코드/소스 제공·취약점 검토와 최종 운영 인수, 설치 ZIP은 후속이다.

다음은 Java 부모 실행 어댑터의 시작 정책/모듈 fingerprint/오류 매핑을 구현하고 권한·원본 버전/SHA·캐시·최종 게시·설치 옵션에 연결하는 단계다. factory 활성화는 필요한 저장소 강제 경계와 운영 검증을 마친 후 결정한다.

공식 근거: Microsoft [Job 생성 속성](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute), [프로필 삭제와 재시도](https://learn.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-deleteappcontainerprofile), [사용자 known folder](https://learn.microsoft.com/en-us/windows/win32/api/shlobj_core/nf-shlobj_core-shgetknownfolderpath), [대체 스트림](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-findfirststreamw), [사용자 가용 공간](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getdiskfreespaceexw), [NTFS 사용자 quota](https://learn.microsoft.com/en-us/windows/win32/fileio/disk-quota-limits).
