# ImageGate 작업 지침

- 사용자 요청에 따라 Node.js 24 + React만 유지합니다. 2026-11-16 Spring Boot/Java/Gradle 소스·빌드 설정을 로컬 해시 검증 보관 후 제거했습니다. 새 기능·실행·테스트에 Java를 다시 도입하지 않습니다.
- Node 코드는 backend/{기능}/{controller,service,util,repository,config}로 구분하고 프런트엔드는 frontend/src의 React 컴포넌트로 관리합니다. 의존성은 정확한 버전과 pnpm lockfile로 고정합니다.
- 패키지는 기능(system/security/media/viewer/conversion)을 먼저 나누고 각 기능 내부에 controller/service/util을 구분합니다.
- 설정·도메인·예외·저장소도 해당 기능의 config/domain/exception/repository에 둡니다. 전역 역할별 패키지로 모으지 않습니다.
- controller는 요청·응답, service는 업무·정책, util은 기술 보조 역할을 담당합니다. 빈 패키지에 가짜 기능을 만들지 않습니다.
- 테스트도 대상 기능·역할 패키지에 맞춥니다. 상세 규칙은 docs/ARCHITECTURE.md를 따릅니다.
- 기능 수정 전 docs/PROJECT_MAP.md에서 호출 체인과 변경 영향도(8절)를 확인하고 연관 부분까지 검증합니다. 구조가 바뀌면 PROJECT_MAP.md를, 작업을 끝내거나 새로 발견하면 docs/REMAINING_WORK.md를 갱신합니다.
- 최우선 기준은 뷰잉 속도와 보안입니다. 설치 기능 설정은 사용자별 권한을 대체하지 않습니다.
- ImageMagick은 초기 의존성과 실행 경로에서 제외합니다. 미구현 모듈 선택을 자동 우회하지 않습니다.
- img2tile은 사용자가 내부 개발 모듈의 사용을 확인했습니다. 실행 파일·DLL·실제 업무 자료·비밀정보는 저장소에 넣지 않습니다.
- 설치 설정은 config/simpleimgviewer.example.yml에 반영하며 실제 config/simpleimgviewer.yml은 저장소에서 제외합니다. config/imagegate.example.yml은 현재 예제 경로 안내만 제공합니다.
- 변경 후 Node 테스트·React 빌드와 실제 브라우저/모듈 검증을 수행합니다. docs의 기존 Java/Gradle 기록은 현재 실행 지침으로 사용하지 않습니다.
- 로컬 소스 보관본은 .runtime/legacy-source-backups에 두고 Git·배포에 포함하지 않습니다. 업무 자료·기존 저장소는 소스 정리 대상이 아닙니다.

- 설치 ZIP 생성·검증은 최종 기능 구현과 전체 검증/테스트가 완료된 최종 배포 단계에서만 수행합니다. 개발 중에는 build/test만 실행합니다.
