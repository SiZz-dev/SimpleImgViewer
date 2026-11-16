# img2tile 대체 변환 엔진 단계별 설계

구현 후속 상태는 [최소 워커](../native/imagegate-converter/README.md), [v2 자원 경계](LIBVIPS_RESOURCE_BOUNDARY.md), [v3 LPAC 부모 실행기](LIBVIPS_FILE_NETWORK_BOUNDARY.md)를 따른다. 아래 단계별 설계 전체가 구현/운영 승인되었다는 뜻은 아니다.

작성일: 2026-11-11. 상태: 설계 제안. 이 문서는 실행 코드·설치 설정·현재 지원 범위를 변경하지 않는다. 버전은 구현 착수 시 공식 릴리스와 보안 공지를 확인하여 고정한다.

## 1. 목표와 결정

img2tile 실행 파일·DLL·리소스를 사용하지 않고 이미지 분석, 페이지 선택, PNG/JPEG 미리보기, 썸네일, 대용량 타일 피라미드를 제공한다. 디코더를 새로 작성하지 않고 libvips와 필요한 오픈소스 코덱으로 별도 변환 워커를 구성한다. 작업 정책·출력 검증·비공개 캐시·권한 검증은 ImageGate가 소유한다.

- 서버: JDK 21 / Spring Boot 4.0.1 / Gradle Wrapper 8.14.5 유지.
- 워커: C/C++의 작은 실행 프로그램에서 libvips C API를 호출하는 안을 채택한다. 빌드 도구는 CMake, 서버 Gradle 빌드와 분리한다. 서버는 승인된 설치본을 사용하며 개발 중 네이티브 도구를 자동 다운로드하지 않는다.
- JNI/JNA로 서버 JVM에 코덱을 로드하지 않는다. 최초 구현은 작업별 프로세스로 장애·자원 경계를 분리한다. 상주 워커 풀은 계측 후 별도 결정한다.
- ImageMagick/GraphicsMagick 로더와 의존성을 빌드·배포에서 제외한다. PDF/SVG/외부 URL/불필요한 로더·인코더도 최초 허용 범위에서 제외한다.
- 동영상·음성은 기존 FFmpeg/FFprobe 경로를 유지한다. 뷰어 조작·메모·스탬프를 변환 워커에서 재구현하지 않는다.
- 라이브러리의 지원 목록과 제품의 검증된 지원 범위를 구분한다. 모든 포맷·프로필의 무조건적 지원을 약속하지 않는다.

## 2. 현재 코드에서 필요한 변경

| 대상 | 현재 상태 | 설계 변경 |
| --- | --- | --- |
| ImageConverter | 미리보기 변환 SPI 존재 | 유지. 독립 metadata/geometry 계약 추가, 필요할 때 결과와 연결 |
| TileNativeConverter | identity와 타일 출력 디렉터리 계약 | 유지 또는 명시적 결과 DTO로 점진 확장. 워커 원시 경로를 공개 DTO에 노출하지 않음 |
| ConversionConfiguration | IMG2TILE만 생성 | LIBVIPS 명시 분기 추가. 선택 모듈 불가 시 시작 실패, 자동 대체 금지 |
| TileConfiguration / TilePyramidPublisher | Img2TilePyramidValidator에 직접 의존 | 공통 validator 계약으로 분리하고 모듈별 출력 파서를 주입 |
| NativeTileRenderer | classic TIFF RGB8 방향1 검사와 img2tile Source 사용 | 공통 source profile/geometry와 엔진별 승인 정책으로 분리 |
| Img2TilePyramidValidator | 특정 모듈 fingerprint·출력 예외 규약 | 기존 엔진에만 유지. 새 엔진에 overlap 보정 등 예외 규칙을 복사하지 않음 |
| Jp2InputProfile | 기존 모듈의 제한된 JP2 승인 정책 | 기존 엔진에 한정. 새 엔진의 프로필과 캐시 identity를 별도 관리 |
| NativeModuleSnapshot / ProcessExecutor | 실행 정체성·시간·출력·동시 실행 제한 | 재사용 가능성 검증. 새 DLL/플러그인/프로필 리소스까지 정체성 범위 확장 |
| 큐·캐시·HTTP·뷰어 | 보호된 게시/읽기 경로 존재 | 공개 계약 최대한 유지. 새 엔진용 캐시 namespace만 분리 |

메타데이터 probe 역시 악성 파일을 해석하는 작업이다. 서버에서 새 코덱으로 사전 디코딩하지 않고 제한된 워커에서 수행한다. 워커 결과는 신뢰하지 않고 필드·산술·기하·예산을 서버에서 재검증한다.

## 3. 처리 흐름과 워커 계약

인증/파일 권한 확인 → 서버가 mediaId를 비공개 원본으로 해석 → 큐/용량 접수 → 제한 워커 실행 → 출력 검증 → 원본·엔진·권한 재확인 → 원자적 캐시 게시 → 보호된 HTTP 제공 순서다.

워커 연산은 probe / preview / pyramid 세 가지로 제한한다. 셸을 거치지 않는 인자 배열로 실행하며, 경로·숫자·옵션은 서버만 생성한다. 사용자가 명령·옵션·출력 이름·실행 경로·URL을 전달하지 못하게 한다. 파일명의 libvips 옵션 구문 해석을 피하도록 워커가 허용된 명시적 loader API와 별도 인자로 파일을 연다.

내부 요청: protocolVersion, operation, jobId, 정규 원본 경로, 전용 작업 경로, 1기반 page, maxWidth/maxHeight, 출력 PNG/JPEG, 승인 profileId. API의 페이지는 1기반이며 코덱 인덱스 변환은 어댑터 한 곳에서 수행한다.

probe 결과: format, pageCount, encodedWidth/Height, uprightWidth/Height, bitDepth, components, colorSpace, alpha, orientation, engineBuildId, profileId, encodedToUpright 변환. 크기·페이지 수의 범위/곱셈 오버플로를 검사하며 전체 픽셀 할당 없이 분석한다. 모든 페이지의 픽셀을 검사하지 않고 필요한 페이지와 상한 내 메타데이터만 처리한다.

preview 결과: 작업 폴더 기준 상대 출력 경로, 실제 치수·MIME, geometryPolicyVersion, 표시용 색상 정책. 경로는 파일 존재·심볼릭 링크·상위 경로 이탈·regular file 여부를 확인한다.

pyramid 결과: 상대 manifest 경로, 원본/표시 치수, tileSize=256, overlap=0, level 범위, coordinateSpaceId. 초기에는 기존 클라이언트의 레벨 계산 규칙을 유지한다. libvips 출력은 별도 파서로 받아 서버 내부 manifest로 정규화한다. 네이티브 DZI를 직접 서빙하지 않는다.

2026-11-11 프로토콜 구체화: 공통 실행기가 stdout/stderr를 합쳐8192바이트 수집하므로 JSON은 전용 작업 폴더의 고정 result.json(64KiB 상한)으로 분리한다. 진단은 파싱하거나 API에 노출하지 않는다. 큰 타일 인벤토리는 별도 제한 작업 파일로 읽을 예정이다. 첫 protocol v1은 제한된 TIFF 프로필의 probe/preview 사실만 전달하며 전체 색상·방향 변환/편집 기하 승인을 제공하지 않는다. [워커 계약](../native/imagegate-converter/README.md)에 필드·고정 파일명·exit code를 정의했다. 종료 코드0만으로 성공 처리하지 않고 JSON·출력 이미지 검증을 모두 요구한다. 후속에서 최소 libvips8.18.7과 워커를 실제 빌드하여 합성 TIFF2페이지의 probe/PNG/JPEG를 검증했다. 전체 형식·운영/배포 승인을 뜻하지 않으며 Spring 어댑터는 아직 연결하지 않았다.

## 4. 단계별 구현과 통과 기준

### 0단계 — 지원 계약·공급망·기준선 확정

산출물: 포맷/프로필 지원 행렬, 정확한 버전/빌드 옵션/DLL 목록, LICENSE/NOTICE/소스 제공 안내, 기존 성능 기준선, 테스트 표본 목록.

- libvips + TIFF/JPEG/PNG 코덱부터 최소 빌드. JPEG2000은 OpenJPEG, HEIC는 libheif와 승인된 HEVC 디코더를 단계적으로 추가한다.
- 허용 LGPL 조건과 코덱 특허 검토를 분리한다. 실행 파일을 분리한다고 라이브러리 의무가 없어지는 것으로 처리하지 않는다.
- 합성 표본 또는 출처·사용 조건·기대 결과가 확인된 표본만 사용한다. 업무 원본을 저장소에 넣지 않는다.
- 성능은 기존 img2tile 또는 고정 기준 구현으로 같은 하드웨어/입력/동시성에서 기록한다. 기존 모듈을 새 엔진의 정답으로 사용하지 않는다.

통과 기준: 포함 구성요소 식별 완료, 금지 로더/의존성 미포함 확인, TIFF 기준 표본과 측정 방식 확정. 라이선스가 조직 정책에 맞지 않으면 이 단계에서 구성 재선정.

### 1단계 — 모듈 독립 계약과 워커 골격

산출물: 공통 source profile/geometry/validator 계약, 모듈별 factory, 워커 probe 및 고정 프로토콜.

- 기존 img2tile 동작은 유지한 채 공유 계층의 직접 참조부터 제거한다.
- 실행·작업·임시 경로 분리, 실행 전후 엔진 정체성 검증, 제한 stdout/stderr, 취소/timeout 계약 구현.
- 엔진 identity는 wrapper·libvips·DLL·플러그인·색상 리소스·프로토콜·정책 버전과 연결한다.
- 우선 PNG/JPEG의 소형 합성 입력으로 probe/미리보기 실행 경계만 검증한다.

통과 기준: 기존 회귀 통과, 새 모듈 미설치/미구현/변조 시 명확하게 실패, 임의 경로·인자 주입·잘못된 JSON·경로 이탈·과대 치수 거절. OS 격리 전에는 신뢰된 합성 표본으로만 시험한다.

### 2단계 — TIFF 미리보기·다중 페이지

산출물: LibvipsConverter, 페이지별 PNG/JPEG 미리보기·썸네일, TIFF 지원 프로필.

- 최초 범위는 classic TIFF RGB8/방향1로 고정하여 기존 기능과 비교한다.
- 이어서 회색8/16비트, 1비트 문서, LZW/Deflate/CCITT/JPEG 압축을 각각 별도 프로필로 검증한다.
- BigTIFF·planar·CMYK·alpha는 코덱 지원 확인과 표본 검증 후 추가한다. 이름만으로 일괄 허용하지 않는다.
- 비율 유지·확대 금지·페이지 선택·JPEG 배경 합성·원본 불변을 확인한다.

통과 기준: 승인 범위의 실제 decode/출력 재검증, 페이지 혼선 없음, 무손실 경로 기준 래스터 일치. 축소/색변환은 지정 알고리즘과 기대 값으로 비교한다.

### 3단계 — 타일 피라미드 교체

산출물: LibvipsTileConverter, 새 출력 파서/검증기, 기존 게시·큐·HTTP·뷰어 연결.

- libvips dzsave의 tile_size/overlap/suffix/depth를 명시하고 기존 레벨 규약과 대조한다.
- 타일 경계·전체 레벨 개수·끝 타일 치수·PNG 구조/CRC/실제 픽셀·전체 용량을 검증한다.
- 513×257 등의 홀수/비대칭 치수, 1픽셀 경계, 다중 페이지를 포함한다.
- 기존 40MP/4096타일/2MiB 타일/256MiB 페이지 상한을 초기 기준으로 유지한다. 범위 확대는 성능 계측 뒤 별도 검토한다.
- 축소 미리보기를 전체 해상도 타일로 사용하지 않는다. libvips 지원이 모든 입력의 부분 디코딩/낮은 메모리를 보장한다고 가정하지 않는다.

통과 기준: 최상위 무손실 타일을 합친 래스터가 기대 이미지와 일치, 나머지 레벨은 지정 축소 규칙 일치, seam·페이지·좌표 오류 없음. 현재 HTTP 권한/취소/게시 실패 회귀 통과.

### 4단계 — 색상·방향·편집 좌표 통합

산출물: 버전 있는 표시 정책과 좌표 매핑, 검증된 외부 변환 이미지의 편집 연결.

- 표시 기준은 upright sRGB8로 정의하되 원본 정밀도는 보존한다. 16비트→8비트 매핑, ICC 변환, CMYK, premultiplied alpha, 투명 JPEG 배경을 명시한다.
- HDR 입력은 tone mapping을 별도 승인하기 전 거절한다. 회색 sample 값과 화면 RGB 값의 차이를 구분한다.
- 방향1~8/미러링, encoded↔upright↔preview/tile↔사용자 회전 좌표를 검증한다. orientation을 두 번 적용하지 않는다.
- 메모·스탬프·crop/export가 같은 정책을 사용하도록 한다. 치수가 같다는 이유만으로 geometry를 승인하지 않는다.
- full-resolution 편집은 승인된 원본 해상도 디코딩/영역 추출 경로와 연결해야 한다. 작은 preview를 서버 편집 원본으로 재사용하지 않는다. 구현 전에는 해당 형식의 좌표 편집을 계속 차단한다.

통과 기준: 모서리·중앙·경계 영역의 crop, note, stamp와 합성 export 위치 일치. 표시용 색상 변환과 무손실 원본 픽셀 비교를 혼동하지 않음.

### 5단계 — JPEG2000

산출물: JP2/원시 J2K의 독립 승인 프로필 및 회귀 표본.

- OpenJPEG를 통한 RGB/gray, unsigned8/16비트, JP2 컨테이너/원시 codestream을 구분한다.
- 현재 실패한 회색 JP2와 원시 J2K 재현 입력을 필수 회귀로 포함한다. 확장자 변경을 지원 증거로 사용하지 않는다.
- ICC/팔레트/alpha/다른 색공간/정밀도는 개별 승인한다. 첫 구현은 무손실 합성 입력으로 디코더 결과를 독립 기준 래스터와 대조한다.
- 미리보기 승인과 타일·편집 승인을 분리한다. 새 엔진 검증 없이 기존 JP2 제한을 전역 삭제하지 않는다.

통과 기준: 기존 회색 픽셀 불일치 해소와 원시 J2K 실제 decode 확인, 8/16비트 기준 래스터 일치, 손상·과대 입력 제한 통과.

### 6단계 — HEIC/HEIF

산출물: 승인 코덱 빌드, provenance가 있는 표본과 표시 정책.

- primary image/thumbnail/auxiliary/depth/sequence와 grid를 구분한다. 최초는 단일 primary 정지 이미지로 한정하고 나머지는 명시적으로 거절한다.
- 유효 8/10비트 입력, 회전/미러링·색상·alpha 여부를 검증한다. 10비트 SDR과 HDR을 구분한다.
- libheif 자체 보안 한도를 유지하고 프로세스/입력/출력 한도도 함께 적용한다. plugin 디렉터리는 설치본으로 고정한다.
- 타일·편집은 미리보기와 별도의 기하/원본 해상도 검증 후 활성화한다.

통과 기준: 기대 색상/방향이 확인된 유효 입력의 실제 변환, 손상 컨테이너·과다 참조·큰 grid의 제한 시험, 배포 코덱 조건 확인. 표본이 없으면 미완료로 유지.

### 7단계 — 운영 격리·성능·실패 복구

0~6단계에서도 입력·출력·시간·경로 제한은 적용한다. 이 단계에서는 운영 OS의 강제 제한을 통합 검증하며, 완료 전 미신뢰 업무 파일을 운영 투입하지 않는다.

- Windows Job Object 등의 메모리/CPU/프로세스 수 제한과 kill-on-close를 적용한다. 네트워크는 방화벽/샌드박스 정책으로 차단하고 서비스 계정 ACL로 작업 외 경로를 제한한다. Job Object 자체를 파일/네트워크 샌드박스로 해석하지 않는다.
- 읽기 전용 입력, 작업별 전용 TEMP, 승인 DLL/플러그인 로딩 경로, 비상 종료 후 임시 결과 처리, 디스크 예산을 검증한다. 애플리케이션 예약 용량과 OS 디스크 강제 제한을 구분한다.
- 파일당 초기 전체 기한60초/타일120초, 최대 크기·동시성은 현재 설정 상한을 출발점으로 삼는다. probe와 여러 하위 실행이 각각 전체 기한을 새로 부여받지 않도록 남은 시간을 전달한다.
- 첫 preview 표시, 첫 가시 타일, 전체 준비, warm cache, 동시 요청의 p50/p95와 최대 RSS/CPU/디스크를 측정한다. cold/warm 및 해상도·압축·포맷별로 분리한다.
- 수치 목표는 0단계의 기준선과 운영 자원 예산으로 확정한다. 안전 한도를 풀어 성능 통과로 처리하지 않는다. 검증/재인코딩 비용도 전체 시간에 포함한다.
- 변환 중 권한/파일 버전 철회, 손상 출력, timeout, 강제 종료, 엔진 교체, 디스크 부족을 주입하고 비공개 실패·명시 재시도·새 ready 게시를 확인한다.

통과 기준: 승인된 격리 환경에서 하드 제한과 자식 프로세스 정리 확인, 정한 지연/자원 예산 충족, 원본 보존과 최종 게시 권한 회귀 통과.

### 8단계 — 설정 전환·회귀·최종 배포

- config/imagegate.example.yml에 새 모듈·워커 경로·프로필·출력 정책·예산의 검증된 옵션만 추가한다. 실제 config/imagegate.yml은 수정/커밋하지 않는다.
- module=libvips의 executable은 새 wrapper 실행 파일로 정의한다. 기존 예약 예제의 vips.exe 경로는 구현과 동시에 정정하고 프로토콜 검사로 임의 실행 파일을 거절한다.
- config/domain은 기능 내부에 둔다. 엔진 설정은 conversion/config, 형식/색상/좌표 정책은 conversion/domain, 실행 기술은 conversion/util/process, 업무 판정은 conversion/service가 담당한다. 기존 ImageGateProperties 바인딩과의 연결은 유지한다.
- 서버 입력/프로필/기하/색상/리샘플링/타일 옵션/엔진·DLL identity를 캐시 키에 포함한다. 기존 캐시는 새 엔진에서 재사용하지 않으며, 기존 annotation의 좌표 정책이 다르면 검증 전 편집을 차단한다.
- 새 설치에서 img2tile 폴더가 없어도 기동·preview·tile·편집 회귀가 통과해야 한다. 새 경로가 기존 모듈 실행/resource/validator에 의존하지 않는지 검사한다.
- 개발 중 img2tile을 비교용 선택 구성으로 남길 수 있으나 새 엔진 실패 시 자동 fallback은 없다. 기존 모듈 삭제는 별도 변경으로 수행하며 사용자 설치 폴더를 자동 삭제하지 않는다.
- 일반 Gradle 회귀, 기존 모듈이 있는 Windows의 -Pimg2tileIntegration, 향후 -PlibvipsIntegration의 새 워커 시험을 구분한다. 최종 새 엔진 단독 시험은 img2tile 없이 수행한다. 플래그는 구현 전까지 실행 가능한 것으로 문서화하지 않는다.
- SBOM/라이선스·NOTICE·코덱 조건과 운영 인수를 마친 후에만 설치 ZIP 생성·검증한다. 설계/개발 단계에서는 ZIP을 만들지 않는다.

통과 기준: img2tile 없는 설치의 전체 승인 범위 동작, 명시 설정/실패/캐시 분리/복구 검증 완료. 실제 SSO·파일별 권한 연계 등 기존 운영 인수 항목은 별도로 완료해야 한다.

## 5. 예상 패키지와 산출물

실제로 기능을 구현하는 단계에서만 클래스/폴더를 생성한다. 다음 이름은 제안이며 빈 패키지를 미리 만들지 않는다.

```text
com.sizzdev.imagegate.conversion
  config/     ConversionConfiguration, Libvips 설정 검증
  domain/     ImageSourceProfile, ConversionGeometry, EngineCapabilities
  service/    공통 source inspection / pyramid validation 계약
    libvips/  LibvipsConverter, LibvipsTileConverter, LibvipsPyramidValidator
  util/
    process/  기존 실행 경계·snapshot 재사용 및 강제 제한 연결
    libvips/  WorkerProtocolCodec, 출력 파싱 기술 보조
  repository/ 기존 cache/job/work 저장소 재사용
native/imagegate-converter/  CMake 및 libvips wrapper 소스 (제안)
```

워커에 HTTP controller를 만들지 않는다. 기존 media/viewer API가 요청·응답을 소유하며, 네이티브 모듈 실행은 내부 service를 통해서만 가능하다. 테스트는 해당 기능/역할 구조를 따른다.

## 6. 다음 구현 단위

첫 작업은 0단계의 최소 빌드/공급망 표와 1단계의 공통 타일 검증 계약 분리다. 즉시 모든 포맷을 활성화하거나 libvips 실행 파일을 내려받지 않는다. 기존 테스트를 유지하면서 TIFF 합성 표본으로 새 워커 경계를 먼저 검증한다.

## 7. 공식 근거와 라이선스 범위

- [libvips](https://github.com/libvips/libvips): demand-driven 처리, 포맷 지원과 선택 의존성, LGPL-2.1-or-later. 실제 성능과 사용 가능 로더는 빌드/입력별로 확인한다.
- [dzsave](https://www.libvips.org/API/current/method.Image.dzsave.html): 타일 크기·overlap·출력 형식·피라미드 옵션. 기본값에 의존하지 않는다.
- [OpenJPEG](https://github.com/uclouvain/openjpeg): JPEG2000 구현, BSD-2-Clause.
- [libheif](https://github.com/strukturag/libheif): 코덱 플러그인·보안 한도·LGPL, 예제 프로그램 MIT. 추가 코덱의 조건까지 별도 식별한다.

오픈소스 채택은 조건 없는 사용을 의미하지 않는다. 정확한 배포 버전과 모든 의존성의 고지/소스 제공·교체 조건을 점검한다. HEVC 특허 검토와 조직의 LGPL 허용 여부는 별도 판단이며, CLI 프로세스 분리만으로 해결되었다고 간주하지 않는다.
