# 운영 지표 활성/비활성 교차 비교

2026-10-27. [운영 지표](PREVIEW_METRICS.md)의 수집 유무만 바꾼 실제 미리보기 처리와 반복 수명을 검증합니다. 별도 opt-in 작업이며 일반 build/test에는 포함하지 않습니다. 운영 코드/설치 기본값을 변경하지 않습니다.

~~~powershell
$env:JAVA_HOME='C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat --no-daemon --console=plain previewMetricsComparisonTest
.\gradlew.bat --no-daemon --console=plain previewMetricsComparisonTest -PmetricsFirst=on
.\gradlew.bat --no-daemon --console=plain previewMetricsComparisonTest -PmetricsFirst=off -PmetricsRounds=100
~~~

## 비교 조건

JDK21의 별도 JVM/힙128~512MiB,2워커/큐2, 원본 열기 허가1/대기10초를 고정합니다. 실제 LocalMediaStore·권한/FeaturePolicy·ImagePreviewRenderer·PreviewQueue·PreviewCache·PreviewMetrics를 사용합니다. 원본은 동일 시드2048×2048 합성 PNG이며 출력512×512/썸네일256×256입니다. native open/버퍼 IO를 실험용 구현으로 교체하거나 PreviewTiming 단계 관찰자를 추가하지 않습니다.

실행당 두 블록에서 모드별 새로운 원본2개씩 등록합니다. 각 모드의 최초 처리 뒤 같은 원본을 재사용하고 매번 별도 캐시에 실제 렌더링합니다. 블록과 반복 라운드에서 on/off 순서를 교차하며 두 번째 독립 JVM은 최초 모드도 뒤집습니다. 기본 rounds20이면 최초4배치/반복80배치, 총84배치·미리보기168개/썸네일168개/원본8개입니다. rounds100이면404배치·미리보기808개/썸네일808개/원본8개입니다.

rounds는5~100, first는off/on만 허용합니다. build 아래 정규 경계에만 합성 파일을 쓰고 시작 여유 공간과 매 배치256MiB 예비 공간을 검사합니다. 테스트 전체1200초, barrier10초, 배치 ready45초 상한을 둡니다. JDK native I/O 하드 종료를 제공하는 기한은 아닙니다. 합성 원본과 캐시는 보존하며 실제 저장소/설치 YAML·모듈을 수정하거나 운영 보안/OS 캐시를 변경하지 않습니다.

## 시간과 지표 해석

- firstReadyMs/allReadyMs는 두 워커 진입 barrier 해제부터 처음1개/모두2개가 큐 ready로 관측될 때까지입니다. 허가 대기·원본 SHA·디코딩·PNG/썸네일·캐시 게시를 포함합니다.5ms 폴링/스케줄링 오차가 있고 업로드/등록·레지스트리 초기 구성/HTTP/브라우저 표시 시간은 제외합니다.
- firstFromAdmissionMs/allFromAdmissionMs는 해당 배치 큐 구성/접수 시작부터 ready까지이며, admissionMs(큐 구성/접수·두 워커 진입 동기화)를 더합니다. 접수/큐 시작 시의 지표 기록 비용을 제외하지 않도록 함께 비교합니다. 레지스트리 초기 등록은 여전히 제외합니다.
- withVerificationMs는 해당 배치의 큐 구성/접수부터 출력 검증·종료·캐시 writer 재확보까지입니다. ready 시간보다 범위가 넓습니다.
- 첫 원본 처리와 재처리를 구분합니다. 파일 생성 직후 native open 변동과 JVM 워밍업 때문에 작은 최초 표본에서 지표 오버헤드의 인과 효과를 단정하지 않습니다. 원본 등록 후 배치 시작까지의 나이도 기록합니다.
- 활성 모드의 jobTotalMs/queueWaitMs/admissionWaitMs/nativeOpenMs는 해당 블록·모드 누적 Timer 합입니다. 두 동시 Job의 시간을 합한 값이며 배치 벽시계 시간과 같지 않습니다.
- 힙/GC 값은 강제 GC 없는 JVM 관측이며 네이티브 메모리/RSS/누수 부재/운영 장시간 안정성 증명이 아닙니다. 활성/비활성 객체와 fixture가 같은 JVM에 공존하므로 모드별 힙 사용량으로 비교하지 않습니다.

## 각 반복의 검증

두 이미지와 썸네일을 실제 디코딩하고, 재인코딩 결과 파일 SHA가 모든 모드/블록/반복에서 완전히 일치하는지 확인합니다. 마지막에 원본8개를 다시 전체 SHA/속성 경계로 검증합니다.

활성 모드는 매 배치 종료 후34개 고정 meter·accepted/ready/Job duration/큐 시작·허가 admitted/native opened가 해당 모드 누적 Job 수와 같은지 확인합니다. 나머지 실패·거절·취소 표본은0, 현재 queue waiting/running/original active/waiting Gauge는0이어야 합니다. 비활성 모드는 레지스트리의 meter가0개여야 합니다. 파일/사용자/경로 라벨을 만들지 않습니다.

실제 renderer close를 큐에 위임하고 매 배치 종료 후 캐시 writer를 다시 확보합니다. 상태2개/완료 PNG4개만 허용해 work/임시/추가 항목 잔재를 거절합니다. 최소한 이 반복 범위의 큐/허가·lease 종료, 집계 누락/누적·카디널리티 증가·픽셀 변경을 검증하며 운영 중단/실자료 복구를 수행하지 않습니다.

## 보고서와 한계

build/reports/metrics-comparison/latest.json과 test-results/previewMetricsComparisonTest에 기록합니다. 각 독립 실행 뒤 latest를 다른 이름으로 복사해 보존합니다. latest는 컴파일/VM 중단 시 이전 결과일 수 있으므로 createdAt·storage·passed와 Gradle 성공을 함께 확인합니다.

서버 합성 측정의 평균/백분위는 실 업무/브라우저 P95나 모든 파일 시스템/부하에 대한 성능 보장이 아닙니다.100회 조건도 수 분 단위의 유한 반복이며 수 시간/수일 운영 soak를 완료한 것으로 표현하지 않습니다. 설치 지표 수집 기본 비활성·원본 열기 제한 기본 비활성·관리 API 비공개·설치 ZIP 최종 단계 원칙을 유지합니다.

## 순서 역전 독립 비교 결과

두 독립 JVM에서 최초 모드를 off/on으로 뒤집었습니다. 각 실행은 schema2/rounds20,84배치·원본8개·미리보기/썸네일 각168개입니다. 준비시간 표는 접수·큐 구성·워커 진입 동기화부터 두 결과 ready까지의 반복 배치40개/모드 평균과 nearest-rank 백분위입니다.

| 반복 처리 | 꺼짐 평균 | 켜짐 평균 | 꺼짐 P50 | 켜짐 P50 | 꺼짐 P95 | 켜짐 P95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 실행1 | 929.4ms | 969.0ms | 874.5ms | 894.3ms | 1327.9ms | 1337.3ms |
| 실행2 | 936.9ms | 890.7ms | 898.7ms | 848.7ms | 1238.4ms | 1161.2ms |

실행1에서는 켜짐 평균이 약4.3% 길고 실행2에서는 약4.9% 짧아 방향이 일치하지 않았습니다. 현재 합성 표본에서 일관된 추가 지연을 확정하지 않으며, 계측이 성능을 개선했다고 해석하지도 않습니다. JVM/GC·파일/캐시·OS 상태·반복 시점의 변동을 포함합니다. 최초 처리 표본은 모드별2개/실행으로 별도 JSON에 보존하며 순수 지표 비용의 근거로 사용하지 않습니다.

두 실행의 원본 SHA/전체 반복 픽셀·썸네일 파일 SHA/치수, 지표 정확한 누적 및32개 고정 meter/비활성0개, 모든 배치 종료 후 Gauge0·writer 재확보·work/임시/추가 파일 잔재0을 검증했습니다. 독립 JVM 간 출력 SHA도 같았습니다.

보고서: [실행1](../build/reports/metrics-comparison/run1.json), [순서 역전 실행2](../build/reports/metrics-comparison/run2.json). schema1의 exploratory.json은 접수 포함 ready 필드 보강 전 프로토콜 점검이며 이 표/공식 집계에서 제외합니다.

## 추가100회 반복 결과

최초 off로 시작해 각 블록·모드100회씩 반복한 독립 JVM 실행이 Gradle8분17초에 성공했습니다. fixture 실제 실행 구간은469.0초였습니다. 두 블록 합계404배치/미리보기808개·썸네일808개/원본8개입니다.

| 추가 반복(모드별200배치) | 평균 | P50 | P95 |
| --- | ---: | ---: | ---: |
| 꺼짐 | 931.2ms | 873.4ms | 1324.1ms |
| 켜짐 | 937.8ms | 855.3ms | 1298.6ms |

접수 포함 전체 ready 평균의 켜짐 차이는 약0.7%였고, P50/P95에서는 켜짐이 더 짧았습니다. 앞선 두 실행의 차이 방향도 일치하지 않아 이 결과만으로 일정한 계측 비용/성능 개선이나 운영 SLA를 확정하지 않습니다. 기본 비활성 설정은 유지합니다.

모든 배치의 원본 검증·미리보기/썸네일 출력 SHA·지표 예상 누적/32개 고정 meter·비활성0개·Gauge0·캐시 writer 재확보 및 잔재0을 확인했습니다. 이전 독립 실행과도 출력 SHA가 같았습니다. 공식3실행 합계572배치, 미리보기/썸네일 각1144개, 원본24개의 최종 SHA를 검증했습니다(schema1 탐색 실행 제외).

추가 반복 JVM의 배치 경계 힙 최대 관측값은178.2MiB였습니다. 관측 전/후 GC 횟수는2/2080, 누적 GC 시간은15/4237ms입니다. 강제 GC나 OS/RSS 측정을 하지 않아 이 수치로 누수 부재/네이티브 메모리 상한을 주장하지 않습니다. 수 분 단위 유한 반복 결과이며 수 시간/수일 운영 soak는 잔여입니다.

보고서: [추가100회 반복](../build/reports/metrics-comparison/repeat100.json).

## 최초 처리 표본(반복 통계와 분리)

| 실행 | 꺼짐 평균 | 켜짐 평균 | 모드별 표본 수 |
| --- | ---: | ---: | ---: |
| 1 | 4627.4ms | 5407.5ms | 2 |
| 2 | 5665.7ms | 5525.7ms | 2 |
| 추가100회 | 5306.5ms | 4631.6ms | 2 |

위 수치는 접수/새 큐·캐시 구성·최초 원본 SHA/렌더링/게시를 포함하며 최초 native 파일 열기·JVM 초기화 변동이 큽니다. 생성 시점/원본 나이도 완전히 같지 않고 표본이 적어, 반복 결과와 합쳐 순수 계측 비용으로 계산하지 않습니다. 최초 표시 속도 문제가 해소됐다는 결과도 아닙니다.

## 전체 회귀 및 배포 경계

2026-10-27 JDK21 build -Pimg2tileIntegration -PffmpegIntegration이6분40초에 성공했습니다. 일반 Java567개/88개 suite의 실패·오류·건너뜀은0이며 새 opt-in 비교 클래스는 일반 test 결과에 포함되지 않았습니다. 실제 img2tile·FFmpeg 통합과 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다.

실행 JAR 해시 IO smoke와 테스트/fixture 클래스 항목0개를 확인했습니다. 이번 변경은 테스트/Gradle 검증 작업과 문서에 한정하며 런타임 코드/의존성/설치 기본값·실제 설치 YAML/모듈/업무 자료를 변경하지 않았습니다. distributionReady=false를 유지하고 설치 ZIP은 생성하지 않았습니다.

다음 작업은 대형 이미지 타일 조회의 현재 변환 모듈 지원 범위를 확인하고 보호된 API·좌표/레벨·캐시 및 용량/동시 처리 계약을 설계하는 단계입니다. 검증되지 않은 출력 규격을 추측해 원본 해상도 타일을 제공하지 않습니다.

현재 용량 부족 분류 추가 버전은 고정 meter34개입니다. 위 성능 측정의32개는 당시 구현의 실제 기록이며 변경하지 않습니다. NATIVE_CAPACITY_RETRY.md를 참고하세요.
