# 이미지 미리보기 작업 큐·캐시

## 현재 처리 경로

등록 → 헤더 후보 분류·원본 보관 → 첫 페이지 비동기 접수 → 원본 SHA-256 확인 →
최신 파일 권한 확인 → 디코딩·크기 제한·PNG 재인코딩 → 권한 재확인 →
원자적 상태 게시 → 인증된 미리보기 API.

원본 형식과 무관하게 현재 미리보기 전송 형식은 PNG입니다. 원본과 원본 메타데이터를 직접 게시하지 않습니다.
ImageMagick·libvips 자동 우회는 없습니다. 외부 conversion.enabled와 내부 preview.enabled는 별개입니다.

- JDK ImageIO가 실제 읽을 수 있는 PNG/JPEG/GIF/BMP/TIFF 등을 처리합니다.
  GIF는 요청한 이미지 인덱스의 정지 미리보기이며 애니메이션 재생 기능이 아닙니다.
- img2tile 활성 시 해당 어댑터의 TIFF/HEIF/JPEG2000 MIME은 그 모듈로 처리합니다.
  모듈 실패 시 JDK/다른 모듈로 자동 우회하지 않습니다.
- 외부 결과도 다시 디코딩하여 PNG로 재인코딩합니다.
- JDK 디코더가 없는 WebP/HEIF 등의 실제 지원은 설치 모듈에 따라 달라집니다.
  등록 후보 분류만으로 지원 완료를 표시하지 않습니다.
- 영상·음성은 이 이미지 작업 큐에 넣지 않습니다. 재생 소스는 별도 FFprobe/FFmpeg 재생 서비스에서 제공합니다.
- 페이지는 1부터 시작하며 요청 상한은 10000입니다. 실제 없는 페이지는 변환 실패로 반환됩니다.
  전체 페이지 수는 아직 제공하지 않으며 img2tile .doc.inf의 값을 추측해 사용하지 않습니다.
- 출력 픽셀은 축소 미리보기의 크기입니다. 검증된 JDK 형식의 원본 좌표와 EXIF 방향 정규화는 [원본 좌표계](IMAGE_GEOMETRY.md)에 구현했습니다.
  외부 변환기의 원본 매핑은 미확인이며 소스 크기를 원본 편집 좌표로 사용하면 안 됩니다.

## 큐와 복구

workers 수와 queue-capacity로 실행·대기 수를 제한합니다. 같은 키의 실행 중 요청은 하나로 합쳐집니다.
대기 용량이 찬 명시적 POST는 429 PREVIEW_QUEUE_FULL을 반환합니다.
원본 등록이 이미 성공한 경우에는 등록 ID와 preview.errorCode를 반환하므로 원본을 재업로드하지 않고 재시도할 수 있습니다.
GET 상태 조회는 작업을 접수하지 않습니다. 클라이언트 폴링 간격은 1초 이상을 권장합니다.

상태는 not_requested / disabled / queued / running / ready / failed입니다.
완료·실패 상태는 디스크에 저장합니다. 프로세스가 종료되어 남은 queued/running은
다음 기동 후 failed + INTERRUPTED로 표시합니다. 자동 재개하는 영속 메시지 큐가 아닙니다.
재시작 후에도 ready 캐시는 재사용하며 중단·실패 작업은 권한 확인을 거친 POST retry=true로 재접수합니다.
동일 저장소를 쓰는 두 서버는 worker.lock으로 동시 시작을 거부합니다. 다중 인스턴스·분산 큐는 미지원입니다.

## 캐시

키: 정확한 조직·사용자 ID, 미디어 ID·원본 버전·내용 SHA-256, 페이지,
미리보기 크기·원본 픽셀 제한·cache-version, 엔진 식별자, JDK 버전.
img2tile 실행 파일·인접 DLL·resource 내용의 시작 시점 지문도 엔진 식별자에 포함합니다.
모듈 파일은 서버를 중지한 상태에서 교체하고 재시작하세요.
새 디코더/출력 정책을 배포할 때 cache-version도 갱신합니다.

비공개 converted-directory/previews-v1/<SHA256>/ 아래에 state.properties와 result-UUID.png를 저장합니다.
작업 폴더 work-UUID는 작업 종료 시 정리합니다. 변환본을 먼저 원자적으로 이동하고 상태 파일을 원자적으로 교체합니다.
중간 산출물만으로 ready가 되지 않습니다. 정상 결과의 SHA-256은 게시 시 한 번 기록합니다.
온라인 캐시 조회는 파일 크기·수정 시각·정규 경로를 확인하며 매 요청마다 전체 해시를 재계산하지 않습니다.
누락·변경된 산출물은 CACHE_INVALID로 처리하여 제공하지 않고 명시적 재생성을 요구합니다.

캐시는 접근 허가를 저장하지 않습니다. 상태·메타데이터·소스 요청 모두 최신 파일 권한/설치 정책을 확인합니다.
소스 ID만 알고 있어도 다른 사용자·조직·파일·페이지에 사용할 수 없습니다.
응답은 no-store이며 OS 경로·네이티브 로그를 노출하지 않습니다.
파일시스템 전체는 서비스 계정만 쓰도록 제한해야 합니다.
OS 관리자가 내용을 같은 크기·시각으로 바꾸는 공격까지 방어하는 온라인 무결성 검사는 아닙니다.

## 설치 옵션과 제한

imagegate.preview:

| 항목 | 기본 / 범위 |
| --- | --- |
| enabled | true. false면 접수·소스 제공 중지 |
| workers | 2 / 1~4 |
| queue-capacity | 32 / 1~256 |
| max-width / max-height | 1600 / 1~4096 |
| max-source-pixels | 16000000 / 1~40000000 |
| cache-version | preview-v1 / 영문·숫자·하이픈·밑줄 1~64자 |

JDK 디코딩은 원본 크기를 먼저 읽어 픽셀 상한을 확인한 뒤 해당 페이지를 전체 디코딩합니다.
파일 입력 상한 100MiB, 최종 PNG 출력 상한 32MiB를 적용합니다.
외부 img2tile의 원본 픽셀 수를 JVM에서 사전 판독할 수 없는 형식에는 이 픽셀 상한을 보장하지 않습니다.
외부 프로세스는 기존 제한 시간·동시성·출력 검증을 적용하나 OS 메모리·CPU·네트워크 격리는 아직 필요합니다.
JDK 내부 디코더에는 강제 종료 가능한 작업별 제한 시간이 없습니다. 픽셀 제한이 OS 격리를 대체하지 않습니다.

준비/실패 캐시 보존 기간·용량 목표·자동 퇴거를 구현했습니다. [캐시 정리](CACHE_MAINTENANCE.md)의 이미지 범위를 참고하세요.
imagegate.preview.cache는 enabled=true, retention=7d, cleanup-interval=5m, max-bytes=5368709120(5GiB)가 기본입니다.
원본/주석/스탬프·전체 디스크 하드 할당량은 이 정리의 대상이 아닙니다.
실행/대기는 유한하지만 디스크 전체 사용량을 보장하지 않으므로 전용 볼륨 용량 감시가 필요합니다.
실패 정리 중 오류나 강제 종료가 남긴 work-/임시 파일, 이전 정책의 캐시는 서버 중지 상태에서 정리합니다.
운영 적용 전 OS 작업 격리·디스크 하드 할당량·감사/관측·실제 샘플 부하 시험을 완료해야 합니다.

## API

- POST /api/v1/media: 이미지 첫 페이지 자동 접수, 201. preview와 statusUrl 포함.
- POST /api/v1/media/{id}/previews?page=1&retry=false: CSRF 필요. 접수/실행 중 202, 캐시 적중·실패/비활성 상태 200.
- GET /api/v1/media/{id}/status?page=1: PreviewResponse 조회.
- GET/HEAD /api/v1/media/{id}/sources/{sourceId}/content?page=1: ready PNG만 반환.
  최신 권한을 재검사하고 파일 확인부터 전송 콜백 종료까지 사용 수명을 보호합니다. GET은 32KiB 버퍼로 복사하며 HEAD는 같은 길이/형식/no-store 헤더와 빈 본문을 반환합니다.

PreviewResponse: state, page, errorCode, source(준비 전 null).
source: sourceId, role=preview, page, mimeType=image/png, width, height, url.
미디어 요약의 status는 ready/failed/processing이며 세부 상태는 preview.state에서 확인합니다.
메타데이터 조회는 첫 페이지 기준입니다. 다른 페이지 처리 실패가 첫 페이지 ready를 덮어쓰지 않습니다.
미인가 또는 다른 파일/페이지의 소스는 404, 게시 직후 파일 경합/누락은 409 PREVIEW_NOT_READY가 될 수 있습니다.
실패 원인은 PREVIEW_FAILED, DECODER_UNAVAILABLE, INTERRUPTED, CACHE_INVALID 등 정제된 코드로 전달합니다.
실제 로그인/SSO 연동은 아직 필요하며 테스트용 인증 주체는 운영 우회 수단이 아닙니다.

## 개발 검증 기록

2026-10-21: 실제 img2tile 테스트 2개를 포함한 전체 63개 테스트 통과, 실패/오류/건너뜀 0.
초기 전체 실행에서 신규 실제 워커 테스트가 한 차례 failed 상태를 반환했습니다. 당시 상세 원인은 수집되지 않아 확정하지 못했습니다.
테스트 내부 원인 표시를 보강한 후 단독 실행과 전체 실행은 통과했습니다. 이를 원인 해결로 간주하지 않으며, 최종 검증에서 반복 재현·실제 업무 샘플·환경별 안정성 확인이 필요합니다.
설치 ZIP 생성은 최종 기능 구현과 전체 검증·테스트 완료 이후로 연기했습니다.

원본 크기·EXIF 방향은 ready 캐시에 함께 저장합니다. 출력 알고리즘 키는 현재 orientation-v3-thumbnail입니다. 이전 미보정/썸네일 없는 캐시를 새 처리 결과로 재사용하지 않습니다.

## Windows 메타데이터 동시 접근 보완

상태 조회가 state.properties를 읽는 동안 원자적 교체가 발생하면 Windows에서 AccessDeniedException이 생기는 문제를 동시 읽기/쓰기 테스트로 재현했습니다. PreviewCache는 동일 인스턴스의 상태 읽기·교체·게시·콘텐츠 메타데이터 조회를 키 기반 고정 64개 잠금으로 동기화합니다. 렌더링은 이 잠금 밖에서 수행합니다. 무제한 키별 잠금 목록을 만들지 않고 다른 항목의 병렬성을 유지합니다. OS 계정 권한·백신·외부 프로세스에 의한 파일 잠금까지 해소하는 기능은 아닙니다.

## 전용 썸네일

검증된 래스터에서 작은 PNG를 함께 만들고 보호된 썸네일 API/목록 소스로 제공합니다. 기본은 활성/최대 변 256px이며 원본을 재디코딩하지 않습니다. 같은 캐시 수명/용량 목표에 포함하며 정리 시 두 PNG를 함께 제거합니다. THUMBNAILS.md를 참고하세요.

## 종료 시 작업·전송 수명

PreviewQueue는 종료 시작 시 새 접수/콘텐츠 콜백을 거절하고 워커·유지보수에 중단을 요청합니다.
실행되지 않고 회수된 대기 Job은 failed/INTERRUPTED를 기록한 후 자신의 lease를 해제합니다.
이미 워커가 가져간 Job은 시작 경계와 게시 직전에 closing/interrupt를 검사합니다.
변환기가 interrupt를 소비하고 결과를 반환해도 종료 중 게시하지 않으며, 실패 상태와 work 정리를 마친 뒤 lease를 해제합니다.
플래그 없는 InterruptedIOException도 INTERRUPTED로 분류합니다. 재시작 뒤 재접수는 명시적 retry를 요구합니다.

기존 previews-v1/worker.lock의 OS 잠금은 PreviewCache가 소유합니다.
Queue가 생성 시 한 번 claimWriter를 호출하며, 다른 writer와의 동시 실행을 계속 거절합니다.
캐시 종료는 새 lease·게시·유지보수 삭제를 차단하고, 마지막 변환/이미지·썸네일 전송 lease까지 잠금을 유지합니다.
같은 키의 중복 lease도 모두 해제되어야 합니다. 마지막 해제 시 추가 close 호출 없이 잠금이 해제됩니다.
닫힌 인스턴스는 다시 사용하지 않고 재시작 시 새 PreviewCache 인스턴스를 생성합니다.
종료된 이전 인스턴스의 파일 읽기/쓰기는 거절합니다.

메타데이터의 기존 64개 키별 잠금은 유지합니다. 공유 읽기 수명 가드가 각 파일 작업을 보호하고
close/마지막 잠금 해제만 독점 가드를 사용합니다. 렌더링과 전송 콜백 전체를 전역 잠금으로 직렬화하지 않습니다.
유지보수도 항목별 가드 안에서 변경하므로 writer 잠금을 해제한 후 이전 인스턴스가 삭제를 진행하지 않습니다.
진행 중 파일 I/O가 반환하기 전에는 close의 독점 가드 확보도 지연될 수 있습니다.

워커/유지보수 대기는 기존처럼 각각 최대 5초(워커 대기 초과 시 유지보수 대기 생략)이고 호출자의 interrupt 플래그를 보존합니다.
대기 초과·호출자 중단에도 진행 중 Job/응답의 저장소 잠금을 강제로 풀지 않습니다.
마지막 lease가 끝날 때 해제하며, 반환하지 않는 디코더/응답은 잠금을 계속 보유합니다.
JDK 디코더의 강제 종료·OS 격리나 전체 프로세스 종료의 하드 기한을 제공하는 기능은 아닙니다.
work 정리는 검증한 previews-v1/키/work-UUID 정규 경계 안에서 링크를 따라가지 않고 수행합니다.
강제 JVM 종료/전원 장애와 정리 I/O 실패의 잔재 처리는 기존 오프라인 복구 절차를 따릅니다.

이번 종료 처리 단계 검증: 2026-10-23 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(4m38s). 전체 Java490개/78개 suite, 실패·오류·건너뜀0. 신규12개(미리보기 캐시 수명4/큐 종료·취소·미리보기/썸네일 전송8)와 기존 동시성/정리 회귀, 실제 img2tile·FFmpeg 통합 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. 실행 JAR에서 writer claim·새 lease 거절·중복 점유별 잠금 유지·마지막 해제 후 재오픈·INTERRUPTED 상태 보존·닫힌 인스턴스 읽기/쓰기 차단 smoke 통과. 테스트/fixture runtime JAR 항목0개. 실제 모듈·설치 YAML·업무 파일 변경 및 설치 ZIP 생성 없음. UI/운영 P95 재측정·최종 배포 승인은 별도이며 distributionReady=false 유지.

## 렌더링 단계 사이의 취소 (2026-10-26)

ImagePreviewRenderer와 ThumbnailRaster는 PreviewCancellation.check를 통해 현재 스레드의 interrupt를 확인합니다.
플래그를 지우지 않고 고정 메시지의 InterruptedIOException을 던지며, 기존 큐의 INTERRUPTED 실패·명시적 retry 정책으로 연결됩니다.
이미 취소된 요청은 권한/원본 조회나 work 생성 전에 중단합니다.
권한 확인, 원본 경로 조회·해시 읽기, 외부 모듈 선택/반환, 디코더 메타데이터/픽셀 읽기,
크기 조정, PNG 저장, 썸네일 생성, 최종 권한 확인/결과 반환 경계에 검사합니다.
외부 모듈이나 ImageIO 호출에서 제어가 돌아왔을 때 취소되어 있으면 후속 디코딩/인코딩/결과 반환을 진행하지 않습니다.

디코더에서 반환받은 원본 래스터는 크기 조정 이후 해제하고 예외·취소 시에도 finally로 flush합니다.
생성한 출력 래스터도 PNG·썸네일 처리 전체를 finally로 감싸 flush하며, reader.dispose와 Graphics.dispose를 유지합니다.
ThumbnailRaster는 자신이 만든 래스터만 해제하고 빌려 받은 입력 preview는 해제하거나 변경하지 않습니다.
flush는 이미지 자원 해제 요청이며 JVM heap의 즉시 회수나 보안상 메모리 소거를 보장하지 않습니다.

이미 만들어진 부분 PNG/native 파일은 게시하지 않습니다.
렌더러/썸네일 유틸은 임의 경로를 삭제하지 않고, 해당 work를 소유한 PreviewQueue가 unwinding 후 정리합니다.
직접 render/write를 호출하는 내부 호출자는 작업 폴더 수명을 소유해야 합니다.
취소 신호가 반환 직후 도착하는 경합은 큐의 게시 직전 검사로 막습니다.

이 변경은 협력적 취소 경계이며, 실행 중인 JDK 디코더/Java2D/PNG 인코더/동기 파일 I/O를 강제로 중단하는 기능은 아닙니다.
개별 호출이 반환하지 않으면 해당 단계와 lease/저장소 잠금도 계속 유지됩니다.
강제 종료 가능한 OS 프로세스 격리·메모리/CPU 하드 제한·전체 작업 기한과 실제 업무 부하 인수는 별도입니다.

이번 렌더링 취소 단계 검증: 2026-10-26 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(5m43s). 전체 Java505개/80개 suite, 실패·오류·건너뜀0. 신규15개(렌더러 취소/자원/큐 재시도11·취소 util1·썸네일3), 기존 좌표/방향·래스터/정리 회귀와 실제 img2tile·FFmpeg 통합 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. 실행 JAR에서 interrupt 보존·취소된 썸네일 무생성·빌린 입력 소유권·새 호출의 실제 PNG/알파 보존 smoke 통과. 테스트/fixture runtime JAR 항목0개. 실제 모듈·설치 YAML·업무 파일 변경 및 설치 ZIP 생성 없음. 실제 업무 P95/메모리 부하·JDK 호출 강제 종료·OS 격리·최종 배포 승인은 별도이며 distributionReady=false 유지.

취소·명시적 재시도 반복 및 동시 처리의 opt-in 검증과 실측 결과는 [PREVIEW_STRESS.md](PREVIEW_STRESS.md)를 참고합니다.
