# 패키지 구조 및 관리 규칙

최종 게시·응답 권한 재검사는 conversion/service.ImagePreviewRenderer 및 playback/service.PlaybackService와 editing/service.CropService가 담당합니다. 각 캐시는 service의 PublicationCheck를 ready metadata 기록 직전에 호출하고 실패한 시도의 이동 산출물을 정리합니다. repository에서 사용자/업무 정책을 해석하지 않습니다. PUBLICATION_AUTHORIZATION.md의 hook/호환 overload·잠금/최신성 범위를 따릅니다.

변환 용량 부족의 분류는 conversion/service.TileQueue와 기존 PreviewQueue가 담당하고, 고정 실패 상태 저장은 conversion/repository.TileJobStore/PreviewCache가 담당합니다. PreviewMetrics는 conversion_busy를 별도 고정 outcome으로 집계합니다. viewer.js/viewer-tiles.js는 고정 안내문·명시적 재시도·화면 수명만 관리하며 서버의 용량/권한 정책을 우회하지 않습니다. NATIVE_CAPACITY_RETRY.md를 따릅니다.

성능 검증의 `media/controller.MixedMediaPerformanceTests`는 실제 HTTP API·공유 native 실행 용량·합성 미디어의 혼합 조회를 측정하는 테스트 전용 코드입니다. Gradle mixedMediaPerformanceTest에서만 실행하며 운영 인증/컨트롤러/설치 설정을 추가하지 않습니다. 측정 계약과 해석 범위는 MIXED_MEDIA_PERFORMANCE.md를 따릅니다.

기능을 먼저 구분하고 각 기능 안에서 역할을 구분합니다.
새 기능을 전역 controller/service/util 패키지에 모으지 않습니다.

~~~text
co.kr.satu.imagegate
├─ ImageGateApplication
├─ system
│  ├─ controller
│  ├─ service          FeaturePolicy
│  ├─ util
│  └─ config           ImageGateProperties
├─ security
│  ├─ controller
│  ├─ service
│  ├─ util
│  └─ config           SecurityConfiguration
├─ media
│  ├─ controller
│  ├─ service
│  ├─ util
│  └─ domain           MediaType
├─ viewer
│  ├─ controller
│  │  └─ dto           ViewerSessionRequest
│  ├─ service          MediaPositionValidator
│  └─ util
└─ conversion
   ├─ controller
   ├─ service          ImageConverter
   │  └─ img2tile      Img2TileConverter
   ├─ util
   │  └─ process       ProcessExecutor / BoundedProcessExecutor
   ├─ config           ConversionConfiguration
   └─ exception        ConversionException
~~~

## 역할

- controller: HTTP 요청·응답·입력 형식 검증·상태 코드 변환. 비즈니스 로직과 직접 파일/프로세스 처리는 넣지 않음.
- controller.dto: 기능별 API 요청·응답 모델.
- service: 해당 기능의 업무 처리, 권한/설치 정책 적용, 트랜잭션·작업 조정, 변환기 SPI/어댑터.
- util: 해당 기능의 기술 보조 처리. 업무 정책·사용자 권한 결정·HTTP 의존성은 넣지 않음.
- domain: 기능의 도메인 모델·상태.
- config: 해당 기능의 Spring 구성·설정 바인딩.
- exception: 해당 기능의 예외·오류 분류.
- repository: 로컬 파일·DB 등 저장소를 필요한 기능 내부에 생성. 컨트롤러에서 직접 호출하지 않음.

controller → service → 해당 기능의 util/도메인/저장소 방향을 유지합니다.
service와 util에서 controller/DTO를 무분별하게 참조하지 않도록 합니다.
현재 ViewerSessionRequest 기반 위치 검증은 API 경계 검증이며, 업무 서비스 도입 시 별도 위치 값 객체로 분리합니다.
기능 간 호출은 service의 명확한 계약을 통해 수행합니다.
아직 클래스가 없는 controller/service/util 패키지는 package-info.java로 역할을 명시합니다.
비어 있는 기능에 가짜 API·의미 없는 서비스 구현을 추가하지 않습니다.
테스트 패키지도 대상 기능·역할 구조와 일치시킵니다.

## 설치 정책과 보안

system.service.FeaturePolicy는 설치 기능 사용 여부를 계산합니다.
security는 인증·권한을 관리하고, 각 업무 service는 설치 정책과 사용자 권한을 함께 검사합니다.
YAML의 기능 활성화 값은 사용자 권한 부여나 미구현 기능 제공을 의미하지 않습니다.

## 미디어 기반 추가

- security.domain.ViewerPrincipal / security.service.CurrentActor: 신뢰된 인증 주체.
- media.controller: 등록·메타데이터 조회·오류 응답.
- media.service: 설치 기능과 파일별 접근 정책 적용.
- media.repository: MediaStore SPI와 LocalMediaStore.
- media.util: 헤더 후보 분류. 전체 디코딩 검증은 워커 단계.
- media.config: 로컬 저장소와 기본 소유자 정책 등록.

## 미리보기 처리 추가

- media.controller.MediaPreviewController: 상태·접수·PNG 콘텐츠 HTTP 처리.
- media.service.MediaPreviewService: 요청마다 파일 권한 재검사·등록 후 비동기 접수 조정.
- conversion.service: PreviewQueue(제한 큐), PreviewRenderer SPI, ImagePreviewRenderer(검증·재인코딩).
- conversion.repository.PreviewCache: 비공개 변환본·원자적 상태 저장.
- conversion.domain.PreviewStatus / conversion.util.PreviewKeys: 상태 값·버전별 캐시 키.
- conversion.config.PreviewConfiguration: 설치 옵션과 변환 모듈 연결.

컨트롤러가 저장 경로를 생성하거나 변환 프로세스를 직접 실행하지 않습니다.

## 뷰어 세션 추가

- viewer.controller: 세션 생성/조회/삭제와 HTTP 입력 검증·응답 매핑.
- viewer.service.ViewerSessionService: 전체 파일 접근·버전 확인, 선택 페이지만 접수.
- viewer.domain: ViewerOpenOptions, ViewerSession, ViewerSessionView.
- viewer.repository.ViewerSessionStore: 개수 제한·고정 만료를 가진 메모리 상태.
- viewer.config/exception: 설정 연결·세션 오류 분류.
- media.controller/dto: 페이지 상태·표시 좌표·PNG 소스 매핑.

서비스는 API DTO 대신 도메인 값으로 작업하며 세션의 비공개 원본 레코드를 직접 JSON으로 반환하지 않습니다.

## 기본 UI 추가

- viewer.controller.ViewerPageController: 데이터가 없는 /viewer HTML 셸 제공.
- viewer.controller.ViewerBootstrapController: 인증된 도구 설정과 CSRF 토큰 제공.
- viewer.service.ViewerCapabilitiesService: 설치 정책과 실제 구현 도구의 교집합.
- resources/static/viewer: HTML/CSS/네이티브 ES module. viewer-core는 좌표·확대·소스 검증 계산.
- scripts/test-viewer.mjs: 개발 전용 합성 API/PNG로 실제 Chromium 동작·캡처 검증.

브라우저는 원본 저장 경로를 알지 못하며 미디어/페이지 서비스 API만 호출합니다.

## 편집 기능 패키지

editing/controller는 주석·스탬프 요청/응답 및 입력 상한 필터, service는 파일 권한·원본 좌표·revision 정책, repository는 전용 디스크 스냅샷·개인 스탬프 저장소, util은 경로 검사·이미지 정규화를 담당합니다. domain/config/exception도 editing 내부에 둡니다. 상세 계약은 ANNOTATIONS_STAMPS.md를 참고하세요.

editing/controller.CropController는 PNG 첨부 응답 전송만 담당하고, editing/service.CropService는 권한·원본 버전/무결성·상한·임시 작업·슬롯 수명을 관리합니다. editing/util.CropRaster는 JDK 래스터의 방향 보정·자르기·제한 PNG 인코딩을 담당합니다. 스트림은 서비스 콜백 수명 안에서만 유효하며 전송이 끝난 뒤 정리합니다.

편집 반출은 editing/controller.EditedExportController → 공통 CropService 저장/스트리밍 경로로 연결합니다. AnnotationService가 권한과 revision을 검사한 서버 스냅샷을 제공하고, StampStore가 내부 전용 전체 해상도 PNG를 검증해 로드합니다. editing/util.EditRaster가 밝기·메모·스탬프·회전만 수행합니다. 브라우저 주석 배열/HTML/스탬프 URL을 합성 입력으로 받지 않습니다.

## 재생 기능 패키지

playback/controller는 준비/상태/HTTP Range·HEAD를 처리하고 controller/dto는 공개 재생 정보를 매핑합니다. playback/service는 파일 권한·선택 파일 지연 접수·제한 큐·게시/스트림 수명 및 FFmpeg 고정 레시피를 담당합니다. repository는 비공개 상태/불변 재생 캐시, util은 단일 바이트 구간·안전 저장 경로, config는 모듈·캐시 구성, domain/exception은 기능 내부 값과 오류를 관리합니다. 미디어와 뷰어 세션은 재생 서비스 계약을 호출하며 브라우저는 원본 경로/명령을 알지 못합니다.

playback/repository.PlaybackCache는 불변 재생본의 참조 수명·사용 시각·보수적 비재귀 퇴거를 관리합니다. playback/service.PlaybackService는 변환/전송 수명과 전용 주기 작업의 시작·종료를 조정합니다. 설치 옵션은 system/config.ImageGateProperties.PlaybackCachePolicy에 두며 CACHE_MAINTENANCE.md의 범위/상한을 따릅니다.

이미지 캐시는 conversion/repository.PreviewCache가 고정 잠금·사용 수명·보관/용량 정리를 담당합니다. conversion/repository.PreviewCache는 writer lock의 수명을 기존 lease와 공유 수명 가드로 보호하고 conversion/service.PreviewQueue는 lock 확보 요청·Job 소유권·변환/정리 스케줄러를 관리하고 media/service.MediaPreviewService가 최신 권한을 재확인한 동기 콘텐츠 콜백을 제공합니다. controller가 파일 경로를 받아 지연 Resource로 반환하지 않습니다.

성능 검증은 src/test의 media/controller.ViewerPerformanceTests에 두고 실제 API·보안 경로를 호출합니다. performance 태그/별도 Gradle 작업으로 격리하며 운영 소스에 테스트 인증/벤치마크 API를 추가하지 않습니다.

썸네일은 conversion/util.ThumbnailRaster가 검증된 미리보기 래스터에서 생성하고 ImagePreviewRenderer.Result/PreviewStatus로 내부 결과를 전달합니다. PreviewCache는 두 산출물을 한 상태로 게시/검증/정리하며, 기존 미디어 서비스의 권한/수명 콜백을 통해 제공됩니다. 목록은 thumbnail 소스 전용 URL 검증을 사용합니다.

실행 취소 기록은 editing/repository.AnnotationHistoryStore가 actor/파일 버전/페이지별 제한된 항목 차이·커서·TTL/개수를 관리합니다. AnnotationService가 CRUD/undo/redo와 저장소 기록 순서를 직렬화하고 기존 AnnotationStore의 원자적 revision 검사를 유지합니다. AnnotationController는 과거 항목을 입력받지 않고 문서와 가능한 조작 상태를 매핑합니다. 공개 응답에 stampOwner를 포함하지 않습니다.

audit/config.AuditRequestFilter는 SecurityContextHolderFilter 뒤에서 허용된 변경/반출 라우트의 동기 HTTP 결과만 기록합니다. 필터 자동 중복 등록은 비활성화합니다. audit/service.AuditEvents는 고정 필드와 허용 패턴을 적용하며 변환 큐는 별도의 JOB_FAILED를 발행합니다. Logback 전용 회전 파일은 일반 콘솔 로그와 분리합니다.

media/service.UploadAdmission은 신뢰 actor별/서버 전체 원본 등록 슬롯의 접수·해제를 관리하며 대기 큐를 만들지 않습니다. MediaService가 스트림을 열기 전에 접수하고 try-with-resources로 등록 전체 수명을 보호합니다. LocalMediaStore는 설치 파일 크기·실제 원본 볼륨 여유 공간 검사와 미게시 파일 정리를 담당합니다. 조회·재생에는 슬롯을 적용하지 않습니다.

빌드 시 라이선스 수집/검증은 gradle/license-audit.gradle에 두며 운영 API/패키지/런타임 의존성을 추가하지 않습니다. 고정 식별 기준선은 config/oss에, 생성된 재검증 결과·추출 고지문은 build/reports/licenses에 둡니다. 기술적 식별 검증과 최종 배포 준비 완료를 구분합니다.

HttpViewerPerformanceTests는 src/test의 opt-in 실제 HTTP 성능 fixture입니다. 기존 합성 fixture/수집기를 재사용하되 모든 요청/CSRF/응답은 루프백 Tomcat과 JDK HttpClient로 처리합니다. 테스트 신뢰 주체 필터는 테스트 문맥의 기존 보안 체인에만 삽입하며 운영 코드에 등록하지 않습니다. SecurityConfiguration의 AccessDeniedHandler는403을 직접 반환하여 컨테이너 오류 디스패치의 상태 덮어쓰기를 피합니다.

maintenance/controller.MaintenanceCommand는 오프라인 인자/JSON/종료 코드, service.CacheMaintenanceService는 명시적 실행 시각·조정, repository.CacheOrphanRepository는 기존 잠금·고정 구조·스냅샷·제한된 파일 작업을 담당합니다. domain은 검증된 옵션과 보고서입니다. Spring 빈/웹 컨트롤러로 등록하지 않습니다. ImageGateApplication은 첫 인자가 --maintenance일 때에만 CLI를 실행하고 Spring 서버를 시작하지 않습니다.

maintenance/repository.RecoveryRehearsalTests는 운영 백업 구현을 추가하지 않는 교차 저장소 복구 회귀 테스트입니다. 중지한 합성 원본/주석/스탬프를 새 경로로 복사해 실제 저장소로 다시 읽고 식별자·바이트·참조·revision 및 누락 실패를 확인합니다. 운영 중지/백업/격리 복원 인수는 BACKUP_RECOVERY.md에 정의합니다.

maintenance/controller.OriginalIntegrityCommand는 --maintenance --verify-originals의 인자/JSON/종료 코드를 담당하고, service.OriginalIntegrityService는 Clock과 저장소 실행을 조정합니다. repository.OriginalIntegrityRepository는 기존 원본의 고정 깊이 탐색/카탈로그 검증/상한 내 스트리밍 해시와 전후 변경 검사를 담당합니다. domain의 옵션/보고서와 함께 관리하며 Spring 빈·HTTP API·설치 설정 자동 import·복구 쓰기를 추가하지 않습니다.

media/config.UploadIngressConfiguration은 원본/스탬프 설치 상한으로 공유 MultipartConfigElement를 구성하고 수신 임시 경로의 canonical 경계/저장소 중첩을 확인합니다. system/config.ImageGateProperties.Upload에 경로/요청 여유를 바인딩하며 개별 stream 정책은 기존 service/repository에서 유지합니다. media/controller.MultipartExceptionHandler는 컨트롤러 선택 전 MaxUploadSizeExceededException만 전역 처리합니다. 실제 수신 검증은 media/controller.UploadIngressHttpTests의 루프백 Tomcat/JDK HttpClient/테스트 전용 인증 필터로 수행하며 운영 인증 구현을 추가하지 않습니다.

media/service.MultipartAdmission은 대기 큐/사용자 식별 상태 없는 단일 JVM 수신 semaphore와 중복 해제 방지 lease를 관리합니다. media/config.MultipartIngressFilter는 본문/파트를 읽지 않고 모든 multipart 요청을 접수·고정429로 거절하며 동기 finally 또는 비동기 완료에서 lease를 해제합니다. UploadIngressConfiguration이 빈과 비활성 Servlet 등록을 제공하고 SecurityConfiguration이 CSRF 앞에 한 번 삽입합니다. 외부 감사 필터의 수명 안에 두어 HTTP 결과를 기록할 수 있게 합니다. 원본 복사의 UploadAdmission과 역할을 구분합니다.

media/config.UploadReadTimeoutConfiguration은 내장 Tomcat HTTP/1.1 커넥터의 별도 본문 읽기 유휴 타임아웃을 설정하고 HTTP/2 백엔드 정책 미지원은 시작 실패로 처리합니다. media/util.UploadReadTimeouts는 제한된 원인 타입 탐색만 담당합니다. controller.MultipartExceptionHandler의 MVC 응답과 ERROR 전용 config.UploadTimeoutErrorFilter는 확정된 multipart SocketTimeout만 고정408로 매핑합니다. ERROR 필터는 내부 접수 표식/상태/원인을 확인해 상수 응답으로 끝내며 다른 요청은 기존 체인에 넘깁니다. 일반 /error 허용이나 인증 우회를 추가하지 않습니다. 실제 검증은 media/controller.UploadReadTimeoutHttpTests의 별도 루프백 커넥터·부분 임시 파일·raw HTTP 소켓으로 수행합니다.

config/proxy는 운영 선택 예제이며 기본 런타임 자동 import/배포 대상으로 추가하지 않습니다. scripts/test-proxy-config.mjs는 명시적 경로의 native syntax-only 실행/고정 JSON 결과만 제공하고 서비스 시작/재시작은 하지 않습니다. media/controller.ProxyForwardingHttpTests는 실제 예제 YAML/내장 Tomcat으로 신뢰된 loopback의 프로토콜 전달·신뢰하지 않는 peer/위조 사용자 헤더 경계를 검사합니다. 테스트 인증 fixture는 src/test 안에 유지합니다.

media/service.TenantOriginalQuota는 원본 등록 중 조직별 사용/예약과 중복 해제·계수 불확실성 차단 정책을 관리합니다. LocalMediaStore는 그 정책에 실제 저장량을 제공하고 각 쓰기 전 예약·원자 게시 후 확정·미게시 파일 정리 후 해제 순서를 유지합니다. quota 활성 writer의 수명 잠금과 제한된 고정 깊이 스캔은 저장소가 담당합니다. MediaConfiguration은 설치 옵션과 저장소 종료 수명을 연결하고 MediaExceptionHandler는507 계약을 매핑합니다. 조회 경로는 계수 정책을 호출하지 않습니다.

maintenance/controller.UploadResidueCommand는 --maintenance --inspect-upload-residue의 엄격한 인자/JSON/종료 코드를 담당하고 service.UploadResidueService가 Clock과 실행을 조정합니다. repository.UploadResidueRepository는 고정 깊이 속성 탐색·기존 quota marker READ/shared 잠금·예산/전후 변경 검사를 담당합니다. domain.UploadResidueOptions/Report는 검증된 옵션과 비공개 결과 계약입니다. 파일 내용/카탈로그 읽기·삭제·Spring 빈/HTTP 경로·설치 설정 자동 import는 추가하지 않습니다.

maintenance/controller.EditingReferenceCommand는 읽기 전용 --verify-editing-references의 옵션/결과/종료 코드를 담당하고 service.EditingReferenceService가 Clock과 실행을 조정합니다. repository.EditingReferenceRepository는 고정 깊이 스탬프/주석 탐색·제한된 metadata 판독·현재 원본/버전/기록된 스탬프 소유 참조·전후 스냅샷 검사를 담당합니다. util.ReadOnlyStorageLock은 기존 marker만 READ/shared로 잠그는 기술 보조이며 파일 생성/변경은 하지 않습니다. 기존 runtime 저장소를 생성해 검사하지 않으며 Spring 빈/HTTP 경로·이미지 디코딩·복구/삭제를 추가하지 않습니다.

복구 리허설은 writer 재개방 전 OriginalIntegrityRepository/EditingReferenceRepository를 복원 경로에 실행하고, 테스트 전용 메모리 파일 목록·SHA-256과 함께 누락/변조/혼합 버전·부분 검사를 검증합니다. 테스트 복사 보조 코드는 기존/중첩 목적지를 거절하며 운영 manifest·자동 복원 API로 노출하지 않습니다.

maintenance/controller.BackupManifestCommand는 --verify-backup-manifest의 엄격한 옵션/JSON/종료 코드, service.BackupManifestService는 Clock과 실행 조정, repository.BackupManifestRepository는 외부 고정 크기 TSV/신뢰 해시·완전한 부모 목록 검증과 세 저장소의 제한된 NOFOLLOW 탐색/스트리밍 해시/기존 READ 잠금/전후 스냅샷을 담당합니다. domain은 옵션/비공개 결과이며 운영 manifest 생성·서명·복원/삭제·Spring 빈/HTTP API는 추가하지 않습니다. BackupManifestFixture 생성기는 src/test 전용입니다.

maintenance/service.RecoveryAcceptanceService는 같은 util.ReadOnlyStorageScope 수명 내 manifest→원본→참조→manifest를 순차 실행하고 첫 불일치/부분 검사에서 중단합니다. controller.RecoveryAcceptanceCommand는 CLI/집계 JSON/종료 코드, domain.RecoveryAcceptanceOptions/Report는 단계별 상한과 비공개 결과를 담당합니다. ReadOnlyStorageScope는 root-bound·thread-bound 기존 READ/shared 잠금과 root 스냅샷을 보유하며 각 저장소의 inspectWithinScope는 살아 있는 정확한 범위만 재사용합니다. 독립 명령의 기본 검사도 이 범위를 소유하고 API/설치 설정·복구 쓰기는 추가하지 않습니다.

OriginalIntegrityRepository의 단독 inspect는 기존 원본 marker를 READ/shared로 소유하고, 통합 inspectWithinScope는 살아 있는 ReadOnlyStorageScope.verifyOriginals 계약으로 원본 root/수명을 검증해 같은 비공개 코어를 호출합니다. 코어는 정상 tenant/검증 항목의 속성·고정 카탈로그 해시를 상한 내 보관하고 종료 재검사를 수행합니다. OriginalIntegrityCommand는 사용 중인 writer를4, 잘못된 marker/종료 변경·사용 불가를5로 고정 변환합니다. 별도 JVM 잠금 fixture는 src/test에만 둡니다.

conversion/util/process.ProcessExecutionBoundary는 정규 절대 실행/작업 경로의 NOFOLLOW 정체성과 생성 환경을 담당합니다. BoundedProcessExecutor가 명령 스냅샷/크기·실행 슬롯/기한/출력/정리를 소유하며 시작 직전/종료 후 경계를 재검사합니다. 임의 환경 설정을 controller에서 받지 않으며 기존 img2tile·FFmpeg SPI 계약은 유지합니다. 환경 주입/수명 fixture는 src/test 전용입니다.

conversion/util/process.NativeModuleSnapshot은 선택 EXE/인접 라이브러리/선택 resource의 상한 내 불변 목록·속성·SHA-256·내용 식별자와 사용 불가 latch를 관리합니다. img2tile 어댑터와 FfmpegEngine이 시작/작업 전후 내용 및 호출 전후 속성 검사를 수행하고, 스냅샷 식별자를 각 시작 캐시 엔진 키에 포함합니다. 기존 캐시 GET 경로에는 전체 해시를 추가하지 않습니다. 설치 모듈 자동 갱신/서명 검증/OS 격리는 추가하지 않으며 새 baseline은 새 인스턴스에서만 구성합니다.

conversion/util.PreviewCancellation은 플래그를 보존하는 기술적 취소 검사만 담당합니다. conversion/service.ImagePreviewRenderer는 권한·원본 검증·변환/디코딩/인코딩 경계와 생성 래스터 수명을 조정하며 ThumbnailRaster는 빌린 preview를 변경/해제하지 않고 자체 래스터만 정리합니다. 큐가 부분 작업 파일 정리와 게시 차단을 소유합니다.

conversion/util.PreviewTiming은 고정 단계·경과 시간과 비활성 span을 제공하는 기술 보조입니다. ImagePreviewRenderer의 일반 생성자는 계측을 비활성화하고, 같은 기능/패키지의 신뢰된 진단 생성자만 관찰자를 받습니다. src/test의 PreviewStressTests가 ThreadLocal 표본 수집·CPU/합계/잔여 시간 보고를 소유하며 API/사용자 입력·설치 설정으로 관찰자를 제공하지 않습니다.

PreviewTiming의 HASH_*는 ORIGINAL_HASH 내부 진단 단계이며 보고서는 부모 합계와 구분합니다. withTimedClose는 기본 경로에서 원본 스트림을 그대로 반환하고 진단 모드에서만 직접 위임하는 close 계측 스트림을 사용합니다. 업무 원본 SHA 일치 판단은 ImagePreviewRenderer가 계속 수행하고 관찰자는 그 판단에 관여하지 않습니다.

ImagePreviewRenderer의 패키지 한정 실험 생성자만 OriginalOpener를 받을 수 있습니다. 모든 생성자는 conversion/service.OriginalOpenAdmission을 통해 설치 preview.original-open 정책을 적용하며 비활성 기본값은 기존 Files.newInputStream 경로를 유지합니다. 허가는 open 반환/실패 직후 해제하고 SHA/권한 검증은 그대로 수행합니다. PreviewRenderer의 기본 close는 무동작이며 PreviewQueue가 실제 renderer의 대기 입구 종료를 소유합니다. OriginalOpenTimeoutException은 ORIGINAL_OPEN_BUSY로 고정 매핑합니다. src/test의 OpenPermitGate는 운영 의존성이 아니며 runtime 성능 모드에서는 워커보다 큰 비제한 계수 도구로만 사용합니다. ORIGINAL_OPEN_POLICY.md를 참고하세요.

PreviewConfiguration은 PreviewRenderer SPI를 일반 빈으로 구성하고 PreviewQueue에 주입합니다. renderer 빈의 자동 destroy는 비활성화하고 큐가 close를 소유합니다. src/test의 media/controller.OriginalOpenPolicyHttpTests는 실제 Tomcat/보안/CSRF/큐/캐시/renderer를 사용하며 conversion/service.ControlledOriginalOpenFixture만 기존 패키지 한정 opener를 제어합니다. 합성 신뢰 주체/권한 철회/지연·중단·실패 제어는 테스트 소스에만 존재하고 운영 HTTP에 노출하지 않습니다. ORIGINAL_OPEN_HTTP_ACCEPTANCE.md를 참고하세요.

conversion/service.PreviewMetrics는 기존 MeterRegistry의 고정32개 meter와 비활성 NONE을 소유합니다. PreviewQueue의 Job claim/해제와 OriginalOpenAdmission의 허가/원본 호출 수명에만 연결하며 API/경로/actor 값을 받지 않습니다. PreviewConfiguration이 설치 preview.metrics-enabled로 선택하고 registry를 주입합니다. 원본 슬롯 반환 후 Timer 기록, 고정 enum 결과와 RuntimeException 기록 격리를 유지합니다. 조회/캐시 전송 계측·공개 관리 경로는 추가하지 않습니다. PREVIEW_METRICS.md를 참고하세요.

conversion/service.PreviewMetricsComparisonTests는 src/test의 opt-in 교차 비교 fixture입니다. 일반 build/test에서 제외되는 preview-metrics-comparison 태그/Gradle 작업을 사용하고 지표 on/off·2워커·실제 renderer/큐/원본 SHA/픽셀/종료 및 writer 재확보를 확인합니다. 성능 보고서/합성 저장소만 build 아래에 생성하며 런타임 API/의존성/설치 기본값을 추가하지 않습니다. PREVIEW_METRICS_COMPARISON.md를 참고하세요.

conversion/domain.TilePyramidLayout은 레벨·행/열·core/stored/originalCore 경계와 총 타일 수를 정수/long으로 계산합니다. 처리/디코딩 승인·픽셀/디스크 한도를 대신하지 않습니다. conversion/service/img2tile.Img2TilePyramidProbeTests는 src/test의 실제 모듈/합성 TIFF 계약 검증이며 네이티브 DZI 선언과 실제 타일 경계·전체 해상도 픽셀/페이지 선택을 대조합니다. 현재 타일 HTTP/큐/캐시 런타임 서비스는 추가하지 않았습니다. TILE_PYRAMID_CONTRACT.md를 참고하세요.


## 타일 산출물 검증

conversion/service/img2tile.Img2TilePyramidValidator는 승인 모듈·신뢰된 원본 프로필과 읽기 전용 산출물 예산/규약 검증을 담당합니다. conversion/util.TilePngVerifier는 제한된 단일 RGB/RGBA8 PNG의 구조·CRC·디코딩을 확인하며, conversion/domain.TilePyramidManifest는 경로 없는 내부 불변 인벤토리입니다. 이 단계는 Spring 빈·공개 DTO·캐시 게시 기능을 추가하지 않습니다. 테스트는 동일 기능/역할 패키지와 실제 img2tile 통합 테스트에 둡니다.


## 타일 정규화·게시 구성 요소

conversion/service.TilePyramidPublisher는 원본 산출물 검증·타일별 정규화·게시 전 재검사를 조정하고, conversion/util.TilePngSanitizer는 새 RGBA8 래스터/기본 PNG metadata로 재인코딩합니다. conversion/repository.TileCache는 별도 비공개 저장소의 writer 잠금·원자적 generation 게시·전송 lease·명시적 퇴거를 관리하며 conversion/domain.TileCacheManifest를 저장합니다. Spring/HTTP/작업 큐는 아직 연결하지 않았습니다. 상세 범위는 TILE_CACHE_PUBLICATION.md를 참고하세요.


## 타일 큐 연결

conversion/config.TileConfiguration은 활성 설정에서만 캐시·상태 저장소·NativeTileRenderer·TileQueue를 구성합니다. system/config.ImageGateProperties.Tiles가 설치 예산을 바인딩하고 conversion/util.TileKeys가 actor/원본 버전·모듈/프로필 키를 만듭니다. conversion/repository.TileJobStore는 영속 상태와 개수 경계를 관리합니다. Img2TileConverter는 기존 실행기를 공유하는 TileNativeConverter도 구현합니다. NativeTileRenderer는 원본/권한·SHA·메타데이터와 native/게시를 조정하며 TileQueue는 제한 접수·기한·취소/종료와 commit gate를 담당합니다. TileWorkCleanup은 알려진 private work만 제한적으로 정리합니다. HTTP 컨트롤러/공개 DTO는 아직 없고 TILE_QUEUE.md를 따릅니다.


## 타일 유지보수

conversion/service.TileMaintenanceService는 배타 writer 아래 기동 중단 상태 복구/재계수와 별도 주기 작업을 조정합니다. TileCache는 pin/최근 사용·TTL/용량/항목 정리, TileJobStore는 스냅샷 기반 기록 제거, TileWorkStore는 durable owner·active set·중단 작업 정리를 담당합니다. TileWorkCleanup은 bounded inventory/바이트 집계도 제공합니다. 설정은 ImageGateProperties.TileMaintenance이며 컨트롤러/임의 경로 삭제 API는 추가하지 않습니다. TILE_MAINTENANCE.md를 참고하세요.


## 타일 HTTP 경계

media/controller.MediaTileController와 명시적 DTO TilePyramidResponse가 준비/상태·PNG GET/HEAD 응답만 담당합니다. media/service.MediaTileService가 CurrentActor/현재 파일 권한·버전·설치 정책과 스트림 직전 재검사를 조정하며 conversion/service.TileQueue의 view/content 계약을 사용합니다. 컨트롤러/미디어 서비스가 cache repository나 파일 경로를 직접 열지 않습니다. TileView/MediaTileView는 내부 값이며 공개 JSON으로 직접 반환하지 않습니다. TileAdmissionException/TileUnavailableException은 접수·부재 오류를 고정 HTTP 코드로 매핑하는 근거이고 TileRequestExceptionHandler는 해당 컨트롤러의 타입 오류만 처리합니다. 감사는 기존 audit/config 필터·고정 이벤트에 연결했습니다. TILE_HTTP_API.md를 참고하세요.


## 고해상도 뷰어 레이어

static/viewer/viewer-tiles.js가 페이지/context 수명·제한 metadata/PNG 접수·DOM/Blob URL·수동 retry를 관리하고 viewer-core.js의 순수 manifest/타일 기하/viewport 계산을 사용합니다. viewer.js는 기존 surface 변환·렌즈와 페이지 수명만 연결합니다. viewer/service.ViewerCapabilitiesService가 설치 tiles/image 기능의 교집합을 전달하고 보안 설정은 새 정확한 정적 파일만 허용합니다. 서버 편집 준비/권한이나 원본 geometry는 클라이언트에서 확장하지 않습니다. TILE_VIEWER_UI.md를 따릅니다.

## 승인 TIFF 편집 좌표 연결

media/service.MediaPreviewService는 선택적 TileQueue의 현재 actor/원본 버전/페이지 ready manifest를 읽고 미리보기 비율을 대조하여 ImageGeometry를 보완합니다. 임의 클라이언트 좌표나 cache 파일 경로를 신뢰하지 않으며 작업 접수는 하지 않습니다. AnnotationService/CropService는 기존 page 검증 경로를 계속 사용합니다. viewer.js는 타일 완료 후 서버 page 응답의 동일 source/크기/변환을 검사하여 편집기에 전달합니다. 기존 원본 좌표 페이지의 초안은 타일 재시도로 초기화하지 않습니다. NativeViewerIntegrationTests와 scripts/test-native-viewer.mjs는 opt-in 실제 native/HTTP/Chrome 검증이며 테스트 인증은 운영 JAR에 포함하지 않습니다. TILE_VIEWER_UI.md를 따릅니다.

## 타일 준비 진단과 게시 증거

conversion/util.TileTiming은 고정 단계/경과 시간만 전달하는 선택 생성자 관측자이며 운영 구성에서는 비활성입니다. NativeTileRenderer/TilePyramidPublisher/TileCache가 해당 역할의 구간을 측정합니다. Img2TilePyramidValidator.PublicationProof는 최초 전체 검증의 비공개 불변 증거로 발급 validator/경로/Source/inventory/바이트 해시에 묶입니다. publisher는 증거를 이용한 모든 경계/바이트 재검사 후 게시하며, 외부 DTO/입력으로 사용하지 않습니다. TilePngVerifier.decode가 검증한 래스터의 소유권을 Sanitizer에 넘겨 같은 바이트의 중복 디코딩을 줄입니다. TILE_PREPARATION_PROFILE.md를 따릅니다.

Img2TilePyramidValidator의 선택 관측자는 native inventory/속성/canonical/바이트 읽기/SHA/PNG 검증을 구분합니다. TileCache의 파일 도우미는 객체별 관측자로 경계·open/read/write/force/close를 구분하고 TilePyramidPublisher는 정규화 전 원본 검사와 PNG 처리를 나눕니다. TileTiming.withTimedClose는 실제 Closeable 수명/예외 억제를 보존합니다. 성능 테스트는 render 밖 생성자/픽셀 검증을 기록에서 제외하고 정해진 IO 호출 수를 대조합니다. TILE_IO_PROFILE.md를 따릅니다.

TilePngSanitizer는 PNG decode/copy/encode/output-check를 선택적으로 구분하고 새 TYPE_INT_ARGB의 전용 버퍼에 getRGB를 채웁니다. decoded/native 버퍼 공유와 메타데이터 복사는 하지 않습니다. 압축 후보/기준 복사 구현은 test의 PngCodecProfileTests에만 있으며 운영 압축은 기본값을 유지합니다. PNG_CODEC_PROFILE.md를 따릅니다.

TilePyramidPublisher의 package-private Normalizer 생성자는 동일 JVM 교차 실험에서만 기준 복사와 전용 버퍼를 연결합니다. 공개 생성자는 항상 표준 sanitizer를 선택하고 모든 입출력/게시 검증을 유지합니다. 기준 구현/할당·GC 수집은 src/test에만 있고 운영 설정/API로 노출하지 않습니다. PNG_CROSSOVER.md를 따릅니다.
