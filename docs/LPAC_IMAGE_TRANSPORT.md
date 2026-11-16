# 이미지 LPAC 전송 어댑터

현재 상태: [YAML 선택·서버 연결·실제 Chrome 및 혼합 부하 검증](LPAC_INSTALLATION_LOAD.md)을 추가했다. 아래의 서버 선택 미구현 설명은 선행 단계 기록이다. 기본 Windows Job은 유지하며 다른 Windows 환경의 운영 인수는 남아 있다.

2026-11-16 후속: 실패 작업의 [소유 기록·요청 무효화·시작/유휴 회수](LPAC_WORK_RECOVERY.md)를 연결했다. 아래의 미구현 회수 설명은 선행 기록이며, 현재 범위와 미확인 데이터 보존 조건은 회수 문서를 따른다.

2026-11-16 후속: [고정 Noto 폰트 프로필](LPAC_NOTE_FONT.md)이 있는 snapshot에서는 LPAC 한글 메모를 지원한다. 프로필이 없는 기존 snapshot의 메모 거절은 유지한다. 아래의 폰트 미검증 설명은 선행 단계 기록이며, 현재 조건·캐시 검사 수정·검증 결과는 폰트 문서를 따른다.

2026-11-16 후속: 작업 풀·예약·제한 실행·종료·목록 정리는 공통 LpacTransport로 분리했다. LpacImageTransport의 이미지 정책·제한과 서버 선택 상태는 유지하며, [미디어 어댑터](LPAC_MEDIA_TRANSPORT.md)를 추가했다. 두 native CLI의 제한 요청 파서는 lpac-request.h를 공유한다.

2026-11-16. `LpacImageTransport`를 ImageEngine의 내부 주입 지점에 연결하고 전용 네이티브 실행기로 실제 이미지 변환을 검증했다. **일반 서버 factory/YAML에서는 아직 선택할 수 없다.** 현재 설치의 기본 Windows Job 실행은 유지한다. 테스트용 launcher를 운영 실행기로 재사용하지 않고 별도 `native/process-job/lpac-image.cpp`를 추가했다. 새 의존성·폰트·설치 ZIP은 추가하지 않았다.

## 실행 계약

conversion/service의 전송 서비스는 런타임 참조 검증, 작업별 예약, 제한 실행, 결과 반출과 현재 작업 정리를 담당한다. conversion/util의 바인딩과 바이너리 요청 인코더는 경로 필드/형식만 담당한다. ImageEngine은 기존 withRuntime/동시 실행 상한/종료 대기를 유지하며 선택한 전송을 호출한다. MediaService/EditingService의 권한·원본·최종 게시 검사는 계속 해당 서비스가 수행한다.

요청은 `SIVLPAC1` magic, little-endian uint32 작업·자원 값, 길이가 붙은 UTF-8 문자열과 복수 스탬프 목록이다. 전체 1MiB, 스탬프 최대 200개, 유효 UTF-8·남은 길이·완전 소비를 검사한다. 네이티브 CLI는 이 요청 하나 또는 신뢰된 로컬 관리자의 `--recover`만 받는다. 프로그램은 node.exe, 진입점은 승인 runtime의 실제 image-worker.mjs로 고정한다. shell·임의 argv·test hold/probe·자동 prepare는 제공하지 않는다. core의 기존 shared-node compile guard를 공유하지만 별도 진입점의 선택 범위는 고정 이미지 작업뿐이다.

원본과 스탬프를 새 UUID 폴더에 복사하고 작업 고유 LPAC SID에 읽기만 부여한다. JSON은 부모가 필드별로 바인딩한 내용을 그대로 기록하며 문자열 전역 치환을 하지 않는다. 공유 runtime 전체 digest와 파일/디렉터리 pin, 노출된 비밀정보가 없는 코드의 AR 읽기/실행 DACL을 유지한다. 기존 Node LPAC과 동일하게 registryRead capability 하나만 사용한다. 인터넷/사설 네트워크 capability는 추가하지 않는다. `--preserve-symlinks`와 `--preserve-symlinks-main`은 Node 시작의 드라이브 루트 realpath 접근을 피하기 위한 기존 검증 옵션이며, 실제 snapshot과 입력 경로의 reparse는 별도로 거절한다.

원본은 100MiB, 스탬프는 각각 2MiB/합계 64MiB다. 출력 파일은 각각 32MiB, 출력 디렉터리 감시는 합계 64MiB다. 설치 processing의 메모리/CPU 시간/CPU 비율과 최대 120초 wall 제한을 적용한다. stdout/stderr를 각각 8KiB로 제한하며 JSON은 stdout만 파싱한다. native wall 제한은 내부 exit 124를 504로 매핑한다. 포함 실패는 503이며 기본 Job 경로로 우회하지 않는다.

## 수명과 반출

호스트 `lease.sqlite`는 읽기 전용/share-none, `runtime.use`는 읽기 전용/share-read 핸들로 네이티브 부모와 실제 자식이 유지한다. LPAC 자식에게 호스트 SQLite 쓰기 핸들을 부여하지 않는다. 경로·reparse·링크·표식·retired를 검사하고 runtime은 runtime.use 옆 payload로 고정한다. Node 부모의 전용 stdin 파이프는 자식에 넘기지 않고, 복사 전/실행 전/실행 루프에서 연결 상태를 확인한다. 부모 종료나 close 시 Job 종료와 실제 자식 종료를 기다린다. close의 graceful 연결 종료가 끝나지 않으면 5초 뒤 launcher를 종료한다.

자식 종료 뒤 출력 inventory·정규 파일·단일 링크·크기를 검사하고 프로필 제거를 시도한다. Node 어댑터는 다시 호스트 작업 잠금을 얻고 active 상태/고정 runtime을 검증한 다음, 허용 출력만 기존 호스트 작업 폴더에 배타 생성한다. 도중 실패하면 자신이 만든 호스트 출력만 제거한다. 이후 기존 서비스의 최종 게시 검사가 수행된다. 부모에서 이미지를 다시 디코딩하지 않으며, 형식 전체의 의미 검증이나 모든 악성 디코더 취약점이 해소됐다는 뜻은 아니다.

작업 풀은 SQLite 단일 소유권, 기본 40개 항목 상한, 768MiB 시작 여유 공간을 적용한다. 아직 native root가 생기지 않은 예약도 계산해 동시 접수 시 용량을 중복 사용하지 않는다. 정상 완료는 사전 검사한 고정 입력/출력/캔어리만 unlink/rmdir 한다. 실패·비정상 종료·미확인 파일은 보존하고 접수 용량에 포함한다. 자동 재귀 삭제나 실패 root 회수는 구현하지 않았다. 프로필 cleanup이 Windows 종료 시점 때문에 지연되면 고정 state namespace의 명시적 `--recover`로 복구한다. 작업 root/요청 파일의 안전한 재시작 회수와 운영 관리 연결은 후속 단계다.

## 검증

```powershell
.\scripts\build-lpac-image.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-lpac-image-transport.mjs
$env:SIV_NATIVE_TESTS='1'
& '.tools/runtimes/node-24.22.1/node.exe' --test 'backend/test/**/*.test.mjs'
```

승인된 기존 Node/sharp stage와 shared-node 준비 도구가 필요하다. 빌드는 소스·고정 compiler 지문별 새 경로를 사용하며 기존 실행 파일을 덮어쓰지 않는다. 실제 실행 보고서는 `build/reports/lpac-image-transport/verification.json`이다. 합성 시험의 `SIV_LPAC_FIXTURE_ID`는 .tools/bi 아래 기존 8자리 UUID 접두사 fixture만 재사용하며 업무 자료를 사용하지 않는다.

- 실제 LPAC 11개 시나리오: 미리보기와 최종 게시, 두 스탬프/중복 배치·밝기·회전·crop의 기존 출력 픽셀 일치, 미검증 메모 거절, 풀 중복 소유 거절, 게시 직전 원본 변경 거절, 잘린 요청/후행 데이터/닫힌 부모 파이프 거절, 부모 SIGKILL과 자식 종료, close/버전 회수 보호, 입력·스탬프·공유 코드 쓰기/호스트 파일 읽기 거절, native wall 제한.
- 시험용 승인 세대는 실제 워커 실행 뒤 파일 접근을 검사하고 고정 export 출력에 준비 표식을 남긴 뒤 대기한다. 이 세대는 합성 시험 전용이며 전용 launcher에 hold 모드나 프로브 실행 옵션을 추가하지 않는다. stdout/stderr는 자식 종료 뒤 전달되므로 실행 중 준비 확인에는 이 표식을 쓴다.
- 전체 Node 94개 통과·실패/건너뜀 0. 공통 LPAC 스트림 회귀 16개, 실제 Chrome 13개 시나리오, React 빌드와 90개 패키지 라이선스 기준선 통과. distributionReady=false 유지.

메모는 승인 폰트 인수가 끝날 때까지 이 어댑터에서 `LPAC_NOTES_NOT_VALIDATED`로 거절한다. 일반 서버의 메모 기능은 기존 경로를 유지한다. FFmpeg 운영 전송, 폰트·메모, 실패 작업 회수, 동시 실행 성능과 지원 Windows 환경 인수 후에 서버 선택 옵션을 공개해야 한다.
