# 연계 시스템 없는 개발 환경의 자동 로그인

2026-11-11. dev 단독 프로필에서 서버가 고정 개발 관리자 ViewerPrincipal을 생성한다. 브라우저에 로그인 화면/비밀번호 입력이 필요하지 않다. 기본 실행 및 설치 예제의 자동 로그인 값은 false다. 실제 SSO·외부 파일 권한 수신은 후속이다.

## IntelliJ 실행

IntelliJ 실행 구성은 공유 **SimpleImgViewer** 하나로 통합했습니다. 개발 자동 로그인과 FFmpeg 경로/해시 YAML을 함께 적용하고 동시 중복 실행을 비활성화했습니다. 프로젝트 전용 FFmpeg는 먼저 [모듈 설치](NATIVE_MODULE_INSTALLATION.md)를 완료해야 합니다. 기존 JVM을 중지하고 다시 실행해야 적용됩니다. build를 정리해 YAML이 사라졌으면 `./gradlew.bat installNativeModules`로 재생성합니다. dev 프로필만 지정한 일반 실행은 이 추가 인자를 자동 수신하지 않습니다.

1. 실행 구성을 **SimpleImgViewer**로 선택한다. 공유 설정은 [.run/SimpleImgViewer.run.xml](../.run/SimpleImgViewer.run.xml)에 있다. 예전 ImageGate 및 용도별 중복 구성은 제거했다. 기존 구성이 IDE 메모리에 계속 보이면 프로젝트를 다시 연다.
2. 실행 중인 기존 서버를 중지한 뒤 새 설정으로 다시 실행한다. 실행 중 JVM에는 소스/설정 변경이 자동 적용되지 않는다.
3. [뷰어](http://192.168.0.86:8080/viewer)로 접속한다. 실제 서버 IP/포트가 다르면 해당 주소를 사용한다.

CLI에서는 다음처럼 프로필을 명시한다.

```powershell
./gradlew.bat bootRun --args="--spring.profiles.active=dev"
```

JAR 실행도 --spring.profiles.active=dev를 명시할 수 있다. 자동 로그인을 끄려면 dev 프로필을 제거하거나 --imagegate.security.development-auto-login=false를 지정한 뒤 재시작한다. 껐을 때 실제 인증 공급자가 없으면 보호 API는401이다.

## 계정과 권한

| 항목 | 개발 설정 |
| --- | --- |
| 프로필 |dev 단독. 다른 프로필과 함께 활성화하고 자동 로그인을 켜면 기동 거절 |
| 사용자 |dev-admin |
| 조직 |development |
| 권한 |ROLE_DEV_ADMIN, development 조직의 모든 MediaAction |
| 데이터 |기본 .runtime/development/media, 수신 임시 .runtime/development/multipart |

같은 서버의 모든 개발 접속자는 이 계정을 공유한다. development 조직 안에서는 다른 소유자의 파일도 열람/메모/스탬프/자르기/편집 반출/원본 다운로드/인쇄 권한을 가진다. 다른 조직의 파일과 사용자별 스탬프·뷰어 세션 경계는 유지한다. 설치에서 비활성화한 기능, 미지원 포맷, 파일·용량 제한과 미리보기 준비 조건을 권한으로 우회하지 않는다. 외부 프로그램 활성화와 승인도 별도다.

실제 데이터와 섞지 않도록 개발용 저장소를 분리했다. IMAGEGATE_DEV_STORAGE_ROOT 또는 명시적 설치/명령행 설정으로 저장소를 선택할 수 있으며, 기존 업무 저장소를 개발 환경에 연결하기 전 데이터 범위를 확인한다.

## 구현과 설정

- SecurityConfiguration은 DevelopmentAutoLoginFilter를 SecurityContextHolderFilter 뒤, 감사/CSRF 필터 앞에 배치한다. 개발 인증은 Security 체인에만 등록하며 별도 Servlet 필터로 중복 등록하지 않는다.
- DevelopmentAuthenticationService는 설정으로만 principal/authority를 만들며 사용자 ID·조직 ID·권한 헤더를 읽지 않는다. 이미 존재하는 신뢰된 Authentication은 덮어쓰지 않는다. 요청 간 로그인 인증을 세션에 저장하지 않으며 기본 SecurityContext 수명 정리를 유지한다.
- 기본 MediaAuthorizationPolicy에서 같은 조직의 설정된 개발 관리자에게 전체 MediaAction을 허용한다. 일반 모드의 소유자 검사와 연계 시스템이 별도로 제공하는 정책 SPI는 유지한다.
- ImageGateProperties.Security는 development-auto-login, development-tenant-id, development-user-id를 바인딩한다. 생략하면 false/development/dev-admin이며 잘못된 ID는 설정 검증에서 거절한다. application-dev.yml만 자동 로그인을 켠다. config/imagegate.example.yml에는 비활성 기본값을 넣었다.
- 활성 프로필이 정확히 dev 하나가 아닌데 자동 로그인을 true로 설정하면 시작에 실패한다. 시작 로그에 공유 개발 관리자 모드가 활성화됐음을 표시한다. 정상 기본 실행에 개발 계정을 자동 활성화하지 않는다.
- HTTP Basic/폼 로그인/기본 생성 비밀번호를 추가하지 않았다. CSRF 보호와 기존 파일별/조직별 권한 검사를 유지한다.

메인 화면 상단 브랜드와 브라우저 제목은 SimpleImgViewer로 변경했다. Java 패키지 com.sizzdev.imagegate와 애플리케이션/저장소 이름은 유지한다.

## 검증

새12개는 기본 비활성·dev 단독 조건·권한/ID 생성·헤더 무시·기존 인증 보존·모든 파일 작업/조직 경계·YAML 기동 조건과 실제 HTTP bootstrap/CSRF/업로드를 확인한다. 기존 설치 설정·업로드/인증 경계와 합친 집중43개가 실패·오류·건너뜀0으로 통과했다. 실제 HTTP는 임시 포트와 합성 PNG/별도 저장소만 사용했다. 일반/운영 모드에 인증이 생기지 않는지도 기존 회귀와 함께 검증한다.

사용자가 실행 중인8080 JVM은 임의로 종료하지 않았다. 새 실행 설정으로 재시작한 뒤 변경이 반영된다. 설치 ZIP은 만들지 않는다.
