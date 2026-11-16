# 이미지·재생 서비스의 관리형 런타임 연결

2026-11-13. 버전 참조를 실제 Node 이미지/편집/재생 서비스에 연결했다. 선택 기능이며 기본값은 꺼짐이다. 실행 경계는 기존 Windows Job의 메모리/CPU/프로세스/부모 수명 제한이다. **이 옵션은 LPAC 파일·네트워크 격리를 활성화하지 않는다.** 테스트용 LPAC launcher를 서버 실행기로 등록하지 않는다. 설치 ZIP은 생성하지 않았다.

## 설정과 시작

`config/simpleimgviewer.example.yml`의 `runtimeVersions`를 사용한다. 실제 설정은 Git에서 제외한 simpleimgviewer.yml에 둔다.

```yaml
runtimeVersions:
  enabled: false
  root: .runtime/node/runtimes
  maxBytes: 1073741824
  maxVersions: 10
```

활성화 전에 승인된 snapshot을 버전 관리자에 게시하고 node 종류의 활성 버전을 지정해야 한다. playback.enabled가 true면 ffmpeg 활성 버전도 필요하다. 비어 있거나 변경된 버전은 거절하며 기존 processing/playback의 직접 실행 파일 경로로 자동 우회하지 않는다. root는 storage.root 및 dist와 분리하며 한 서버가 소유한다. 자동 다운로드·자동 게시·원격 관리 API는 추가하지 않았다. 후보 준비/승인은 [버전 관리 컴포넌트](RUNTIME_VERSION_LIFECYCLE.md)의 신뢰된 설치 절차를 사용한다. 최종 배포/라이선스 인수 완료를 뜻하지 않는다.

서버는 ManagedRuntimes를 먼저 초기화하고 ImageEngine/PlaybackService에 전달한다. 시작 중 승인/설정 검증이 실패하면 카탈로그·작업 저장소 잠금을 정리한 뒤 실패한다. 활성 버전 전환은 소유 프로세스 내부의 신뢰된 관리 서비스만 수행할 수 있으며, 아직 외부 관리 엔드포인트를 제공하지 않는다.

## 참조와 프로세스 수명

ImageEngine.withRuntime는 원본 조회/캐시 선택부터 렌더링, 게시 직전 검사와 작업 정리까지 한 node 참조를 유지한다. 미리보기·스탬프 생성·편집 export 모두 같은 경로를 사용한다. 재생 서비스는 원본 FFprobe, FFmpeg, 결과 FFprobe와 최종 게시를 한 ffmpeg 참조에 묶는다. 전환 이후에도 이미 시작한 작업은 원래 버전의 경로와 digest를 사용한다.

종료 시에는 새 작업을 거절하고, 버전 acquire가 진행 중인 작업도 추적한다. 실제 자식 close와 작업/참조 정리를 기다린 뒤 버전 관리자와 저장소를 닫는다. FFmpeg stdout와 stderr를 각각 제한해 수집하며 JSON은 stdout만 파싱한다. 오류 출력과 내부 모듈 digest/원본 SHA는 사용자 재생 응답에 노출하지 않는다.

각 버전 폴더에는 payload 밖의 고정 1바이트 `runtime.use`가 있다. Windows Job 실행기의 `--runtime-use`는 경로·reparse·단일 링크·표식을 검사하고, 실행 파일이 해당 버전의 payload 바로 아래에 있는지도 확인한다. 읽기 전용 공유 핸들을 네이티브 실행기와 실제 자식이 함께 유지한다. 회수는 이 파일의 쓰기 핸들을 얻은 동안만 진행하므로 JS 참조가 사라지거나 부모/실행기가 종료되는 순간에도 실제 자식의 핸들이 남아 있으면 회수를 거절한다. 기존 `--work`의 lease.sqlite 핸들과 retired 검사도 유지한다.

## 게시와 캐시 바인딩

WorkDirectories.publish의 마지막 rename 직전에 검증 callback을 실행한다. 캐시 용량 검사/대기 이후에도 현재 store.require 권한/종류·원본 버전/SHA·고정 런타임 inventory를 재검사한다. 이 검사는 현재 테넌트/사용자 및 설치 기능 정책이며, 아직 정해지지 않은 외부 로그인/파일별 권한 연계를 대신하지 않는다. 실패하면 공개 캐시를 만들지 않고 작업을 정리한다.

이미지/재생 캐시 ID에 해당 작업의 runtime digest를 포함하고 facts에 원본 버전·종류·페이지/previewEdge 등을 바인딩한다. 재생 URL과 이미지 URL은 최신 버전으로 변환을 다시 시작하지 않고 해당 캐시 바인딩과 현재 원본/접근 정책을 확인한다. 따라서 전환 후 이전 런타임을 회수해도 이미 게시된 URL은 계속 읽을 수 있다. 오디오/영상 Range 응답도 같은 정책을 사용한다.

캐시 바인딩은 v2로 변경했다. v1의 바인딩 없는 임시 캐시는 원본을 지우지 않고 새 페이지/재생 요청 시 새 ID로 생성한다. 배포 직후 v1 URL을 계속 쓰는 클라이언트는 페이지 정보를 다시 요청해야 한다. 전환 중 v2 URL의 유지와 이 배포 경계는 구분한다.

## 무결성 레이블 수정

기존 시험 snapshot의 실행 파일은 Low 레이블이었다. Windows는 실행 파일 레이블에 따라 자식의 무결성을 낮출 수 있어, Job 경로에서 시작한 워커가 일반 작업 폴더에 쓸 수 없었다. 새 공유 snapshot은 **Medium 레이블과 기존 AR 읽기/실행 DACL**로 게시한다. 업무 작업 폴더나 원본 ACL은 낮추지 않는다. LPAC 프로필·작업 복사본의 기존 Low 정책은 그대로다. 관리형 Job 자식이 Low이면 suspended 상태에서 거절하며 임의 권한 승격이나 우회 실행을 하지 않는다.

기존 Low snapshot의 바이트 해시는 같아도 실행 레이블은 달라질 수 있으므로 새 게시 도구로 새 버전을 준비해야 한다. 기존 게시물을 제자리에서 바꾸지 않는다. 현재 호스트에서 Medium snapshot의 LPAC Node/FFmpeg 변환과 공유/개별 작업 ACL 대조군도 재검증했다. Windows 동작 근거는 [Mandatory Integrity Control](https://learn.microsoft.com/en-us/windows/win32/secauthz/mandatory-integrity-control)과 [TOKEN_MANDATORY_POLICY](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-token_mandatory_policy)를 따른다.

## 검증

```powershell
.\scripts\build-process-job.ps1 -TestTools
$env:SIV_NATIVE_TESTS='1'
& '.tools/runtimes/node-24.22.1/node.exe' --test 'backend/test/**/*.test.mjs'
# 최신 shared-node/shared-ffmpeg 준비 도구와 승인된 stage 필요
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-managed-runtime-services.mjs
```

실제 HTTP/모듈 시나리오는 `build/reports/managed-runtime-services/verification.json`에 기록한다. 전환 중 이미지/재생 참조 보호, JS 참조 없이 남은 실제 자식의 회수 차단, 이전 URL/Range 유지, 실제 스탬프·export, 마지막 게시 지점의 기능/원본/런타임 변경 거절, 캐시 URL 접근 재검사, 종료 후 카탈로그 소유권 해제를 포함한다. 단위/네이티브 시험에는 시작 실패 정리, acquire 중 종료, 실제 부모/launcher SIGKILL, Low 실행 파일 시작 거절, 잘못된 사용 표식의 참조 누수 방지가 있다.

최종 결과는 전체 Node/실제 모듈 83개 통과·실패/건너뜀 0, 관리형 실제 HTTP/모듈 9개 시나리오 통과다. Chrome 13개 시나리오와 React production 빌드·npm 90개 라이선스 기준선도 통과했다. Medium snapshot의 LPAC Node 재사용 9개/FFmpeg 재사용 13개, 스트림 16개 및 별도 Node 5개·FFmpeg 1개 ACL 대조군도 통과했다. 회귀 도중 공간 부족으로 거절된 시험은 실행 완료로 집계하지 않았고, 활성 lease/링크가 없는 오래된 합성 복사본 6개만 정리한 뒤 재검증했다. 업무 저장소·설치 모듈·검증 보고서는 보존했다.

다음 단계는 이 서비스 계약을 LPAC 전송 어댑터로 연결하는 것이다. 운영용 고정 요청/입출력 바인딩, 복수 스탬프와 메모/폰트, 부모 수명·작업 잠금·프로필 회수 및 최소 OS 인수를 완료한 뒤 LPAC 선택을 노출해야 한다. 현재 설치의 runtimeVersions를 LPAC 보안 기능으로 설명하지 않는다.
