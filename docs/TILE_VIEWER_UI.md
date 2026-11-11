# 고해상도 타일 뷰어 레이어

2026-10-28. 기존 미리보기 먼저 표시 후, 선택한 TIFF 페이지의 준비된 타일만 덧그리는 UI를 연결했습니다. 기본 tiles.enabled=false는 유지합니다. 새 외부 JavaScript 라이브러리·CDN·ImageMagick을 추가하지 않았습니다.

## 동작과 설치

bootstrap.features.tiles는 imagegate.tiles.enabled와 IMAGE_VIEW 설치 기능의 교집합입니다. 인증/파일 권한은 API에서 계속 별도 확인합니다. 정확한 viewer-tiles.js 정적 자산만 기존 보안 allowlist에 추가했고 wildcard나 미디어 경로의 익명 접근을 열지 않았습니다. UI는 기존 preview.enabled 미리보기가 준비되어야 동작하며, tile API 단독 클라이언트와 구분합니다.

파일 목록/썸네일을 위한 타일 준비는 하지 않습니다. 선택 페이지의 미리보기를 먼저 표시한 후 image/tiff 후보에서만 POST pyramids를 한 번 요청합니다. queued/running은 GET pyramid로만 확인합니다. 자동 실패 retry나 tile GET 오류에서 POST 재접수는 하지 않습니다. 폴링/숨긴 탭의 대기는 약3분의 협력적 한도로 종료하고, 실패/만료 시 사용자가 ‘다시 준비’를 누른 경우만 retry=true로 요청합니다. 파일 전환/닫기·페이지 수명 종료는 metadata/PNG 요청을 abort합니다. 이미 접수된 서버 준비 작업을 취소하는 새 API는 만들지 않았습니다.

미지원/준비 실패는 기본 미리보기를 유지합니다. 404 tile은 상태를 재조회하여 만료/퇴거인지 파일 접근 철회인지 확인합니다. 상태도401/403/404이거나 버전이 바뀌면 기존 세션/미리보기/타일·Blob URL을 지웁니다. 만료이면서 파일 접근은 유효하면 미리보기를 유지하고 수동 재준비를 안내합니다.

## 화면·좌표

미리보기 surface의 displayWidth/displayHeight는 변경하지 않습니다. 각 PNG는 level/column/row의 originalCore를 해당 미리보기 좌표로 투영하여 같은 surface 안에 배치합니다. 확대/이동/90도 회전은 한 번만 적용되고 밝기는 이미지 레이어에만 적용하며 메모·스탬프 overlay는 기존 위치/권한을 유지합니다. 돋보기는 현재 확보한 타일도 함께 그립니다.

메타데이터가 검증되면 ‘실제 크기’와 확대율은 원본 가로 픽셀 기준으로 동작합니다. 수직 크기는 정수 미리보기의 반올림 차이가 있을 수 있습니다. 확대 상한은 원본 기준16배를 후보로 하되 기존 surface scale4096 상한을 둡니다. fit/포인터 기준 확대는 기존 좌표계를 사용하므로 metadata가 도착할 때 화면 위치를 바꾸지 않습니다.

클라이언트가 originalCoordinatesAvailable/editingReady를 임의로 true로 만들거나 previewToOriginal을 조작하지 않습니다. 이미 서버가 원본 좌표를 확인한 페이지에서는 메모/사용자 스탬프의 클릭 좌표를 유지하며 회전/원본100%로 검증했습니다. MediaPreviewService는 승인된 현재 actor/버전/페이지의 ready 타일 manifest를 읽기 전용으로 확인하여, 외부 TIFF 미리보기의 원본 좌표를 보완합니다. 미리보기 비율과 원본 크기는 한 미리보기 픽셀의 반올림 범위 내에서 일치해야 합니다. 권한/파일 레코드를 반환 직전에 다시 확인합니다. 미준비·다른 페이지·비율 불일치는 편집 좌표를 제공하지 않으며, 캐시 오류는 고정 저장소 오류로 거절합니다. 서버 편집 요청도 같은 page 경로로 재검증하며 crop/export의 원본/출력 픽셀 상한은 유지합니다.

브라우저는 ready 타일 수신 후 원본 좌표가 없던 페이지의 서버 page API를 한 번 재조회합니다. 동일 ID/버전/페이지/source·미리보기 크기와 원본 affine를 검증한 응답만 채택합니다. 기존 좌표가 있던 페이지나 재시도에서는 편집기를 재초기화하지 않아 저장 전 입력을 보존합니다. 파일/페이지 전환으로 abort된 응답은 무시합니다.

## 속도·자원 경계

회전된 viewport 모서리를 역투영하고 화면 pixel density/DPR에 맞는 level을 선택합니다. 최대48개 tile을 넘으면 더 낮은 level로 조정합니다. 원본 전체 canvas/bitmap을 만들지 않습니다. 동시 fetch/decode는 최대4개, 보관된 압축 Blob은16MiB, 단일 응답은2MiB입니다. viewport/level 밖의 요청을 취소하고 이미지/Blob URL을 즉시 해제합니다. 진행 decode와 기본 preview·브라우저 GPU/내부 캐시는 별도이므로 이 숫자가 전체 프로세스 메모리 하드 상한은 아닙니다.

주기 requestAnimationFrame으로 pan/zoom 갱신을 묶으며 숨긴 탭에서는 타일을 해제하고 새 PNG 요청을 중단합니다. 준비 metadata 폴링도 숨김 동안 중단합니다. 복귀 시 기존 generation을 사용하되 만료/실패는 자동 POST로 복구하지 않습니다.

## 클라이언트 검증

media ID/page/version·정규 generation UUID, MIME·schema/원본40MP/4096 tiles/tile256/overlap0/min/maxLevel·항등 원본 변환·기존 geometry/비율과 정확한 동일 서버 URL template을 검사합니다. 불일치·임의 외부 URL을 이미지 요청으로 만들지 않습니다. tile fetch는 credentials=same-origin/cache=no-store/redirect=error이고 기존 CSRF 계약을 사용합니다.

PNG는 스트리밍 바이트 상한에 더해 서명·IHDR·기대256 이하 치수/RGBA8/비인터레이스를 Blob URL 생성/디코딩 전에 확인하고, decode 후 자연 치수를 다시 확인합니다. 이는 서버의 SHA·CRC·정규화 검증을 대체하지 않습니다. 비동기 반환은 context/epoch/버전/abort를 확인해 다른 파일·페이지 또는 이전 retry 결과를 DOM에 남기지 않습니다. 재시도 버튼의 pointerdown은 viewport에 전달하지 않아 메모 모드에서도 버튼 클릭이 주석 생성이 되지 않습니다.

## 검증

기존 Chrome20개 시나리오와 타일/좌표10개를 scripts/test-viewer.mjs에서 검증합니다. 신규는 viewport 제한/원본100%·회전/밝기/돋보기·확인된 좌표의 메모/업로드 스탬프·테마, 미확인 편집 좌표 유지, 수동 실패/만료 retry, 페이지/파일 전환 늦은 반환, 외부 template 차단, 큰 PNG 치수 pre-decode 거절, redirect 차단, 권한/버전 상실 시 URL 해제를 포함합니다. pure core 검증은 홀수 끝 타일·회전/확대 범위·좌표/URL/overlap/version 거절을 포함합니다.

브라우저 tile metadata/PNG 및 이미지·권한 fixture는 합성 자료입니다. 이 결과를 실제 업무 TIFF·SSO·전체 프로세스 메모리/장시간 부하/P95 인수로 간주하지 않습니다. Java의 실제 내부 img2tile/FFmpeg·HTTP 보안 회귀와 구분합니다. 설치 ZIP은 최종 배포 단계에만 생성하며 전체 운영/라이선스 인수는 남아 있습니다.

### 이전 UI 단계 검증 결과

2026-10-28 전체 build -Pimg2tileIntegration -PffmpegIntegration이5분46초에 성공했습니다. Java699개/104개 suite 실패·오류·건너뜀0이고 실제 모듈 회귀/라이선스16개 규칙·63개 구성요소/53개 포함 JAR 기준선도 통과했습니다. 최종 JS 편집 capture 충돌을 보완한 뒤 Chrome29개 전체 및 ViewerUiApiTests 5개/assemble43초를 다시 통과했습니다. 모바일은 타일 enabled 상태로 확인했습니다.

Dark/light의 viewer-tiles.png·viewer-tiles-light.png와 모바일 viewer-mobile.png를 확인했습니다. 최종 실행 JAR의7개 관련 자산은 브라우저가 검증한 소스와 byte 일치하며 공개 DTO/유지보수/캐시/해시 IO smoke도 통과했습니다. 테스트/fixture 포함 항목은0개입니다. 보고서는 build/reports/tile-contract/ui-verification.json, 브라우저 상세는 build/ui-tests/results.json입니다.

실제 설치 YAML/모듈/업무 자료를 변경하지 않았고 신규 외부 런타임 의존성·설치 ZIP 생성은 없습니다. 실제 업무 TIFF/동시 부하·SSO 인수는 남아 있어 distributionReady=false입니다.

## 실제 native TIFF 브라우저 인수 실행

`NativeViewerIntegrationTests`는 `-Pimg2tileIntegration -PnativeViewerIntegration`을 함께 지정할 때 실행합니다. 일반 test는 native-viewer 태그를 제외합니다. Windows의 JDK21, 승인된 img2tile 설치, Node와 Playwright, 설치된 Chrome이 필요하며 새 브라우저/의존성을 다운로드하지 않습니다.

```powershell
$env:IMAGEGATE_NODE = 'C:/path/to/node.exe'
$env:IMAGEGATE_NODE_MODULES = 'C:/path/to/node_modules'
$env:IMAGEGATE_BROWSER = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
.\gradlew.bat test -Pimg2tileIntegration -PnativeViewerIntegration --tests '*NativeViewerIntegrationTests'
```

테스트는 격리된 임시 원본/파생/주석/스탬프 저장소와 임의 포트의 loopback 서버를 사용합니다. 합성 RGB8/방향1 TIFF(1026×514)를 등록한 뒤 실제 native 미리보기/타일, 진짜 CSRF/편집 API, Chrome의 회전 후 클릭 메모·직접 업로드한 합성 스탬프를 검증합니다. 인증 헤더는 테스트 BeanPostProcessor에만 있으며 운영 인증 설정은 바꾸지 않습니다. `scripts/test-native-viewer.mjs`는 이 테스트 서버에만 사용합니다. 업무 자료·실제 설치 YAML은 변경하지 않습니다.

결과/화면은 `build/native-viewer-tests/results.json`, `native-tiff-editing.png`에 남습니다. 일반 UI 회귀는 `scripts/test-viewer.mjs`의30개 시나리오로 별도 확인합니다. 이 합성 인수는 실제 업무 TIFF·SSO/ACL·P95 부하 검증을 대체하지 않습니다.

## TIFF 좌표 연계 검증 결과

2026-10-28 `build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration`이6분14초에 성공했습니다. Java711개/106개 suite의 실패·오류·건너뜀0이며, 신규 MediaPreviewGeometryTests11개와 실제 NativeViewerIntegrationTests1개를 포함합니다. 미준비/페이지/비율 불일치, 비활성/비TIFF/선택 큐 부재, 손상된 증거, 조회 중 권한/버전 변경을 거절하고 기존 geometry를 보존하는 계약을 검증했습니다.

실제 native 축소 미리보기는 처음 원본 좌표가 없으며, 타일 준비 후 서버 page 재조회로 확인된 좌표를 제공합니다. 합성 TIFF를90도 회전한 Chrome에서 메모와 업로드 스탬프의 저장 좌표가 모두(513,257)로 확인됐습니다. 최종 JavaScript의 일반 Chrome30개 시나리오도 통과했습니다. 저장 전 초안은 타일 재시도로 초기화되지 않습니다.

전체 Java 집계는 `build/reports/tile-contract/geometry-java-verification.json`, 실제 native 브라우저 결과/화면은 `build/native-viewer-tests/`, 일반 UI 결과는 `build/ui-tests/results.json`입니다. 라이선스16개 규칙·63개 식별 구성요소/53개 포함 JAR 기준선도 통과했으며, 배포 가능 판정은 별도입니다. 실제 설치 설정/모듈/업무 자료 변경과 설치 ZIP 생성은 수행하지 않았습니다.

최종 UI 로딩 병렬화 이후 실제 NativeViewerIntegrationTests/ViewerUiApiTests와 assemble도47초에 통과했습니다. 실행 JAR의7개 뷰어 자산은 검증한 소스와 byte 일치하며 테스트/fixture 항목은0개입니다. 통합 보고서는 `build/reports/tile-contract/geometry-verification.json`입니다.
