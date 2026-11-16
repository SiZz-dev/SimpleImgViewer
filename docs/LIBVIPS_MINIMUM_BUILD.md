# libvips 최소 변환 워커 구성 계획

최신 후속은 [자원 경계](LIBVIPS_RESOURCE_BOUNDARY.md)를 우선한다. Windows Job 제한/미디어 전 시작 검사와 native16파일·11구성요소·13고지 후보 검증을 추가했고 집중62개 및 후보 규칙6개가 통과했다. 서버 libvips 활성화/파일·네트워크 sandbox/배포 승인 완료는 아니다.

2026-11-11. 초기 구성 계획과 후속 구현을 함께 기록한다. 최신 상태: 최소 libvips8.18.7과 전용 워커의 실제 컴파일 및 합성 TIFF2페이지 변환을 통과했다. 도구/SDK/소스는 .tools에만 준비했고 시스템 설치·실제 설치 YAML·img2tile은 변경하지 않았다. 네이티브 배포 고지·취약점·OS 격리와 Spring 연결은 미완료다. 정확한 현재 구성은 [워커 계약](../native/imagegate-converter/README.md)과 [빌드 입력](../native/imagegate-converter/build-inputs.json)을 우선한다.

## 최초 범위

TIFF RGB8/방향1의 페이지 분석·PNG/JPEG 미리보기·PNG 타일을 합성 표본으로 검증한다. libvips C API를 호출하는 작은 별도 워커를 만든다. Spring Boot 서버에는 네이티브 라이브러리를 로드하지 않는다.

| 구성 후보 | 최초 사용 목적 | 확인할 사항 |
| --- | --- | --- |
| libvips | 분석·축소·타일 생성 | LGPL-2.1-or-later, 정확한 버전·빌드 옵션·보안 공지·배포 고지 |
| GLib 및 해당 빌드의 필수 의존성 | libvips 런타임 | 실제 DLL 목록과 전이 의존성별 라이선스·출처·소스 제공 조건 |
| libtiff | TIFF 입력·페이지 | 선택 압축 코덱을 포함한 실제 의존성·고지·큰 입력 제한 |
| libpng / zlib | PNG 결과 | 포함 버전의 원문 고지·압축 처리 제한 |
| JPEG 구현(예: libjpeg-turbo) | JPEG 미리보기 | 선택 구현의 복수 라이선스/하위 코드·DLL·옵션 |
| Windows C/C++ 런타임 및 빌드 도구 | 워커 빌드/실행 | 설치 도구와 실제 재배포 파일의 범위 구분, 재배포 조건 |

이 표는 전체 SBOM을 대신하지 않는다. 실제 해석된 모든 DLL·플러그인·내장 코드·리소스를 식별해야 한다. 고정 버전과 배포 형태를 정하기 전 LGPL 조건이 충족되었다고 주장하지 않는다.

## 최초 빌드에서 제외

ImageMagick/GraphicsMagick 로더·라이브러리, PDF/SVG/URL 로더, OpenSlide/RAW 등 불필요한 입력 로더, HEIF/JPEG2000/AVIF/JXL 등 아직 검증하지 않은 코덱, 불필요한 인코더와 플러그인 자동 검색을 제외한다. 제외 옵션 이름은 선택한 libvips 릴리스의 Meson 설정을 확인하여 고정하며 추정 옵션을 실행하지 않는다.

의존성 자동 다운로드/미확인 fallback 빌드를 허용하지 않는다. 입력 loader는 명시적으로 선택하고 워커가 노출하는 operation과 출력 포맷을 allowlist로 제한한다. 배포 디렉터리 밖 DLL/플러그인 검색과 부모 PATH에 의존하지 않는다.

JPEG2000 단계에는 OpenJPEG, HEIC 단계에는 libheif와 승인된 HEVC decoder만 추가한다. 단계별 SBOM/고지·실제 기능·변조 검증을 다시 수행한다. HEIC의 코덱 특허와 조직의 LGPL 허용 여부는 별도로 확인한다.

## 설치본 승인에 필요한 증거

1. 공식 소스 URL·태그/commit·소스 해시, 패치 목록과 재현 가능한 빌드 명령.
2. compiler/도구 버전과 모든 빌드 옵션, 실제 loader/operation 인벤토리.
3. 실행 파일·DLL·플러그인·ICC 등 리소스별 SHA-256과 로딩 경로.
4. 구성요소별 LICENSE/NOTICE/저작권 및 필요한 소스 이용/교체 안내.
5. 금지 로더 미포함, 허용 포맷의 실제 합성 입력 변환, 워커 프로토콜 검사.
6. CPU/메모리·프로세스·네트워크·파일 접근 제한 시험. 일반 프로세스 분리만으로 OS 샌드박스 완료를 주장하지 않음.

실행 파일/DLL/업무 자료는 저장소에 추가하지 않는다. 프로젝트 설치 ZIP은 최종 기능 및 운영 인수 완료 전 생성하지 않는다.

## 이번 코드 변경의 범위

TileSourceProfile과 TilePyramidValidator를 추가하여 공유 게시 계층이 img2tile의 Source/PublicationProof와 출력 폴더 이름에 직접 의존하지 않게 했다. img2tile validator의 소유자·경로·원본 프로필·전체 inventory/해시 재확인은 유지한다. 게시 계층은 adapter가 반환한 경로도 작업 폴더 내부의 정규 파일인지 독립 검사한다.

현재 factory는 계속 img2tile만 생성한다. TileConfiguration의 구체 validator 선택, NativeTileRenderer의 승인 TIFF 검사, TileCache의 기존 source profile allowlist와 캐시 identity 확장은 후속 구현 대상이다. 새 validator 계약은 신뢰된 서버 코드용이며 외부 플러그인 업로드/API로 개방하지 않는다. 테스트용 다른 파일 배치는 새 엔진/포맷을 승인하는 것이 아니다.

## 다음 구현

2026-11-11 후속: 초기8.18.0 API 후보에서 안정판8.18.7로 변경하고 LLVM-MinGW/공식 SDK·소스로 최소 DLL과 워커를 빌드했다. SDK에 빠진 코덱 공개 헤더는 대응 버전에서 준비하고, 원본 SDK 대신 .pc 사본만 재배치했다. 지원되는 메모리 제한 API 검출과 modules/Magick/HEIF/OpenJPEG 비활성을 확인했다. runtime은 EXE1+DLL15개(9,684,480바이트)이며 TIFF의 전이 WebP/SharpYUV DLL을 포함한다. 이는 직접 WebP 입력 지원을 뜻하지 않는다. Java client는 아직 Spring factory에 연결하지 않았다.

이번 공통 계약 변경의 검증: 집중61개 통과, 전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration 성공(10분37초). Java848개/119 suites 실패·오류·건너뜀0, 라이선스16개 규칙/63개 구성요소/53개 포함 JAR 대조 통과. 새 테스트 fixture의 실행 JAR 포함0개. 일반 UI40개는 별도 재실행하지 않았고, libvips 유효 입력 검증은 아직 수행하지 않았다. 검증 결과는 build/reports/conversion-contract/verification.json에 기록한다.

후속 실제 워커 포함 검증은48개(native3+합성 프로토콜38+공통 계약7)의 실패·오류·건너뜀0,50초다. 이어서 build -x test는27초에 성공하고 기존 Java 라이선스 검증을 통과했다. 전체848개와 HTTP/Chrome 회귀는 재실행하지 않았다. native 결과·런타임 SHA는 build/reports/libvips-native/에 기록한다. Java 의존성 고지 검증과 native 공급망/배포 승인을 동일하게 취급하지 않는다.

다음은 OS 강제 격리·native 고지/취약점 점검, 공통 source inspector와 엔진별 검증/캐시 정책 주입, 더 다양한 TIFF/기하 인수다. libvips 예약 설정은 승인된 어댑터 구현 전까지 fail-closed 상태로 유지한다.

공식 근거: [libvips 소스/라이선스](https://github.com/libvips/libvips), [libvips 빌드 안내](https://www.libvips.org/install.html), [타일 생성 API](https://www.libvips.org/API/current/method.Image.dzsave.html), [OpenJPEG](https://github.com/uclouvain/openjpeg), [libheif](https://github.com/strukturag/libheif). 선택 버전의 원문과 전이 의존성을 최종 판단 근거로 삼는다.
