# 설치 및 시스템 설정

최신 기능 범위·실환경 인수 순서·통과 기준은 [운영 인수 계획](OPERATIONAL_ACCEPTANCE.md)을 먼저 확인합니다. 현재 실제 로그인/SSO 공급자와 업무 공유 ACL은 미연동이며, 아래 설치/health 확인만으로 운영 전환을 완료할 수 없습니다.

## 최종 배포 단계의 설치 패키지 생성

아래 패키징은 최종 기능 구현과 전체 검증·테스트가 완료된 뒤에만 수행합니다.
개발 중에는 build/test만 실행하며 설치 ZIP은 생성하지 않습니다.

최종 배포 시 JDK 21 환경에서 실행:

~~~powershell
.\gradlew.bat build installationZip
~~~

build/distributions/imagegate-0.1.0-SNAPSHOT-installation.zip이 생성됩니다.
압축을 풀면 lib/실행 JAR, config/imagegate.yml, bin/start.ps1·start.sh가 있습니다.
외부 모듈·DLL·실제 미디어·비밀정보는 패키지에 포함하지 않습니다.
프로젝트 예제는 config/imagegate.example.yml이며 설치본에서는 imagegate.yml로 이름을 바꿔 포함합니다.

## 설치 시 선택

1. JDK 21과 별도 변환 모듈을 설치합니다.
2. config/imagegate.yml에서 기능·경로·모듈·실행 제한을 선택합니다.
3. 모듈 검증을 마친 환경에서만 conversion.enabled를 true로 변경합니다.
4. Windows: bin/start.ps1 실행. Linux/macOS: sh bin/start.sh 실행.
5. GET /actuator/health 확인. 업무 API는 검증된 ViewerPrincipal이 필요합니다. 실제 업무 인증 공급자는 후속 연동 대상입니다.

시작 스크립트는 설치 폴더를 작업 기준으로 하고 prod 프로필 및 외부 YAML을 적용합니다.
JAVA_HOME이 있으면 해당 Java를 사용하며 없으면 PATH의 Java를 사용합니다. Java 21이어야 합니다.
prod는 Secure 세션 쿠키를 사용하므로 실제 서비스는 HTTPS 구성이 필요합니다.
시작 스크립트는 콘솔 프로세스로 실행합니다. 서비스 등록·백그라운드 운영은 배포 단계에서 구성합니다.

## 설정 항목

| 경로 | 의미 |
| --- | --- |
| server.port/address | 서비스 포트·바인딩 주소. 예제는 8080/127.0.0.1 |
| imagegate.storage.root | 상대 저장 경로들의 기준 폴더 |
| storage.originals-directory | 원본 보관 경로. 절대 경로 또는 root 기준 상대 경로 |
| storage.converted-directory | PNG/JPG 및 작업별 변환 결과 생성 경로 |
| storage.annotations-directory / stamps-directory | 주석 스냅샷 / 사용자 스탬프 전용 경로, 기본 annotations / stamps |
| editing.max-annotations-per-page / max-note-length | 페이지 주석 수 / 메모 길이, 최대 100 / 2000 |
| editing.max-stamps-per-user | 개인 스탬프 수, 최대 100 |
| editing.max-stamp-upload-bytes / max-stamp-pixels | 스탬프 입력 바이트 / 픽셀 상한, 최대 5242880 / 4194304 |
| crop.max-source-pixels | 자르기 원본 픽셀 상한, 기본 16000000 / 최대 40000000 |
| crop.max-output-pixels / max-output-bytes | PNG 사본 픽셀/바이트 상한, 최대 8000000 / 16777216 |
| crop.max-concurrent-jobs | 자르기·다운로드 동시 처리 수, 기본 1 / 최대 2 |
| features.image.enabled | 이미지 기능 전체 사용 여부 |
| features.image.rotation/zoom | 회전·확대/축소 |
| features.image.crop-and-save/brightness | 영역 자르기/저장·밝기 |
| features.image.annotations/magnifier | 메모·어노테이션·돋보기 |
| features.image.stamps/stamp-upload | 스탬프 및 사용자 이미지 업로드 |
| features.image.export-edited | 편집 사본 내보내기 |
| features.video/audio.enabled | 영상/음성 사용 여부 |
| features.video/audio.seek/playback-speed | 탐색 막대·배속 사용 여부 |
| conversion.enabled | 외부 이미지 변환 사용 여부. 기본 false |
| conversion.module | img2tile/none/libvips/imagemagick |
| conversion.modules.{name}.executable | 해당 변환기 실행 파일의 경로 |
| conversion.timeout | 외부 프로세스 제한 시간. 양수~5분 |
| conversion.max-concurrent-jobs | 외부 프로세스 동시 실행 수. 1~8 |
| preview.enabled | 내부 이미지 미리보기 큐 사용 여부. 기본 true |
| preview.workers / queue-capacity | 실행 수 1~4 / 대기 수 1~256, 기본 2 / 32 |
| preview.max-width / max-height | PNG 미리보기 한계 크기 1~4096, 기본 1600 |
| preview.max-source-pixels | JDK 디코딩 원본 픽셀 상한 1~40000000, 기본 16000000 |
| preview.cache-version | 출력 정책 변경 시 교체하는 캐시 버전 |
| viewer.session-ttl | 뷰어 세션 고정 만료, 기본 30m, 1m~2h |
| viewer.max-sessions | 인스턴스 전체 세션 상한, 기본 1000, 1~10000 |
| viewer.max-sessions-per-user | 조직+사용자별 세션 상한, 기본 20, 1~100 |

features/conversion/storage/preview/viewer/editing/crop은 모두 imagegate 아래의 키입니다.
원본·변환·주석·스탬프 네 저장 경로가 같거나 서로 포함되면 시작 실패. 활성 변환기는 실제 경로도 확인해 별칭 중첩을 거부합니다.
상대 경로는 프로세스 작업 폴더 기준 root 아래에서 해석됩니다.
변환 과정의 resource·임시 산출물은 converted-directory의 작업별 하위 폴더에 생성됩니다.
별도 OS 임시 디렉터리와 혼동하지 마세요. 원본 폴더를 웹 정적 경로로 노출하지 않습니다.

상위 enabled=false이면 하위 설정이 true여도 해당 유형의 기능은 모두 비활성입니다.
stamps=false이면 stamp-upload도 비활성입니다.
이 설정은 설치 정책이며 사용자별 권한을 부여하지 않습니다.
FeaturePolicy에서 실효 사용 여부를 계산합니다. 실제 UI/API는 구현하면서 이 정책과 사용자 권한을 함께 적용합니다.
현재 미구현 기능을 true로 해도 아직 화면이나 API가 생기는 것은 아닙니다.

현재 활성화 가능한 변환기는 img2tile뿐입니다.
libvips·ImageMagick은 향후 확장용 경로 예약이며 바이너리·의존성·어댑터를 포함하지 않습니다.
예약 모듈 또는 none으로 enabled=true를 지정하면 시작에 실패합니다. 자동 대체 실행은 없습니다.
활성 모듈 실행 파일이 없거나 경로·설정이 잘못된 경우도 시작 실패합니다.

## 외부 YAML 지정과 변경

개발 중에는 예제를 config/imagegate.yml로 복사하면 자동 로딩합니다.
다른 파일은 IMAGEGATE_CONFIG 환경변수 또는 Spring Boot 명령 인자로 지정할 수 있습니다.

~~~powershell
$env:IMAGEGATE_CONFIG = 'D:/ImageGateConfig/site-a.yml'
.\gradlew.bat bootRun
~~~

JAR 직접 실행 시 필수 설정 파일로 지정:

~~~powershell
java -jar lib/imagegate-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod --spring.config.additional-location=file:D:/ImageGateConfig/site-a.yml
~~~

내장 import는 개발 편의를 위해 optional입니다. 운영 시작 스크립트는 설치 YAML이 없으면 실행을 거부합니다.
환경변수·명령행은 YAML보다 우선할 수 있습니다. 운영에서는 사용하지 않는 과거 환경변수를 제거하세요.
변경 후 재시작해야 합니다. HTTP로 시스템 경로나 실행 모듈을 바꾸는 API는 제공하지 않습니다.
imagegate의 알 수 없는 키(오타), 잘못된 enum·시간·동시성·경로는 시작 단계에서 거부합니다.

기존 conversion.img2tile.enabled/executable 키는
conversion.enabled/module/modules.img2tile.executable로 변경되었습니다.
IMG2TILE_EXECUTABLE 및 내장 기본 설정의 IMG2TILE_ENABLED 환경변수 별칭은 유지합니다.
설치 예제에는 변환 기본값 false가 명시되어 있으므로 설치 시에는 YAML 또는 새 환경변수
IMAGEGATE_CONVERSION_ENABLED=true로 명확히 설정하세요.

## 현재 고정된 처리 상한

입력 100MiB, 출력 32MiB, 미리보기 가로·세로 각 4096px 상한은 어댑터에 적용되어 있습니다.
이 값은 이번 설치 옵션에 포함하지 않았습니다.
OS CPU/메모리/네트워크 제한은 [변환 실행기](CONVERSION_EXECUTION.md)를 참고하세요.
미리보기 복구·PNG 캐시·모듈 업데이트·미구현 디스크 할당량/퇴거 제한은 [파이프라인](PREVIEW_PIPELINE.md)을 참고하세요.
외부 원본 픽셀 상한과 JVM 디코더 강제 제한 시간은 아직 보장하지 않습니다.
동일 변환 저장소를 여러 서버가 쓰는 구성은 worker.lock으로 차단합니다.

주석·스탬프는 디스크에 보존하며 저장소별 writer.lock으로 단일 쓰기 인스턴스만 허용합니다. 백업 시 주석과 스탬프를 함께 보존하세요. 상세 제한은 [편집 저장](ANNOTATIONS_STAMPS.md)을 참고하세요.

뷰어 세션은 현재 메모리 기반이며 재시작 시 사라집니다. [세션·페이지](VIEWER_SESSIONS.md)를 참고하세요.

편집 PNG 합성은 features.image.export-edited로 제어하며 crop 설정의 출력/원본 상한과 동시 전송 슬롯을 자르기와 공유합니다. 서버 글꼴·반출 권한·주석 충돌 계약은 [편집 반출](EDITED_EXPORT.md)을 참고하세요. 글꼴 파일은 패키지에 포함하지 않습니다.

## 영상·음성 모듈

playback 설정으로 사용 여부·FFmpeg/FFprobe 절대 경로·실행 파일 SHA-256·프로세스 제한·작업/스트림 상한을 선택합니다. 기본은 비활성입니다. 현재 D:/DG_module/ffmpeg/bin의 LGPL 3 이상 shared 설치본을 검증했습니다. config/imagegate.example.yml의 playback.enabled를 true로 선택하고 현재 해시를 확인해 운영 설정에 반영하세요. 모듈 업데이트 시 해시도 다시 확인해야 하며 재시작이 필요합니다. 영상은 WebM, 음성은 MP3 열람용 재생본으로 정규화합니다. [상세 설정·상한·라이선스·검증](PLAYBACK.md)을 참고하세요.

## 재생 캐시 정리 설정

imagegate.playback.cache에서 사용 여부, 보관 기간, 주기, 완료 재생본 용량 목표를 지정합니다. 기본은 활성/7일/5분/10GiB입니다. 사용 중 파일은 보호하며 원본·주석·스탬프는 정리하지 않습니다. 전체 디스크 하드 할당량은 아닙니다. 상세 동작과 운영 경계는 [CACHE_MAINTENANCE.md](CACHE_MAINTENANCE.md)를 참고하세요.

이미지 PNG 캐시도 imagegate.preview.cache에서 별도로 설정합니다. 기본은 활성/7일/5분/5GiB입니다. 영상·음성 캐시 용량과 별도이며 전체 디스크 하드 할당량은 아닙니다. 이미지 처리 비활성 시에도 캐시 정리는 독립적으로 동작할 수 있습니다.

썸네일은 imagegate.preview.thumbnail-enabled(기본 true), thumbnail-max-edge(기본 256, 64~512)로 설정합니다. 변경 시 새 캐시 키로 처리하며 다음 파일 선택/준비 시 생성합니다. 상세 동작은 THUMBNAILS.md를 참고하세요.

주석 실행 취소/다시 실행은 imagegate.editing.history에서 사용 여부, 단계 수, 전체/사용자별 컨텍스트 수, TTL을 설정합니다. 기본은 활성/20단계/128개/사용자별 8개/30분입니다. 메모리 기록이므로 재시작 후 사라지며 저장된 주석은 유지됩니다. EDIT_HISTORY.md를 참고하세요.

감사 로그: imagegate.audit.enabled 기본 true, logging.file.path 기본 ./.runtime/logs. audit.log에 고정 스키마를 기록하며 10MB/일별 회전, 30일/아카이브 1GB 정리 목표를 사용합니다. OS ACL·디스크 감시와 상세 한계는 AUDIT_LOGGING.md를 참고하세요.

원본 등록 보호: imagegate.upload의 max-file-bytes(기본100MiB), max-concurrent(4), max-concurrent-per-user(2), min-free-bytes(512MiB)를 설정합니다. multipart-directory(기본 ./.runtime/multipart, 현재 작업 디렉터리 기준)와 multipart-overhead-bytes(기본64KiB)로 Servlet 임시 경로/전체 요청 여유를 설정합니다. 공통 파일 상한은 원본·스탬프 상한 중 큰 값이며 전체 요청 상한은 그 값+여유입니다. 임시 경로와 네 저장소 중첩/링크·정션은 기동 거절됩니다. 기존 spring.servlet.multipart.*의 max-file-size/max-request-size/file-size-threshold/location/enabled/resolve-lazily 설정은 제거하고 이관합니다. 파싱 전 동시 요청 수는 multipart-max-concurrent로 별도 제한하며, TCP 연결/수신 시간·임시 볼륨 바이트 할당량·프록시/조직 영구 할당량은 별도입니다. 설정 범위·413/429/507·동시 쓰기 한계는 UPLOAD_LIMITS.md를 참고하세요.

의존성 검증은 check/build의 verifyRuntimeLicenses가 수행합니다. 기준선과 다른 버전·JAR/POM/고지문·실행 JAR 포함물은 실패하므로 운영자가 자동 승인/우회하지 않습니다. 이 검증의 성공은 전체 재배포 준비 완료를 의미하지 않으며 외부 모듈과 고지/소스 자료의 잔여는 RUNTIME_LICENSES.md를 참고하세요.

## 오프라인 캐시 잔재 점검

서버와 남은 외부 변환기를 중지한 뒤 java -jar lib/imagegate-0.1.0-SNAPSHOT.jar --maintenance --help로 확인합니다. 실제 converted 경로를 절대 경로로 명시해야 하며 기본은 비삭제 점검입니다. 기존 캐시 잠금이 사용 중이면 거절하고 저장소를 새로 만들지 않습니다. 상세 대상·명시적 apply·상한·부분 실패와 운영 전제는 OFFLINE_MAINTENANCE.md를 참고하세요. 개발 중 실제 설치 데이터 정리는 수행하지 않았습니다.

## 백업·복구

원본/주석/스탬프는 같은 서비스 중지 시점의 세트로 백업하고 새 빈 경로에 복원합니다. 절대 저장 경로가 storage.root 밖에 있을 수 있습니다. 절차·무결성/참조 검사·운영 인수 범위는 [BACKUP_RECOVERY.md](BACKUP_RECOVERY.md)를 따릅니다. 자동 백업/복구 도구는 아직 제공하지 않습니다.

원본 등록 SHA-256 대조는 [ORIGINAL_INTEGRITY.md](ORIGINAL_INTEGRITY.md)의 읽기 전용 오프라인 명령으로 수행합니다. 실제 저장소 writer 중지와 명시적인 원본 절대 경로가 필요합니다.

imagegate.upload.multipart-max-concurrent는 기본4, 1~16의 단일 JVM 공통 multipart 수신 슬롯입니다. CSRF보다 앞에서 접수하며 포화 시 본문 읽기 없이429 UPLOAD_INGRESS_BUSY를 반환합니다. 원본 복사 단계의 max-concurrent/max-concurrent-per-user와 별개이고 일반 이미지 조회/재생에는 사용하지 않습니다. 비인증 요청도 같은 전역 수신 상한을 사용하며 슬롯은 권한을 부여하지 않습니다. 느린 송신자의 점유 시간·프록시/OS 임시 볼륨 총량 제한은 별도입니다. UPLOAD_LIMITS.md 참조.

imagegate.upload.body-read-idle-timeout는 기본30s, 1s~5m(정수 밀리초)의 HTTP/1.1 요청 본문 유휴 상한입니다. 다음 데이터 읽기 대기 시간을 제한하며 전체 전송 마감/최소 전송률은 아닙니다. 같은 내장 커넥터의 JSON 등 다른 본문에도 적용되고 헤더/keep-alive/응답 쓰기 타임아웃은 변경하지 않습니다. 현재 server.http2.enabled=true는 기동 거절되며 프록시 앞단 HTTP/2와 ImageGate HTTP/1.1 백엔드를 구분합니다. 프록시 전체 시간/최소 전송률·연결/본문 제한은 별도 운영 검증이 필요합니다. UPLOAD_LIMITS.md 참조.

## 선택 운영 프록시

IntelliJ에서 내장 Tomcat으로 개발할 때는 Apache/NGINX가 필요하지 않으며 현재 Run 구성을 유지합니다. 운영 프록시를 선택하면 [REVERSE_PROXY.md](REVERSE_PROXY.md)의 예제/백엔드 신뢰 경계/구문 점검 및 실제 TLS·SSO 인수를 따릅니다. config/proxy 예제는 자동 활성화하지 않습니다. Apache의 최대 수신 시간·최소 전송률과 NGINX OSS의 유휴 제한을 구분합니다.

## 선택 원본 용량 제한

imagegate.upload.tenant-original-max-bytes는 기본0으로 비활성입니다. 양수로 지정하면 모든 조직에 각각 같은 원본 누적 바이트 한도를 적용하며 설정 변경 후 재시작합니다. 예:107374182400은 조직당100 GiB. 활성화 시 같은 로컬 원본 저장소에는 quota writer 하나만 실행하고 .quota-writer.lock을 수동 삭제하지 않습니다. 기존 원본·중단 업로드 잔재의 재계수, 오류/한도 축소, 정션 제한과 물리 볼륨 한계는 [ORIGINAL_QUOTA.md](ORIGINAL_QUOTA.md)를 따릅니다.

## 원본 업로드 잔재 점검

서버/외부 writer를 중지한 뒤 --maintenance --inspect-upload-residue --help로 도움말을 확인합니다. 실제 originals canonical 절대 경로와 --workers-stopped가 필수이며 읽기 전용입니다. 알려진 구조를 확인해도 삭제 승인을 의미하지 않으며 --apply는 지원하지 않습니다. [UPLOAD_RESIDUE_INSPECTION.md](UPLOAD_RESIDUE_INSPECTION.md)의 상한·결과·잠금·종료 코드를 따릅니다.

## 주석·스탬프 참조 점검

서버/모든 writer를 중지하고 --maintenance --verify-editing-references --help로 확인합니다. 실제 originals/annotations/stamps의 독립된 canonical 절대 경로와 --workers-stopped가 필수입니다. metadata를 제한해서 읽지만 메모·평문 사용자·파일 이름을 결과에 노출하지 않습니다. [EDITING_REFERENCE_INSPECTION.md](EDITING_REFERENCE_INSPECTION.md)의 범위/잠금/상한/종료 코드와 구조·참조 판정의 한계를 따릅니다.

복원본의 전체 파일/디렉터리 목록·크기·SHA-256은 [BACKUP_MANIFEST_VERIFICATION.md](BACKUP_MANIFEST_VERIFICATION.md)의 --verify-backup-manifest 명령으로 대조합니다. 보호된 외부 manifest와 별도 신뢰 해시가 필수이며 원본/참조/실제 인증·렌더링 인수와 함께 수행합니다.

[RECOVERY_ACCEPTANCE.md](RECOVERY_ACCEPTANCE.md)의 --verify-recovery로 네 단계(manifest 시작/원본/참조/manifest 종료)를 같은 기존 저장소 잠금 범위에서 실행할 수 있습니다. offlineChecksPassed는 오프라인 결과이며 운영 복구/서비스 전환 승인을 의미하지 않습니다.

[EXTERNAL_PROCESS_SECURITY.md](EXTERNAL_PROCESS_SECURITY.md)에 img2tile·FFmpeg 공통 실행 경로/환경/수명 제한과 별도 OS 격리 인수 범위를 정의합니다.

[NATIVE_MODULE_APPROVAL.md](NATIVE_MODULE_APPROVAL.md)에 시작 시 고정 EXE/DLL/resource 기준선·새 작업 전후 내용 검사·변경 후 인스턴스 차단과 캐시 식별/운영 재승인 절차를 정의합니다. 시작 로컬 기준선은 공급자 서명/외부 bundle 승인을 대신하지 않습니다.
