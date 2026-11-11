# 런타임 의존성 라이선스 식별과 변경 검증

2026-10-22 기준, 고정된 실제 파일을 기술적으로 식별한 목록입니다. 법무 승인이나 최종 배포 준비 완료를 뜻하지 않습니다. 대표 라이선스 표기 하나로 JAR 내부의 하위 구성요소 조건을 덮어쓰지 않습니다.

## 범위

런타임 해석 JAR 62개와 실행 JAR 자동 주입 도구 1개를 식별했습니다. 실제 BOOT-INF/lib에는 53개가 포함됩니다. 라이선스/의존성 집계용 starter 10개는 실행 JAR에서 제외됩니다. 루트에 복사된 Spring Boot launcher 코드 98개 항목은 별도의 내용 해시와 Apache-2.0 근거로 추적합니다.

새 런타임/브라우저 의존성을 추가하지 않았습니다. Gradle 내장 Groovy/Java API만 사용하며 이 기능은 빌드 시에만 실행됩니다. HTTP 조회·이미지 변환·재생 처리 경로는 변경하지 않습니다.

## 실행

~~~powershell
.\gradlew.bat runtimeLicenseInventory
.\gradlew.bat verifyRuntimeLicenses
.\gradlew.bat build -Pimg2tileIntegration -PffmpegIntegration
~~~

- runtimeLicenseInventory: 런타임 JAR SHA-256, 직접/상위 POM 선언과 각 POM 해시, LICENSE/NOTICE/COPYING/COPYRIGHT 원문과 해시, 실제 포함 라이브러리 해시, launcher 해시를 수집합니다.
- verifyRuntimeLicenses: config/oss/runtime-license-baseline.json과 비교합니다. check/build에 자동 연결됩니다. test 단독 실행은 Java 테스트만 수행합니다.
- licenseAuditRulesTest: 동일 검증 함수에 정상/변조/누락/미식별 fixture 16개를 적용합니다. 검증 작업에 자동 포함됩니다.

첫 수집에 필요한 Maven POM이 캐시에 없으면 Maven Central에서 받습니다. 오프라인에서 캐시가 부족하면 실패합니다. 라이선스 URL을 자동 실행하거나 외부 프로그램을 호출하지 않습니다. XML 외부 엔터티/DOCTYPE를 차단하고 고지 파일당 2MiB, JAR당 128개/합계16MiB의 수집 상한을 적용합니다.

결과는 build/reports/licenses/runtime-inventory.json, verification.json, texts/에 생성됩니다. JSON에는 캐시/개인 홈 절대 경로를 넣지 않습니다. texts는 콘텐츠/항목명 해시 경로로만 쓰며 원본 ZipEntry 경로를 디스크 경로로 사용하지 않습니다. 이전 실행의 고지 텍스트가 남아 있을 수 있으므로 현재 inventory에 참조된 파일만 현재 목록입니다. 이 디렉터리를 그대로 최종 배포 고지 묶음으로 간주하지 않습니다.

## 실패 조건과 기준선 변경

새/제거/변경된 좌표나 버전, JAR 파일명/해시, 직접·상위 POM 해시, 고지문 해시, 실제 포함 라이브러리의 누락/추가/바이트 변경, launcher 내용 변경, 중복 식별자, 미식별 또는 허용하지 않은 대표 라이선스를 차단합니다. 파일 해시 일치는 공급자 서명/출처 검증이나 취약점 검사 결과를 뜻하지 않습니다.

자동 승인/기준선 갱신 명령은 제공하지 않습니다. 의존성을 변경할 때 실제 파일/POM/포함 고지와 upstream의 정확한 버전 근거를 읽고 정책 파일을 명시적으로 수정해야 합니다. primaryLicense는 검토 시 선택·식별한 대표 조건이며, additionalTerms와 내장 고지의 별도 조건도 계속 적용됩니다. 허용 목록을 바꾸는 경우도 코드 검토 대상입니다. status=IDENTIFIED는 사람의 법적 승인으로 해석하지 않습니다.

## 주요 식별 사항

| 구성요소 | 현재 식별 근거/조치 |
| --- | --- |
| Spring Boot/Framework/Security, Micrometer, Jackson 등 | 실제 POM과 내장 고지에서 Apache-2.0 식별. Spring 고지에는 ASM 등의 별도 조건도 포함되어 원문 전체 보존 |
| Logback 1.5.22 | 실제 logback-parent:1.5.22 POM의 EPL-1.0/LGPL 선언을 보존. 이번 기준선은 EPL-1.0을 대표 조건으로 기록. 최신 홈페이지의 EPL 버전을 과거 파일에 소급 적용하지 않음 |
| Jakarta Annotation 3.0.0 | 해당 버전 LICENSE.md/POM의 EPL-2.0 및 GPL+Classpath 대체 조건을 보존. 대표 조건 EPL-2.0 |
| SLF4J 2.0.17 | 내장 LICENSE.txt와 상위 BOM의 MIT 선언 |
| HdrHistogram 2.2.2 / LatencyUtils 2.0.3 | CC0/추가 BSD 선택 근거 확인. 대표 조건은 BSD-2-Clause로 기록 |
| Tomcat 11.0.15 | 대표 Apache-2.0 외에도 XML 스키마의 CDDL-1.0/EPL-2.0 조건이 고지되어 전체 원문 보존. 모든 항목을 Apache 단일 라이선스로 표시하지 않음 |

버전 고지의 상세 조건·배포 고지·소스 이용 안내는 최종 배포 형태에 맞춰 준비해야 합니다. 특히 EPL 프로그램의 배포에서는 해당 구성요소 소스 이용 방법과 고지·배포 조건을 확인합니다. 단순히 오픈소스라는 이유로 고지/소스 제공 의무가 없어지는 것으로 처리하지 않습니다. [EPL-1.0 원문](https://www.eclipse.org/legal/epl/epl-v10.html), [EPL 안내](https://www.eclipse.org/legal/epl/faq/)를 참고하세요.

주요 원천: [Logback 고정 버전 POM](https://repo.maven.apache.org/maven2/ch/qos/logback/logback-parent/1.5.22/logback-parent-1.5.22.pom), [Jakarta Annotation 3.0.0 LICENSE](https://github.com/jakartaee/common-annotations-api/blob/3.0.0/LICENSE.md), [LatencyUtils 2.0.3 LICENSE](https://github.com/LatencyUtils/LatencyUtils/blob/LatencyUtils-2.0.3/LICENSE), [SLF4J 라이선스](https://www.slf4j.org/license.html), [Spring Boot 4.0.1 LICENSE](https://github.com/spring-projects/spring-boot/blob/v4.0.1/LICENSE.txt). 직접/상위 POM의 정확한 좌표와 바이트 해시는 정책 파일에 기록합니다.

## 현재 미완료 배포 항목

verification.json은 검증 성공 시에도 distributionReady=false입니다. 아래를 충족했다는 주장을 하지 않습니다.

- 8개 JAR(Logback 두 개, Jakarta Validation, Hibernate Validator, JBoss Logging, JSpecify, LatencyUtils, SnakeYAML)은 수집 규칙에 해당하는 고지 텍스트가 내부에 없습니다. 라이선스 미식별과 혼동하지 않으며, 최종 고지 묶음에는 공식 버전 소스의 해당 텍스트를 보완해야 합니다. 일부 내장 고지도 전문 대신 링크만 있으므로 배포 전문/저작권 고지 준비가 별도로 필요합니다.
- EPL 등 구성요소 소스 이용/제공 안내와 수령자 대상 배포 조건, 전체 저작권·NOTICE 묶음 검토.
- JDK/JRE 배포판 및 제3자 내용, FFmpeg/FFprobe와 모든 실제 DLL/코덱의 버전·출처·소스·고지. 사용자 설치 경로의 EXE/DLL은 수집/복사/재배포하지 않습니다.
- img2tile은 사용자가 허용한 내부 개발 모듈이며 오픈소스로 오분류하지 않습니다. 포함 제3자 DLL의 근거와 내부 모듈 재배포 조건은 별도 관리합니다. ImageMagick은 현재 실행/의존성에서 계속 제외합니다.
- Gradle Wrapper/플러그인 및 테스트·브라우저 개발 도구는 이 런타임 기준선 밖입니다. 소스/개발 도구를 배포하는 경우 그 범위도 별도로 검토해야 합니다.
- 외부 이미지·폰트·아이콘을 추가할 때도 출처/조건을 따로 기록해야 합니다. 현재 CSS는 설치된 OS의 Segoe UI/Malgun Gothic 등 시스템 글꼴을 선택하며 글꼴 파일·외부 CDN·별도 이미지/아이콘 파일을 포함하지 않습니다. 이는 글꼴 재배포 허가를 의미하지 않으며, 로컬 UI 자산을 자동 식별하는 스캐너도 아닙니다.
- 정식 SPDX/CycloneDX SBOM, 중첩/재패키징된 모든 하위 코드의 전용 SBOM, CVE/취약점 검사, 미디어 코덱 특허 및 ImageGate 자체 배포 라이선스 선정은 별도 작업입니다.

[FFmpeg 공식 안내](https://ffmpeg.org/legal.html)는 활성 옵션과 정확히 대응하는 소스/배포 조건 검토를 요구합니다. 현재 모듈의 LGPL shared·해시 검사와 전체 재배포 검증을 동일한 것으로 취급하지 않습니다. 설치 ZIP은 최종 기능·전체 인수 검증을 마친 최종 배포 단계에만 생성합니다.

## 고정 파일 목록

대표 조건은 하위 구성요소의 별도 조건을 생략하는 선언이 아닙니다. '고지 수'는 수집된 원문 파일 수이며 배포 준비 완료 여부가 아닙니다. 전체 SHA-256/POM 근거는 config/oss/runtime-license-baseline.json을 참고하세요.

| 구성요소 좌표 | 대표 조건 | 실행 JAR | 고지 수 |
| --- | --- | --- | --- |
| ch.qos.logback:logback-classic:1.5.22 | EPL-1.0 | 포함 | 0 |
| ch.qos.logback:logback-core:1.5.22 | EPL-1.0 | 포함 | 0 |
| com.fasterxml.jackson.core:jackson-annotations:2.20 | Apache-2.0 | 포함 | 2 |
| com.fasterxml:classmate:1.7.1 | Apache-2.0 | 포함 | 2 |
| commons-logging:commons-logging:1.3.5 | Apache-2.0 | 포함 | 2 |
| io.micrometer:micrometer-commons:1.16.1 | Apache-2.0 | 포함 | 2 |
| io.micrometer:micrometer-core:1.16.1 | Apache-2.0 | 포함 | 2 |
| io.micrometer:micrometer-jakarta9:1.16.1 | Apache-2.0 | 포함 | 2 |
| io.micrometer:micrometer-observation:1.16.1 | Apache-2.0 | 포함 | 2 |
| jakarta.annotation:jakarta.annotation-api:3.0.0 | EPL-2.0 | 포함 | 2 |
| jakarta.validation:jakarta.validation-api:3.1.1 | Apache-2.0 | 포함 | 0 |
| org.apache.logging.log4j:log4j-api:2.25.3 | Apache-2.0 | 포함 | 2 |
| org.apache.logging.log4j:log4j-to-slf4j:2.25.3 | Apache-2.0 | 포함 | 2 |
| org.apache.tomcat.embed:tomcat-embed-core:11.0.15 | Apache-2.0 | 포함 | 2 |
| org.apache.tomcat.embed:tomcat-embed-el:11.0.15 | Apache-2.0 | 포함 | 2 |
| org.apache.tomcat.embed:tomcat-embed-websocket:11.0.15 | Apache-2.0 | 포함 | 2 |
| org.hdrhistogram:HdrHistogram:2.2.2 | BSD-2-Clause | 포함 | 1 |
| org.hibernate.validator:hibernate-validator:9.0.1.Final | Apache-2.0 | 포함 | 0 |
| org.jboss.logging:jboss-logging:3.6.1.Final | Apache-2.0 | 포함 | 0 |
| org.jspecify:jspecify:1.0.0 | Apache-2.0 | 포함 | 0 |
| org.latencyutils:LatencyUtils:2.0.3 | BSD-2-Clause | 포함 | 0 |
| org.slf4j:jul-to-slf4j:2.0.17 | MIT | 포함 | 1 |
| org.slf4j:slf4j-api:2.0.17 | MIT | 포함 | 1 |
| org.springframework.boot:spring-boot-actuator-autoconfigure:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-actuator:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-autoconfigure:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-health:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-http-converter:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-jackson:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-jarmode-tools:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-micrometer-metrics:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-micrometer-observation:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-security:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-servlet:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-starter-actuator:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-jackson:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-logging:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-micrometer-metrics:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-security:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-tomcat-runtime:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-tomcat:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-validation:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter-webmvc:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-starter:4.0.1 | Apache-2.0 | 제외된 starter | 2 |
| org.springframework.boot:spring-boot-tomcat:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-validation:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-web-server:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot-webmvc:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.boot:spring-boot:4.0.1 | Apache-2.0 | 포함 | 2 |
| org.springframework.security:spring-security-config:7.0.2 | Apache-2.0 | 포함 | 1 |
| org.springframework.security:spring-security-core:7.0.2 | Apache-2.0 | 포함 | 1 |
| org.springframework.security:spring-security-crypto:7.0.2 | Apache-2.0 | 포함 | 1 |
| org.springframework.security:spring-security-web:7.0.2 | Apache-2.0 | 포함 | 1 |
| org.springframework:spring-aop:7.0.2 | Apache-2.0 | 포함 | 2 |
| org.springframework:spring-beans:7.0.2 | Apache-2.0 | 포함 | 2 |
| org.springframework:spring-context:7.0.2 | Apache-2.0 | 포함 | 2 |
| org.springframework:spring-core:7.0.2 | Apache-2.0 | 포함 | 2 |
| org.springframework:spring-expression:7.0.2 | Apache-2.0 | 포함 | 2 |
| org.springframework:spring-web:7.0.2 | Apache-2.0 | 포함 | 2 |
| org.springframework:spring-webmvc:7.0.2 | Apache-2.0 | 포함 | 2 |
| org.yaml:snakeyaml:2.5 | Apache-2.0 | 포함 | 0 |
| tools.jackson.core:jackson-core:3.0.3 | Apache-2.0 | 포함 | 2 |
| tools.jackson.core:jackson-databind:3.0.3 | Apache-2.0 | 포함 | 2 |

최종 검증: JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java 249개 통과(실패/오류/건너뜀 0), 라이선스 변경 거절 규칙 16개 시나리오 통과, 실제 63개 구성요소·53개 포함 JAR·런처 기준선 비교 성공. UI 변경이 없어 브라우저 테스트는 재실행하지 않았으며 설치 ZIP은 생성하지 않았습니다.
