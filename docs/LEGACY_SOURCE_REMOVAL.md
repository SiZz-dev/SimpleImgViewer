# Spring Boot 소스 제거 기록

2026-11-16 사용자 요청으로 Node.js24 + React만 현재 실행 대상으로 유지한다. Java/Gradle 소스를 비교용으로 보존하던 이전 방침을 변경했다. 기능 이관 완료와 운영·라이선스 배포 승인 여부는 별도이며, 설치 ZIP을 생성하지 않았다.

## 보관과 제거

제거·수정 전 파일400개, 총2,810,892바이트를 로컬 `.runtime/legacy-source-backups/springboot-20261116-155206/files`에 복사했다. 미등록 `com/sizzdev` Java 소스와 기존 TestFiles4개도 포함한다. `inventory.json`에 상대 경로·바이트 수·SHA-256·기준 Git commit을 기록했고 원본과 복사본 해시를 모두 대조한 뒤 제거했다. 기존 추적 파일의 변경 patch도 같은 위치에 보관했다. 보관본은 Git에서 제외되며 이번 작업 PC의 로컬 자료다. 새 clone에서 자동 제공되는 파일이 아니다.

현재 트리에서 제거한 항목:

- `src`: Java 구현·테스트·Spring YAML/logback 설정·기존 정적 뷰어
- `gradle`, Gradle wrapper·build/settings/properties/lock 파일
- Java 정적 뷰어/NativeViewerIntegration/이전 URL 화면 전용 시험 스크립트3개
- Tomcat 전용 프록시 backend YAML 예제
- 로컬 IntelliJ Gradle 연결·Java 컴파일러·기존 Java 모듈 설정

공유 `.run/SimpleImgViewer.run.xml`은 기존 Node 실행을 유지하고 로컬 모듈은 `.idea/simpleimgviewer.iml`의 WEB_MODULE로 교체했다. 열려 있는 IntelliJ가 이전 compiler/JDK·모듈 메타데이터를 다시 생성하는 현상을 확인했다. 프로젝트를 다시 열어 현재 modules.xml과 Node 실행 구성을 읽도록 한다. 남은 IDE JDK 선택은 서버 실행 의존성이 아니다. `.idea/workspace.xml`의 개인 편집·변경 이력과 `.gradle` 등 이전 생성 캐시는 이번 소스 정리에서 건드리지 않는다. 기존 data와 Node 저장소·런타임·네이티브 모듈도 유지한다. 원래 src 안에 있던 TestFiles는 보관본에 남으며 새 Node 개발 파일 경로는 `.runtime/development/url-files/TestFiles`다.

## 현재 실행·설정

`scripts/start.ps1`, `scripts/start.sh`는 Node24와 `scripts/start.mjs`를 실행한다. 일반 실행은 자동 로그인을 켜지 않는다. 개발 실행은 `scripts/dev.mjs` 또는 IntelliJ의 SimpleImgViewer 구성을 사용한다. 화면은 먼저 `scripts/build-web.mjs`로 빌드한다.

`scripts/create-url-sample.ps1`은 이제 Node/sharp 생성기를 호출한다. 합성800×500 JPEG만 만들고 기존 파일은 덮어쓰지 않으며 workspace의 `.runtime`/`build` 밖 출력을 거절한다. JDK·폰트·외부 변환기는 사용하지 않는다.

현재 설치 예제는 `config/simpleimgviewer.example.yml`이다. `config/imagegate.example.yml`은 이 경로를 안내하는 주석만 남겼다. 실제 기존 설정 파일은 변경하지 않는다. 네이티브 설치 도구의 `build/native-modules/imagegate.native.yml`은 Node가 읽는 호환 형식이므로 유지하며, Spring 실행을 안내하던 주석만 수정했다.

## 문서와 복원

이전 Java 연구·보안·회귀 문서는 이력으로 유지한다. 문서에 나오는 Gradle 명령·Java 패키지·삭제된 src/시험 스크립트 경로는 현재 실행 지침이 아니다. 현재 경로는 README·ARCHITECTURE·NODE_REACT_MIGRATION의 최신 상태를 따른다. 이전 테스트 수를 현재 Node 테스트 수에 합산하지 않는다.

비교가 필요하면 먼저 보관본 파일을 inventory 해시와 대조하고, 별도의 비교 디렉터리에서 복원한다. 현재 Node 트리에 전체 보관본을 그대로 덮어쓰면 최신 README·설정·실행 도구까지 되돌아가므로 소스 비교에 필요한 파일만 선택한다. Git 기준 commit은 추적했던 파일의 추가 복원 근거이며, 미등록 변경분의 근거는 로컬 files와 inventory다.

HEIC/JPEG2000·타일·운영 인증/권한·Windows 환경별 인수는 계속 남아 있다. 이번 정리는 현재 실행에서 불필요한 Java 의존성을 제거하는 작업이다. 다음 기능 작업은 기존에 예고한 무결성 검증 전용 Worker Thread 후보 비교다.

## 제거 후 검증

전체 Node/실제 네이티브 테스트를 순차 실행해120개 통과·실패/건너뜀0을 확인했다. 최초 병렬 전체 실행에서는 FFmpeg 부모 강제 종료 직후 작업 회수 시험1개가 SQLite `attempt to write a readonly database`로 실패했다. 해당 시험의 단독 재현과 이후 전체 순차 실행은 통과했다. 이 간헐 조건의 원인을 확정하거나 별도 제품 수정을 수행한 것은 아니므로 종료·회수 안정성의 후속 관찰 항목으로 남긴다.

Chrome13개 시나리오, React production 빌드·npm90개 라이선스 기준선을 통과했다. `JAVA_HOME`을 비우고 PATH를 Windows System32로 제한한 환경에서 절대 경로 Node로 일반 서버를 실행해 health/화면200, 미인증 API401을 확인했다. 새 JPEG 생성·기존 바이트 보존·허용 경로 밖 거절, PowerShell 샘플 래퍼와 서버 래퍼의 오류 전달도 확인했다. POSIX start.sh는 Git Bash 구문 검사만 수행했으며 POSIX 운영 기동 검증을 의미하지 않는다. 별도 시작 검증 결과는 `build/reports/node-only-cleanup/verification.json`에 남겼다. 보관본400개 해시도 제거 후 재확인했다.
