# 원본 스트림 열기 API 비교

originalStreamComparisonTest는 src/test의 읽기 전용 비교입니다.
Files.newInputStream(READ), FileInputStream(File), Channels.newInputStream(FileChannel.open(READ))를 비교하며
일반 뷰어의 opener를 선택/변경하는 옵션을 만들지 않습니다. 실제 모듈·설치 YAML·업무 자료는 사용하지 않습니다.

~~~powershell
$env:JAVA_HOME='C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat --no-daemon --console=plain originalStreamComparisonTest
~~~

JDK21·별도 JVM·힙128~512MiB·순차 실행·2048×2048 합성 PNG·32KiB 읽기/SHA 업데이트를 사용합니다.
일반 test/build와 다른 성능 작업에 포함하지 않습니다. 전체 시험은240초 상한을 사용합니다.

## 표본 배치

세 회차의 첫 읽기 실행 순서를 순환시킵니다.

| 회차 | 순서1 | 순서2 | 순서3 |
| --- | --- | --- | --- |
| 1 | FILES | FILE_INPUT | FILE_CHANNEL |
| 2 | FILE_INPUT | FILE_CHANNEL | FILES |
| 3 | FILE_CHANNEL | FILES | FILE_INPUT |

각 첫 읽기는 새로운 등록 원본 파일에 배정합니다. 그 파일을 먼저 다른 API로 열거나 읽지 않습니다.
새 파일 한 개의 첫 읽기 뒤에는 같은 파일을 세 API로 다시 읽고 모두 SHA를 확인합니다.
재읽기 순서도 회차/위치에 따라 회전합니다. 총9개 원본, 첫 읽기9개·재읽기27개입니다.
각 API는 첫 읽기3개·재읽기9개를 가지며 서로 다른 firstOrder 위치를 한 번씩 경험합니다.
등록 후 경과 시간(ageSinceRegistrationMs)도 남겨 파일 생성 시점/대기 차이를 확인할 수 있습니다.

새 파일은 시험 직전에 쓰므로 **OS 디스크 캐시가 비어 있다는 의미가 아닙니다**.
OS 캐시 강제 비우기·보안 기능 해제·강제 GC는 수행하지 않습니다.
세 개의 최초 표본으로 운영 P95나 API 우열을 일반화하지 않습니다.

## 공통 조건과 검증

- 실제 LocalMediaStore 등록/resolveOriginal, 소유자/조직 일치와 실제 FeaturePolicy의 IMAGE_VIEW를 확인합니다.
- 모든 방식은 읽기 전용이며 동일한 정규 절대 경로·NOFOLLOW 일반 파일·기록 크기를 요구합니다.
- 읽기 전후 fileKey/크기/mtime과 정규 경계를 확인하고 전체 바이트·SHA가 등록 기록과 일치해야 합니다.
- 스트리밍 읽기는32KiB이고 청크 경계/마지막에 interrupt를 검사합니다. 플래그를 지우지 않습니다.
- try-with-resources로 스트림/채널을 닫고 원본을 변경하지 않습니다.
- 일반 회귀 테스트가 세 방식의 바이트 보존·SHA 변조 거절·잘못된 경계/크기·사전 취소를 검사합니다.

API마다 실행 중 I/O가 인터럽트에 반응하는 세부 구현은 다를 수 있습니다.
이는 동일한 협력적 취소/무결성 조건의 비교이며 OS hard timeout이나 원자적인 재파스 공격 방어를 보장하는 시험은 아닙니다.
실제 저장소는 기존과 같이 전용 계정·ACL의 신뢰 경계가 필요합니다.

## 보고서

build/reports/stream-comparison/latest.json과 Gradle의 test-results/originalStreamComparisonTest에 결과를 씁니다.
build/stream-comparison/run-*에 합성 원본을 보존합니다. latest는 매번 덮어쓰므로 독립 실행은 별도 이름으로 복사합니다.
컴파일/VM 중단이면 이전 latest가 남을 수 있어 createdAt·storage·Gradle 성공을 함께 확인합니다.
raw samples에 first/repeat·API·순서·파일 나이·open/read/update/close/final/total·바이트/호출 수와 SHA를 기록합니다.
total은 사전 권한/경계 확인을 제외하고 열기부터 종료 후 경계 재검사까지 포함합니다.
검증 통과는 성능 승인이나 다른 운영 파일/OS 환경의 같은 결과를 의미하지 않습니다.

## 2026-10-26 독립 JVM 두 실행 결과

두 실행 모두36회 전체 읽기/SHA·바이트 수·EOF 관계·원본 경계/정체성 검증을 통과했습니다. 총18개 신규 원본과72개 읽기 표본을 보존합니다. 각 API의 최초 표본은 실행당3개이며 순서1/2/3을 한 번씩 차지했습니다.

| API | 실행1 최초 열기 평균 | 실행2 최초 열기 평균 | 두 실행 재열기 범위 |
| --- | ---: | ---: | ---: |
| FILES | 1192.142ms | 1970.523ms | 0.112~0.840ms |
| FILE_INPUT | 1172.929ms | 1657.337ms | 0.111~0.338ms |
| FILE_CHANNEL | 1190.568ms | 1651.922ms | 0.117~0.440ms |

관측 범위에서 세 방식 모두 신규 파일의 첫 열기에 초 단위 지연이 있고 재열기는1ms 미만이었습니다. 첫 실행의 최초 열기는 약1.15~1.22초, 두 번째는 약1.38~2.41초로 실행 사이 변동도 있었습니다. 이 작은 표본으로 보편적인 API 성능 순위나 동일성 검정을 주장하지 않습니다.

이전 스트레스 시험은 등록된 원본 묶음과2워커 렌더링이고 이번 시험은 등록 직후 파일의 순차 검증입니다. 파일 나이·동시성·시스템 상태가 다르므로 이전4초와 이번1~2초를 API 변경의 개선 효과로 비교하지 않습니다.

**결정:** 일반 뷰어의 Files.newInputStream을 교체할 근거가 부족해 현재 동작을 유지했습니다. 다음은 등록 후 경과 시간과 동시 열기 수를 통제한 시험 및 열기 중 스택 관측으로 공통 초기 지연을 좁힙니다. OS 보안 설정이나 원본 SHA 검증은 해제하지 않습니다.

보고서: [실행1](../build/reports/stream-comparison/run1.json), [실행2](../build/reports/stream-comparison/run2.json).

최종 검증: 2026-10-26 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(5m50s). 일반 Java524개/83개 suite, 실패·오류·건너뜀0. 신규12개(3 API별 바이트/SHA·변조·정규/파일 경계·사전 interrupt), 기존 이미지/취소/자원 회귀·실제 img2tile/FFmpeg 통합·라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. opt-in originalStreamComparisonTest 독립 JVM 두 실행35s/50s 통과: 신규 원본18개, 전체 읽기/SHA72표본(최초18/재읽기54), 순서 회전/EOF/전후 정체성/크기·원본 보존 검증. API별 최초 열기 평균 실행1 1192.1/1172.9/1190.6ms, 실행2 1970.5/1657.3/1651.9ms(FILES/FILE_INPUT/FILE_CHANNEL), 재열기 전체0.111~0.840ms. 세 API에 공통 초기 지연과 실행 변동이 있어 생산 opener는 미변경; 순차 비교와 이전2워커 부하를 개선 전후로 해석하지 않음. 실행 JAR hash timing smoke·테스트/fixture runtime JAR 항목0개 확인. 실제 설치 YAML·모듈·업무 자료 변경 및 설치 ZIP 생성 없음. OS 원인/실 운영 P95/최종 배포 승인은 별도이며 distributionReady=false 유지. ORIGINAL_STREAM_COMPARISON.md 참조.

등록 후 경과 시간·동시 열기·표적 스택 관측 및 관측 비활성 대조 결과는 [ORIGINAL_OPEN_CONTEXT.md](ORIGINAL_OPEN_CONTEXT.md)를 참고합니다.
