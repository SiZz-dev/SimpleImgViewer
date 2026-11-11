# 미리보기 운영 지표

2026-10-27. 기존 Actuator/Micrometer 레지스트리에 미리보기 작업과 원본 열기 허가의 고정 지표를 수집합니다. 새 라이브러리/공개 관리 API/외부 exporter는 추가하지 않습니다.

~~~yaml
imagegate:
  preview:
    metrics-enabled: false
~~~

기본은 false이며 기존 YAML에 항목이 없어도 false입니다. 설치 시 true로 선택하고 재시작하면 수집합니다. 실제 config/imagegate.yml은 수정하지 않았습니다. metrics-enabled는 원본 열기 제한의 enabled와 독립적입니다. 원본 열기 제한이 꺼져 있어도 지표를 켜면 실제 open은 계측하고 허가 대기 표본은 생성하지 않습니다.

## 지표 계약

총34개의 고정 meter(11개 이름)를 시작 시 등록합니다. 결과/사유는 아래 정해진 enum 값만 사용합니다. 파일명·mediaId·sourceId·버전·사용자·조직·원본/캐시 경로·명령/예외 문구를 인자나 라벨로 전달하지 않습니다.

| 이름 | 형식 | 라벨/의미 |
| --- | --- | --- |
| imagegate.preview.jobs.accepted | Counter | 큐의 Job/lease를 만든 횟수. 중복 접수·완료 캐시 재사용·조회는 제외 |
| imagegate.preview.jobs.completed | Counter | outcome: ready, failed, interrupted, open_busy, decoder_unavailable, conversion_busy, conversion_failed, queue_full |
| imagegate.preview.jobs.duration | Timer | 위 outcome별 Job 생성부터 실행·부분 작업 정리·lease 해제까지. 큐 대기 포함 |
| imagegate.preview.jobs.rejected | Counter | reason: closing, capacity, executor. submit 입구/실행기 거절 |
| imagegate.preview.queue.wait | Timer | result: started, cancelled. 시작은 Job 생성부터 워커 시작까지. 시작 전 취소는 정리/해제 완료까지 |
| imagegate.preview.queue.waiting | Gauge | 실행을 시작하지 않은 채 수명 종료 전인 Job 수 |
| imagegate.preview.jobs.running | Gauge | 실행 시작부터 작업 정리·lease 해제까지인 Job 수 |
| imagegate.preview.original.wait | Timer | result: admitted, timeout, interrupted, closed, failed. 허가 잠금·조건 대기 시간 |
| imagegate.preview.original.open | Timer | result: opened, failed, interrupted. 실제 opener 호출의 반환/실패까지 |
| imagegate.preview.original.active | Gauge | 실제 원본 open 호출 진행 수. 반환 후 허가 해제/계측 경계의 짧은 차이 포함 |
| imagegate.preview.original.waiting | Gauge | 원본 열기 허가 획득 단계의 현재 대기/접수 수. 잠금 획득·즉시 접수 단계의 짧은 차이 포함 |

Timer는 Micrometer의 count/totalTime/max를 제공합니다. 읽기 단위는 TimeUnit을 명시하고 exporter 단위와 구분합니다. 별도 percentile/SLO histogram이나 운영 P95를 추가·보장하지 않습니다. 카운터/Timer는 해당 프로세스 레지스트리 수명 동안 누적되며 재시작으로 초기화됩니다. 분산 집계·영속 감사 기록·과금/정확한 요청 개수의 대체가 아닙니다.

완료 시각과 지표 기록 시각은 같지 않습니다. ready 게시 뒤 정리/lease 해제가 끝나면 Job 완료 지표를 기록합니다. 실제 open이 정상 반환돼 opened로 기록돼도 그 직후 취소·SHA 불일치·디코딩/권한/게시 실패로 최종 Job이 실패할 수 있습니다. 허가 대기 Timer는 native open 반환/실패 후 기록하며 timeout/대기 취소는 native 표본이 없습니다. 멈춘 native 호출은 active에 남고 Timer는 아직 완료 표본을 갖지 않습니다. 현재 대기는 waiting Gauge로 확인합니다. 여러 Gauge의 읽기는 원자적인 단일 스냅샷이 아닙니다.

실행기 거절은 이미 생성된 Job의 accepted와 terminal queue_full에도 포함될 수 있어 rejected와 accepted를 무조건 합산해 HTTP 요청 수로 해석하지 않습니다. 장애 상태의 재조회/retry=false·같은 처리 중 중복 접수는 새 Job을 만들지 않습니다. 권한/CSRF/HTTP 파싱/저장소 등록 실패, 원본 업로드 시간·브라우저/네트워크 표시 시간은 이 처리 지표의 범위 밖입니다.

## 속도·수명·실패 격리

conversion/service.PreviewMetrics가 고정 meter와 비활성 NONE을 제공합니다. 비활성은 별도 처리 meter를 등록하지 않고 JobSample을 재사용하며 계측용 시계·샘플 할당을 생략합니다. 원본 허가 정책 자체에 필요한 기존 제한 시간 시계는 유지합니다. 상태 조회/완료 콘텐츠·썸네일/HEAD 전송에 새 계측을 넣지 않습니다.

PreviewQueue의 기존 Job 단일 claim이 시작/취소/해제와 지표 소유권을 함께 보호합니다. 종료가 큐 대기 작업을 회수할 때도 interrupted로 한 번만 기록하며 작업·캐시 lease가 살아 있는 동안 수명을 거짓으로 초기화하지 않습니다. OriginalOpenAdmission은 슬롯을 반환한 다음 외부 meter 기록 콜백을 호출합니다. 느린 기록기가 원본 열기 허가를 보유하지 않는지 검증합니다. 사용자 정의 기록기가 작업 스레드 자체를 지연시킬 가능성까지 강제 종료하는 장치는 아닙니다.

Counter/Timer 기록의 RuntimeException은 업무 예외/권한 판단을 대체하지 않고 서비스 인스턴스당 한 번의 고정 경고만 남깁니다. 내부 예외 문구/스택을 경고에 기록하지 않습니다. 기록 실패로 통계가 빠질 수 있으므로 고정 경고 발생 시 레지스트리 설정을 점검합니다. 레지스트리 등록/필수 빈 구성 실패는 시작 실패이며 활성 선택을 조용히 우회하지 않습니다.

## 운영 조회 경계

현재 제공 범위는 애플리케이션의 MeterRegistry 빈 내부 수집입니다. 기본 SimpleMeterRegistry에는 원격 exporter나 영속 저장이 없습니다. IntelliJ의 신뢰된 로컬 디버거/승인된 인프로세스 관리 코드에서 조회할 수 있습니다. 예를 들어 레지스트리 참조 registry에서:

~~~java
registry.get("imagegate.preview.jobs.completed").tag("outcome", "open_busy").counter().count();
registry.get("imagegate.preview.original.wait").tag("result", "timeout").timer().count();
registry.get("imagegate.preview.jobs.duration").tag("outcome", "ready").timer()
        .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS);
registry.get("imagegate.preview.original.waiting").gauge().value();
~~~

management.endpoints.web.exposure.include=health 및 기존 보안 체인을 유지합니다. /actuator/metrics, 개별 metrics 경로, /actuator/prometheus는 일반 미디어 소유자 권한으로도 접근할 수 없습니다. 수집 옵션을 켰다는 이유로 관리 접근을 허용하지 않습니다. 원격 exporter/관리 인증·망/접근 정책은 별도 운영 설계 후 연결해야 합니다.

## 검증 범위

실제 큐의 결과별 단일 집계, 중복/캐시 조회 무계측, 초과/종료 거절, 큐 시작/취소 수명, 허가 시간 초과·열기 실패·대기 인터럽트/종료 회수, 늦은 native 반환 후 Gauge 복원, 기록기 실패의 업무 격리·느린 기록기 이전 허가 반환을 테스트합니다. 설치 기본/구형 YAML 호환·활성 선택/잘못된 boolean을 검증합니다. 실제 HTTP에서는32개 고정 meter·라벨과 성공 캐시 읽기의 관측 불변, 관리 경로401/403을 확인합니다. 별도 비활성 API 문맥에서는 정상 미리보기를 제공하면서 처리 meter가0개임을 확인합니다.

~~~powershell
.\gradlew.bat --no-daemon --console=plain test --tests '*PreviewMetricsTests' --tests '*OriginalOpenPolicyHttpTests' --tests '*MediaPreviewApiTests' --tests '*InstallationConfigurationTests'
.\gradlew.bat --no-daemon --console=plain build -Pimg2tileIntegration -PffmpegIntegration
~~~

합성 테스트의 기능 검증이며 실 업무 부하/운영 P95·모든 exporter의 지연 특성·장시간 안정성 판정을 대신하지 않습니다. 활성/비활성 성능·장시간 반복 비교는 다음 단계로 남깁니다. 기본 비활성·실제 설치 설정/모듈/자료 보존·최종 단계 전 설치 ZIP 미생성 원칙을 유지합니다.

## 최종 검증 결과

2026-10-27 JDK21 build -Pimg2tileIntegration -PffmpegIntegration이4분51초에 성공했습니다. Java567개/88개 suite의 실패·오류·건너뜀은0입니다. 신규15개는 지표 단위/실제 큐·허가 수명12개, 설치 선택1개, 실제 HTTP 고정32개 meter/조회 불변/관리 경로 거절1개, 비활성 실제 API의 미등록1개입니다.

실제 img2tile·FFmpeg 통합과 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다. 실행 JAR 해시 IO smoke·테스트/fixture 항목0개를 확인했습니다. 라이선스 식별 검증과 최종 재배포 준비는 별개여서 distributionReady=false를 유지합니다. 설치 ZIP은 생성하지 않았습니다.

## 활성/비활성 비교 후속

실제2워커/원본 허가1 조건의 교차 비교 두 독립 실행과 추가100회 반복 결과는 [PREVIEW_METRICS_COMPARISON.md](PREVIEW_METRICS_COMPARISON.md)를 참고하세요. 기본 비활성을 유지하며 운영 P95/수 시간 이상 soak 판정과 구분합니다.

변환 용량 부족은 CONVERSION_BUSY/outcome=conversion_busy로 분리합니다. 고정 meter34개/이름11개이며 기존 실험 당시의32개 집계는 역사적 결과입니다. NATIVE_CAPACITY_RETRY.md를 참고하세요.
