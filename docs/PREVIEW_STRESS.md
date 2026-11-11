# 미리보기 취소·재시도 반복 부하 검증

JDK 21의 실제 LocalMediaStore·ImagePreviewRenderer·PreviewQueue·PreviewCache·FeaturePolicy를 사용하는
opt-in 테스트입니다. src/test의 합성 PNG와 소유자 정책만 사용하며 서버/HTTP/SSO·외부 변환 모듈을 시작하지 않습니다.
Mockito나 디코더 SPI 변경 없이 별도 측정 JVM에서 실행하고 런타임 JAR에 테스트 코드를 포함하지 않습니다.

## 실행

~~~powershell
$env:JAVA_HOME='C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat --no-daemon --console=plain previewStressTest
.\gradlew.bat --no-daemon --console=plain previewStressTest -PpreviewStressRounds=6 -PpreviewStressSourceEdge=2048
~~~

기본값은 4회, 1024×1024입니다. rounds는 2~12, sourceEdge는 256~2048이며 범위 밖은 실패합니다.
워커2·대기2·회당4개 항목, 출력 최대512·썸네일256, JVM 힙128~512MiB, 메모리 표본 간격20ms를 고정합니다.
원본5개 중4개는 작업 대상이고1개는 상한 초과 접수 거부 확인용입니다.
테스트 전체에는240초 상한이 있으며 느린 환경/큰 옵션 조합은 실패할 수 있습니다.
일반 test/build·다른 performanceTest·httpPerformanceTest에 포함하지 않고 설치 ZIP도 생성하지 않습니다.

## 검증 흐름

각 회차의 캐시 식별자는 분리하고 같은 회차의 취소/재시작/재시도에는 동일한 키를 사용합니다.

1. 두 워커의 진입을 barrier로 확인하고4개 접수·중복 요청 병합·5번째 거부·상태 조회의 비접수를 검사합니다.
2. 홀수 회차는 실제 renderer 호출 직전, 짝수 회차는 실제 렌더링 반환 후 큐 게시 직전에 스레드 interrupt를 주입합니다.
   픽셀 디코더 내부나 HTTP 취소 API를 통해 중단하는 시험은 아닙니다.
3. 4개 모두 failed/INTERRUPTED와 플래그 보존을 확인하고 큐를 닫습니다.
4. 새 cache가 writer lock을 다시 확보한 상태에서 work/임시/예상하지 않은 항목과 게시 PNG가 없는지 확인합니다.
   이전 회차의 정상 완료 캐시와 이번 회차의 실패 메타데이터는 의도적으로 유지합니다.
5. 동일 키로 새 큐를 열어 retry=false가 변환을 접수하지 않는지 확인한 뒤 명시적 retry로4개를 준비합니다.
6. 실제 미리보기·썸네일 콜백에서 PNG를 디코딩하고 크기를 검증합니다. 종료·잠금 재확보 후 완료 항목마다 PNG2개와 상태1개만 허용합니다.
7. 마지막에 원본5개의 SHA-256을 스트리밍 재검사합니다.

각 회차에서 실제 동시 진입2개·종료 후 활성 호출0개를 단언합니다.
work 디렉터리·임시 파일·추가 PNG/항목·불완전 상태·잘못된 이미지·원본 변경·잠금 재확보 실패는 테스트 실패입니다.
정상 완료 캐시의 개수/바이트는 보고서에 별도 표시하며 작업 잔재와 혼동하지 않습니다.

## 보고서와 해석

build/reports/preview-stress/latest.json과 Gradle test-results/previewStressTest에 결과를 기록합니다.
매번 build/preview-stress/run-*에 새 합성 저장소를 생성해 보존합니다.
latest.json은 덮어쓰므로 비교 결과는 별도 이름으로 복사합니다. 컴파일 실패/VM 강제 종료 시에는 이전 보고서가 남을 수 있으므로
createdAt·storage와 해당 Gradle 실행 성공 여부를 함께 확인합니다. 실제 설치 YAML·모듈·업무 자료는 사용하거나 변경하지 않습니다.

- cancelRendererMs/retryRendererMs: barrier 대기를 제외한 renderer 호출 시간. 취소 주입 모드가 섞인 cancel 수치로 디코딩 성능을 비교하지 않습니다.
- cancelBatchMs/retryBatchMs: 접수·대기·상태 폴링·종료/정리를 포함합니다. retry 배치에는 PNG 검증 읽기도 포함합니다.
- p50/p95: 관측한 전체 표본의 nearest-rank이며 워밍업 표본을 제거하지 않습니다. HTTP/브라우저 최초 뷰잉 P95나 운영 SLA 수치가 아닙니다.
- sampledHeapPeakBytes/sampledJvmNonHeapPeakBytes: JVM MXBean 표본의 최대값입니다. 20ms 사이의 피크는 놓칠 수 있고,
  non-heap은 JVM 메타데이터/코드 영역 등이므로 OS RSS/프로세스 전체 또는 native/디코더 메모리가 아닙니다.
- quiescentHeapUsedBytes/finalHeapUsedBytes·GC 횟수/시간: 강제 GC 없는 관측값입니다.
  정상 객체의 GC 대기와 완료 캐시 증가가 포함되므로 단조 감소·누수 부재를 보장하거나 임의 임계값으로 통과시키지 않습니다.

통과는 제한된 합성 시나리오의 기능/수명 불변식과 JVM 실행 성공을 의미합니다.
장시간 soak, 실제 업무 이미지/SSO/HTTP 부하, OS/native 메모리 계측·하드 격리 및 최종 배포 인수는 별도로 수행합니다.

## 2026-10-26 측정 기록

측정 JVM은 JDK21, 워커2/대기2, 최대 힙512MiB입니다. 두 실행 모두 별도 JVM·새 합성 저장소를 사용했고 다른 Gradle 작업과 동시에 실행하지 않았습니다. 초기 표본도 모두 포함했습니다.

| 조건 | 취소/명시적 재시도 | 렌더러 재시도 P95 | 표본 heap 피크 | 표본 JVM non-heap 피크 | work/임시/추가 항목 |
| --- | --- | --- | --- | --- | --- |
| 1024×1024 / 4회 | 16 / 16 | 605.9ms | 124.4MiB | 33.6MiB | 0 / 0 / 0 |
| 2048×2048 / 6회 | 24 / 24 | 6049.8ms | 248.7MiB | 34.5MiB | 0 / 0 / 0 |

두 실행에서 취소40건/재시도40건, 총80개 작업 시도가 성공적으로 검증되었습니다. 각 단계 종료 후 writer lock을 재확보했고 원본 SHA를 보존했습니다. 완료 캐시40개/PNG80개는 의도한 보관 산출물입니다.

2048 조건 첫 재시도 배치는 12133.1ms였고 이후 배치는 약1.3~1.6초였습니다. 첫 회차의 느린 렌더러 표본이 전체 P95에 포함되어 있습니다. 원본 해시·디코딩·크기 조정·PNG 쓰기 중 어느 구간이 원인인지는 현재 전체 renderer 시간만으로 판단하지 않습니다. 다음 우선 작업은 이 초기 지연의 단계별 계측입니다.

보고서: [기본 조건](../build/reports/preview-stress/default-1024.json), [2048 조건](../build/reports/preview-stress/large-2048.json). 메모리 표본/강제 GC 없는 잔존 힙 수치로 누수 부재를 단정하지 않습니다.

회귀 검증에서 확인한 접수 경계: 큐는 failed 상태를 먼저 기록하고 work 정리·lease 해제 후 active 키를 제거합니다. 그 사이의 retry=true는 기존 활성 작업의 failed 상태를 반환할 수 있습니다. 취소/재시도 시험은 큐 종료로 정리 완료를 확인한 후 새 큐에 접수하며, 동일 큐 재시도 회귀 테스트는 제한된 시간 안에서 명시적 접수와 완료 상태를 확인하도록 보강했습니다.

최종 전체 검증: 2026-10-26 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(8m25s). 일반 Java505개/80개 suite, 실패·오류·건너뜀0. opt-in previewStressTest는 일반 build에서 제외되고 별도 1024×1024/4회(32s)·2048×2048/6회(48s) 두 실행 통과: 취소40/명시적 재시도40, 워커 동시 진입2·큐 상한/중복·writer 회수·원본 SHA 보존·work/임시/추가 항목0. 표본 heap 피크124.4/248.7MiB(최대 힙512MiB), 재시도 renderer P95 605.9/6049.8ms. 2048 초기 배치12133.1ms와 느린 표본을 보존해 후속 단계별 조사 대상으로 기록. 기존 재시도 테스트의 failed 기록/active 정리 경합 대기 보강. 실제 img2tile·FFmpeg 통합 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선·실행 JAR PNG 취소 smoke 통과, 테스트/fixture runtime JAR 항목0개. 실제 모듈·설치 YAML·업무 자료 변경 및 설치 ZIP 생성 없음. 합성 서비스 수준 측정으로 HTTP/실 업무 P95·누수 부재·OS/native 메모리·배포 준비를 주장하지 않으며 distributionReady=false 유지. PREVIEW_STRESS.md 참조.

## 단계별 계측 보고서 v2

기본 생성자의 ImagePreviewRenderer는 계측이 꺼져 있으며 단계마다 시계를 읽거나 관찰자를 생성하지 않습니다.
동일 패키지의 신뢰된 진단 코드가 사용하는 추가 생성자만 PreviewTiming을 받아 opt-in 계측을 활성화합니다.
HTTP/API·설치 YAML에 계측 활성화나 콜백 입력 경로를 추가하지 않습니다.
관찰자에는 고정 enum 단계와 경과 ns만 전달하고 파일 경로·사용자·미디어 식별자를 전달하지 않습니다.
관찰자의 RuntimeException은 결과/업무 예외를 대체하지 않고 이미 설정된 interrupt 플래그를 보존합니다.
디코더/파일 I/O의 실행 순서와 픽셀·크기/권한 검증·자원 해제·게시 정책은 유지합니다.

previewStressTest는 이 관찰자를 활성화합니다. schemaVersion=2에서 각 round의 cancelStageSamples/retryStageSamples에
렌더러 호출별 elapsedMs·stagesMs·unattributedMs·threadCpuMs·interrupted를 기록합니다.
스레드 CPU 시간이 지원/활성화되지 않으면 -1이며, wall time과 CPU 차이만으로 I/O·JIT·스케줄링 등의 원인을 단정하지 않습니다.
javaVersion·os·processors도 기록합니다. 관찰자 자료 수집·자원 해제 등 별도 단계 밖 시간은 unattributedMs에 남깁니다.
계측 자체의 오버헤드가 있으므로 이전 v1 실행과 수치를 단순한 최적화 전후 비교로 취급하지 않습니다.

| 단계 | 포함 범위 |
| --- | --- |
| ACCESS_BEFORE / ACCESS_AFTER | 카탈로그 조회·소유자/설치 정책 확인 |
| ORIGINAL_RESOLVE | 원본 정규 경로/파일 확인 |
| ORIGINAL_HASH | SHA 준비·원본 읽기·해시·기록된 SHA 비교 |
| WORK_CREATE / CONVERTER_SELECT / NATIVE_CONVERT | 작업 폴더·모듈 선택·선택된 변환 실행 |
| DECODER_OPEN / DECODER_SELECT | FileImageInputStream·ImageIO 공급자 탐색 |
| DECODER_METADATA / ORIENTATION | 입력 설정·크기/형식 메타데이터·원본 방향 |
| PIXEL_DECODE | ImageReader.read의 디코딩/필터/색상 변환·래스터 반환 |
| RESIZE | 출력 크기 산정·할당·Java2D 변환/그리기·원본 래스터 flush |
| PREVIEW_PNG | ImageIO PNG writer 선택·압축·파일 저장 |
| THUMBNAIL | 썸네일 크기 조정과 PNG 저장 |
| OUTPUT_CHECK | 출력 파일 크기 확인 |

각 호출의 단계 합계와 unattributedMs를 전체 elapsedMs와 비교합니다.
취소가 렌더러 진입 전에 발생한 호출의 stagesMs는 비어 있는 것이 정상입니다.
원인 조사는 원래 느린 보고서를 보존하고 독립 JVM 재실행의 모든 표본을 비교합니다.
재실행에서 이전 6초 지연이 나오지 않으면 과거 지연의 원인이 확정되었다고 기록하지 않습니다.

## 2026-10-26 초기 지연 단계별 재현

2048×2048/6회 조건을 계측을 켠 새 JVM에서 두 번 순차 실행했습니다. 각 실행에서 취소24/재시도24, 잔재0·원본 SHA 보존과 writer 회수를 확인했습니다. 기존 v1 보고서와 이번 두 원본 보고서를 모두 보존합니다.

| 첫 회차 4개 표본의 평균 | 실행1 | 실행2 |
| --- | ---: | ---: |
| 전체 renderer | 5844.1ms | 6283.2ms |
| ORIGINAL_HASH | 5219.1ms | 5539.3ms |
| PIXEL_DECODE | 318.9ms | 398.9ms |
| RESIZE | 63.1ms | 66.1ms |
| PREVIEW_PNG | 165.0ms | 187.7ms |
| THUMBNAIL | 42.1ms | 55.1ms |
| 원본 읽기/SHA 비중 | 89.3% | 88.2% |
| renderer 호출 스레드 CPU | 679.7ms | 796.9ms |

첫 회차 ORIGINAL_HASH 범위는 실행1 5122.3~5309.8ms, 실행2 5450.8~5662.7ms였습니다. 이후 회차는 각각 54.0~78.3ms와 49.9~80.4ms였습니다.

**관측 결론:** 초기 지연이 디코딩/PNG 단계보다 원본 읽기·SHA 단계에 집중됨을 두 독립 실행에서 재현했습니다. wall time에 비해 renderer 스레드 CPU가 작다는 관측도 있으나, 현재 ORIGINAL_HASH는 파일 열기/읽기·digest.update·닫기/최종 비교를 합친 구간입니다. 디스크/파일 시스템·보안 프로그램·JDK/해시 연산 중 특정 원인을 확정하거나 SHA 검증을 생략할 근거는 아닙니다. 다음은 원본 open/read와 SHA 준비/update/final을 분리해 계측합니다.

두 실행의 재시도 P95는 6064.8ms와 6305.1ms이며 느린 초기 표본을 제외하지 않았습니다. 총48개 retry 표본 모두15개 단계와 잔여 시간의 합이 전체 renderer 시간과0.001ms 이내에서 일치함을 검사했습니다.

보고서: [계측 실행1](../build/reports/preview-stress/stages-2048-run1.json), [계측 실행2](../build/reports/preview-stress/stages-2048-run2.json).

이번 단계 최종 검증: 2026-10-26 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(4m29s). 일반 Java509개/82개 suite, 실패·오류·건너뜀0. 신규4개(계측 util3/renderer 결과·좌표·관찰자 오류 보존1) 및 기존 취소/자원 회귀, 실제 img2tile·FFmpeg 통합·라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. opt-in 2048×2048/6회 독립 JVM 두 실행47s/52s 통과(취소48/재시도48, 잔재0·원본 SHA·writer 회수). 초기 ORIGINAL_HASH 평균5219.1/5539.3ms, 첫 배치 renderer 평균5844.1/6283.2ms의89.3%/88.2%; 이후 hash약50~80ms. 모든48 retry 표본의15개 단계+잔여 시간 합 검증. 원본 읽기/SHA 복합 구간으로 좁혔으나 I/O/연산의 세부 원인은 미확정. 실행 JAR에서 계측·RuntimeException/원래 예외·기존 interrupt 보존 smoke 통과, 테스트/fixture runtime JAR 항목0개. 실제 설치 YAML·모듈·업무 자료 변경 및 설치 ZIP 생성 없음. 실 업무/HTTP P95·OS/native 메모리·최종 배포 승인은 별도이며 distributionReady=false 유지. PREVIEW_STRESS.md 참조.

## 원본 해시 세부 계측 보고서 v3

schemaVersion=3은 기존15개 부모 단계(stagesMs)와 중첩 해시 세부 단계(hashDetailsMs)를 분리합니다.
hashDetailCounts에는 각 세부 단계 호출 수를 기록하며 hashUnattributedMs는 부모 ORIGINAL_HASH에서
세부 단계 합계를 뺀 잔여 시간입니다. 부모/자식 시간을 전체 renderer 합계에 중복 합산하지 않습니다.

| 해시 세부 단계 | 포함 범위 |
| --- | --- |
| HASH_PREPARE | MessageDigest SHA-256 인스턴스 준비 |
| HASH_OPEN | Files.newInputStream으로 원본 스트림 열기 |
| HASH_READ | 32KiB 청크 read 호출 합계, EOF 읽기 포함 |
| HASH_UPDATE | 각 읽은 청크의 digest.update 호출 합계 |
| HASH_CLOSE | 원본 스트림 close |
| HASH_FINAL | digest.digest·16진수 인코딩·기록된 SHA와 비교 |

HASH_READ/HASH_UPDATE는 청크마다 span을 종료해 합산하므로 관찰자/시계 비용이 부모 시간에 추가됩니다.
세부 시간은 동일 실행의 상대적 구간 확인용이며 v1/v2와 정밀한 최적화 전후 수치로 비교하지 않습니다.
기본 생성자의 비활성 계측은 추가 clock/span 할당 없이 원래 입력 스트림을 그대로 사용합니다.
활성 계측만 close를 기록하는 위임 스트림을 사용하며 read/read(byte[])/read(byte[],off,len)를 직접 위임합니다.
try-with-resources의 body/close 예외와 suppressed 예외 순서를 유지합니다.
해시 일치 검사는 마지막에 그대로 수행하며 변조된 원본은 work 생성/디코딩 전에 거절합니다.

호출 수·부모/자식 합계·EOF 및 원본 변조 거절을 별도 회귀 테스트로 확인합니다.
취소된 호출에서는 종료된 구간만 있을 수 있고 HASH_FINAL이 없는 것이 정상입니다.
파일 읽기 시간에 커널/파일 시스템/보안 소프트웨어와 JVM 스케줄링 영향이 포함될 수 있어
한 구간의 wall time만으로 특정 OS 구성요소를 원인으로 단정하지 않습니다.

## 2026-10-26 원본 스트림 열기 지연의 분리 재현

2048×2048/6회 조건을 보고서 v3의 독립 JVM 두 번으로 실행했습니다. 각각 취소24/재시도24, 잔재0·원본 SHA 보존·writer 회수에 성공했습니다. 총48개 retry 표본의 부모/자식 합계와 read=update+EOF, close1회 조건을 검사했습니다.

| 첫 회차 4개 표본의 평균 | 실행1 | 실행2 |
| --- | ---: | ---: |
| ORIGINAL_HASH 전체 | 4408.778ms | 4366.835ms |
| HASH_PREPARE | 0.028ms | 0.031ms |
| HASH_OPEN | 4348.154ms | 4304.036ms |
| HASH_READ | 7.152ms | 7.371ms |
| HASH_UPDATE | 52.246ms | 53.873ms |
| HASH_CLOSE | 0.049ms | 0.042ms |
| HASH_FINAL | 0.022ms | 0.015ms |
| 열기 / 전체 검증 비중 | 98.62% | 98.56% |

초기 HASH_OPEN 범위는 실행1 4298.937~4386.581ms, 실행2 4219.136~4406.825ms였습니다. 이후 회차는 각각 0.091~0.218ms와 0.100~0.187ms였습니다.

원본 하나당 read441회(EOF 포함)/update440회와 close/final 각1회를 확인했습니다. SHA 검증은 생략하지 않았습니다.

**관측 결론:** 이전의 초기 ORIGINAL_HASH 지연을 Files.newInputStream 호출 구간으로 좁혔습니다. SHA 업데이트가 주된 지연이라는 설명은 이번 관측과 맞지 않습니다. 다만 파일 시스템/보안 소프트웨어/JDK 구현 등 하위 원인은 아직 미확정이며, 이 결과가 즉시 API 교체나 OS 보안 설정 해제를 정당화하지 않습니다.

다음 검증은 새 파일과 교차 실행 순서로 원본 스트림 열기 방식(Files.newInputStream/FileInputStream/FileChannel 등)을 비교하고, 동일 읽기/SHA·경로/권한·취소/닫기 조건을 지키는 방법을 평가하는 것입니다. 먼저 읽은 파일을 다른 방식으로 다시 여는 결과만 비교하면 캐시 효과를 API 효과로 오인할 수 있습니다.

보고서: [해시 분리 실행1](../build/reports/preview-stress/hash-2048-run1.json), [해시 분리 실행2](../build/reports/preview-stress/hash-2048-run2.json). 이전 v1/v2 보고서는 그대로 보존했습니다.

이번 해시 분리 단계 최종 검증: 2026-10-26 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(4m32s). 일반 Java512개/82개 suite, 실패·오류·건너뜀0. 신규3개(스트림 비활성/위임·body/close 예외 보존2, 다중 청크/EOF·변조 원본 거절1), 기존 픽셀/좌표·취소/자원 회귀·실제 img2tile/FFmpeg 통합·라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. 2048×2048/6회 독립 JVM 두 실행46s/45s, 취소48/재시도48·원본 SHA·writer 회수·잔재0. v3 모든48 retry 표본의 부모/자식 합계·read=update+EOF·close1회 검증. 초기 open 평균4348.154/4304.036ms가 전체 hash 검증의98.625%/98.562%, read 평균7.152/7.371ms·SHA update52.246/53.873ms, 이후 open약0.09~0.22ms. 파일 열기 API 구간으로 좁혔으며 OS/JDK/보안 구성요소의 하위 원인은 미확정. 실행 JAR에서 원본 스트림 유지·위임 read/close·suppressed 예외 보존 smoke와 테스트/fixture runtime JAR 항목0개 확인. 실제 설치 YAML·모듈·업무 자료 변경 및 설치 ZIP 생성 없음. 실제 운영 P95·OS/native 메모리·최종 배포 승인은 별도이며 distributionReady=false 유지. PREVIEW_STRESS.md 참조.

원본 스트림 API의 신규 파일 첫 읽기/재열기 순서 교차 비교는 [ORIGINAL_STREAM_COMPARISON.md](ORIGINAL_STREAM_COMPARISON.md)를 참고합니다. 세 API의 공통 초기 지연이 관측돼 일반 opener를 교체하지 않았습니다.
