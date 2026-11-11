# ImageGate

GitHub 저장소명은 SimpleImgViewer이며, 애플리케이션 이름과 Java 패키지는 ImageGate / `co.kr.satu.imagegate`를 유지합니다.
프로젝트 소스의 라이선스는 [BSD-3-Clause](LICENSE)입니다. 외부 라이브러리와 도구의 라이선스는 각각 적용되며, [의존성 라이선스 관리](docs/RUNTIME_LICENSES.md)를 참고하세요. img2tile·FFmpeg 실행 파일과 DLL, 실제 설치 설정 및 업무 자료는 포함하지 않습니다.

이미지·영상·음성을 열람하는 웹 뷰어입니다. 주요 뷰잉·편집·재생 기능과 개발 환경 검증을 구현했으며 실제 업무 인증 연동과 운영 인수는 남아 있습니다. 최신 범위는 [개발 상태](docs/DEVELOPMENT_PLAN.md), 인수 순서와 통과 기준은 [운영 인수 계획](docs/OPERATIONAL_ACCEPTANCE.md)을 참고하세요.

## 개발 환경

- JDK 21
- Spring Boot **4.0.1**
- Gradle **8.14.5**, Groovy DSL, Wrapper 사용
- Spring MVC / Validation / Security / Actuator
- DB, 프런트엔드 프레임워크, 인증 공급자는 아직 선정하지 않았습니다.

Java 21 toolchain 및 `--release 21`을 사용합니다. Gradle을 실행할 JDK도 21로 지정하세요.

```powershell
# 현재 PC의 JDK 경로 예시. 시스템 전역 설정을 변경하지 않습니다.
$env:JAVA_HOME = 'C:\Program Files\Java\openjdk\jdk-21.0.2'
.\gradlew.bat clean build
.\gradlew.bat bootRun
```

macOS/Linux에서는 JDK 21 설정 후 `./gradlew clean build`, `./gradlew bootRun`을 사용합니다.
Gradle 의존성은 Maven Central 및 공식 Gradle Plugin Portal에서 내려받습니다.

## 현재 동작 범위

- `GET /actuator/health`: 인증 없이 최소 상태만 조회, 정상 시 `{"status":"UP"}`.
- /api/v1/media 등록·조회는 인증된 ViewerPrincipal과 파일 권한이 필요합니다. 실제 로그인/SSO 공급자는 미연동이며 일반 계정·헤더로 우회하지 않습니다. 그 외 요청은 기본 거부합니다.
- CSRF 보호 유지. 개발용 기본 계정, 비밀번호, 임시 인증 우회는 제공하지 않습니다.
- `prod` 프로필에서 세션 쿠키 Secure 속성 적용. 운영은 HTTPS 전제입니다.
- img2tile 어댑터·제한 실행기, 등록·메타데이터·상태·PNG 미리보기·페이지 API, 뷰어 세션, 제한된 비동기 큐·중복 방지·디스크 캐시를 구현했습니다. 클릭 메모·사용자 스탬프 저장을 구현했으며 원본 해상도 영역 자르기·PNG 사본 다운로드도 구현했습니다. 메모·스탬프·밝기·회전 합성 PNG 반출도 구현했습니다. 검증된 FFmpeg 기반 MP4/MPEG·MP3/WAV 재생과 탐색 막대도 구현했습니다. 실제 로그인 연동은 후속 단계입니다.
- 선택한 시안을 기준으로 /viewer에 기본 UI를 연결했습니다. 확대/회전/밝기/돋보기·테마·목록 탐색을 지원합니다. 실제 로그인 공급자가 없으면 파일 열기는 차단됩니다.

## 외부 프로그램

`img2tile` 기본 경로는 `D:/DG_module/img2tile/img2tile.exe`입니다.
`IMG2TILE_EXECUTABLE` 환경변수로 경로를 바꿀 수 있습니다. 기본 비활성 상태이며,
IMAGEGATE_CONVERSION_ENABLED=true 또는 conversion.enabled=true일 때만 어댑터를 등록합니다. 합성 TIFF로 PNG/JPG 출력·페이지 매핑을 검증했습니다.
제한 실행기와 OS 차원의 격리 범위는 [변환 실행기](docs/CONVERSION_EXECUTION.md)를 참고하세요. 내부 프로그램 사용은 사용자 확인으로 진행합니다.

ImageMagick은 초기 의존성·실행 파일·자동 대체 경로에서 제외합니다.
향후 동일한 변환기 인터페이스를 구현해 명시적으로 추가할 수 있습니다.
FFmpeg/FFprobe는 LGPL shared 빌드·실행 파일 해시·버전을 검사하는 별도 어댑터로 연동했습니다. 기본 비활성이며 [재생 설정](docs/PLAYBACK.md)에서 활성화합니다.
외부 실행 파일·DLL·실제 미디어·비밀정보는 저장소에 포함하지 않습니다.

## 디렉터리

```text
src/main/java/co/kr/satu/imagegate/
  system/{controller,service,util,config}
  security/{controller,service,util,config}
  media/{controller,service,util,domain}
  viewer/{controller,service,util}
  editing/{controller,service,util,domain,repository,config,exception}
  playback/{controller,service,util,domain,repository,config,exception}
  conversion/{controller,service,util,config,domain,repository,exception}
src/test/            기능·역할별 테스트
config/              설치 YAML 예제
scripts/             설치 시작 스크립트
docs/                설치·구조·요구사항·개발 순서
```

작업 순서는 [개발 계획](docs/DEVELOPMENT_PLAN.md), 확정 요구사항은
[요구사항](docs/REQUIREMENTS.md), 의존성 정책은 [오픈소스 정책](docs/OPEN_SOURCE_POLICY.md)을 참고하세요.
형식별 분류·미리보기·원본 좌표 편집·타일·재생의 현재 범위와 미지원 실패 계약은 [지원표](docs/FORMAT_SUPPORT.md)를 참고하세요. 헤더 인식만으로 열람 지원을 보장하지 않습니다.

## 초기 구성 검증

- JDK 21.0.2 + Gradle Wrapper 8.14.5에서 clean build --write-locks 성공.
- 기동/health, 비인증 미디어 접근 차단/보안 헤더, CSRF 보호 테스트 3개 통과.
- Java 클래스 버전 65(Java 21), 실행 가능한 Boot JAR 생성 확인.
- Wrapper 배포본 SHA-256 고정, 해석된 의존성은 gradle.lockfile에 기록.
- 미디어 처리·인증 연동·UI 구현 및 전체 의존성 라이선스 검토는 후속 단계입니다.

## API 계약·변환기 사전 점검

- [API 계약 초안](docs/API_CONTRACT.md): 인증 경계·요청·응답·오류·전송 규칙. 파일 등록·메타데이터·미리보기 상태/콘텐츠와 재생 준비/Range API를 구현했습니다.
- ViewerSessionRequest와 실제 미디어 메타데이터 기반 MediaPositionValidator를 추가했습니다.
- [img2tile 점검](docs/IMG2TILE_ASSESSMENT.md): 도움말·종료 코드와 합성 TIFF의 PNG/JPG 변환·페이지 매핑을 확인했습니다. 실제 업무 샘플·서비스 성능은 후속 검증 대상입니다.
- img2tile은 사용자 확인에 따라 팀 내부 프로그램으로 사용 검증 중입니다. 검증 완료 전 자동 변환은 비활성입니다.

## 변환 어댑터 구현

[실행 구조·제한·활성화·테스트](docs/CONVERSION_EXECUTION.md)를 참고하세요.
기본 테스트는 외부 모듈 없이 실행하며, -Pimg2tileIntegration으로 Windows 실제 모듈 테스트를 추가합니다.

## 설치 설정 및 패키지 규칙

[설치 YAML 예제](config/imagegate.example.yml)에서 기능·저장 경로·모듈·실행 파일·제한을 선택합니다.
설치 안내는 [INSTALLATION](docs/INSTALLATION.md), 패키지 규칙은 [ARCHITECTURE](docs/ARCHITECTURE.md)를 참고하세요.
설치 ZIP은 최종 기능 구현과 전체 검증·테스트가 완료된 후에만 생성합니다. 개발 중에는 build/test만 실행합니다.

## 미디어 저장·접근 통제

구현 API·인증 연동 경계·소유자 정책·저장 구조는 [미디어 보안](docs/MEDIA_SECURITY.md)을 참고하세요.
이미지 등록 후 첫 페이지를 비동기 검증하고 완료된 PNG만 보호된 소스 API로 제공합니다. 원본 직접 제공은 미구현입니다.
작업 상태·캐시 키·재시작 후 명시적 재시도·남은 운영 제한은 [미리보기 파이프라인](docs/PREVIEW_PIPELINE.md)을 참고하세요.

## 뷰어 세션·페이지

[세션·페이지 API](docs/VIEWER_SESSIONS.md): 파일 순서·초기 위치·테마·좌측 패널, 사용자별 만료 세션, 전체 파일 권한/원본 버전 재검사, 페이지 미리보기 정보.
검증된 JDK 형식의 원본 편집 좌표·EXIF 방향 보정은 [원본 좌표계](docs/IMAGE_GEOMETRY.md)에 구현했습니다. 외부 모듈의 원본 매핑·전체 페이지 수는 후속 단계입니다. 설치 ZIP은 최종 검증 후 생성합니다.

## 기본 UI

/viewer에서 기본 뷰어를 엽니다. 기존 세션은 ?sessionId=<UUID>, 등록 파일은 ?mediaId=<UUID>로 연결할 수 있습니다.
구현 범위·로그인 연동·CSRF·브라우저 검증은 [뷰어 UI](docs/VIEWER_UI.md)를 참고하세요. 메모·사용자 스탬프의 클릭 배치·수정·저장은 [편집 API](docs/ANNOTATIONS_STAMPS.md)를 참고하세요. 선택 영역 자르기·PNG 저장은 [자르기 API](docs/CROP_EXPORT.md)를 참고하세요. 메모·스탬프·밝기·회전을 적용한 저장은 [편집 반출](docs/EDITED_EXPORT.md)을 참고하세요. 영상·음성 재생과 탐색 막대는 [재생 설정/API](docs/PLAYBACK.md)를 참고하세요. 저장된 메모·스탬프의 [실행 취소/다시 실행](docs/EDIT_HISTORY.md)을 지원합니다.

## 이미지·재생 캐시 운영

[이미지·재생 캐시 자동 정리](docs/CACHE_MAINTENANCE.md): 보관 기간·용량 목표·정리 주기, 전송/변환 수명 보호, 비정상 파일 보존, 재선택 시 재생성. 원본/조직별 하드 할당량은 후속 단계입니다.

## 성능 기준선 측정

[성능 측정 방법](docs/PERFORMANCE.md): 별도 performanceTest로 합성 JPEG/PNG/TIFF 최초 준비·완료 캐시·동시 요청의 P50/P95/처리량/힙·GC를 기록합니다. 운영 SSO/네트워크/브라우저 성능과 구분합니다. 일반 build에서는 실행하지 않습니다.

## 전용 썸네일

[전용 이미지 썸네일](docs/THUMBNAILS.md): 검증한 래스터에서 기본 256px PNG 생성, 보호된 GET/HEAD, 목록 지연 로딩, 미리보기와 함께 용량 합산/정리. 설정의 사용 여부와 최대 변 길이를 조정할 수 있습니다.

운영 감사 로그의 설정·기록 범위·보관 한계는 [AUDIT_LOGGING.md](docs/AUDIT_LOGGING.md)를 참고하세요.

파일 등록 동시 처리·파일 크기·디스크 여유 공간 설정은 [UPLOAD_LIMITS.md](docs/UPLOAD_LIMITS.md)를 참고하세요.

런타임 JAR·POM·고지문 해시와 실제 실행 JAR 포함 목록은 [런타임 라이선스 검증](docs/RUNTIME_LICENSES.md)을 참고하세요. check/build에서 미식별·변경된 구성요소를 차단하며, 최종 배포 고지/외부 DLL 검토는 별도입니다.

실제 루프백 Tomcat HTTP의 최초 준비·본문/썸네일·동시 조회 검증은 [HTTP 성능 측정](docs/HTTP_PERFORMANCE.md)과 [측정 기준선](docs/HTTP_PERFORMANCE_BASELINE.md)을 참고하세요. 테스트 인증은 운영 JAR에 포함하지 않습니다.

비정상 종료 후 알려진 캐시 잔재의 기본 점검/명시적 정리는 [오프라인 유지보수](docs/OFFLINE_MAINTENANCE.md)를 참고하세요. 서버·고아 변환기 중지와 기존 캐시 잠금이 필요하며 자동 정리/웹 API로 실행하지 않습니다.

- [백업·복구 절차와 저장소 리허설](docs/BACKUP_RECOVERY.md)

- [읽기 전용 원본 무결성 점검](docs/ORIGINAL_INTEGRITY.md)

- [운영 프록시 선택 예제·사전 점검·인수 절차](docs/REVERSE_PROXY.md) — IntelliJ 개발 실행에는 불필요

- [조직별 원본 누적 용량 제한](docs/ORIGINAL_QUOTA.md) — 설치 옵션·동시 예약·재시작 재계수

- [원본 업로드 잔재 점검](docs/UPLOAD_RESIDUE_INSPECTION.md) — 읽기 전용 오프라인 목록·잠금·변경 검사

- [주석·스탬프 참조 점검](docs/EDITING_REFERENCE_INSPECTION.md) — 원본/버전/스탬프 소유 참조와 비공개 오프라인 결과

- [백업 manifest 읽기 전용 대조](docs/BACKUP_MANIFEST_VERIFICATION.md)

- [복구 오프라인 통합 점검](docs/RECOVERY_ACCEPTANCE.md)

- [외부 변환 프로세스 실행 경계](docs/EXTERNAL_PROCESS_SECURITY.md)

- [외부 모듈 기준선·변경 감지·재승인](docs/NATIVE_MODULE_APPROVAL.md)
