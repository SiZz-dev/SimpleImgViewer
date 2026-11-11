# ImageGate 작업 지침

- JDK 21, Spring Boot 4.0.1, Gradle Wrapper 8.14.5 기준을 유지합니다.
- 패키지는 기능(system/security/media/viewer/conversion)을 먼저 나누고 각 기능 내부에 controller/service/util을 구분합니다.
- 설정·도메인·예외·저장소도 해당 기능의 config/domain/exception/repository에 둡니다. 전역 역할별 패키지로 모으지 않습니다.
- controller는 요청·응답, service는 업무·정책, util은 기술 보조 역할을 담당합니다. 빈 패키지에 가짜 기능을 만들지 않습니다.
- 테스트도 대상 기능·역할 패키지에 맞춥니다. 상세 규칙은 docs/ARCHITECTURE.md를 따릅니다.
- 최우선 기준은 뷰잉 속도와 보안입니다. 설치 기능 설정은 사용자별 권한을 대체하지 않습니다.
- ImageMagick은 초기 의존성과 실행 경로에서 제외합니다. 미구현 모듈 선택을 자동 우회하지 않습니다.
- img2tile은 사용자가 내부 개발 모듈의 사용을 확인했습니다. 실행 파일·DLL·실제 업무 자료·비밀정보는 저장소에 넣지 않습니다.
- 설치 설정은 config/imagegate.example.yml에 반영하며 실제 config/imagegate.yml은 저장소에서 제외합니다.
- 검증은 변경에 맞는 Gradle 테스트를 실행합니다. 실제 내부 모듈이 있는 Windows에서는 -Pimg2tileIntegration으로 통합 테스트를 포함합니다.
- Java 패키지 루트는 co.kr.satu.imagegate이며, 소스와 테스트 모두 같은 루트를 사용합니다.

- 설치 ZIP 생성·검증은 최종 기능 구현과 전체 검증/테스트가 완료된 최종 배포 단계에서만 수행합니다. 개발 중에는 build/test만 실행합니다.
