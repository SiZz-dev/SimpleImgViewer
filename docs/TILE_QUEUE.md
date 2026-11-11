# 타일 준비 큐·설치 설정

UI 레이어와 승인 TIFF의 서버 좌표 연계는 TILE_VIEWER_UI.md를 따릅니다. 검증되지 않은 원본 좌표의 편집은 비활성입니다. 실행 용량 부족 CONVERSION_BUSY·접수 거절 TILE_QUEUE_FULL과 수동 재시도 계약은 NATIVE_CAPACITY_RETRY.md를 따릅니다.

후속 단계에서 타일 HTTP API를 연결했습니다. 현재 호출 계약은 TILE_HTTP_API.md를 우선하며 UI는 후속입니다.

최신 유지보수·기동 복구 구현은 TILE_MAINTENANCE.md를 우선합니다. 아래는 큐/게시 단계의 구현 기록입니다.

2026-10-28. 승인된 img2tile의 TIFF 준비 경로를 제한 큐와 Spring 설치 설정에 연결했습니다. 기본 비활성이며 공개 HTTP API/뷰어의 고해상도 레이어는 아직 없습니다. 이전의 단독 구성 요소 상태보다 이 문서의 최신 범위를 우선합니다. 타일 정규화/게시 저장소는 TILE_CACHE_PUBLICATION.md, native 규약은 TILE_PYRAMID_CONTRACT.md를 참고하세요.

## 설치와 지원 범위

config/imagegate.example.yml 및 application.yml의 imagegate.tiles를 사용합니다. enabled=false일 때 타일 큐/캐시·상태/작업 저장소를 만들지 않습니다. true는 conversion.enabled=true 및 module=img2tile을 요구하며, 승인된 모듈 fingerprint가 아니거나 필요한 빈/모듈이 없으면 기동 실패합니다. 자동 모듈 우회나 ImageMagick 실행은 없습니다. 실제 config/imagegate.yml은 수정하지 않았습니다.

타일 경로가 현재 승인하는 원본은 classic TIFF(version42), RGB/3samples/8bit/방향1, 비압축·contiguous planar입니다. 투명/팔레트/그레이/CMYK/YCbCr·압축 TIFF·BigTIFF·방향2~8·HEIC/JP2 등으로 범위를 확대하지 않습니다. 기존 미리보기 형식 지원은 그대로입니다. TIFF 원본 메타데이터/JDK raw type과 bounded orientation 검사로 프로필을 정하고 원본 전체 래스터는 JDK로 만들지 않습니다.

설정: workers1(1~2), queue-capacity8(1~32), max-tracked-jobs256(1~1024, workers+queue 이상), timeout120s(1s~5m 정수 ms), max-source-pixels40MP, max-tiles4096, max-tile-bytes2MiB, max-page-bytes256MiB, min-free-bytes1GiB, cache-version=tiles-v1. 파일·픽셀 예산은 더 작게 설정할 수 있으나 기존 hard 검증 상한을 넘길 수 없습니다. cache-version은1~64자리 영숫자/underscore/hyphen입니다.

Img2TileConverter가 TileNativeConverter SPI를 함께 구현하며 미리보기와 동일한 BoundedProcessExecutor와 NativeModuleSnapshot을 사용합니다. 별도 무제한 실행기를 만들지 않습니다. native 옵션은 이미 검증한 -page/-odir/-otype png/-otype_tile png/-otilesize256/-oname %c_%r.%e/-f이며 resource와 output을 분리합니다. 미리보기는 계속 -oscaleonly를 사용합니다. 공유 슬롯 상한은 conversion.max-concurrent-jobs이고 두 큐의 동시 수요가 크면 native BUSY가 타일 작업 실패로 기록될 수 있습니다. 우선순위/선점 스케줄러는 구현하지 않았으며 기본 tile worker1을 유지하고 동시 부하·메모리 측정 후 슬롯을 조정해야 합니다.

## 키·권한·무결성

TileKeys는 길이 접두어의 SHA-256으로 actor tenant/user, 원본 tenant/owner/mediaId/version/SHA, page, 승인 모듈 fingerprint, 검증 기하/비압축 프로필·tile256/overlap0·RGBA 정책, 설치 cache-version/자원 예산, JDK 버전을 결합합니다. 원본 이름·경로는 포함하지 않습니다. 키/설정은 인증·권한이 아닙니다.

상태 조회/접수/취소에서 NativeTileRenderer.verifyAccess가 MediaStore.find의 현재 레코드 전체·tenant와 VIEW 권한/IMAGE_VIEW 설치 기능을 확인합니다. 워커는 실행 전에도 다시 검사하고 원본 canonical 경계·정확한 크기(최대100MiB)·전체 SHA와 지원 프로필을 확인합니다. native 호출 전후 module 내용 기준선이 검증됩니다.

게시 직전에는 출력/정규화 세트 검증 이후 원본 SHA·모듈 내용·최신 레코드/권한을 다시 확인하고 최종 queue commit gate로 들어갑니다. 이 gate는 종료/취소/기한/인터럽트 확인과 원자 이동을 같은 queue 잠금에서 결정합니다. 캐시 재사용도 최종 검증/gate를 통과합니다. 게시가 먼저 이긴 경우 완료 결과를 유지하며, 취소가 먼저 이긴 경우 늦은 native/JDK 반환이 게시되지 않습니다. 별도 업무 ACL 시스템의 트랜잭션과 OS 파일 변경까지 원자적으로 잠근다는 의미는 아닙니다. API/전송에서도 최신 권한을 다시 적용하는 작업은 다음 단계에 필요합니다.

## 접수·재시작·자원

TileQueue는 worker+queue 상한과 동일 키 중복 접수를 제어합니다. 상태 조회는 실행을 시작하지 않습니다. 실패는 retry=true인 명시적 접수만 재시도하며, 재시작 후 queued/running 상태에 활성 job이 없으면 failed/INTERRUPTED로 판정합니다. 자동 복구 실행이나 폴링 재실행은 하지 않습니다.

상태는 converted/tile-jobs-v1의 고정 키 properties에 원자 교체합니다. key/page/state/fixed error만 기록하며 파일명·경로·actor 입력·native 진단을 넣지 않습니다. 파일4KiB/기록 개수·canonical 경계/정규 파일을 검사합니다. 기록 수가 설치 상한을 초과하면 기동을 거절하고, 상한에 도달하면 신규 키 접수를 거절합니다. 미확인 항목·손상·중단 .state 임시 파일이 있으면 새 접수/기동을 실패시키고 보존합니다. 상태 기록 자동 TTL/정리·기동 잔재 복구는 아직 없으며 삭제를 임의 실행하지 않습니다.

접수 시 현재 usable space가 minFreeBytes+(활성/대기 작업수+1)×(2×maxPageBytes+117440512)를 충족해야 합니다. native/정규화 두 세트와 최대 원본100MiB/리소스·metadata 여유를 보수적으로 예약합니다. 완료/실패/queued 취소 때 논리 예약이 해제됩니다. 이미 생성한 파일 용량과 예약을 함께 잡으므로 보수적인 제한이며 파일시스템 공간의 OS 하드 예약이나 다른 프로세스·다른 볼륨/서버를 제어하는 할당량은 아닙니다. native가 출력 예산을 넘기는 순간 OS에서 차단하는 총량/CPU/메모리 격리도 별도입니다.

작업 기한은 큐 대기 제외 running부터입니다. watchdog 인터럽트와 최종 monotonic 기한 재검사로 늦은 게시를 차단합니다. 기존 native 호출 timeout과 협력적 checkpoint가 함께 적용됩니다. JDK/파일시스템 호출의 강제 종료를 보장하지 않습니다. close는 신규 접수를 막고 대기 작업을 failed/INTERRUPTED로 정리하며 실행 중 작업에는 인터럽트를 보냅니다. 무시하는 워커가 있어도 queue/cache lease가 writer 잠금을 계속 보유하고 늦은 게시를 거절합니다.

작업 폴더는 converted/tile-work-v1/job-UUID입니다. 종료된 producer의 알려진 resource/output/DZI/level/PNG 이름만 제한 깊이·개수/canonical 검사 후 삭제합니다. 미확인 항목/링크/정션/변경이 있으면 보존하고 고정 경고를 남깁니다. 최종 캐시는 tiles-v1에 유지됩니다. TileJobStore는 같은 converted 루트의 TileCache writer/lease 수명 아래에서만 사용해야 합니다.

## 검증과 다음 작업

집중57개/7개 suite가51초에 통과했습니다. 큐 포화·중복/queued 취소·실패 명시적 retry·시간 초과 후 비협력 워커의 늦은 commit 차단·close writer lease·중단 상태와 권한, 키 분리, 상태 상한/손상/경로, 설치 정책 검증과 실제 Spring→공유 img2tile→검증/정규화→캐시 게시/픽셀 일치 및 권한 철회를 포함합니다. 원본/버전/ACL/모듈/기능 정책이 native 생성 중 바뀌면 게시가 거절됨을 별도 합성 테스트로 확인했습니다. 전체 빌드 결과는 아래에 기록합니다.

다음은 타일 캐시/작업 상태·중단 work의 수명과 보수적인 자동 정리/기동 재계수 검증입니다. 이후 권한 API/GET·HEAD·현재 UI의 고해상도 레이어를 연결합니다. 실제 SSO/업무 샘플·동시 미리보기/타일/재생 부하/P95·OS 격리·최종 라이선스/배포 인수는 계속 남아 있고 설치 ZIP은 생성하지 않습니다.

### 최종 전체 검증

2026-10-28 build -Pimg2tileIntegration -PffmpegIntegration이5분에 성공했습니다. Java659개/98개 suite의 실패·오류·건너뜀은0이며 신규22개를 포함합니다. 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선과 실행 JAR 타일 캐시/해시 IO smoke·테스트/fixture 항목0개를 확인했습니다. 상세 보고서는 build/reports/tile-contract/queue-verification.json입니다.

설치 예제와 기본 YAML만 갱신했으며 실제 config/imagegate.yml·모듈/업무 자료를 변경하지 않았습니다. 신규 외부 런타임 의존성·설치 ZIP 생성은 없고 distributionReady=false입니다.
