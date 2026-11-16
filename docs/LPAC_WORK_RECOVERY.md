# 실패·중단 LPAC 작업 회수

현재 상태: [YAML 선택·서버 연결·실제 Chrome 및 혼합 부하 검증](LPAC_INSTALLATION_LOAD.md)을 추가했다. 아래의 YAML 미연결 설명은 선행 단계 기록이다. 설치 `isolation` 옵션은 기존 회수 정책을 연결하며 미확인 데이터 보존 조건은 유지한다.

2026-11-16. 이미지/FFmpeg 전송의 정상 정리와 실패·부모 종료·삭제 중단 후 회수를 소유 기록 기반으로 연결했다. LpacTransport가 풀의 SQLite 단일 소유권과 실행/회수 직렬화를 유지하고, conversion/service.LpacWorkRecovery가 기록·요청 무효화·허용 목록 정리 정책을 담당한다. 일반 서버 factory/YAML에는 LPAC을 아직 연결하지 않았다. HTTP 관리/삭제 API, 설치 ZIP, 새 의존성은 추가하지 않았다.

## 범위와 기본 정책

내부 전송 생성자의 recovery 옵션은 다음과 같다. 현재는 신뢰된 설치/조립 코드용 옵션이며 실제 YAML에 미구현 선택 항목을 추가하지 않았다.

```javascript
recovery: {
  enabled: true,
  minAgeMs: 60000,
  scanLimit: 200,
  intervalMs: 60000
}
```

풀 소유권을 얻은 시작 시 한 번 검사하고, 실행/예약/다른 회수가 없는 유휴 상태에서 주기적으로 검사한다. intervalMs: 0은 주기 실행만 중지하고 enabled: false는 회수 전체를 중지한다. 생성 후 최소 보존 시간이 지난 항목만 대상이다. 스캔 상한은 삭제 전에 전체 풀 목록을 수집하는 단계에서 검사하므로 한도 초과로 일부만 지우지 않는다. 종료 시 타이머를 중지하고 실행과 회수의 완료를 기다린 후 소유권을 반환한다.

maxEntries는 기본 40개다. 새 작업은 향후 생성될 UUID root와 request/receipt까지 접수 용량에 예약한다. .imagegate-lpac-state와 owner.sqlite는 제어 파일로 분리하며, 기록 없는 기존 폴더와 미확인 항목도 용량에 포함한다. .receipt.next는 짧은 원자적 갱신 중 또는 중단 복구에 사용되는 메타데이터다. 상한은 접수 정책이며 파일시스템 하드 쿼터는 아니다.

## 소유 기록과 지연 실행 차단

각 작업은 풀 바로 아래 다음 이름을 사용한다.

- UUID: 네이티브 bin/input/out 작업 root.
- UUID.request: 고정 바이너리 실행 요청.
- UUID.receipt.json: 부모가 먼저 기록하는 소유/진행 증거.
- UUID.receipt.next: 같은 풀에서 원자적 기록 교체에 쓰는 임시 파일.

기록에는 schemaVersion/id/생성 시각, allocated→prepared→retiring 상태, 고정 레이아웃·스탬프 수와 요청의 길이/SHA-256만 저장한다. 원본 경로·런타임 경로·메모 내용·업무 식별자는 기록하지 않는다. Native가 사용할 요청은 prepared 기록을 파일 sync/원자적 교체한 뒤 쓰고 sync하며, 완료 전에는 실행하지 않는다. 이미 존재하는 root/request/기록은 새 UUID의 소유 항목으로 채택하지 않는다.

회수와 정상 완료는 요청 파일의 r+ 핸들을 먼저 확보한다. 네이티브 Request는 실행 전체 동안 read/share-read 핸들을 유지하므로 실제 실행기가 아직 요청을 사용 중이면 쓰기 장벽 획득에 실패한다. 반대로 회수가 쓰기 핸들을 보유하면 새 실행기는 같은 요청을 열 수 없다. 최소 보존 시간만으로 실행 종료를 추정하거나 PID로 프로세스를 종료하지 않는다.

prepared 요청은 기록한 전체 해시/길이와 일치해야 한다. retiring은 이전 요청 또는 부모가 남긴 무효화 표식만 허용한다. 삭제 전 retiring 상태를 sync/원자적 교체하고 요청의 첫 magic을 RETIRED 표식으로 덮어쓴 뒤 truncate/sync한다. 늦게 실행되는 원래 요청 경로는 무효한 요청 또는 존재하지 않는 파일로 거절된다. 정상 성공 경로도 같은 장벽과 무효화를 적용한다.

이 정책은 프로그램이 생성한 원래 요청 경로의 지연 실행을 막는다. 같은 OS 계정의 신뢰된 관리자가 요청 바이트를 다른 곳에 복사해 실행하는 행위를 인증하는 수단은 아니다. 프로세스 SIGKILL에 대한 재개를 검증했으며 하드웨어 전원 차단의 모든 파일시스템 지속성을 보장한다는 의미는 아니다.

## 네이티브 증명과 삭제

소유 기록과 요청 장벽 외에도 다음 조건을 확인한다.

1. 고정 state 경로의 --recover로 실제 named Job이 남아 있는지 확인한다. busy가 있으면 보존한다. 이전 launcher가 먼저 종료돼 Request 핸들이 사라졌더라도 살아 있는 자식을 이름 기반 Job 검사로 보호한다.
2. 고정 --inspect-residue root layout stamps 명령으로 canonical 로컬 NTFS 경로, reparse/ADS/단일 링크, 폴더/파일명과 크기를 검사한다. 이 명령은 실행 파일/인자를 전달받지 않고 읽기 검사만 한다.
3. bin의 두 canary, 이미지/미디어별 고정 입력, job.json 및 허용 출력만 대상으로 한다. 이미지 입력 100MiB, 스탬프별 2MiB, 이미지 출력별 32MiB, 미디어 재검사 입력/출력 256MiB 등의 상한을 유지한다. 중단된 복사/삭제의 누락 파일·0바이트 파일은 허용하며 임의 하위 폴더는 허용하지 않는다.
4. Node가 경로·단일 링크와 전체 파일 목록을 재확인한 뒤 확인된 파일을 개별 unlink하고 빈 폴더만 rmdir 한다. 요청과 갱신 임시 파일을 정리한 후 소유 기록을 마지막에 지운다.

재귀 삭제와 와일드카드 삭제는 사용하지 않는다. 삭제 중 중단되면 retiring 기록과 무효 요청이 남고 다음 회수는 빠진 파일을 허용해 재개한다. 작업 root/request가 이미 지워지고 마지막 기록만 남은 경우도 마무리한다. 요청이 부분 기록됐거나 변경돼 해시가 맞지 않으면 보존한다. 최초 소유 기록 자체가 손상된 경우에도 소유권을 추정하지 않는다.

원본·스탬프 저장소·게시 캐시·runtime payload는 회수 대상으로 해석하지 않는다. 회수는 요청 안의 경로를 따라가지 않으므로 원본이나 사용했던 runtime을 이미 교체/회수했어도 확인된 작업 잔여물 정리가 가능하다. 미확인 파일·ADS·하드링크·junction·잘못된 기록·기존 미등록 폴더는 자동 삭제하지 않고 용량에 남긴다. 수동 확인 없이 기존 폴더를 새 기록으로 승격하지 않는다.

## 검증과 재현

```powershell
.\scripts\build-lpac-image.ps1
.\scripts\build-lpac-media.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-lpac-work-recovery.mjs
$env:SIV_NATIVE_TESTS='1'
& '.tools/runtimes/node-24.22.1/node.exe' --test 'backend/test/**/*.test.mjs'
```

실제 인수 도구는 이전 승인 stage/버전 관리 인수 보고서의 합성 runtime만 사용한다. 실제 업무 자료를 사용하지 않는다. build/reports/lpac-work-recovery/verification.json에 8개 시나리오를 기록한다.

- 시작 전 allocation 회수.
- 미확인 파일·ADS·하드링크 각각의 네이티브 검사 거절과 원본 보존. 시험 항목을 명시적으로 제거한 후 회수하고 늦은 원래 요청 실행을 거절.
- 파일 하나를 삭제한 직후 Node에 SIGKILL을 보내고 retiring 기록에서 재개.
- 실제 이미지 워커가 실행 중일 때 풀의 중복 소유와 Request 쓰기 장벽을 거절.
- Node 부모 SIGKILL 후 실제 자식/프로필·작업·요청 회수, runtime 핸들 해제와 늦은 실행 거절.
- 실제 FFprobe 실패의 자동 시작 회수 후 새 FFmpeg 요청 정상 완료.

전체 Node 109개(실패/건너뜀 0), 이미지 LPAC 11개, 폰트/메모 9개, 미디어 LPAC 13개, Chrome 13개와 React 빌드·90개 npm 의존성 라이선스 기준선이 통과했다. 주기 실행·기록 위조·최소 보존 시간·opt-out·스캔 상한·junction 보존은 Node 회귀에 포함한다. 최종 배포 승인을 뜻하지 않으며 distributionReady=false를 유지한다.

다음은 동시 부하와 뷰잉 속도, 지원 Windows 환경 인수 및 설치 선택 설정 연결이다. 일반 서버의 기본 Windows Job 실행은 유지한다.
