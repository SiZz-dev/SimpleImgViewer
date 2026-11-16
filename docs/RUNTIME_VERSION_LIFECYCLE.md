# 런타임 버전 게시·교체·회수

2026-11-13. Node/sharp·FFmpeg 공유 snapshot의 관리 컴포넌트를 구현하고 선택적인 Windows Job 서비스 경로에 연결했다. `runtimeVersions`는 기본 꺼짐이다. LPAC 운영 경로와 HTTP 관리 API는 아직 제공하지 않는다. 설치 ZIP을 생성하지 않는다.

## 구성

- `conversion/service/runtime-versions.mjs`: 게시 승인, 활성 버전 선택, 참조 수, 복구/회수 정책.
- `conversion/repository/runtime-catalog.mjs`: SQLite 소유권 잠금·버전/활성 포인터 저장과 트랜잭션.
- `conversion/util/runtime-inventory.mjs`: 경로·구조·크기·단일 링크·스트리밍 SHA-256 검증과 native digest 계산.
- `conversion/service/runtime-snapshots.mjs`: 고정 SHA-256으로 승인한 내부 native 실행기의 `--prepare`만 호출. 요청에서 임의 실행 파일을 선택하지 않는다.

호출자는 신뢰된 설치/게시 주체이며 원본 승인 inventory, 비밀정보 없는 런타임 파일과 정규 절대 경로를 제공해야 한다. 원본 설치/stage를 변경하거나 회수하지 않는다. 관리 root의 `versions/<UUID>/payload`에만 새 snapshot을 생성한다. Node/FFmpeg 실행기의 기존 파일/디렉터리 pin과 권한 경계를 유지한다. 이 정책은 원본 이미지 권한이나 최종 캐시 게시 승인에 대한 대체 수단이 아니다.

`RuntimeVersions(root, {maxBytes, maxVersions})`의 기본 한도는 정상/미완료/격리 상태의 승인 바이트 합계 1GiB, 회수되지 않은 버전 10개다. 새 게시를 시작하기 전에 전체 승인 크기를 보수적으로 예약한다. 실패한 게시도 격리된 버전으로 계수한다. 이 수치는 OS 디스크 quota, 다른 프로그램 쓰기, 업무 원본/결과 캐시의 용량 한도가 아니다. 설치 YAML의 runtimeVersions는 검증된 버전 선택 기능이며 LPAC 활성화 옵션이 아니다.

## 상태와 활성화

게시 상태는 `publishing → ready → retiring → deleting → retired`로 이동한다. 준비 실패 또는 게시 중단은 `quarantined`로 보존한다. 원본과 결과의 전체 inventory 및 native digest가 일치해야 ready가 된다. 목록에는 UUID·종류·상태·digest·승인 바이트·활성 여부·사용 참조 수를 표시한다.

`publish`와 `activate`를 분리한다. 준비한 새 버전은 자동 선택되지 않으며 종류(node/ffmpeg)별 활성 포인터는 별도의 SQLite 트랜잭션으로 전환한다. 이전 ready 버전으로의 명시적 롤백도 전체 파일을 재검증한다. 활성 내용이 변조돼도 다른 버전으로 자동 우회하지 않는다. retired/quarantined 버전은 활성화하지 않는다.

`acquire(kind)`는 현재 활성 버전을 검증하고 불변 `{id, kind, path, useFile, nativeDigest, verify, release}` 참조를 반환한다. 전환 전에 받은 참조는 계속 이전 버전을 가리킨다. 호출자는 **실제 native 프로세스가 닫힌 뒤** release해야 한다. release는 멱등이며 참조가 남은 관리자의 close와 해당 버전 retire는 거절한다. 후속 Job 어댑터는 useFile의 읽기 공유 핸들을 실제 자식까지 상속하고, 회수는 쓰기 핸들을 얻어야 진행한다.

카탈로그 소유자는 별도 SQLite 파일의 EXCLUSIVE 트랜잭션을 수명 동안 유지해 한 프로세스만 상태를 변경한다. PID 파일을 추측해 삭제하지 않는다. 게시/활성 포인터 커밋에는 SQLite FULL 동기화와 트랜잭션을 사용한다. 인식하지 못하는 SQLite 보조 파일·심볼릭 링크·정규 경로 불일치·다중 링크·과대 메타데이터는 SQLite를 열기 전에 거절한다. 외부 사용자나 관리자에 의한 카탈로그/상위 namespace 장악까지 방어하는 설계는 아니며 관리 root는 신뢰된 설치 소유 영역이어야 한다.

## 회수와 중단 복구

활성 버전과 참조가 남은 버전은 회수하지 않는다. 먼저 retiring 상태를 커밋하고 payload 디렉터리를 같은 UUID 영역의 retired로 이동한다. native 프로세스의 root/file pin이 남아 있거나 접근 권한이 부족해 rename할 수 없으면 busy로 보존한다. 다른 활성 버전의 작업은 계속 사용할 수 있다.

rename 이후 deleting 상태를 커밋하고 전체 잔여 트리를 검사한다. 임의 재귀 삭제를 사용하지 않는다. 승인 목록에 있는 동일 크기/SHA-256의 단일 링크 파일만 unlink하고, 승인 디렉터리를 자식부터 비운 뒤 rmdir한다. 다른 디렉터리/파일·변조·링크가 있으면 보존하고 오류를 기록한다. 원본 설치와 다른 UUID 영역에는 삭제를 수행하지 않는다.

다시 시작할 때 publishing은 자동 승인하지 않고 quarantined로 보존한다. retiring/deleting은 `recover()`가 이어서 처리한다. 일부 파일 삭제 후 중단됐더라도 이미 없는 승인 항목만 허용하며 남은 항목의 내용/유형을 다시 검증한다. 아직 사용 중이면 busy, 미확인 내용이 있으면 preserved 결과를 반환한다. 완전히 회수한 UUID의 retired 기록과 사용 표식만 남은 세대 디렉터리는 tombstone으로 남겨 재활성화를 차단한다. tombstone/격리 버전의 관리 정리와 카탈로그 보존 정책은 운영 인수 후속이다.

## 검증과 재현

```powershell
# 기능/역할 구조의 새 테스트도 포함하는 전체 Node 테스트
$env:SIV_NATIVE_TESTS='1'
& '.tools/runtimes/node-24.22.1/node.exe' --test 'backend/test/**/*.test.mjs'
# 기존에 검증된 shared-node/shared-ffmpeg 실행기·stage가 필요
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-runtime-versions.mjs
```

새 파일시스템/프로세스 시험 13개에서 게시/활성화 분리, 참조를 유지한 교체/롤백, 단일 관리자 소유권, 재시작 후 선택 보존, 변조 시 우회/삭제 금지, 용량 예약, 미완료 게시 격리, 중단된 목록 기반 삭제 재개, 미확인 파일/하드링크 보존, descriptor 경로/대소문자 충돌, 참조 해제/close, 게시 프로세스 실제 SIGKILL 복구, 미확인 SQLite sidecar 보존을 확인한다.

`scripts/test-runtime-versions.mjs`는 실제 Node/sharp·FFmpeg와 native snapshot 어댑터로 다음 4개 인수를 수행한다. 결과는 `build/reports/runtime-versions/verification.json`이다.

- 실제 워커에 무해한 합성 주석을 추가한 별도 후보 B를 만들어 A/B 콘텐츠 digest를 다르게 승인한다. 두 세대의 동시 이미지 변환 출력이 같고, 전환/롤백/기존 참조가 올바른 세대를 가리킨다. 설치된 Node/sharp 버전을 업그레이드한 시험은 아니다.
- 관리자의 참조/소유권을 종료하고 다시 열어도 기존 native Node gate의 pin 때문에 A 회수가 busy로 보존된다. B는 계속 변환할 수 있다. 이는 관리 소유권 재개방 시험이며 이 구간의 Node 부모 SIGKILL 인수로 확대하지 않는다. 게시 중 SIGKILL은 별도 프로세스 시험에서 수행한다.
- native gate 종료와 프로필 회수 후 A의 파일 112개를 실제로 제거하고 retired 재활성화를 거절한다. 이 gate는 실제 워커 import 전 대기 상태다.
- 실제 FFmpeg 세대 C에서 음성을 변환하고 D로 교체한 뒤 C의 파일 10개를 회수한다. node 종류의 B 선택은 유지한다.

이미지/재생 서비스의 참조 수명·작업 잠금/retired·원본 버전/접근·캐시 게시 재검사는 [선택적인 Windows Job 경로](MANAGED_RUNTIME_EXECUTION.md)에 연결했다. 다음 단계는 LPAC 전송 어댑터의 운영 계약이다. 메모/폰트·복수 스탬프·최소 지원 OS 및 공급망/최종 배포 인수도 계속 남아 있다.

최종 검증: 기능별 새 시험 13개를 포함한 Node/실제 모듈 전체 78개가 실패·건너뜀 없이 통과했다. 실제 관리 모듈 인수 4개, Chrome 13개 시나리오, React production 빌드와 npm 90개 라이선스 기준선도 통과했다. 아직 운영 서버에 LPAC/버전 관리자 경로를 활성화한 상태는 아니다.
