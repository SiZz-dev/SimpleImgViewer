# 원본 최초 열기 시간·동시성·스택 관측

originalOpenContextTest는 새2048×2048 합성 원본과 FILES 읽기 전용 API로
등록 완료 후 경과 시간과 동시에 진입 가능한 워커 수를 비교하는 opt-in 시험입니다.
일반 뷰어·원본 저장 정책·OS 캐시/보안 설정은 바꾸지 않습니다.

~~~powershell
$env:JAVA_HOME='C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat --no-daemon --console=plain originalOpenContextTest
~~~

별도 JDK21 JVM, 힙128~512MiB, 시험 전체240초 상한입니다.
build 아래 정규 경계에만 쓰며 시작 시1GiB 이상의 여유 공간을 요구합니다.
일반 test/build·다른 성능 시험에는 포함하지 않습니다. 실제 모듈·업무 자료·설치 YAML은 사용하지 않습니다.

## 실험 배치

즉시(최소 대기0ms)/등록3초 후 × 워커1/2의 네 조건을 두 블록에서 실행합니다.
두 번째 블록은 조건 순서를 뒤집습니다. 각 조건에 항상 새 원본2개를 등록해 등록 묶음 크기를 맞춥니다.
워커1 조건은 그중1개만 측정하고 선택 슬롯을 블록별로 바꿉니다.
워커2 조건은 두 파일을 각각 워커에 배정합니다. 측정하지 않은 동반 파일도 마지막에 SHA를 검증합니다.

대상 워커는 소유자/조직·실제 FeaturePolicy와 카탈로그/원본 경계를 확인한 뒤 barrier에서 대기합니다.
마지막 파일 등록 완료 시점부터 목표 시간이 지날 때 barrier를 해제합니다.
첫 열기 후 같은 파일을 같은 워커 풀에서 다시 읽습니다. 원본16개, 첫 읽기12/재읽기12가 기본 측정 대상입니다.
모든 원본16개를 마지막에 다시 전체 SHA 검증하며 열기/읽기/업데이트/닫기/최종 비교는 기존 API 비교와 동일합니다.
원본은32KiB 청크로 읽고 EOF·원본 크기·SHA·전후 정체성을 검사합니다.

actualAgeAtOpenMs는 해당 원본 등록 메서드 반환부터 열기 표지 시작까지입니다. 목표값은 최소 대기이며 정확한 실제 시간을 별도로 기록합니다.
openAfterReleaseMs와 openMs로 같은 조건의 열기 구간 겹침을 재구성할 수 있습니다.
workers는 설정된 동시 진입 수이고 관측 peakActiveOpens가 실제 열기 표지 겹침 수입니다.
빠른 열기가 서로 겹치지 않을 수 있으므로 workers=2만으로 OS 열기 호출이 항상 겹쳤다고 해석하지 않습니다.
새로 쓴 파일은 OS cache-cold를 의미하지 않고 시험 중 강제 캐시 비우기나 강제 GC도 하지 않습니다.

## 표적 스택 관측

src/test의 OpenStackSampler는 명시적으로 등록한 이 시험의 작업 스레드만,
stream open 직전/직후 표지가 활성인 동안20ms 간격으로 관측합니다.
전체 JVM 스레드 덤프·다른 앱/서버 프로세스·업무 스레드는 수집하지 않습니다.
표지의 동일성을 스택 조회 전후에 확인해 열기 종료 뒤 읽기 구간이 섞이는 것을 줄입니다.
경계 전후 아주 짧은 실행 프레임이 포함될 수 있으므로 표본을 보고 해석해야 합니다.

파일 경로·내용·사용자·미디어 ID·스레드 ID/이름은 출력하지 않습니다.
고정 합성 라벨·Thread.State·클래스/메서드명·native 여부·개수만 집계합니다.
최대64라벨, 라벨별16스택 종류, 스택당12프레임으로 제한하고 초과 표본은 droppedSamples에 기록합니다.
samplingErrors는0이어야 하며 모든 단계 종료 후 active 표지도0이어야 합니다.
20ms 미만 열기에 표본이 없을 수 있고 표본 비율이 정확한 시간 비율은 아닙니다.
스택 조회의 VM/스케줄링 오버헤드가 포함되므로 비계측 API 비교와 정밀한 속도 개선 수치로 비교하지 않습니다.
native open 프레임이 관측돼도 특정 디스크/파일 시스템 드라이버/보안 제품이 원인이라고 단정할 수 없습니다.

## 결과와 안전 경계

build/reports/open-context/latest.json과 Gradle test-results/originalOpenContextTest에 기록합니다.
합성 원본은 build/open-context/run-*에 보존하며 독립 실행의 latest는 별도 이름으로 복사합니다.
이전 JSON이 남을 수 있으므로 createdAt·storage와 해당 Gradle 실행 성공을 확인합니다.
기본 경계/소유/무결성/취소/닫기 조건을 유지하며 워커 풀과 관측기를 각각 중단하고 종료를 확인합니다.
JDK 동기 I/O의 강제 종료나 OS 원인 확정·운영 SSO/HTTP/장시간 부하 인수는 별도입니다.

## 스택 비관측 대조군

~~~powershell
.\gradlew.bat --no-daemon --console=plain originalOpenContextTest -PopenContextSampleStacks=false
~~~

기본값은 true이며 값은 true/false만 허용합니다. 이 옵션은 테스트 작업에만 있고 설치/HTTP 설정이 아닙니다.
false이면 같은 작업 표지·시간/무결성 측정을 유지하되 주기적 getStackTrace를 실행하지 않습니다.
schemaVersion=2의 sampleStacksEnabled와 stackSampling.enabled로 구분합니다.
v1 보고서는 기본 활성 수집 상태입니다.

각각 새 JVM/새 원본을 사용하므로 별도 실행 간 시스템 부하·캐시 조건 차이가 남습니다.
비관측 결과를 단순 차감해 순수한 관측 비용을 확정하지 않고,
지연의 존속과 조건별 경향을 확인하는 대조군으로 사용합니다.
반복 표본이 적고 순서만 역전시킨 소규모 진단이므로 일반적인 원인/성능 SLA를 주장하지 않습니다.

## 2026-10-26 관측 실행2회와 비관측 대조군

모든 실행은 독립 JVM/새 합성 원본이며 조건 순서를 두 번째 블록에서 뒤집었습니다. 실행당 원본16개, 최초 열기12/재읽기12와 원본16개 SHA 재검사를 통과했습니다. 두 관측 실행에서 actualAge 하한·활성 표지 회수·원본 정체성/크기·관측 오류0을 확인했습니다.

| 최초 열기 조건 | 관측 실행1 평균 | 관측 실행2 평균 | 스택 비관측 대조 평균 |
| --- | ---: | ---: | ---: |
| 즉시 / 1워커 | 1740.9ms | 1754.6ms | 1203.3ms |
| 등록3초 후 / 1워커 | 1565.4ms | 2080.5ms | 1204.6ms |
| 즉시 / 2워커 | 5930.4ms | 6817.0ms | 3822.8ms |
| 등록3초 후 / 2워커 | 6578.4ms | 5925.5ms | 3819.9ms |

1워커 조건은 실행당 최초 표본2개, 2워커는4개입니다. 작은 표본과 별도 실행의 환경 변동이 있어 인과/운영 P95나 관측 오버헤드의 정확한 크기를 단정하지 않습니다.

스택 비관측 대조에서도 2워커 최초 열기 구간은 실제로 약3.69~3.80초 겹쳤고 관측 peakActiveOpens=2였습니다. 표지 겹침과 워커 설정을 함께 확인했습니다. 재열기 표본은 모든 실행에서1ms 미만이었습니다.

관측 실행1의 1787개, 실행2의 1849개 스택 표본은 모두 최상단이 sun.nio.fs.WindowsNativeDispatcher.CreateFile0 [native]였습니다. 상태는 RUNNABLE로 기록됐으나 이 값으로 CPU 사용률을 추론하지 않습니다. Java 측에서 Windows 파일 열기 호출을 벗어나기 전이라는 위치를 확인한 것이며 파일 시스템/드라이버/보안 제품 중 하나를 원인으로 확정한 것은 아닙니다.

대조군은 sampleStacksEnabled=false이며 스택0개, 관측 오류0개, 종료 시 활성 표지0개였습니다. 관측 실행들은 비관측보다 절대 시간이 길어 계측 영향/환경 변동을 고려해야 하지만, 비관측에서도 공통 최초 열기 지연과 동시 열기 조건의 더 긴 시간이 남았습니다. 따라서 관측기를 유일한 원인으로 해석하지 않습니다.

**후속 결정:** 일반 뷰어/API·SHA/OS 보안 설정은 그대로 유지합니다. 이 환경에서 등록3초 후라는 대기 조건은 지연을 없애지 못했고, 고정 sleep을 추가하는 개선안의 근거도 아닙니다. 다음은 동일2워커/2파일에서 원본 열기 단계만1개로 제한하는 대조 시험으로 전체 처리 시간·최초 결과 시간·취소/예외·허가 회수 여부를 비교합니다. 기본 동작 변경은 그 근거를 확보한 뒤 별도 검증합니다.

보고서: [관측 실행1](../build/reports/open-context/run1.json), [관측 실행2](../build/reports/open-context/run2.json), [스택 비관측 대조](../build/reports/open-context/control-no-stacks.json).

최종 검증: 2026-10-26 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(4m57s). 일반 Java531개/84개 suite, 실패·오류·건너뜀0. 신규7개(대상/라벨/스택 상한·비관측 표지4, 3 API 열기 실패 시 표지 회수3) 및 기존 원본 경계·SHA·취소 회귀, 실제 img2tile/FFmpeg 통합·라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. opt-in originalOpenContextTest 관측 두 실행1m32s/1m25s 및 비관측 대조1m3s 성공: 총72측정 표본·원본48개 최종 SHA·파일 정체성 검증, 활성 표지/관측 오류0. 관측 스택3636표본의 최상단은 WindowsNativeDispatcher.CreateFile0(native), 하위 OS/드라이버/보안 제품 원인은 미확정. 비관측 최초 open 평균 즉시1워커1203.3ms/2워커3822.8ms, 3초 후1워커1204.6ms/2워커3819.9ms; 2워커 열기 중첩 약3.69~3.80s 확인. 단순3초 대기로 해소되지 않았고 기본 뷰어·OS/SHA 정책은 미변경. 실행 JAR hash timing smoke와 OpenStackSampler 포함 테스트/fixture JAR 항목0개 확인. 실제 설치 YAML·모듈·업무 자료 변경 및 설치 ZIP 생성 없음. 실 운영 P95·OS/native 메모리·배포 준비와 구분하며 distributionReady=false 유지. ORIGINAL_OPEN_CONTEXT.md 참조.

2워커를 유지하며 열기만1개로 제한한 실제 미리보기 준비 대조 결과는 [ORIGINAL_OPEN_ADMISSION.md](ORIGINAL_OPEN_ADMISSION.md)를 참고합니다.
