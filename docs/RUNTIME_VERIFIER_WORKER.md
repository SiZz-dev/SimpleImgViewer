# 전체 런타임 검증 Worker Thread 후보

현재 후속 상태: [YAML 선택·서버 소유권과 실제 편집/미디어 인수](RUNTIME_VERIFIER_CONFIGURATION.md)를 완료했다. 아래의 factory/YAML 미연결 설명은 후보 비교 단계의 기록이다. 현재 기본값은 계속 main이다.

2026-11-16. 전체 목록·경로·링크·파일 크기·바이트 수·SHA-256 검사 코드를 그대로 사용하면서 메인 스레드 밖에서 실행하는 후보를 구현했다. 현재는 시험 코드에서만 조립하며 application factory/YAML 선택이나 일반 서버 기본 동작은 변경하지 않았다. 새 의존성·다운로드·설치 ZIP은 없다.

## 역할과 제한

`conversion/service.RuntimeVerifierPool`은 입장·고정 worker·대기열·시간 초과·오류·종료 수명을 담당한다. `conversion/util.runtime-verify-worker`는 고정 프로토콜의 root/descriptor만 받아 descriptor를 재검증하고 기존 `verifyRuntimeTree`를 실행한다. 사용자에게 worker 경로·소스·실행 인자·env·검사 생략 옵션을 받지 않는다. 이미지/영상 decoder를 이 thread에서 실행하지 않는다.

| 정책 | 현재 후보 |
| --- | --- |
| 기본 thread / 허용 최대 | 1개 / 2개 |
| 기본 대기 요청 / 내부 허용 최대 | 8개 / 32개 |
| 기본 deadline | 입장부터30초, 내부 한도1~120000ms |
| 파일 검사 | thread당 파일2개·각1MiB 버퍼 |
| V8 제약 | thread당 old32MiB, young8MiB, stack2MiB |
| stdout+stderr | thread당 누적8192바이트 초과 시 거절·종료 |

읽기 버퍼는 thread1개에서 최대2MiB,2개에서 최대4MiB다. thread isolate·코드·스택·파일 핸들 등은 이 버퍼 상한에 포함되지 않는다. V8 resourceLimits도 Buffer/ArrayBuffer나 프로세스 전체 RSS의 하드 상한을 뜻하지 않는다. 고정 `execArgv: []`와 최소 별도 env를 사용하고 fd 추적을 켠다. Worker의 파일 핸들 종료와 V8 제한의 범위는 [Node24.22.1 공식 문서](https://nodejs.org/download/release/v24.22.1/docs/api/worker_threads.html)를 따른다. Worker Thread는 같은 서버 프로세스의 검증 연산 분리이며 기존 LPAC/Job 파일·네트워크 격리를 대체하지 않는다.

## 실패·참조·종료 계약

부모는 descriptor를 정규화해 독립 복사본을 보관한 뒤 고정 worker로 전달한다. 응답은 요청 ID와 nativeDigest, 정확한 필드 구조에 묶는다. 예외 메시지·호스트 파일 경로는 응답으로 전달하지 않고 고정 오류 코드만 전달한다. 완료 proof를 저장하거나 TTL로 재사용하지 않는다.

용량은 active+queued 요청으로 계산해 초과 요청을429로 거절한다. queued deadline은 해당 요청만 제거한다. active deadline·thread 오류·잘못된 응답·출력 초과는 thread를 종료하고 **exit를 확인한 뒤** 요청을 실패 처리한다. 부모에서 다시 검사해 성공으로 우회하지 않는다. monotonic deadline을 응답 처리 때도 확인해 지연된 timer callback보다 늦은 응답이 먼저 성공하지 않도록 한다. 다음 독립 요청은 새 고정 worker를 만들 수 있다.

`close()`는 새 입장을 막고 queued 요청을503으로 거절한다. active 검증은 완료 또는 기존 deadline까지 기다린 뒤 모든 thread의 종료를 확인한다. 정상 응답은 기존 util의 모든 파일 읽기·close가 끝난 이후에만 전송한다. RuntimeVersions의 진행 중 동일 버전 proof 공유와 임시 usage 참조는 유지하며, 시험에서만 `verifyTree` 경계에 pool을 주입한다. 게시/활성화/회수의 기존 검사와 native 실행 직전·게시 전 검증 정책도 생략하지 않는다.

## 비교 도구와 조건

```powershell
$env:SIV_SPEED_YAML = '.tools/load/<fixture ID>/<run ID>/lpac.yml'
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-runtime-worker-comparison.mjs
```

기존 승인된 합성 Node/FFmpeg 카탈로그·현재 LPAC 실행기 build manifest가 필요하다. 각 후보는 별도 Node 서버 프로세스·새 YAML/저장소/LPAC 풀·별도 HTTP 부하 클라이언트를 사용한다. 실제 설치 YAML·IntelliJ 서버는 바꾸지 않는다. 초기 PNG를 실제 LPAC으로 변환하고 두 런타임·thread를 예열한 뒤 [앞선 응답성 시험](RUNTIME_RESPONSIVENESS.md)의 idle1.5초/image5초/mixed5초 모델을 사용한다. startup과 thread 최초 생성 비용은 표에 포함하지 않는다.

기본 메인 방식/main, thread1, thread2를 이 순서와 역순으로 각2회 실행했다. 이후 메인 스레드에서 전체 proof를 FIFO로 직렬화하는 main-fifo 대조군을2회 추가했다. FIFO 대조군은 메인 스레드 SHA와 대기열 공정성 효과를 구분하려는 시험 전용 조립이며 운영 설정이 아니다. 이번 실제 control 실행은 원래6회 뒤에 추가한 순서이고, 재현 스크립트는 처음부터4개 후보를 순서 반전해 실행한다. 작은 표본·시기 차이 때문에 정확한 인과 기여율로 해석하지 않는다.

Windows x64 `10.0.26300`, Intel Core i5-8250U, loopback에서 측정했다. 별도 회귀 시험은 측정 종료 후 수행했다. 모든 후보에서 전체 proof 반복, cache decoder0회, 동일 페이지 source·기하, 실제 비공개 PNG와 부하 중 위조 ID/source·CSRF 거절, 종료 후 참조·thread0을 확인한다. worker 후보의 자원 표본에서는 thread 최대1/2개를 관찰했다. 원시 표본은 `build/reports/runtime-worker-comparison/verification.json`과 후보별 디렉터리에 저장한다.

## 결과

아래 각 셀은1차/2차 시험의 p95다. 표본을 합친 percentile이나 평균 p95가 아니다.

| 혼합 부하 응답 | 메인 기본 | 메인 FIFO | thread1 | thread2 |
| --- | ---: | ---: | ---: | ---: |
| 페이지 manifest | 3128.87/3088.60ms | 1885.70/1755.17ms | 1324.94/1294.63ms | 2173.69/1329.83ms |
| 캐시 PNG 본문 | 158.51/165.56ms | 135.71/125.30ms | 33.41/34.23ms | 129.54/43.63ms |
| health | 10.99/8.59ms | 8.79/6.80ms | 2.45/3.83ms | 5.95/3.82ms |
| 서버 관측 RSS 최대 | 107.0/112.9MiB | 110.9/108.0MiB | 145.3/131.3MiB | 149.3/158.8MiB |

thread1의 혼합 페이지는 기본 대비 약58% 짧았고, 본문은 약79% 짧았다. FIFO 대조군도 페이지 지연을 줄였으므로 thread 이동만의 효과라고 주장하지 않는다. FIFO 대조군과 비교해도 thread1의 본문 지연 감소가 관찰됐다. 반면 thread1의 **이미지 단독** 페이지 p95는911.54/819.64ms로 기본805.22/812.75ms보다 느렸으며, thread2는2867.39/1045.64ms로 편차가 컸다. 낮은 부하를 포함해 항상 빠르다고 보장할 수 없다.

5초 요청 생성 기간과 완료 대기를 포함한 mixed에서 기본 proof 수는 Node2/FFmpeg11, FIFO는3/7, thread1은5/6이었다. thread1은 페이지 응답 표본15개, 기본은6개다. 이미지 열람에 돌아간 검증 기회와 FFmpeg 검증 빈도가 달라졌으므로 전체 처리량 향상이나 실제 영상 변환 처리량으로 해석하지 않는다. mixed의 FFmpeg 작업은 변환이 아닌 바이너리 전체 검증 포화 모델이다.

메인 event-loop utilization은 기본 약0.98, thread1 약0.26이었다. 이는 장비 전체 CPU 사용률이 아니며 thread들의 CPU 작업도 포함하지 않는다. p99 loop 지연은 기본 약10~11ms, thread1 약16ms였고 Windows timer 해상도·I/O 깨움 조건의 영향을 받으므로 이 값만으로 개선/악화를 판정하지 않는다. RSS는50ms 간격의 서버 프로세스 표본이며 worker 메모리를 포함하지만 실제 순간 피크·native 자식·클라이언트는 포함하지 않는다.

## 검증 범위와 다음 단계

시험은 같은 크기의 경계/꼬리 변조·링크·미등록 파일 거절, 완료 proof 재사용 부재, 입력 snapshot 독립성,1/2개 thread·대기열 상한, queued/active deadline, 지연된 timer보다 늦은 응답 거절, digest 불일치·비정상 exit, close 중 active drain과 RuntimeVersions 회수/해제 경쟁을 확인한다. 후보의 초기 실제 LPAC 이미지 변환과 HTTP 부하도 통과했다. Chrome 회귀는 현재 기본 서버의13개 시나리오이며 후보별 전체 편집·재생 UI 인수를 대체하지 않는다.

최종 전체 Node/네이티브 시험은 순차130개 통과·실패/건너뜀0이었다. React production 빌드와 npm90개 라이선스 기준선도 통과했다. 비교 후 내부 기본 thread 수를1개로 정하고 응답 필드·monotonic deadline 검사를 강화했으며, 마지막 회귀는 이 최종 코드로 실행했다. 비교 표의 전체 파일 읽기·해시 알고리즘과 각 실험의 명시적 thread 수는 동일하다.

후속 연결 대상은 **thread1 후보**다. 일반 서버 기본 방식은 유지한다. 다음은 명시적 YAML 선택과 서버 종료 수명에 연결하고, 실제 편집·미디어 API·시간 초과/종료 실패 경계까지 인수하는 것이다. 장시간·실제 업무 규모 부하와 운영 Windows/계정 인수, 메모리 예산 확인은 남아 있다. 운영 기본값 변경이나 최종 배포 승인을 완료한 단계로 표시하지 않는다.
