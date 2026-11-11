# 대형 이미지 타일 공급 계약 v1

2026-10-28. 실제 변환 모듈의 타일 출력 규약 확인, 좌표/경계 도메인·읽기 전용 산출물 validator/내부 normalized manifest와 PNG 재인코딩·원자적 비공개 캐시 구성 요소를 구현했습니다. 보호된 API·작업 큐와 런타임 연결은 후속 단계입니다. 타일 HTTP API/브라우저 연결은 아직 없으며, 후속 작업 큐/설치 연결의 최신 상태는 TILE_QUEUE.md를 따릅니다. 아래 endpoint/설정 제안은 현재 호출 가능한 기능이 아니며 실제 설치 YAML에 추가하지 않습니다.

## 확인한 모듈과 결정

기존 Img2TileConverter는 -oscaleonly로 PNG/JPEG 축소 미리보기만 생성합니다. 모듈 이름만으로 타일 지원을 가정하지 않고 설치 EXE의 내장 도움말에서 -otilesize/-oname/-otype_tile과 관련 옵션을 확인했습니다. 해당 옵션만 BoundedProcessExecutor의 인자 배열로 실행했으며 셸/추측한 옵션·ImageMagick을 사용하지 않았습니다.

합성513×257 RGB TIFF 두 페이지(orientation1/6, 페이지별 서로 다른 픽셀)를 만들고 원본을 수정하지 않은 채 변환했습니다. 실행 EXE/DLL/resource의 NativeModuleSnapshot을 호출 전후 검증했습니다. 설치 모듈 fingerprint와 산출물은 build/reports/tile-contract/latest.json에 기록합니다. 이 관측은 해당 모듈/입력 프로필에 한정합니다.

| 항목 | 실제 관측 |
| --- | --- |
| 출력 | mipmap.dzi + mipmap_files/{level}/{column}_{row}.png |
| 레벨 |0~10, 페이지당17개 PNG, 최대 레벨은 원본513×257 |
| 페이지 선택 | API1/2에 해당하는 CLI0/1의 서로 다른 픽셀 모두 일치 |
| 전체 해상도 내용 | 페이지당131841픽셀을 입력의 encoded-raster 좌표와 전부 대조 |
| 원본 무결성 | 변환 전후 원본 전체 SHA 동일 |
| overlap | DZI 선언1, 실제 모든 타일 치수는 overlap0 구조. 선언과 실제 경계 불일치 |
| orientation6 | manifest와 픽셀 모두513×257 encoded raster 유지. upright257×513 회전 보정은 적용되지 않음 |

**결정:** 네이티브 DZI를 브라우저에 직접 제공하지 않습니다. TIFF RGB/8bit의 검증된 모듈 프로필을 기반으로 모든 레벨의 개수·치수·경계·PNG를 확인한 뒤, 서버가 실제 overlap0 계약의 공개 JSON을 생성하는 방향입니다. 모든 모듈/파일에 overlap을 임의로0으로 덮는 일반 보정이 아닙니다. 모듈 fingerprint 또는 규약 변경 시 새 검증/승인 없이는 기존 프로필을 재사용하지 않습니다.

최초 게시 구현 대상은 검증 가능한 TIFF RGB/8bit/방향1입니다. 방향6의 raw 동작은 관측했지만 전체8방향·미러링과 기존 편집 좌표 연동을 검증하기 전 타일 사용 범위를 넓히지 않습니다. HEIC/HEIF/JP2/J2C·BigTIFF·CMYK/알파/1bit·손상/특수 TIFF의 타일 규약은 미검증입니다. 현재 미리보기 지원을 타일 지원으로 확대 해석하지 않습니다. 축소 미리보기를 원본 해상도 타일로 표현하지 않습니다.

## 좌표 계약과 구현

Deep Zoom의 레벨/크기/overlap 의미는 [OpenSeadragon DziTileSource](https://openseadragon.github.io/docs/OpenSeadragon.DziTileSource.html) 및 [TileSource 공식 구현](https://openseadragon.github.io/docs/openseadragon.js.html)의 기본 레벨 모델을 참고했습니다. 클라이언트 라이브러리를 추가/배포하거나 버전을 선정한 단계는 아닙니다.

conversion/domain.TilePyramidLayout은 정수 계산만으로 레벨·행/열 개수·전체 타일 수와 세 경계를 제공합니다.

- maxLevel = ceil(log2(max(width,height))), level0은1×1, maxLevel은 전체 raster.
- 해당 level의 축소 분모 d=2^(maxLevel-level). 크기는 ceil(width/d), ceil(height/d).
- 행/열은 tileSize 기준 올림 나눗셈. 좌상단0기반 column/row, 오른쪽/아래 끝은 exclusive pixel edge.
- core: overlap 없는 화면 배치 영역. stored: 실제 PNG가 포함해야 할 overlap 확장/이미지 경계 clipping 영역. originalCore: 해당 core가 덮는 원본 raster 영역.
- 예:513×257/tile256/overlap0은 최상위3열×2행이며 우하단 core=(512,256,1,1). overlap1이면 첫 PNG는257×257이어야 해 현재 모듈의256×256과 맞지 않습니다.
- 원본 좌표는 encoded raster 기준입니다. 정방향 편집 좌표/사용자 회전과 혼동하지 않습니다. 현재 ImageGeometry의40MP 상한/기존 편집 정책도 변경하지 않습니다.

도메인의 양수 int 치수·power-of-two64~1024 tileSize/overlap0~16 범위는 기하 표현 범위입니다. 모듈 지원이나 처리 승인·할당량이 아닙니다. 현재 네이티브 프로필에서 검증한 tileSize는256뿐입니다. 연산 중간/개수는 long을 사용하며 큰 값도 실제 파일/배열/전체 레벨 객체를 만들지 않고 계산합니다. 서비스는 반드시 설치 픽셀/파일 수·바이트 예산을 먼저 검사한 뒤만 열거/변환해야 합니다.

## 제안 API: 권한 확인 후 비동기 준비와 읽기 분리

| 요청 | 제안 동작 |
| --- | --- |
| POST /api/v1/media/{mediaId}/pages/{page}/pyramids?retry=false | 인증/CSRF·현재 원본 버전/VIEW 권한·설치 정책·지원 프로필·사전 예산 확인 후 제한 큐 접수. queued/running202, 기존 ready200. 실패는 명시적 retry=true만 재접수 |
| GET /api/v1/media/{mediaId}/pages/{page}/pyramid | 준비 상태와 ready manifest. 변환/디코딩을 시작하지 않음 |
| GET 또는 HEAD /api/v1/media/{mediaId}/pages/{page}/pyramids/{tileSetId}/tiles/{level}/{column}/{row} | 최신 권한/버전·tileSetId/행렬 범위·산출물 정체성 확인 후 immutable PNG. native/JDK 원본 디코딩을 시작하지 않음 |

제안 ready JSON은 state, tileSetId, page, schemaVersion, rasterWidth/rasterHeight, tileSize, tileOverlap, minLevel/maxLevel, coordinateSpaceId, rasterToOriginal, tileUrlTemplate을 담습니다. 최초 방향1 프로필에서만 raster와 upright 원본 좌표가 같아 항등 변환을 선언합니다. 미검증 방향/크기에서 editingReady/fullResolution을 주장하지 않습니다. tileSetId/manifest만으로 메모·스탬프·반출 권한을 부여하지 않습니다.

state는 disabled/not_requested/queued/running/ready/failed/unsupported를 제안합니다. 오류 코드는 TILE_PROFILE_UNAVAILABLE, TILE_GEOMETRY_UNVERIFIED, TILE_LIMIT_EXCEEDED, TILE_QUEUE_FULL, TILE_OUTPUT_INVALID, INTERRUPTED처럼 고정합니다. 구체 HTTP 상태/DTO는 다음 구현에서 기존 MediaException 계약과 맞춰 확정합니다. 비공개 캐시 경로/EXE/원시 native 진단·DZI/임의 URL은 응답하지 않습니다.

미인증401, 다른 조직/소유자·권한 철회/오래된 버전은404 정책을 유지합니다. 각 PNG/manifest/HEAD에 no-store와 nosniff, 고정 Content-Type과 HEAD 본문 없음/정확한 길이를 적용합니다. source/tileSet ID는 인증 수단이 아닙니다. 저장소 정적 서빙·디렉터리 listing·확장자/경로/외부 URL 입력·Range/공개 CDN은 초기 범위에서 제외합니다.

## 생성·검증·게시·캐시

초기 방식은 페이지당 전체 피라미드를 한 번 준비하고 화면에 보이는 타일만 조회합니다. -coord/-coord_level의 부분 생성 동작은 아직 검증하지 않았으므로 GET마다 native를 실행하지 않습니다. 기존 축소 미리보기가 첫 화면을 제공하고, 별도 타일 준비가 성공한 경우에만 고해상도 레이어를 연결합니다.

1. 현재 권한/원본 버전·SHA 확인, 원본 크기/방향/색상 프로필 검증. 신뢰되지 않은 DZI나 출력 PNG 크기로 원본을 추측하지 않음.
2. TilePyramidLayout의 개수/수준·원본 픽셀·추정 작업/출력 바이트·볼륨 여유/예약을 검사한 후 Job/lease 획득. 한 페이지 동일 키 중복 접수는 한 번만 수행.
3. 별도 private work/작업 환경에서 승인된 모듈로 생성. 입력·출력 경계/모듈 snapshot·제한 시간·프로세스 슬롯을 유지.
4. DZI는 제한 길이/노드/허용 속성·namespace만 처리하고 DTD/외부 엔터티/외부 리소스를 금지. 이는 향후 런타임 파서 요구이며 현재 probe의 테스트 전용 속성 추출기를 운영 파서로 사용하지 않음.
5. 레벨/이름을 정규 십진수로 고정하고 예상 PNG 전부·개수·치수·실제 format·누적 바이트·누락/추가 파일·링크/정션/변경을 검사. 선언 overlap과 다른 프로필은 알려진 normalization 규약으로만 처리하며 임의 추론/다른 모듈 자동 우회를 하지 않음.
6. 원본 metadata/EXIF/GPS/텍스트를 복사하지 않고 검증된 작은 tile raster를 한 개씩 PNG로 재인코딩. 전체 원본/피라미드를 JVM 비트맵으로 만들지 않음. 파일 SHA/정체성 목록과 서버 manifest를 생성.
7. 원본/권한/모듈을 다시 확인한 뒤 immutable generation/manifest를 원자적으로 게시. 검증 전 부분 레벨/네이티브 DZI는 제공하지 않음. 실패/취소/종료 시 게시 차단과 참조 수명/정리를 유지.

키는 기존 PreviewKeys와 같은 길이 접두어 방식으로 tenant/user·mediaId/version/SHA·page·모듈 fingerprint·검증된 geometry/orientation 프로필·tileSize/실제 overlap·PNG/색상·생성 정책 버전을 결합합니다. 설치 경로/원본 파일명은 키/URL에 넣지 않습니다. 원본/주석/스탬프와 물리적으로 분리한 private tile 캐시를 쓰고 writer lock·변환/전송 lease·진행 작업 보호·알려진 완료 generation만의 TTL/LRU 정리를 둡니다. 캐시 hit에도 현재 권한을 확인합니다.

## 설치/부하 제안과 게시 전 조건

다음 값은 설계 제안이며 아직 application.yml/config/imagegate.example.yml에 추가하지 않습니다. 미구현 키는 현재 엄격한 설정 바인딩에서 사용할 수 없습니다.

- tiles.enabled=false, 승인된 img2tile TIFF 방향1 프로필만 선택. ImageMagick/다른 모듈 선택을 자동 대체하지 않음.
- tileSize256/실제 overlap0, maxSourcePixels40000000, maxTilesPerPage4096, maxTileBytes2MiB, maxPageBytes256MiB를 시작 후보로 검증. 기하 값 허용과 자원 승인 분리.
- 별도 workers1/queue8, 기존 native 실행기 전체 슬롯과 조정해 첫 미리보기 우선. worker를 늘리기 전에 네이티브 메모리/출력 최대 사용량 측정.
- 작업 전 출력+정규화 임시본 두 세트/리소스·예비 여유를 예약. 전체 tile 캐시 목표8GiB/보관7일과 진행·전송 중 eviction 금지, 기동 시 중단 작업/예약 재계수 제안.
- native 실행·전체 처리·대기 기한과 취소 checkpoint를 구분. JDK decode 강제 종료/OS CPU·메모리·네트워크/파일 총량 hard limit은 기존과 별도이며 파일 수/작은 PNG만으로 네이티브 전체 원본 할당을 제어했다고 주장하지 않음.

실행/명령·부하 예산·안전 파서/산출물 검증·캐시 수명·파일별 권한/GET/HEAD·브라우저 확대/회전·메모/스탬프 좌표 일치·실 포맷/대형 원본 인수 이후에만 타일 제공을 켭니다. 이 단계는 그 구현을 완료한 상태가 아닙니다.

## 다음 구현 순서

읽기 전용 DZI/타일 산출물 validator·내부 normalized manifest와 단일 타일 PNG 재인코딩·private 캐시의 원자적 generation 게시 구성 요소를 구현했습니다. 다음은 캐시 키/제한 큐·설치 옵션/수명이며, 이후 권한 API·현재 UI의 고해상도 레이어를 차례로 연결합니다. 미검증 방향/형식은 지원 상태를 부풀리지 않고 기존 미리보기 계약을 유지합니다.

## 검증 결과

2026-10-27 JDK21 build -Pimg2tileIntegration -PffmpegIntegration이6분52초에 성공했습니다. Java579개/90개 suite의 실패·오류·건너뜀은0입니다. 신규12개는 타일 기하11개와 실제 모듈 계약1개이며 집중 검증은47초에 성공했습니다. 실제 모듈 계약은 서로 다른 두 페이지의 전체263682픽셀·모든34개 타일의 레벨/치수·원본 SHA·호출 전후 모듈 기준선을 확인했습니다. 낮은 레벨의 리샘플링 품질·대형/실 업무 TIFF·다른 형식/8방향 전체 인수는 완료한 상태가 아닙니다.

기존 실제 img2tile·FFmpeg 통합 및 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다. 실행 JAR 해시 IO smoke와 테스트/fixture 클래스 항목0개를 확인했습니다. 실제 설치 YAML/EXE·DLL/업무 자료를 변경하지 않았고 설치 ZIP은 생성하지 않았습니다. 라이선스 식별 검증과 최종 배포 준비를 구분하여 distributionReady=false를 유지합니다.

실제 관측 보고서: [tile-contract/latest.json](../build/reports/tile-contract/latest.json).


## 읽기 전용 validator 구현 (2026-10-28)

Img2TilePyramidValidator는 아직 런타임 큐/HTTP에 연결하지 않은 내부 서비스입니다. 승인 fingerprint를 코드의 고정 프로필과 대조하고, 호출자가 원본에서 신뢰성 있게 확인한 TIFF_RGB/8bit/3samples/방향1/양수 페이지·원본 크기를 받습니다. DZI가 원본 사실이나 모듈 정체성의 근거가 되어서는 안 됩니다. 실제 NativeModuleSnapshot 검증과 원본/권한 검사는 호출 측 책임이며, 단순 문자열 입력만으로 모듈/원본 검증을 대체하지 않습니다. 기존 native preview의 geometry=null과 편집 정책은 그대로입니다.

검증 예산은 원본40MP,4096타일,PNG당2MiB,페이지 합계256MiB(DZI 포함),DZI8KiB입니다. 생성자는 더 작은 내부 예산만 허용하며 설치 YAML 키를 추가하지 않았습니다. 처리 전 기하/개수와 파일 전체 크기 예산을 확인합니다. 파일별 읽기는 상한+1바이트로 제한하고, 타일 한 개씩만 PNG 구조/CRC와 JDK 디코딩을 검증합니다. 전체 원본 래스터를 할당하지 않습니다.

DZI는 namespace·속성·단일 Size·정규 정수 문자열만 허용합니다. DTD/외부 엔터티/외부 스키마/XInclude를 차단하고 depth4/속성8/name128 제한을 둡니다. 노드/태그를 임의 탐색하지 않으며 주석/처리 지시/추가 텍스트·요소를 거절합니다. 파서 설정을 지원하지 못하는 환경에서는 실패하며 보안 기능을 끄고 재시도하지 않습니다.

출력 디렉터리는 mipmap.dzi와 mipmap_files의 정확한 레벨/파일만 허용합니다. 모듈 실행 환경의 resource 등은 출력 디렉터리 바깥에 두어야 합니다. 예상 경로 집합에 없는 항목, 누락, 비정규 이름, 링크/정션·canonical 이탈, 특수 파일, 크기/치수 불일치는 실패합니다. 고정 깊이·예상 개수 안에서 탐색하며 기본 링크 추적을 사용하지 않습니다. 단일 PNG는 RGB/RGBA8, 비인터레이스, 정상 CRC/압축 래스터·종결/EOF를 요구합니다. 현재 실제 native RGB 출력은 이 프로필로 확인합니다.

Windows의 BasicFileAttributes.fileKey가 null인 경우도 있으므로 키만으로 신뢰하지 않습니다. canonical 경로/파일 유형·크기/생성·수정 시각과 읽기 전후 스냅샷, 전체 인벤토리 재검사 및 두 번째 SHA 검사를 결합합니다. 키가 제공되면 중복도 거절합니다. 이는 모든 하드링크나 악의적인 동일 권한 프로세스와의 TOCTOU 경합을 제거하는 OS 보장이 아닙니다. 같은 크기/시각/내용의 교체는 식별되지 않을 수 있습니다. 반드시 생산 프로세스를 종료하고 다른 writer/전송/정리 작업이 없는 독점 private 작업 디렉터리를 유지해야 합니다. OS ACL/격리와 후속 안전 재인코딩·원자적 게시를 생략할 수 없습니다.

TilePyramidManifest는 schemaVersion/profile/moduleIdentity/page, overlap0 layout, 방향1의 original-upright-pixel-edges 좌표계, DZI SHA, 누적 바이트, 정렬된 타일 좌표/치수/크기/SHA의 불변 목록입니다. 경로·원시 XML·URL은 담지 않습니다. 내부 검증 결과이며 ready HTTP DTO/게시 승인서가 아닙니다. verifyUnchanged는 전체 구조·PNG·해시를 다시 확인하고 기존 매니페스트와 비교합니다. 페이지가 다른 경우에도 일치하지 않습니다. PNG 메타데이터 제거/재인코딩, 원본 픽셀과 모든 축소 레벨의 내용 일치 증명, 원본/권한 재검사·캐시/전송 lease는 이 서비스의 완료 범위가 아닙니다.

협력적 인터럽트 checkpoint는 각 파일/단계에 있지만 JDK XML/PNG 호출의 hard timeout이나 OS 메모리 상한을 제공하지 않습니다. 실패 메시지는 고정이며 내부 cause는 공개 DTO로 반환하지 않습니다.

후속 단계에서 단일 PNG 재인코딩·원자적 캐시 게시 구성 요소를 구현했습니다(TILE_CACHE_PUBLICATION.md). 제한 큐/수명·설치 옵션, 권한 API·브라우저 연결은 계속 남아 있습니다.

### validator 검증 결과

2026-10-28 집중 타일45개(기하11/native1/validator33)가34초에 통과했습니다. validator33개는 정상 불변 결과·XML namespace/외부 참조/구조·길이·추가/누락/비정규 이름·치수·PNG 위조/잘림/CRC/압축/후행 바이트·파일/페이지 예산·잘못된 원본 프로필·내용 변경·인터럽트·실제 Windows 정션 거절을 포함합니다. native 통합은 출력 전용 디렉터리를 분리해 방향1의17타일 normalized manifest/verifyUnchanged 성공과 방향6 UNAVAILABLE를 확인했습니다.

전체 build -Pimg2tileIntegration -PffmpegIntegration은4분39초에 성공했고 Java612개/91개 suite의 실패·오류·건너뜀은0입니다. 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선과 실행 JAR 해시 IO smoke/테스트·fixture 항목0개를 확인했습니다. 신규 외부 런타임 의존성·실제 설치 YAML 변경·설치 ZIP 생성은 없습니다. 타일 HTTP/캐시 게시/UI는 계속 후속 범위이고 distributionReady=false입니다. 보고서는 build/reports/tile-contract/validator-verification.json입니다.
