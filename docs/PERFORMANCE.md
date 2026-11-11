# 뷰어 API 성능 기준선

## 실행

~~~powershell
$env:JAVA_HOME = 'C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat performanceTest
# 설치된 실제 img2tile을 TIFF 경로에 사용하는 경우
.\gradlew.bat performanceTest -PperformanceImg2tile=true
~~~

Gradle Wrapper 8.14.5 / JDK 21 / Spring Boot 4.0.1을 유지합니다.
추가 라이브러리·벤치마크 서비스·운영 인증 우회 없이 개발 전용 테스트로 실행합니다.
performance 태그는 일반 test/build에서 항상 제외하고 별도 performanceTest에서만 실행합니다.
작업은 항상 다시 실행하며 이전 결과가 있다고 UP-TO-DATE로 생략하지 않습니다.
설치 ZIP과 연결하지 않습니다.

## 범위와 보안

MockMvc의 실제 Spring Security 필터·controller/service·로컬 원본 저장소·변환 워커·PNG 파일 전송을 실행합니다.
기존 보안 테스트와 동일한 테스트 인증 주체를 요청에 설정합니다.
실제 SSO 공급자·운영 로그인·HTTP 서버/네트워크·TLS·브라우저 디코딩/화면 표시를 측정하지 않습니다.
운영 코드를 변경하거나 테스트 헤더/임시 로그인 API를 추가하지 않습니다.
개별 소스마다 익명 401, 다른 사용자/조직 404, 캐시 생성 후 권한 회수 404를 확인합니다.
인증/권한 확인을 생략한 수치를 뷰어 성능으로 보고하지 않습니다.

측정은 워밍업 이후의 **애플리케이션 캐시 미적중**과 **완료 캐시 재사용**을 구분합니다.
OS 디스크 페이지 캐시를 강제 비우지 않습니다. 최초 JVM 기동 시간·fixture 생성·브라우저 페인트는 제외합니다.
따라서 cold-register-to-png는 등록 API 시작부터 준비 상태 폴링과 PNG 전체 응답 수신까지이며 디스크 콜드 시작 시간이 아닙니다.
10ms 상태 폴링, 테스트 인증/CSRF 준비와 MockMvc 응답 검증 비용도 포함합니다.
완료 후 PNG 디코딩/크기 검증은 지연 측정 밖에서 별도로 수행합니다.
로컬에 설치된 Spring Test 7.0.2의 MockHttpServletResponse.ResponseServletOutputStream 소스를 확인하면 단일 바이트 write마다 flush/커밋 검사를 수행합니다.
큰 PNG의 GET 수치는 이 Mock 출력 비용의 영향을 받으므로 실제 Tomcat 처리량/네트워크 전송 시간으로 해석하지 않습니다.
따라서 같은 소스의 warm-head를 따로 측정해 권한·캐시 경계·파일 확인까지의 경로를 구분합니다.
HEAD와 GET의 차이를 전부 실제 서버 전송 비용으로 계산해서도 안 됩니다.

## 합성 샘플과 요청

고정 난수 seed의 RGB 합성 이미지를 JDK ImageIO 기본 인코딩 설정으로 생성합니다. 외부 업무 자료·개인 자료는 읽지 않습니다.
단순 단색보다 PNG 압축률이 낮은 샘플이므로 일반 스캔 문서/실사진의 대표값으로 간주하면 안 됩니다.

| 입력 | 크기 | 엔진 |
| --- | --- | --- |
| JPEG | 3200×2400 (7.68MP) | JDK ImageIO |
| PNG | 2400×1600 (3.84MP) | JDK ImageIO |
| TIFF | 2400×1600 (3.84MP) | 기본 JDK / 선택 시 실제 img2tile |

각 형식마다 새 미디어 ID로 5회 등록→준비→PNG를 측정합니다.
생성된 하나의 재생성 불필요 캐시에 5회 예열 후 동시 클라이언트 1/4/8명으로 각각 24회 HEAD·24회 본문 PNG GET·24회 전용 썸네일 PNG GET을 요청합니다.
이는 등록 동시 부하나 여러 파일/페이지 작업 집합의 성능 측정은 아닙니다.
썸네일은 256px로 고정해 전송 바이트/동시 조회를 비교합니다.
미리보기는 최대 1600×1600, 원본 픽셀 제한 16MP, 변환 워커 2개·큐 32개를 사용합니다.
프로세스 시작 전에 준비된 운영 원본을 직접 벤치마크 입력으로 받는 기능은 제공하지 않습니다.

## 산출물과 해석

- build/reports/performance/viewer-<시각>.json: 환경·설정·샘플 입력/출력 크기·각 요청 원시 시간·단계별 집계.
- 같은 이름의 .md: 읽기 쉬운 지연/처리량/힙 표.
- build/test-results/performanceTest 및 build/reports/tests/performanceTest: 성능 실행의 JUnit 결과.
- build/performance/run-*: 격리한 합성 원본/파생 캐시. 기본 설치/실제 데이터 경로와 분리합니다.

P50/P95는 정렬된 요청 시간의 nearest-rank 방식입니다.
기본 cold 5회·warm 24회의 P95는 소수 표본 결과이며 안정적인 운영 P95의 근거가 아닙니다.
처리량 필드 requestsPerSecond/표의 req/s는 HEAD/GET에서는 완료 HTTP 요청 수/벽시계 시간입니다.
cold-register-to-png의 경우 등록·폴링·PNG를 묶은 워크플로 완료 수/초이며 개별 HTTP 요청 수/초가 아닙니다. 타이머는 System.nanoTime을 사용합니다.
기본은 수치 수집이며 업무 환경의 합격 기준을 임의로 정하지 않습니다.
명시한 warm P95 한도를 넘거나 요청/디코딩/권한 검사가 실패하면 테스트도 실패합니다.
측정 도중 오류가 나도 가능한 범위의 보고서를 passed=false로 보존하며 부분 결과를 전체 성공으로 표시하지 않습니다.

메모리는 20ms 주기의 JVM 사용 힙 표본 최댓값과 단계 전 힙, GC 횟수/시간 차이입니다.
힙에는 Spring·합성 입력·MockMvc 수신 본문/클라이언트 버퍼가 포함됩니다.
순간 피크를 놓칠 수 있으며 프로세스 RSS/네이티브 img2tile 메모리/운영 사용자당 메모리를 뜻하지 않습니다.
성능 작업은 별도 JVM, 최소 힙 256MiB·최대 힙 1GiB, 단일 Gradle fork로 실행합니다.
다른 부하·백신·파일시스템/디스크·CPU 전원 정책의 영향도 있으므로 동일 환경에서 비교해야 합니다.

## 조정 가능한 개발 옵션

~~~powershell
.\gradlew.bat performanceTest -PperformanceColdSamples=10 -PperformanceWarmRequests=100
# 팀이 정한 기준이 있을 때만 적용 (아래 숫자는 사용 예시)
.\gradlew.bat performanceTest -PperformanceMaxWarmP95Ms=500
~~~

coldSamples 3~30, warmRequests 24~1000, maxWarmP95Ms는 유한한 0 이상 값입니다.
전체 측정에는 10분 제한이 있으며 큰 샘플 수 조합의 완료 시간을 보장하지 않습니다.
0은 지연 한도 검사 비활성입니다. performanceImg2tile은 true/false만 받습니다.
설정은 개발 Gradle 옵션이며 설치 YAML의 새로운 운영 옵션이 아닙니다.
합성 결과는 build 아래에만 보관하고 저장소에 넣지 않습니다. 반복 실행 시 디스크 사용량을 확인하세요.

## 남은 검증

실제 업무 샘플·SSO 연동 HTTP/TLS·브라우저 최초 표시/상호작용·동시 변환 큐 지연,
대형/다중 페이지·썸네일/타일·캐시 정리 중 부하·장시간 안정성·영상 최초 재생/seek를 별도로 측정해야 합니다.
OS CPU/메모리 격리 및 디스크 하드 할당량도 이 벤치마크로 완료 처리하지 않습니다.
설치 ZIP은 최종 기능 구현과 전체 배포 검증 이후에만 생성합니다.

최초 기준선과 측정값 해석은 [PERFORMANCE_BASELINE.md](PERFORMANCE_BASELINE.md)를 참고하세요.

## 실제 HTTP 후속 검증

httpPerformanceTest는 별도 루프백 Tomcat/HttpClient 요청을 사용합니다. 일반 test/build와 기존 performanceTest에서 분리되며 상세 범위·인증 fixture·결과는 HTTP_PERFORMANCE.md 및 HTTP_PERFORMANCE_BASELINE.md를 참고하세요. 이 작업도 운영 SSO/TLS/브라우저/업무 샘플 검증을 대신하지 않습니다.
