# 원본 열기 전용 허가 대조 실험

이 문서의 최초 결과는 테스트 전용 허가 실험입니다. 이후 설치 선택·유한 대기/종료를 구현한 현재 운영 정책과 별도 측정은 [ORIGINAL_OPEN_POLICY.md](ORIGINAL_OPEN_POLICY.md)를 참고하세요. 기본 설정은 계속 비활성입니다.

두 PreviewQueue 워커와 두 원본을 고정하고 원본 열기 허가를1개/2개로 비교하는 opt-in 시험입니다.
실제 ImagePreviewRenderer·LocalMediaStore·FeaturePolicy·PreviewCache를 사용하며,
SHA/정규 원본 조회/권한·픽셀 검증·썸네일·게시/정리 정책을 유지합니다.

~~~powershell
$env:JAVA_HOME='C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat --no-daemon --console=plain originalOpenAdmissionTest
.\gradlew.bat --no-daemon --console=plain originalOpenAdmissionTest -PopenAdmissionFirstLimit=1
~~~

별도 JDK21 JVM·힙128~512MiB·2048×2048 합성 PNG를 사용합니다. firstLimit는1/2만 허용합니다.
두 블록에서 허가1/2 실행 순서를 뒤집고, 두 번째 독립 실행은 시작 허가도 바꿉니다.
각 조건에 새 원본2개를 등록하고 최초 처리 뒤 같은 파일을 새로운 캐시로 재처리합니다.
실행당 원본8개·8배치·미리보기16개입니다. 워커는 항상2개이며 렌더러 진입 barrier로 시작을 맞춥니다.
스택 수집·강제 GC·OS 캐시/보안 변경·실제 모듈/업무 자료/설치 YAML 사용은 하지 않습니다.
build 아래 정규 경계에만 쓰고1GiB 여유 공간과 전체240초 상한을 요구합니다.
일반 build/test 및 다른 성능 작업에 포함하지 않습니다.

## 허가 범위와 안전성

OpenPermitGate는 src/test에만 있는 공정 Semaphore 실험 도구입니다.
대기는 interruptible이며 취소 플래그를 복원하고 InterruptedIOException으로 종료합니다.
획득 뒤 다시 취소를 검사하고 성공/실패 모두 finally에서 허가를 반환합니다.
대기 취소가 다른 작업의 허가를 반환하지 않는지 검증합니다.

허가는 Files.newInputStream이 반환되거나 실패할 때 즉시 해제합니다.
반환된 스트림의 읽기·닫기·SHA·디코딩·PNG/썸네일 생성까지 묶어서 직렬화하지 않습니다.
원본 경로는 기존 권한/카탈로그 확인으로 얻고 전체 SHA 비교도 그대로 수행합니다.
관찰자는 IO를 교체하지 않습니다. 같은 패키지의 별도 진단 생성자만 실험용 opener를 받습니다.
public/default 생성자의 원본 열기는 기존 Files.newInputStream을 유지하고 HTTP/설치 옵션은 추가하지 않습니다.
실험용 다른 바이트를 공급해도 SHA 불일치가 work 생성 전에 거절되는 것을 테스트합니다.

단위 검증은 허가 범위·읽기/닫기 전 반환·열기 실패 반환·사전 취소·대기 중 취소·대기자 허가 오반환 방지를 확인합니다.
JDK native open 자체의 강제 취소/하드 기한을 제공하지는 않습니다.
멈춘 open이 허가를 보유하는 위험과 전역 입구 직렬화의 서비스 영향은 실제 도입 전에 별도로 검토해야 합니다.

## 보고서

build/reports/open-admission/latest.json과 Gradle test-results/originalOpenAdmissionTest에 씁니다.
build/open-admission/run-*의 합성 원본/완료 캐시는 보존하고 독립 실행 JSON을 다른 이름으로 복사합니다.
컴파일/VM 중단 시 이전 latest가 남을 수 있어 createdAt·storage·Gradle 성공을 함께 확인합니다.

- firstReadyMs/allReadyMs: barrier 해제 후 큐의 ready 상태가 처음1개/모두2개 관측될 때까지입니다.
  허가 대기·원본 SHA·이미지 처리·캐시 게시·상태 폴링을 포함하는 서버 준비 시간입니다.
  5ms 폴링 및 스케줄링 오차가 있으며 HTTP/브라우저 최초 표시나 운영 SLA가 아닙니다.
- renderMs: 실제 renderer 호출 전체이며 허가 대기를 포함합니다.
- gateWaitMs/nativeOpenMs: 허가 대기와 실제 opener 호출을 분리합니다. 기다린 시간을 제외해 개선으로 보이게 하지 않습니다.
- batchWithVerificationMs: 접수부터 이미지/썸네일 검증·큐 종료까지로, ready 수치보다 범위가 큽니다.
- gate: active/waiting0, acquired=released=2, available=capacity를 매 배치 검증합니다.

매 배치 실제 PNG/썸네일을 디코딩하고 크기를 검사합니다. 종료 후 writer를 다시 확보하고
상태2개/완료 PNG4개만 허용해 work/임시/추가 파일 잔재를 검사합니다.
마지막에 원본8개를 다시 전체 SHA로 확인합니다.
기존 cache/원본 보안 조건을 약화하지 않고, 결과와 실패 시나리오를 확인하기 전 기본 동작을 바꾸지 않습니다.

## 2026-10-26 독립 JVM 두 실행 결과

실행1은 허가2를 먼저, 실행2는 허가1을 먼저 시작했고 각 JVM의 두 번째 블록에서도 순서를 뒤집었습니다. 각 실행의 최초 처리 배치는 허가별2개(파일4개), 재처리 배치도 허가별2개입니다.

| 최초 처리 배치 평균 | 실행1 허가2 | 실행1 허가1 | 실행2 허가2 | 실행2 허가1 |
| --- | ---: | ---: | ---: | ---: |
| 첫 미리보기 ready | 5120.4ms | 1841.0ms | 5253.2ms | 1895.7ms |
| 미리보기2개 모두 ready | 5137.6ms | 3182.9ms | 5329.8ms | 3192.6ms |

첫 ready는 각 실행에서 약64.0% / 63.9%, 모두 ready는 약38.0% / 40.1% 짧았습니다. 허가 대기를 빼지 않은 큐 ready 관측 결과입니다.

허가1의 두 번째 호출은 실제 허가 대기 뒤 열리며, 대기 시간은 renderMs와 ready 시간에 포함됩니다. 반환된 스트림 읽기·SHA·크기 조정/PNG 단계는 잠그지 않았습니다. 허가1/2 모두 매 배치 acquired=released=2, active/waiting0, available=capacity였습니다.

재처리의 모두 ready 평균은 실행1 허가1 555.4ms/허가2 637.7ms, 실행2 허가1 587.0ms/허가2 629.0ms였습니다. 작은 표본이라 일반적인 warm 성능 우열이나 운영 SLA를 확정하지 않습니다.

총 원본16개·미리보기32개(썸네일32개), 배치16개의 픽셀 크기·원본 SHA 재검증·writer 재확보·work/임시/추가 산출물 잔재0을 확인했습니다. 사전 취소·대기 취소·열기 실패 및 반환된 스트림을 아직 읽거나 닫지 않은 상태에서도 허가가 반환됨을 단위 검증했습니다.

**결정:** 이 환경의 최초 준비 지연을 줄일 후보로 open-only 제한1의 근거가 확보됐습니다. 일반 생성자/기본 설치 동작은 아직 변경하지 않았습니다. 다음 단계는 환경별 설치 선택·유한 허가 대기·취소/실패/종료 수명을 갖춘 운영용 정책을 구현하고 비활성 호환성과 활성화 효과를 검증하는 것입니다. native open 자체가 반환하지 않을 때의 OS 격리 한계는 별도입니다.

보고서: [실행1](../build/reports/open-admission/run1.json), [순서 역전 실행2](../build/reports/open-admission/run2.json). 현재 호스트의 합성 데이터 결과로 실 업무/HTTP/브라우저 P95나 모든 파일 시스템의 이득을 주장하지 않습니다.

## 전체 회귀 및 실행 JAR 검증

2026-10-26 JDK21의 build -Pimg2tileIntegration -PffmpegIntegration을 통과했습니다. 일반 Java537개/85개 suite의 실패·오류·건너뜀은0입니다. 독립 성능 작업은 일반 test에서 제외되며 별도 두 실행은47s/44s에 성공했습니다. 최종 build 재확인은23s(test UP-TO-DATE)였습니다.

라이선스16개 규칙과63개 식별 구성요소/53개 포함 JAR 기준선 검증을 통과했습니다. 이는 최종 배포 준비 판정이 아니며 distributionReady=false를 유지합니다. 실행 JAR의 해시 IO 위임·닫기/예외 smoke를 통과했고, OpenPermitGate/OpenStackSampler를 포함한 테스트/fixture 클래스 항목은0개였습니다. 실제 설치 설정/모듈/업무 자료 변경이나 설치 ZIP 생성은 수행하지 않았습니다.
