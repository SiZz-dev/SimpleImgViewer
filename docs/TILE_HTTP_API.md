# 보호된 타일 HTTP API

후속 UI 레이어는 TILE_VIEWER_UI.md를 따릅니다. 원본 좌표가 미확인된 native TIFF의 편집은 아직 비활성입니다.

2026-10-28. 타일 준비/상태와 정규화 PNG GET/HEAD를 연결했습니다. 기본 tiles.enabled=false이며 현재 뷰어 UI의 고해상도 레이어는 아직 연결하지 않았습니다. 원본/모듈 프로필은 TILE_QUEUE.md, 저장/수명은 TILE_CACHE_PUBLICATION.md와 TILE_MAINTENANCE.md를 따릅니다.

## 요청 계약

| 요청 | 필수/선택 파라미터 | 동작 |
| --- | --- | --- |
| POST /api/v1/media/{mediaId}/pages/{page}/pyramids | mediaId, page(1~10000); retry=false 선택 | 인증/CSRF·현재 파일 권한/버전·설치 정책 후 제한 큐 접수. queued/running202, ready/failed/disabled/unsupported200 |
| GET /api/v1/media/{mediaId}/pages/{page}/pyramid | mediaId, page | 상태/ready 매니페스트만 조회. 변환·디코딩을 시작하지 않음 |
| GET 또는 HEAD /api/v1/media/{mediaId}/pages/{page}/pyramids/{tileSetId}/tiles/{level}/{column}/{row} | 위 경로 값 전부. tileSetId는 응답의 정규 UUID, 좌표는0기반 정수 | 현재 actor/파일 버전 키·페이지/generation/범위 확인 후 불변 PNG. HEAD 본문 없음 |

준비 요청 본문은 없으며 사용자 파일 경로/외부 URL/파일명·출력 형식·모듈 인자를 받지 않습니다. 운영 인증은 기존 신뢰 인증 공급자가 만든 ViewerPrincipal/CurrentActor를 사용합니다. POST의 CSRF header/token은 기존 bootstrap 계약을 따릅니다. 테스트 전용 Bearer 식별 fixture는 운영 인증 기능이 아닙니다.

인증되지 않은 요청401, 파일이 없거나 다른 조직/사용자/권한 철회·현재 버전에 해당하지 않는 generation은404입니다. CSRF 누락/위조 및 기존 설치 기능 정책 거절은403입니다. tileSetId/URL은 인증 수단이 아니고 다른 actor/파일/페이지에 빌려 쓸 수 없습니다. 권한은 캐시 hit/HEAD에서도 확인합니다. 파일 레코드가 준비/조회 도중 바뀌면 공개 응답 직전 다시 확인해 숨깁니다. 전송은 bytes 검증 후 response header를 쓰기 직전 다시 확인합니다. ACL 시스템과 전체 네트워크 전송을 한 트랜잭션으로 잠그는 계약은 아닙니다.

## 응답

최상위는 state/page/mediaVersion/errorCode/pyramid입니다. disabled는 PROCESSING_DISABLED, 정적 MIME 미지원은 unsupported/TILE_PROFILE_UNAVAILABLE이며 준비 작업을 만들지 않습니다. MIME가 TIFF여도 실제 그레이/알파/압축/방향 등 미지원 프로필은 워커에서 failed/TILE_PROFILE_UNAVAILABLE로 판정될 수 있습니다. 실패한 준비는 GET/일반 POST가 자동 재시도하지 않고 retry=true인 명시적 POST만 재접수합니다.

ready의 pyramid에는 schemaVersion=1, tileSetId, mimeType=image/png, rasterWidth/rasterHeight, tileSize=256, tileOverlap=0, minLevel=0/maxLevel, coordinateSpaceId, rasterToOriginal, tileUrlTemplate만 담습니다. 내부 key/source SHA·profile/moduleIdentity·파일 인벤토리·바이트 합계·경로/원시 DZI는 반환하지 않습니다. geometry/coordinate space는 승인된 원본 방향1의 original:{mediaVersion}:{page}:upright-v1이며 affine(a,b,c,d,e,f)=(1,0,0,1,0,0)입니다. 이미지 회전은 이후 화면 변환이고 이 좌표 계약을 바꾸지 않습니다. 이 정보가 편집/반출 권한이나 설치 source-pixel 상한을 추가로 부여하지 않습니다.

URL template은 동일 서버의 /api/v1/media/.../tiles/{level}/{column}/{row} 형식입니다. 클라이언트는 현재 viewport에 필요한 좌표만 요청하고 네이티브 DZI를 직접 읽지 않아야 합니다. 최초 승인 범위는 classic TIFF RGB8/방향1/비압축·contiguous이며 다른 형식의 기존 미리보기 지원과 별개입니다.

manifest가 준비됐더라도 응답 직후 TTL/압박 정리로 퇴거될 수 있습니다. 해당 tile GET은404, 상태 조회는not_requested가 되고 새 명시적 준비 요청에서만 재생성합니다. 정상 캐시가 남아 있으면 오래된 작업 기록이 정리되어도 ready를 복원합니다. failed/중단 표식과 함께 남은 orphan generation을 상태 확인 없이 바로 제공하지 않습니다.

## HTTP·오류·수명

PNG 응답은 image/png, no-store, nosniff, inline filename=tile.png와 검증한 정확한 Content-Length를 씁니다. GET/HEAD 모두 한 tile의 길이/SHA를 확인한 후 콜백 수명 안에서 전송하며 원본/native/전체 피라미드를 디코딩하지 않습니다. 전송 경로는 매니페스트를 한 번 읽고 기대 page/generation/좌표를 검사합니다. HEAD도 캐시 파일 SHA를 읽지만 PNG 본문을 쓰지 않습니다. 원시 저장 경로를 지연 Resource로 반환하지 않습니다. 전송 lease는 callback 종료/연결 실패까지 퇴거와 writer 해제를 막습니다.

Range/If-Range는 지원하지 않고, 유효한 tile 요청의 해당 header는400 INVALID_REQUEST로 거절합니다. ETag/Last-Modified/Accept-Ranges를 제공하지 않으며 조건부 요청으로304나 부분 PNG를 반환하지 않습니다. 상태·오류 응답도 no-store이며 기존 Spring Security 헤더를 유지합니다. 공개 CDN/정적 폴더 serving·디렉터리 listing을 추가하지 않았습니다.

| 상황 | 공개 응답 |
| --- | --- |
| page 범위/숫자·retry 형식 오류, Range |400 INVALID_REQUEST |
| 정규 UUID가 아닌 tileSetId·없는/만료된 generation·유효 정수이지만 범위 밖인 타일 좌표 |404 MEDIA_NOT_FOUND |
| 대기/worker·tracked 상태 상한 |429 TILE_QUEUE_FULL |
| 접수 시 볼륨 여유/예약 부족 |507 INSUFFICIENT_STORAGE |
| 접수 시 캐시 구조 항목 예산 초과 |507 TILE_LIMIT_EXCEEDED |
| 손상/변경된 tile 또는 manifest·스토리지 오류·종료 중 큐 |503 STORAGE_UNAVAILABLE |

오류 ProblemDetail은 고정 status/code만 노출하고 원시 예외·모듈 실행 결과·파일 경로를 반환하지 않습니다. 워커의 profile/timeout/interrupted/TILE_FAILED 및 실행 용량 부족 CONVERSION_BUSY는200 상태 문서 안의 고정 errorCode로 표시합니다. 실제 실행기의 접수 거절도429 TILE_QUEUE_FULL로 정리하며 영속 실패/수동 재시도 계약은 NATIVE_CAPACITY_RETRY.md를 따릅니다. 이미 전송을 시작한 뒤 클라이언트가 끊으면 IOException을 전파해 연결이 종료되며 PNG 뒤에 오류 JSON을 섞지 않습니다.

감사는 POST의 TILE_REQUEST와 비동기 TILE_FAILED를 기존 고정 schema로 기록합니다. 본문/원본명/경로/헤더/쿼리·generation을 기록하지 않고 GET/HEAD별 감사 이벤트를 추가하지 않습니다. 설치 audit.enabled를 따릅니다. 공개 취소 API나 연결 중단에 따른 준비 작업 자동 취소는 이번 범위에 없습니다.

## 검증과 다음 단계

실제 루프백 Tomcat/HttpClient로 인증/CSRF/다른 사용자·조직/권한 철회·버전/generation/좌표·POST 포화/실패 retry·PNG/HEAD/조건부/Range·비공개 DTO·손상/퇴거/기록 정리 후 상태를 확인합니다. 이 HTTP fixture는 native 생성과 신뢰 identity만 테스트용으로 대체하며 실제 서비스/큐/검증/정규화/캐시/보안 필터를 사용합니다. 실제 내부 img2tile·FFmpeg 회귀는 별도의 기존 통합 테스트에서 수행합니다. 결과는 아래에 기록합니다.

다음은 현재 뷰어 UI에 고해상도 타일 레이어를 연결하고 확대/회전·전환/늦은 응답/권한 오류/어노테이션 좌표와 브라우저 검증을 진행하는 작업입니다. 실제 SSO/업무 포맷·동시 부하/P95·OS 격리·최종 라이선스/운영 인수는 남아 있고 설치 ZIP은 최종 단계까지 생성하지 않습니다.

### 최종 검증 결과

집중51개/6개 suite가1분8초에 통과했습니다. 최종 기대 페이지/단일 manifest 읽기 보완을 포함한 build -Pimg2tileIntegration -PffmpegIntegration은2026-10-28에6분10초로 성공했습니다. Java699개/104개 suite의 실패·오류·건너뜀은0이며 신규18개(실제 HTTP7/비활성1/서비스8/감사1/기대 페이지1)를 포함합니다. HTTP native 생성 fixture와 별도의 실제 내부 모듈 통합 회귀를 구분하여 검증했습니다.

실행 JAR 공개 DTO allowlist·유지보수·타일 캐시·해시 IO smoke, 테스트/fixture 포함 항목0개와 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선도 통과했습니다. 상세 보고서는 build/reports/tile-contract/http-verification.json입니다.

실제 설치 YAML/모듈/업무 자료를 변경하지 않았고 신규 외부 런타임 의존성·설치 ZIP 생성은 없습니다. UI와 최종 운영/배포 인수는 남아 있어 distributionReady=false를 유지합니다.

고해상도 UI 연결은 TILE_VIEWER_UI.md에 구현되어 있습니다. 승인 TIFF의 준비된 동일 actor/버전/페이지 타일 결과는 MediaPreviewService의 원본 좌표 보완에도 사용하며, 공개 tile DTO 자체가 편집 권한을 부여하지 않습니다. 실제 native→HTTP→Chrome 인수는 opt-in NativeViewerIntegrationTests로 별도 검증합니다.
