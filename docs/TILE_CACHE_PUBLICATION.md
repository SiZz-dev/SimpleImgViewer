# 타일 PNG 정규화와 비공개 캐시 게시

최신 유지보수·기동 복구 구현은 TILE_MAINTENANCE.md를 우선합니다. 아래는 큐/게시 단계의 구현 기록입니다.

2026-10-28 게시 구성 요소 구현 기록입니다. 후속 단계에서 Spring/작업 큐/설치 설정을 연결했으며 최신 상태는 TILE_QUEUE.md를 따릅니다. 미디어 API/브라우저 연결은 아직 없습니다. 원본 형식/권한·지원 범위는 TILE_PYRAMID_CONTRACT.md를 따르며 최초 TIFF RGB8 방향1 프로필만 대상으로 합니다.

## 처리 순서와 역할

- conversion/service.TilePyramidPublisher: 승인 모듈·원본 프로필 기반 validator 실행, 타일별 입력 SHA 재대조, 정규화 요청과 게시 직전 원본 산출물 전체 재검사 조정. 원본 SHA/현재 업무 권한·버전/모듈 snapshot 확인은 향후 호출 서비스에서 별도로 수행해야 합니다. source의 프로필/치수/페이지를 native DZI에서 신뢰해 만들면 안 됩니다.
- conversion/util.TilePngSanitizer: 입력2MiB/가로·세로1~256 범위에서 CRC/구조·래스터 검증 후 PNG reader의 ignoreMetadata로 읽고, 새 TYPE_INT_ARGB 래스터에 RGBA 픽셀을 복사합니다. ImageIO의 기본 PNG metadata로 저장하며 원본 metadata 객체를 전달하지 않습니다. 결과를 다시 검사하여 IHDR/IDAT/IEND만 허용합니다. 텍스트/EXIF/GPS/ICC/임의 ancillary chunk는 게시본에 복사하지 않습니다. 프로필/감마를 이용한 시각적 색상 일치와 업무 CMYK 등 추가 형식 인수는 완료 범위가 아닙니다.
- conversion/repository.TileCache: 별도 tiles-v1 루트의 writer 잠금, 정규화된 단일 타일·manifest 저장, 원자적 generation 게시/명시적 퇴거와 전송 수명. 파일 경로를 반환하지 않고 검증한 바이트의 동기 InputStream 콜백만 제공합니다.
- conversion/domain.TileCacheManifest: schemaVersion/policy/key/generation/sourceManifestSha256/page/layout/totalBytes/정렬된 tiles. 입력 DZI나 원본 파일명·경로·실행 명령은 저장하지 않습니다. 공개 API DTO가 아니며 접근 권한을 부여하지 않습니다.

검증된 원본 산출물의 manifest 전체 직렬화 SHA를 provenance로 기록합니다. 게시본 tile SHA는 재인코딩된 PNG 기준이고 입력 tile SHA와 구분됩니다. RGBA 채널 값은 보존하지만 source 전체 픽셀과 낮은 레벨의 리샘플링 품질이 올바른지는 native 인수 범위에 남아 있습니다.

## 원자적 게시와 읽기

캐시 생성자는 canonical 루트와 worker.lock을 확인하고 배타 writer 잠금을 획득합니다. 새 디렉터리는 기존 canonical 조상에서 한 단계씩 생성/확인합니다. 파일/루트의 심볼릭 링크·정션/경로 이탈은 거절합니다. OS 파일 ACL·같은 권한 프로세스에 대한 격리를 대체하지 않습니다.

64자리 소문자 hex 키만 허용합니다. 키가 조직/사용자·미디어/버전/SHA·페이지·모듈/기하·정규화 정책을 포함하도록 만드는 작업은 다음 서비스/큐 단계입니다. 현재 임의 키를 받는 저장소 호출은 내부 계약이며 인증/캐시 스코프 구현으로 해석하지 않습니다.

새 세트는 .work-UUID에 CREATE_NEW로 작성하고 파일별 force(true)를 실행합니다. 메타데이터 JSON과 정렬된 타일의 개수/좌표/치수/바이트·SHA를 검사하고, 입력 소스를 재검증한 뒤 stage의 인벤토리/내용/manifest를 다시 대조합니다. 완성 디렉터리를 최종 키 디렉터리로 ATOMIC_MOVE합니다. 원자 이동이 지원되지 않으면 실패하며 copy/부분 게시로 우회하지 않습니다. 기존 키는 덮어쓰지 않고 source manifest SHA가 같은 완성 generation만 구조/내용을 다시 검증해 재사용합니다.

게시 디렉터리는 manifest.json과 level_column_row.png의 평면 구조입니다. 원본 native DZI와 resource는 게시하지 않습니다. 한 tile2MiB, 전체 tile256MiB,4096타일/원본40MP·tile256/overlap0을 상한으로 둡니다. JSON은 별도2MiB이며 중복 필드/추가 필드/후행 JSON·깊이8/문자열256/숫자20 초과를 거절합니다. 타일 전체 바이트 상한은 manifest/임시본/파일시스템 부하·볼륨 전체 하드 한도가 아닙니다.

읽기는 요청 generation/좌표를 검사하고 선택한 한 파일의 canonical 경계·길이·SHA를 확인한 후 콜백에 전달합니다. 원본/native/전체 피라미드 디코딩은 하지 않습니다. 매니페스트 JSON은 매번 제한된 읽기/해시를 거치고, 동일 SHA의 파싱된 결과는 최대16개만 보관합니다. 좌표 인덱스는 최대32개 레벨 계산으로 찾으며 전체 타일 목록을 매 요청마다 검색하지 않습니다. 실제 HTTP/동시 클라이언트 지연 측정은 API 연결 이후 필요합니다.

## 수명·실패와 남은 작업

동일 키 게시/퇴거/초기 읽기 접수는 고정64개 stripe 잠금으로 직렬화합니다. 읽기 접수 후 per-key lease가 콜백 전체를 보호해 명시적 evict가 진행 중인 키를 건너뜁니다. 콜백 실패/연결 중단에도 finally로 반환합니다. close는 신규 작업을 차단하고 진행 중인 게시/전송이 모두 끝난 뒤 writer 잠금을 해제합니다. 별도 JVM/인스턴스는 그 전까지 writer를 획득하지 못합니다.

명시적 evict는 알려진 전체 인벤토리를 먼저 확인한 뒤 .delete-UUID로 원자 분리하여 새 읽기를 차단하고 소유 파일만 비재귀 삭제합니다. 작업 실패/인터럽트는 최종 키를 게시하지 않고, 직접 만든 알려진 파일만 정리합니다. 정션/미확인 파일·디렉터리가 있으면 삭제하지 않고 실패합니다. 전원 차단·디스크 장애 시 전체 트랜잭션 내구성이나 청소 성공을 보장하지 않습니다. 중단 .work/.delete의 기동 재계수·정리/TTL·LRU 자동 스케줄러/전체 캐시·임시 볼륨 예약은 아직 구현하지 않았습니다.

입력 생성 프로세스는 종료되어 있어야 하며, 작업·캐시 루트는 독점 private ACL로 보호해야 합니다. 경로/속성/해시 재검사는 악의적인 동일 권한 writer와의 모든 TOCTOU/하드링크 경합을 제거하는 OS 보장이 아닙니다. 협력적 checkpoint는 JDK PNG 디코딩/파일시스템 호출의 hard timeout이나 OS CPU/메모리 상한이 아닙니다.

다음 단계는 범위가 분리된 캐시 키·제한 큐/중복 접수·작업 수명과 설치 옵션 연결입니다. 기본 비활성을 유지하며, 예산/원본·권한/모듈 재검사와 복구·정리 정책을 검증한 뒤 API/현재 뷰어의 고해상도 레이어를 연결합니다. ImageMagick과 신규 외부 런타임 의존성을 추가하지 않았고 설치 ZIP은 최종 배포 단계까지 생성하지 않습니다.

## 집중 검증 및 실행 JAR

최종 집중26개(정규화2/캐시23/실제 native1)가33초에 통과했습니다. 텍스트 metadata 제거·RGB/알파 픽셀 보존, 게시 전 실패/취소와 소유 파일 정리, 파일 이름 충돌 시 미소유 파일 보존, 게시 전 staging 비노출·내용 변조 거절, 재시작/재사용/다른 source 충돌, SHA/세대/좌표/키·JSON 중복/추가/누락/null/후행 데이터 거절, 부분/미확인 항목 보존, 실제 Windows 정션, reader/퇴거/close/writer 수명을 확인했습니다. native 통합은17개 clean PNG와 최상위131841픽셀을 확인했습니다.

실행 JAR의 실제 타일 정규화→게시→읽기→close→재열기→퇴거 smoke와 기존 해시 IO smoke도 통과했고, 테스트/fixture JAR 포함 항목은0개입니다. smoke fixture는 build/verification에만 생성하며 운영 소스나 JAR에 포함하지 않습니다. 최종 전체 빌드 결과는 아래에 별도 기록합니다.

### 최종 전체 검증

2026-10-28 JDK21 build -Pimg2tileIntegration -PffmpegIntegration이4분55초에 성공했습니다. Java637개/93개 suite, 실패·오류·건너뜀0이며 신규25개(정규화2/캐시23)와 기존 실제 native 통합을 포함합니다. 기존 미리보기·편집/재생/권한, 실제 img2tile/FFmpeg 및 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다. 실행 JAR smoke도 통과했습니다. 보고서는 build/reports/tile-contract/publication-verification.json입니다.

설치 YAML/모듈/업무 자료 변경·설치 ZIP 생성은 없으며, 신규 외부 런타임 의존성을 추가하지 않았습니다. 타일 큐/설정/API/UI 연결·자동 정리와 최종 운영/배포 인수는 아직 남아 있어 distributionReady=false를 유지합니다.
