# 동시 열람 중 이벤트 루프와 API 응답성 검증

후속: [검증 전용 Worker Thread 후보·FIFO 대조 비교](RUNTIME_VERIFIER_WORKER.md)를 완료했다. 현재 일반 서버 기본 방식은 유지하며 아래의 후보 비교 계획은 이 계측 시점의 기록이다.

2026-11-16. 제한 병렬 파일 검증의 다음 단계로, 해시 검증 부하 중 일반 API와 캐시 열람 응답을 별도로 측정했다. 이번 변경은 합성 인수 도구·문서 추가이며 운영 검증기나 실행 방식은 바꾸지 않았다. 전체 해시 검증, 파일2개·버퍼 합계2MiB 상한과 기존 참조/종료 정책을 유지한다.

## 재현과 부하 조건

```powershell
$env:SIV_SPEED_YAML = '.tools/load/<fixture ID>/<run ID>/lpac.yml'
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-runtime-responsiveness.mjs
```

앞선 LPAC 시험의 승인된 합성 Node/FFmpeg 카탈로그, 현재 LPAC 실행기 빌드 manifest가 필요하다. 도구는 새 저장소·LPAC 풀·임시 포트로 YAML 서버를 시작하고 합성800×500 PNG를 실제 변환한다. 실제 설치 YAML·IntelliJ 서버·업무 자료를 수정하지 않는다. 결과는 `build/reports/runtime-responsiveness/verification.json`에 기록한다.

HTTP 클라이언트는 `backend/test/conversion/fixtures/runtime-http-load.mjs`를 별도 Node 프로세스로 실행한다. 서버의 이벤트 루프가 멈춰도 클라이언트의 경과 시간에 그대로 반영되도록 분리했다. 연결을 먼저 예열하고 다음 순서로 비교했다.

| 단계 | 요청 생성 기간 | 부하 |
| --- | --- | --- |
| idle | 1.5초 | 경량 API·캐시 본문 조회만 수행 |
| image | 5초 | 캐시 페이지3개 요청을 동시 실행하고, 배치마다5ms 후 반복 |
| mixed | 5초 | image 부하에 FFmpeg 런타임 acquire/release를 대기 간격 없이 반복 |

별도 probe 루프는 health·bootstrap·PNG 본문을 순서대로 조회하고 매 응답 후20ms 대기한다. 이는 고정 도착률이나 고정 사용자 수 부하가 아니다. PNG 응답의 실제 signature를 검사한다.12개 probe마다 위조 미디어 ID·위조 source·CSRF 없는 업로드가404/404/403으로 거절되는지 확인한다. 위조 미디어 검사는 runtime acquire가 없는 파일 정보 경로를 사용해 idle 기준선에 해시 검증을 섞지 않았다. 초기 도구에서 위조 페이지 조회가 검증 부하를 만들던 문제를 수정한 뒤 다시 측정했으며, 아래 표는 수정 후 결과다. idle의 실제 런타임 proof 수0도 검증한다.

전체 트리 검증은 진행 중인 같은 버전 요청 사이에서만 공유한다. 따라서 동시 페이지 요청3개가 각각 별도 proof를 수행하는 모델과 구분한다. cache 구간의 이미지 decoder 호출0회, 반복된 전체 proof와 모든 FFmpeg 참조 반환을 확인한다. 측정 종료 후 이미 시작한 페이지 요청과 FFmpeg 검증을 끝내므로 단계 전체 벽시계는 요청 생성 기간보다 길 수 있다.

## 개발 PC 결과

Windows x64 `10.0.26300`, Intel Core i5-8250U, loopback, 단일 서버 프로세스에서 측정했다. 측정 중 별도 Node/Chrome 회귀 작업은 병행하지 않았다. 아래는 단계별1회 시험이며 운영 SLA나 여러 장비의 보장값이 아니다.

| 응답 p95 | idle | image | mixed |
| --- | ---: | ---: | ---: |
| health | 3.63ms | 4.96ms | 7.61ms |
| bootstrap | 2.84ms | 6.47ms | 7.85ms |
| 캐시 PNG 본문 | 11.26ms | 68.15ms | 157.48ms |
| 캐시 페이지 manifest | 해당 없음 | 802.24ms | 2954.71ms |

probe별 표본 수는 idle15~16개, image38~39개, mixed27~28개다. 페이지는 image21개, mixed6개다. 작은 표본에서 p95가 최대값과 같을 수 있다. 본문에는 작은 합성 PNG를 사용했으며 대용량 파일 전송·실제 영상 변환·브라우저 렌더링 부하까지 대변하지 않는다. mixed는 FFmpeg 실행이 아닌 승인된 FFmpeg 바이너리 전체 검증을 지속적으로 반복한 포화 시험이다.

| 서버 관측 | idle | image | mixed |
| --- | ---: | ---: | ---: |
| 단계 전체 벽시계 | 1530ms | 5512ms | 5894ms |
| 전체 Node proof | 0 | 7 | 2 |
| 전체 FFmpeg proof/acquire | 0 | 0 | 10 |
| 이벤트 루프 지연 p99 | 16.37ms | 9.31ms | 9.93ms |
| 이벤트 루프 지연 최대값 | 16.38ms | 19.30ms | 11.38ms |
| 이벤트 루프 utilization | 0.065 | 0.826 | 0.982 |
| 주기 표본의 서버 RSS 최대값 | 99,082,240B | 129,302,528B | 119,500,800B |
| 보안 거절 확인 횟수 | 9 | 27 | 18 |

`monitorEventLoopDelay`의 요청 resolution은5ms지만 Windows 타이머 해상도·I/O 깨움 조건의 영향을 받는다. idle의 지연 수치가 더 크다는 사실을 부하가 루프를 개선한 증거로 해석하지 않는다. utilization은 이벤트 루프의 active/idle 비율이며 장비 전체 CPU 사용률이 아니다. RSS는50ms 주기 서버 프로세스 관측치로 정확한 피크나 읽기 버퍼 사용량이 아니며, 클라이언트·네이티브 자식 메모리를 포함하지 않는다.

## 판단과 남은 작업

이 표본에서 일반 API가 긴 시간 멈추는 현상은 확인되지 않았다. 반면 mixed의 캐시 본문 p95는 약157ms, 페이지 p95는 약2.95초로 늘었다. 지속적인 서로 다른 런타임 검증 사이의 CPU·파일 I/O 경합이 열람 경로에 영향을 주는 것으로 추정한다. 이번 계측만으로 SHA 연산·공유 파일 I/O 스케줄링의 기여도를 확정하거나 Worker Thread 전환 효과를 보장하지 않는다.

다음은 검증 전용 Worker Thread 후보를 시험 코드에서 비교하는 단계다. 전체 목록/바이트/해시·게시 전 검증을 유지하고, 제한된 입장·고정 worker 코드·오류 전달·진행 중 참조 유지·종료 대기 조건을 먼저 정의한다. 같은 부하 모델에서 페이지·본문 p95와 일반 API·이벤트 루프·메모리를 비교한 후에만 운영 적용을 판단한다. 새 thread를 요청마다 무제한 생성하거나 완료 검증을 TTL로 재사용하는 방식은 후보로 삼지 않는다.

실제 LPAC 서버의 초기 이미지 변환·전체 proof 반복·캐시 본문 및 부하 중 보안 거절을 검증했다. 전체 Node/네이티브120개(실패/건너뜀0), Chrome13개, React production 빌드·npm90개 라이선스 기준선도 통과했다. 운영 Windows/서비스 계정 인수와 최종 설치 ZIP 생성은 남아 있다.
