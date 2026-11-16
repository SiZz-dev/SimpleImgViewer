# libvips 부모 실행기와 Windows LPAC 경계

최신 후속은 [프로필 회수와 저장 용량 감시](LIBVIPS_PROFILE_LIFECYCLE.md)다. 부모 v2에서 보호 원장·생성 시 Job 배치·프로필 전체 감시/회수를 구현했다. 아래는 초기 경계의 기록이며 Java/factory 활성화나 하드 디스크 quota 완료를 뜻하지 않는다.

2026-11-11. 기존 winjob-v2와 분리한 실험 단계다. 새 C++ 부모 실행기와 contained-v3 워커를 추가했다. Spring factory, 설치 YAML, 기존 img2tile, Java v2 client 계약은 변경하지 않았다. 배포 승인과 설치 ZIP 생성 단계가 아니다.

## 실행 구조

신뢰된 부모 imagegate-sandbox-launcher.exe가 작업별 고유 AppContainer 프로필/SID를 만든다. 입력과 런타임을 새 작업 폴더에 복사하고 복사본에만 보호 DACL과 Low Integrity 레이블을 설정한다. 원본·기존 모듈 ACL, 방화벽, 서비스, 루프백 예외는 수정하지 않는다.

| 경계 | 구현 |
| --- | --- |
| 부모/자식 | 부모가 Job 설정 handle 독점. 자식에 Job/원본 handle 상속하지 않음 |
| 시작 | CREATE_SUSPENDED + STARTUPINFOEX + LPAC opt-out. Job 할당 후 ResumeThread. 실패 시 제한 없는 재실행 없음 |
| 자원 | 프로세스/Job 커밋 각각512MiB, 사용자 CPU 각각30초, 활성 프로세스1, KILL_ON_JOB_CLOSE. 부모 벽시계20초 |
| capability | 생산 contained-v3는0개. registryRead/COM/인터넷/사설망 capability 없음 |
| 입력 | 최대100MiB source.tif 복사본 읽기만 허용. 정규 파일·단일 hard link 확인 |
| 런타임 | 고정 worker basename + 기존15개 DLL만 복사, 읽기/실행만 허용. DLL 누락·추가 파일 거절 |
| 출력 | out에 Modify만 명시적 부여. WRITE_DAC/WRITE_OWNER 없음. 종료 후 고정 파일명·정규 경로·reparse/hard link·파일별32MiB 상한 확인 |
| 경로 | 로컬 고정 NTFS 드라이브, 정규 절대 경로,230자 미만. UNC/device/ADS/상위 경로/reparse/기존 작업 폴더 거절 |
| 환경/handle | 최소 환경, 임시 경로 out. 상속 목록은 NUL stdin과 진단 pipe뿐. 진단 보관8192바이트 |
| 결과 | 고정 launcherBuild/childExit/truncation 또는 실패 단계/Win32 번호. decoder 진단을 공개 응답으로 전달하지 않음 |

생산 CLI는 worker만 고정 선택하며 임의 EXE/셸 명령·격리 해제·한도 상향 옵션이 없다. 필요한 Windows API/NTFS 권한 설정을 제공하지 않는 호스트는 실행을 거절한다. DLL 초기화 전부터 LPAC/Job이 적용된다. 직접 실행한 contained-v3는 미디어 처리 전에 exit23이다.

protocolVersion1/result.json14필드는 유지하지만 BUILD는 imagegate-libvips-worker-contained-v3다. 기존 Java v2 parser가 수용하지 않는다. 새 Java 어댑터/시작 계약/이미지 검증·SHA·원본 버전·사용자 권한·게시 승인은 후속이다.

## Windows 호환성과 시험 구분

- 미등록 SID만으로 CreateProcess를 호출하면 이 호스트에서 오류2였다. CreateAppContainerProfile을 작업마다 사용하고 종료 후 DeleteAppContainerProfile을 호출한다. 기존 프로필을 재사용하지 않는다.
- 콘솔 연결 시 본문 진입 전0xC0000142로 종료했다. DETACHED_PROCESS와 명시적 표준 handle 목록으로 해결했다. 일반 사용자 Medium Integrity 토큰에서 시험한다. 도구 자체의 Low Integrity 토큰은 기존 Medium 폴더 ACL 변경부터 거절될 수 있다. 관리자 권한을 요구하는 설계는 아니다.
- TokenIsLessPrivilegedAppContainer 조회 결과에 의존하지 않는다. 부모가 LPAC opt-out을 필수 설정하고 자식은 AppContainer/Job/capability와 부모가 만든 읽기 전용 canary2개의 차등 ACL을 확인한다. ALL_APPLICATION_PACKAGES 파일은 거절, ALL_RESTRICTED_APPLICATION_PACKAGES 파일은 허용되어야 한다. 악성 설치본에 대한 attestation은 아니다.
- capability0은 TIFF 변환을 수행하지만 시험 프로그램의 Winsock 초기화도 제한했다. 이를 접속 단계 WSAEACCES로 집계하지 않는다. 별도 imagegate-network-probe에 registryRead 하나만 부여해 Winsock을 시작하고, 인터넷/사설망 capability 없이 루프백 connect10013(WSAEACCES)을 검사한다. 생산 launcher CLI로 이 probe를 선택할 수 없다.
- 네트워크 결과는 시험 토큰의 직접 Winsock TCP 루프백 범위다. 모든 IPC·서비스 경유·UDP/IPv6/다른 API 우회 검증을 뜻하지 않는다.

## 재현과 검증

먼저 [최소 워커 빌드](../native/imagegate-converter/README.md)의 도구·소스·검증된 v2 런타임을 준비한다. 새 스크립트는 다운로드/전역 PATH 변경 없이 기존 CMake build를 사용하며 새 .tools/native-sandbox 하위 폴더만 허용한다. 기존 출력은 덮어쓰지 않는다.

```powershell
./scripts/build-libvips-sandbox.ps1
$env:JAVA_HOME = 'C:\Program Files\Java\openjdk\jdk-21.0.2'
$env:IMAGEGATE_SANDBOX_HOME = (Resolve-Path '.tools/native-sandbox/lpac-v1-final').Path
$env:IMAGEGATE_LIBVIPS_WORKER = (Resolve-Path '.tools/native-worker/winjob-v2-verified/bin/imagegate-converter.exe').Path
$env:IMAGEGATE_JOB_PROBE = (Resolve-Path '.tools/native-resource-probe/bin/imagegate-job-probe.exe').Path
./gradlew.bat test -PlibvipsSandboxIntegration -PlibvipsIntegration -Pimg2tileIntegration `
  --tests '*LibvipsSandboxIntegrationTests' --tests '*LibvipsWorkerClientTests' `
  --tests '*LibvipsWorkerIntegrationTests' --tests '*LibvipsResourceIntegrationTests' `
  --tests '*TilePyramidValidatorContractTests' --tests '*Img2TileIntegrationTests' --no-daemon
```

일반 test는 libvips-sandbox 태그를 제외한다. 생산 JAR/저장소에 시험 EXE·합성 자료를 넣지 않는다. 기존 build-libvips-worker.ps1은 sandbox 옵션을 명시적으로OFF로 설정한다.

LPAC6개 시험은 다음을 확인한다.

1. 입력 읽기 허용, 입력 수정·외부 파일·다른 작업 출력·미사용 runtime 파일 수정·부모 VM_WRITE/DUP_HANDLE 거절. 호스트 파일 읽기/쓰기와 외부 TCP connect 대조군, registryRead 시험 토큰 connect10013·추가 수신 없음. 원본 ACL/바이트 보존.
2. 실제 합성 TIFF→PNG80×48 전체3840픽셀, 원본 바이트 보존, 잘못된 페이지 거절.
3. 기존 작업 폴더와 hard link 입력 거절, 기존 내용 보존.
4. 격리 밖 contained-v3 직접 호출 거절.
5. 자식 진입 PID 기록 후 부모의 시험용500ms deadline으로 Job 종료, 해당 PID 생존하지 않음. 생산20초는 변경하지 않음.
6. 미승인 EXE가 있는 런타임 거절, decoder 결과 생성되지 않음.

최종 LPAC6 + 기존 집중62 =68개/6 suites가1분1초에 실패/오류/건너뜀0으로 통과했다. build -x test는20초에 성공했고 Java 라이선스16규칙/63구성요소/53포함 JAR도 대조했다. 실행 JAR의 native/시험 바이너리·TIFF 포함0, 관련 문서의 로컬 링크72개를 확인했다. 정확한 집계와 새17개 생산 후보 파일/5개 시험 파일의 SHA는 build/reports/libvips-sandbox/verification.json을 따른다. 전체848개·HTTP/Chrome·FFmpeg 재실행과 native 배포 승인을 뜻하지 않는다.

## 운영 연결 전에 남은 항목

- 초기 v1은 프로필 회수가 미완료였다. 후속 v2에서 보호 원장·강제 종료 회수·자식 종료 확인을 구현했다. 전원 장애·서비스 계정 등 운영 잔여 항목은 [생명주기 검증](LIBVIPS_PROFILE_LIFECYCLE.md)을 따른다. 외부 경로를 따라가는 일반 재귀 삭제로 대체하지 않는다.
- AppContainer는 OS가 허용한 시스템 자원과 자체 프로필 저장소/레지스트리도 가진다. 모든 읽기/쓰기를 입력/out에 절대 한정하는 가상 파일시스템은 아니다. 원본 저장소의 광범위한 package ACL을 운영 점검해야 한다. 커널·로컬 관리자·같은 사용자의 신뢰된 설치본 변조는 이 프로토타입의 방어 범위가 아니다.
- 작업 상위 폴더/설치 폴더는 다른 로컬 계정이 수정하지 못하는 신뢰된 저장소여야 한다. 현재 경로 검사는 악성 로컬 사용자의 동시 rename/reparse 교체 전체를 차단하는 handle 기반 저장소 구현이 아니다. 해당 ACL 배치와 경로 경합 보강도 운영 연결 전에 검증한다.
- Job 메모리와 결과 파일 크기 검사는 디스크 quota가 아니다. 후속 v2에 작업/프로필 주기적 용량 감시·가용 공간 검사·원장별4개 접수 제한을 추가했지만 하드 quota와 누적 작업 보존·복사 성능 검증은 남아 있다.
- 부모/worker/DLL 전체 고정 해시와 시작 정책을 Java client에 결합하고 권한·원본 버전/SHA·캐시·게시 직전 검사를 연결해야 한다. 전체 native 고지/내장 코드·소스 제공·취약점 검토도 미완료다. 기존 v2 후보16파일 정책이 새 EXE를 승인한 것으로 보지 않는다.
- v3 Job 예산 공격/대조군 확대, 취소/부모 강제 종료·서비스 계정/대상 Windows 버전 시험은 후속이다. 이번 자원3개 회귀는 기존 v2 시험이다.
- libvips 선택은 계속 fail-closed다. HEIC/JPEG2000/타일·편집 승인 프로필은 추가하지 않았다. 최종 기능과 전체 운영 인수 전 ZIP을 만들지 않는다.

근거: Microsoft [AppContainer 실행](https://learn.microsoft.com/en-us/windows/win32/secauthz/implementing-an-appcontainer), [프로필 생성](https://learn.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-createappcontainerprofile), [프로필 삭제](https://learn.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-deleteappcontainerprofile), [토큰 정보](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ne-winnt-token_information_class), [네트워크 시험 capability](https://github.com/microsoft/SandboxSecurityTools/blob/main/LaunchAppContainer/README.md). API를 직접 사용한 wrapper이며 예제 소스를 복사하지 않았다.
