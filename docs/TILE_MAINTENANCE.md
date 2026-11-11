# 타일 캐시·작업 기록·중단 폴더 유지보수

후속 단계에서 타일 HTTP API를 연결했습니다. 현재 호출 계약은 TILE_HTTP_API.md를 우선하며 UI는 후속입니다.

2026-10-28. imagegate.tiles가 활성화된 경우에만 기동 복구/재계수와 유지보수 빈을 만듭니다. 설치 예제/기본 YAML은 갱신했고 실제 config/imagegate.yml은 수정하지 않았습니다. HTTP 타일 API와 UI는 아직 연결하지 않았습니다.

## 설치 옵션

imagegate.tiles.maintenance의 기본값은 enabled=true, retention=7d, interval=5m, max-bytes=8589934592(8GiB), history-retention=1d, work-retention=1h, max-scan-entries=2048입니다. 전체 tiles.enabled=false가 기본이므로 기본 설치에서는 스케줄러/저장소가 생성되지 않습니다.

retention/history-retention은1시간~365일, work-retention은1시간~30일, interval은1분~1일의 정수 밀리초, max-bytes는1MiB~1TiB, max-scan-entries는16~4096만 허용합니다. maintenance.enabled=false도 기동 재계수/중단 상태 복구는 수행하고 자동 삭제는 하지 않습니다. 실제 운영 볼륨/파일 수/동시성 측정 후 조정해야 하며 보관/용량 값은 OS 디스크 하드 할당량이 아닙니다.

## 기동과 보고

TileConfiguration은 기존 TileCache 배타 writer를 획득한 후 TileWorkStore와 TileMaintenanceService를 만들고, 큐 생성 전에 queued/running 상태를 failed/INTERRUPTED로 원자 기록합니다. 자동 변환/재접수는 하지 않습니다. 기존 명시적 retry 계약을 유지합니다.

기동 시 완료 generation의 manifest/정확한 파일 집합·정규 파일·canonical 경계·길이를 확인하고 실제 PNG+JSON 바이트를 재계수합니다. 타일을 디코딩하지 않습니다. 상태 개수와 소유 기록이 있는 중단 work의 바이트도 확인합니다. 이 단계에서는 캐시/폴더/terminal 기록을 삭제하지 않습니다. 각 보고서 inspected/removed/removedBytes/retainedBytes/skipped는 고정 숫자만 담고 경로/actor/원본 이름은 담지 않습니다. retainedBytes는 확인 시점의 항목 합계입니다. skipped/삭제 실패/동시 변경이 있으면 미확인 파일 누락이나 보수적 잔여 추정이 포함될 수 있어 전체 잔여 용량의 정확한 값·상한·하한으로 해석하면 안 됩니다.

스캔은 각 루트의 max-scan-entries와 generation별 최대4096타일/2MiB 파일·2MiB JSON, native work의 제한 깊이·maxTiles+64 항목 경계 안에서 수행합니다. 루트 탐색 상한을 넘기면 그 스캔은 삭제 전 실패합니다. 부팅 비용은 실제 파일 수에 비례하며 대규모 업무 인수/P95 검증은 아직 남아 있습니다. 새 큐 접수에도 캐시 구조 여유를, native 작업 시작에도 owner+폴더의 구조 여유를 확인해 앱이 스스로 탐색 한도 이상을 계속 만들지 않게 합니다. 외부 프로세스가 넣은 미확인 항목은 운영 점검 대상으로 남습니다.

기동 후 기존 큐의 활성 예약은0에서 시작하며, 이미 남은 파일은 FileStore usable space에 반영됩니다. 기존 작업당 두 출력 세트+여유의 보수적 논리 예약을 계속 사용합니다. 저장 공간을 물리적으로 확보하거나 중단 파일의 최대 사용량을 OS에서 강제 제한하는 기능이 아닙니다.

## 완료 캐시의 TTL·용량·개수 정리

별도 imagegate-tile-maintenance 스케줄러가 watchdog과 분리되어 동작합니다. 만료된 캐시부터 지우고, 실제 확인한 완료 세트 바이트가 목표를 넘거나 루트 항목 여유가8개보다 적으면 오래 사용하지 않은 세트를 먼저 선택합니다. 보관 기간은 최소 보존 약속이 아니라 idle 정리 기준입니다. 용량/항목 압박 시 더 최근 결과도 재생성 대상으로 정리할 수 있습니다.

읽기 접수/변환 큐의 key lease는 해당 generation을 보호합니다. 삭제 직전에 같은 stripe 안에서 pin/최근 사용/generation을 다시 확인하고 PNG/JSON 해시를 검증합니다. 익명 static 경로를 만들지 않고 기존 원자 분리/비재귀 삭제를 재사용합니다. 변환/전송/스캔 수명은 writer 잠금을 유지하므로 close 후 지연된 작업이 남아 있으면 새 writer가 그 저장소를 획득하지 못합니다.

사용 시각은 메모리(최대4096키)와 generation 디렉터리 mtime으로 관리합니다. 메타데이터 상태 폴링은 수명을 늘리지 않고 타일 콘텐츠 읽기/게시 시각을 사용합니다. 디렉터리 mtime 쓰기는 키별 최대1분에 한 번이며 재시작 후 근사 idle 기준으로 재사용합니다. 쓰기 실패는 고정 경고를 한 번 남기고 현재 프로세스 메모리 기준을 유지합니다. 서버 clock/파일시스템/갱신 실패·1분 단위 오차 때문에 엄밀한 영구 LRU가 아닙니다. 원본/메모/스탬프는 정리하지 않습니다.

## 작업 기록

오래된 terminal 상태를 지우되 queued/running은 정기 정리에서 지우지 않습니다. ready 기록은 maxTrackedJobs-(workers+queueCapacity)보다 많아지면 오래된 것부터 정리해 새 기록 여유를 확보합니다. 정상 generation이 있으면 기록이 없어도 상태 조회가 캐시 manifest에서 ready를 복원하며 변환을 실행하지 않습니다. generation이 퇴거된 ready 상태는 not_requested가 되어 다음 명시적 준비 요청에서만 생성합니다.

failed 기록은 history-retention이 지난 경우에만 지우며, 같은 키의 generation이 있으면 실패 표식을 보존합니다. 중단/실패 결과를 기록 삭제만으로 ready로 바꾸지 않기 위한 정책입니다. 실패 기록의 보관 기간이 지난 뒤에는 새 명시적 준비 요청을 새 접수로 취급할 수 있습니다. 기록 정리는 SHA/mtime 스냅샷을 삭제 직전에 대조해 새 queued/상태 변경을 지우지 않습니다. 알 수 없는 속성·중복 키·손상 properties나 중단 .state 파일의 추정 삭제는 하지 않으며 기존 기동 실패/운영 점검 계약을 유지합니다.

캐시 stripe를 기다리면서 큐 monitor를 보유하지 않도록 상태/접수를 분리했습니다. publication의 stripe→commit gate와 충돌하는 반대 잠금 순서를 피합니다. 중복 접수/진행 상태는 캐시 파일 접근 없이 답합니다.

## 중단 폴더

새 native 작업은 tile-work-v1/job-UUID.owner에 버전/UUID/키만 먼저 CREATE_NEW+force로 기록하고 unused job-UUID 경로를 변환기에 전달합니다. 활성 owner는 항상 건너뜁니다. 정상 종료는 알려진 native 파일 구조를 검증해 폴더와 owner를 정리합니다. 취소/실패로 일부 작업이 남으면 owner와 함께 보존합니다.

재시작 후 active set이 비어 있고 work-retention이 지난 owner에 대해, 마커 내용/이름과 canonical 경계·알려진 resource/output/레벨/PNG의 제한된 구조를 확인한 뒤에만 자동 삭제합니다. 폴더 생성 전 중단된 owner-only 작업도 같은 방식으로 정리합니다. owner 변경/손상·정션·미확인 파일·소유 기록 없는 이전 폴더는 자동 채택하거나 삭제하지 않습니다.

캐시의 .work/.delete 중단 디렉터리는 완성된 서버 manifest와 모든 파일/해시·정확한 전체 집합이 확인되는 경우에만 같은 grace 이후 정리합니다. manifest가 없거나 일부 파일이 이미 사라진 중단 세트는 보존합니다. 새 owner/manifest는 전용 private ACL 아래의 서버 소유 기록입니다. 같은 권한의 악의적인 프로세스·모든 하드링크/TOCTOU 경합까지 차단하는 서명/OS 격리 수단은 아닙니다.

## 다음 단계

권한/현재 버전·generation을 확인하는 타일 준비/상태·PNG GET/HEAD API와 HTTP 보안 테스트를 연결합니다. 이후 현재 UI에 고해상도 레이어를 연결하고 실제 동시 미리보기/타일/재생 부하·업무 샘플/SSO·OS 격리·전체 운영/라이선스 인수를 진행합니다. 설치 ZIP은 최종 배포 단계까지 생성하지 않습니다.

## 집중 및 실행 JAR 검증

집중139개/16개 suite가1분24초에 통과했습니다. 만료/압박 정리·바이트 재계수·변환 pin/전송 callback 보호·완성 orphan만 회수·손상/미확인/부분 orphan 보존, 소유 work의 기동 후 회수/active 보호/legacy·정션 보존, terminal 기록 정리와 캐시 기반 ready 복원·실패 표식 보존·스냅샷 변경 거절, 큐/캐시 충돌 stripe의 접수/게시 회귀와 유지보수 설정 검증을 포함합니다. 이후 전체 빌드에는 중복/미확인 상태 속성 거절도 추가했습니다.

실행 JAR의 중단 상태 복구→재계수→소유 폴더/기록 정리 smoke, 기존 타일 정규화/게시/재열기 및 해시 IO smoke가 통과했습니다. 테스트/fixture 포함 항목은0개이며 smoke 자료는 build/verification의 합성 파일만 사용했습니다. 전체 빌드 결과는 아래에 기록합니다.

### 최종 전체 검증

2026-10-28 build -Pimg2tileIntegration -PffmpegIntegration이5분25초에 성공했습니다. Java681개/101개 suite의 실패·오류·건너뜀은0입니다. 신규22개와 실제 Spring 타일 큐/내부 img2tile·FFmpeg 회귀, 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다. 실행 JAR smoke도 모두 통과했고 보고서는 build/reports/tile-contract/maintenance-verification.json입니다.

설치 예제/기본 YAML만 갱신했고 실제 config/imagegate.yml·모듈/업무 자료를 변경하지 않았습니다. 신규 외부 런타임 의존성·설치 ZIP 생성은 없으며 distributionReady=false를 유지합니다.
