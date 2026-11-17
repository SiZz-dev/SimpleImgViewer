# 검증 thread 설치 선택과 서버 수명

2026-11-16. 후보 비교에서 선택한 검증 thread1개를 ManagedRuntimes와 서버 factory에 연결했다. 기본값은 `main`이며, 실제 설치 YAML·IntelliJ 실행 서버는 변경하지 않았다. 이 선택은 인증이나 LPAC 선택을 대신하지 않는다.

## 설치 설정

`config/simpleimgviewer.example.yml`을 기준으로 이미 게시·활성화된 승인 런타임 카탈로그가 있을 때만 다음처럼 선택한다.

```yaml
runtimeVersions:
  enabled: true
  root: .runtime/node/runtimes
  verification:
    mode: worker
    maxQueue: 8
    timeoutMs: 30000
```

위 root는 예시다. 실제 승인 카탈로그 경로를 사용하며 Node, 재생 기능을 켜면 FFmpeg 활성 버전도 준비해야 한다. 기존 게시 절차는 MANAGED_RUNTIME_EXECUTION.md와 RUNTIME_VERSION_LIFECYCLE.md를 따른다. 서버 시작 시 런타임을 다운로드하거나 자동 게시하지 않는다. 편집 메모의 LPAC 실행은 기존 승인 폰트 조건도 유지한다.

설정 변경은 Node 서버를 재시작한 뒤 적용된다. 실행 중 thread 수를 바꾸거나 진행 중 검증을 건너뛰는 hot reload는 제공하지 않는다.

| 항목 | 기본값·허용값 | 의미 |
| --- | --- | --- |
| `mode` | `main`, `worker` | main은 기존 메인 검증, worker는 고정 thread1개 |
| `maxQueue` | 8, 정수0~32 | worker의 진행 중1건을 제외한 대기 proof 수 |
| `timeoutMs` | 30000, 정수1000~120000 | worker proof 한 건의 입장~완료 deadline, 대기 시간 포함 |

thread 개수·worker 파일·execArgv·env를 YAML로 받지 않는다. 알 수 없는 키, 잘못된 형식·모드·범위, 관리형 버전 비활성 상태의 worker 선택은 시작 전에400 설정 오류로 거절한다. YAML과 코드 override의 중첩 verification 옵션은 필드별로 병합한다. `main`에서는 queue/deadline 설정값의 형식은 검사하되 별도 pool은 만들지 않는다.

동일 버전의 진행 중 검증은 기존처럼 공유하므로 proof 수와 HTTP 요청 수는 같지 않다. 이 queue는 업로드·decoder 동시 처리 한도를 대신하지 않는다. timeout도 이미지/영상 변환 전체가 아닌 각 proof의 한도다. worker 실패 시 메인 검사나 직접 실행으로 우회하지 않는다. 이후의 독립 요청은 새 고정 thread를 만들어 전체 검증할 수 있다.

## 적용 범위와 자원

RuntimeVersions의 acquire와 lease.verify 경계에 연결한다. 파일 목록·경로·링크·크기·실제 전체 바이트·SHA-256, 동일 진행 proof 공유, 실행 전·게시 전 재검증과 버전 사용 참조는 유지한다. 카탈로그 publish/activate/retire/recovery의 기존 전체 검사는 기존 경로를 사용한다. 폰트 승인도 기존 별도 검사다. 완료 proof 캐시는 추가하지 않는다.

worker는 thread1개·읽기 버퍼 합계 최대2MiB, V8 old32MiB/young8MiB/stack2MiB와 고정 출력 한도를 사용한다. 이 수치는 서버 전체 RSS 하드 상한이 아니다. 격리 선택인 `isolation.mode`와 독립적이며 실제 LPAC 및 Windows Job 모두에서 동작을 확인했다. 자원 제한·권한 범위는 RUNTIME_VERIFIER_WORKER.md를 따른다.

## 소유권과 종료

ManagedRuntimes가 pool을 생성하고 소유한다. 활성 버전 검증도 worker로 수행하며, 초기화가 실패하면 pool과 카탈로그 소유권을 해제한다. LPAC launcher 검사·미디어 초기화 등 후속 기동 실패에서도 application이 이미 만들어진 manager와 저장소를 닫는다. 포트 바인딩 실패도 같은 정리를 수행한다.

서버 close는 공유 Promise를 반환하므로 중복 종료 요청이 같은 정리를 기다린다. HTTP 입구/연결을 닫고 이미지·미디어 작업 종료와 참조 반환을 기다린 뒤 ManagedRuntimes를 닫는다. manager는 새 acquire를 거절하고 pool의 queued proof를 실패 처리하며 active proof와 thread exit, 진행 중 acquire·폰트 승인까지 기다린다. 종료 중 완료된 acquire는 새 참조를 밖으로 반환하지 않고 해제한다. 그 후 카탈로그와 저장소를 닫는다. 외부에서 이미 보유한 참조가 남으면 카탈로그의 기존 RUNTIME_IN_USE 보호는 유지한다.

| 상황 | HTTP 응답/보호 |
| --- | --- |
| proof 대기열 초과 | 429 `RUNTIME_VERIFY_QUEUE_FULL` |
| active proof deadline | thread exit 확인 후504 `RUNTIME_VERIFY_TIMEOUT` |
| 예기치 않은 thread 종료 | exit 확인 후503 `RUNTIME_VERIFY_WORKER_EXIT` |
| manager 종료 중 새 acquire | 503 `RUNTIME_MANAGER_CLOSING` |
| 최종 게시 proof 실패 | 변환 출력 게시 거절, 원본·기존 캐시 보존 |

## 실제 검증

`scripts/test-runtime-verifier-installation.mjs`는 승인된 합성 LPAC fixture YAML을 읽어 별도 설치 YAML·저장소·pool·임시 포트 서버를 만든다. 실제 YAML mode를 사용하며 비교 시험처럼 verifyTree를 대체하지 않는다. 오류 주입은 시험 코드에서만 수행한다.

```powershell
$env:SIV_SPEED_YAML = '.tools/load/<fixture ID>/<run ID>/lpac.yml'
$env:IMAGEGATE_NODE_MODULES = '<Playwright가 설치된 node_modules 절대 경로>'
$env:IMAGEGATE_BROWSER = '<Chrome 실행 파일 절대 경로>'
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-runtime-verifier-installation.mjs
```

같은 fixture 부모 디렉터리에 앞선 설치 부하 시험의 synthetic.mp4/mpeg/mp3/wav가 필요하다. 실행기 경로·해시는 현재 build/lpac-image 및 build/lpac-media manifest를 사용한다. 테스트는 실제 설치 설정이나 업무 자료를 수정하지 않는다.

확인한 범위:

- worker 선택 LPAC의 MP4/MPEG/MP3/WAV 변환·206 Range, Windows Job의 이미지·MP4 변환
- 실제 Chrome6개: 미리보기, 클릭 한글 메모·업로드 스탬프, 합성 자르기 PNG, 테마, 영상/음성 재생·seek·Range
- thread 종료503·deadline504·queue429에서 불필요한 decoder/게시 없음, 사용 참조 해제와 다음 독립 요청의 새 thread
- 변환 완료 후 최종 게시 검증에서 실제 thread를 종료해 신규 출력 미게시·원본/기존 캐시 보존·작업 정리
- 중복 정상 종료, 부분 기동 실패의 thread0/소유권 재확보, 일반 실행의 API401 유지
- manager 검증·폰트 승인 중 종료, 설정 검증·중첩 병합, 포트 충돌 후 같은 저장소·카탈로그 재확보

실제 설치 시험의13개 확인과 Chrome6개는 `build/reports/runtime-verifier-installation/verification.json`, 최종 게시 실패의 별도 집중 검증은 같은 디렉터리 `publication.json`에 기록했다. 재현 스크립트에는 최종 게시 실패도 포함하므로 전체 재실행 시 확인 항목은14개다. 실제 HTTP deadline 시험은 격리된 pool에서만20ms로 주입해 결정적으로 실패시켰으며 YAML에서1000ms 미만을 허용하는 것이 아니다.

전체 Node/네이티브 시험140개(실패/건너뜀0), 기본 main 서버의 Chrome13개, React production 빌드·npm90개 라이선스 기준선을 통과했다. 앞선 후보 비교 도구는 설치 pool이 남아 비교를 오염시키지 않도록 조립 시 main을 명시하고 시험 후보를 주입한다. 일반 응답성 도구는 실제 선택 모드를 결과에 기록한다. 과거의 파일/지연 표본을 이번 설치 연결의 새 성능 측정으로 표시하지 않는다.

다음은 worker 선택 상태에서 장시간 혼합 부하·종료 반복을 수행해 메모리·thread·참조·잔여 작업이 누적되지 않는지 검증하는 것이다. 운영 Windows/서비스 계정·업무 규모 인수와 최종 배포 검토는 남아 있으며 설치 ZIP은 생성하지 않았다.
