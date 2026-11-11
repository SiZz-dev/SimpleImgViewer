# 실제 HTTP 뷰어 성능 검증

대형 TIFF의 타일과 영상/음성 Range를 실제 native 변환 중 함께 조회하는 별도 검증은 [MIXED_MEDIA_PERFORMANCE.md](MIXED_MEDIA_PERFORMANCE.md)를 참고하세요. 기존 HTTP 미리보기 기준선과 실행/표본/자원 범위를 구분합니다.

JDK21 / Spring Boot4.0.1 / Gradle8.14.5 기준입니다. MockMvc 출력 버퍼 비용을 분리하기 위해 Tomcat 임시 포트에 실제 JDK HTTP 클라이언트 요청을 보냅니다. 새 런타임 의존성이나 운영 인증 API를 추가하지 않습니다.

## 실행과 격리

~~~powershell
$env:JAVA_HOME = 'C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat httpPerformanceTest
.\gradlew.bat httpPerformanceTest -PhttpPerformanceImg2tile=true
~~~

일반 test/build에서는 performance 및 http-performance 태그를 제외합니다. 기존 performanceTest는 MockMvc만 실행하며 http-performance 태그를 제외합니다. 이 작업은 항상 재실행됩니다. 설치 ZIP/운영 기동에 연결하지 않습니다.

서버는 127.0.0.1의 임시 포트, 평문 HTTP/1.1만 사용합니다. 로컬 운영 설치 YAML import를 덮어쓰고, 저장소는 build/http-performance/run-* 아래 합성 자료 전용으로 지정합니다. 모듈 실행 경로는 기존 환경 설정/기본 경로를 사용하며 native 옵션을 켠 경우 실제 img2tile을 호출합니다. 개인/업무 원본을 입력받지 않습니다.

src/test의 HttpViewerPerformanceTests만 임의 생성한 토큰→합성 principal 변환 필터를 기존 SecurityContextHolderFilter 뒤에 삽입합니다. 운영 SecurityFilterChain의 CSRF/행위 정책/서비스 권한 검사는 유지합니다. 테스트 필터/토큰/인증 설정은 src/main이나 실행 JAR에 넣지 않습니다. 이 수치는 실제 로그인/SSO·업무 DB ACL 비용을 포함하지 않습니다. 공유 helpers의 MockMvc 빈은 테스트 문맥에 존재하지만 측정 요청은 모두 실제 HttpClient.send→Tomcat 소켓을 사용합니다.

클라이언트는 프록시를 사용하지 않고 리디렉션을 따라가지 않으며 목적지의 scheme/host/port를 제한합니다. 실제 bootstrap에서 받은 CSRF 토큰과 세션 쿠키를 등록 요청에 사용합니다. 잘못된 CSRF 요청은 403/no-store/고정 오류 코드를 확인합니다. 준비된 본문/썸네일마다 익명401·다른 사용자404·다른 조직404·권한 회수404를 확인합니다. 권한 검사를 제외한 빠른 경로로 측정하지 않습니다.

## 측정

기존 MockMvc benchmark와 같은 고정 난수 seed의 RGB 합성 샘플·수집기를 재사용합니다.

| 형식 | 원본 크기 | 엔진 |
| --- | --- | --- |
| JPEG | 3200×2400 | JDK ImageIO |
| PNG | 2400×1600 | JDK ImageIO |
| TIFF | 2400×1600 | 기본 JDK, native 옵션 시 img2tile |

미리보기 최대1600px, 썸네일256px, 워커2/큐32. JVM 기동·fixture 생성·초기 bootstrap/CSRF 거절 검증·640×480 예열은 측정 밖입니다. 기본 cold3회는 새 미디어 ID 등록부터 상태 폴링과 첫 PNG 전체 수신까지의 워크플로입니다. 실제 HTTP multipart 전송·Servlet 파싱/임시 파일·등록 감사 로그·변환 큐/디코딩과 10ms 상태 폴링을 포함합니다. OS 페이지 캐시는 강제로 비우지 않으므로 디스크 콜드 시작이 아닙니다.

이후 준비된 한 파일에 5회 예열하고 동시 클라이언트1/4/8명으로 각각 HEAD, 본문 PNG GET, 썸네일 PNG GET을 24회씩 측정합니다. 3형식×10단계=30단계, 기본657표본입니다. cold9개는 복수 HTTP 요청으로 구성한 워크플로 표본이고 나머지648개는 완료된 단일 요청 표본입니다. PNG 크기/헤더/Content-Length와 디코딩된 크기를 확인합니다. 브라우저 디코딩/페인트 시간은 측정하지 않습니다.

평균/최대 동시 사용자 전체의 대표 워크로드가 아니라 같은 캐시를 반복 읽는 소규모 합성 부하입니다. 여러 파일·페이지·동시 업로드/변환·캐시 정리·영상/음성 seek·느린 네트워크를 대표하지 않습니다.

## 결과와 한계

build/reports/http-performance/http-viewer-*.json/.md에 환경·표본별 시간·P50/P95/max·완료율·전송 크기·힙/GC를 기록합니다. 타이머는 nanoTime, 백분위는 nearest-rank입니다. cold의 완료율은 워크플로 수/초, warm은 HTTP 요청 완료 수/초입니다. 실패 중 수집된 부분 결과는 passed=false로 보존합니다. JVM 기동/설정 검증 실패 전에는 성능 보고서가 생성되지 않을 수 있습니다.

클라이언트는 JDK HttpClient의 전체 응답 byte[]를 사용합니다. 지연은 실제 서버/소켓과 클라이언트 수신/복사·검증 비용을 포함합니다. 힙/GC는 20ms 표본이며 서버와 같은 JVM의 합성 입력·클라이언트 응답 버퍼·공통 테스트 빈을 포함합니다. 서버 전용 힙, 프로세스 RSS, native 모듈 메모리로 해석하지 않습니다. 별도 JVM fork의 힙은256MiB~1GiB입니다.

이 측정은 루프백 환경의 실제 HTTP 기준선입니다. 운영 SSO/DB 권한, TLS/프록시/외부 네트워크, 브라우저 최초 표시, 서비스 SLA나 여러 서버의 확장성을 검증했다는 의미는 아닙니다. MockMvc 결과와 비교해 전송 모형의 영향을 구분할 수 있으나 다른 두 실행의 시간을 그대로 최적화 효과 비율로 환산하지 않습니다.

## 개발 옵션

~~~powershell
.\gradlew.bat httpPerformanceTest -PhttpPerformanceColdSamples=5 -PhttpPerformanceWarmRequests=100
# 업무 환경의 기준이 합의된 경우에만 지정. 500은 사용 예시.
.\gradlew.bat httpPerformanceTest -PhttpPerformanceMaxWarmP95Ms=500
~~~

coldSamples3~30, warmRequests24~1000, native true/false, maxWarmP95Ms는 유한한0이상이며0은 한도 검사 비활성입니다. 한도를 지정하면 모든 warm 단계의 P95에 적용합니다. 전체 테스트에10분 제한이 있으며 최대 조합의 완료를 보장하지 않습니다. 합성 저장/보고서는 build에 남으므로 반복 실행 시 디스크를 점검하세요.

## 이번 실제 HTTP 검증에서 보완한 동작

- CSRF/권한 거절 시 sendError로 컨테이너 ERROR 디스패치를 만들면 기존 /error 접근 정책에 의해 원래403이401로 바뀔 수 있었습니다. AccessDeniedHandler가 직접403/no-store/고정 Problem JSON을 반환하도록 보완하고 실제 CSRF 실패/정상 등록을 함께 검증했습니다. 인증 요구나 파일 ACL을 완화하지 않았습니다.
- bootstrap의 limits.maxUploadBytes가100MiB 고정값이던 부분을 imagegate.upload.max-file-bytes와 연결했습니다. 128바이트 설치 설정의 API 회귀 테스트를 추가했습니다.

측정된 값은 [HTTP_PERFORMANCE_BASELINE.md](HTTP_PERFORMANCE_BASELINE.md)에 기록합니다. 기존 in-process 측정은 [PERFORMANCE.md](PERFORMANCE.md)를 참고하세요.

최종 검증: build -Pimg2tileIntegration -PffmpegIntegration 성공. 일반 Java250개(실패/오류/건너뜀0), 라이선스 규칙16개 및63개 기준선 비교 통과. 별도 httpPerformanceTest의30단계/657표본·권한/CSRF 검증 성공. 최종 실행 JAR에서 두 성능 테스트 클래스/인증 fixture 항목이0개임을 확인했습니다. 브라우저 UI 코드는 변경하지 않아 브라우저 시나리오는 재실행하지 않았고 설치 ZIP은 생성하지 않았습니다.
