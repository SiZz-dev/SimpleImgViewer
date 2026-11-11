# 미리보기 원본 열기 운영 정책

2026-10-26 구현. 현재 호스트의 [비교 실험](ORIGINAL_OPEN_ADMISSION.md)을 근거로 설치별 선택을 제공합니다. 기본은 비활성이며 기존 설치 파일에 항목이 없어도 비활성 기본값을 사용합니다. 적용에는 재시작이 필요합니다.

~~~yaml
imagegate:
  preview:
    original-open:
      enabled: false
      max-concurrent: 1
      wait-timeout: 10s
~~~

- enabled: true일 때 단일 PreviewQueue/서버 인스턴스의 원본 SHA 검증용 open 호출을 제한합니다. 기본 false.
- max-concurrent: 1~4. 워커 수는 별도이며 실제 동시 open은 둘 중 작은 한도입니다. 기본1.
- wait-timeout: 1ms~1m의 정수 밀리초. 기본10s. 허가 잠금과 조건 대기를 포함한 단조 시계 예산입니다. 잘못된 값/알 수 없는 키는 기동 실패입니다. 비활성 설정도 값 검증을 적용합니다.

## 적용 범위

conversion/service.OriginalOpenAdmission이 허가와 대기/취소/종료를 소유합니다. ImagePreviewRenderer는 기존 권한·정규 원본 확인 뒤 이 서비스를 통해 원본 SHA 입력 스트림을 엽니다. 네이티브 open 호출이 반환되거나 실패하는 즉시 finally에서 허가를 반환합니다. 반환된 스트림의 읽기/닫기·전체 SHA 비교·디코딩·PNG/썸네일 생성·캐시 게시는 제한 범위 밖입니다.

JDK 디코더의 별도 FileImageInputStream, 방향 메타데이터 조회, 외부 변환기, 업로드·반출·영상·음성의 파일 열기를 전역으로 직렬화하지 않습니다. 사용자/조직별 순서나 서비스 전체 I/O 대역폭을 보장하지 않습니다. 큐의 기존 워커/대기 개수 상한이 대기자 수도 제한하며 새 실행 스레드/무제한 요청 큐를 만들지 않습니다.

설치 옵션은 권한을 부여하지 않습니다. 원본 SHA·최신 권한·캐시 식별/게시 및 종료 시 게시 차단을 유지합니다. 출력이 바뀌지 않아 이 운영 옵션을 캐시 키에 넣거나 기존 완료 캐시를 폐기하지 않습니다. HTTP에서 실행 경로/허가 개수/실험용 opener를 전달할 수 없습니다.

## 대기 초과·취소·종료

허가 대기 초과는 OriginalOpenTimeoutException으로 종료하고 큐는 failed/ORIGINAL_OPEN_BUSY로 저장합니다. 뷰어는 잠시 후 재시도 안내를 표시합니다. 자동 재시도/제한 우회는 없으며 기존 권한을 다시 확인하는 명시적 retry=true 요청이 필요합니다. 원본 경로나 네이티브 오류 문구를 응답하지 않습니다.

대기는 interruptible이며 인터럽트 플래그를 보존합니다. 허가를 받지 못한 요청은 다른 요청의 허가를 반환하지 않습니다. 사전 취소와 획득 직후 취소를 검사하고 열기 실패에서도 허가를 회수합니다. native open이 반환된 시점에 취소/종료가 확인되면 새 스트림을 닫고 실패하며 close 실패는 suppressed로 보존합니다.

PreviewRenderer의 기본 close는 무동작이고 실제 ImagePreviewRenderer.close는 입구를 닫고 대기자를 깨웁니다. PreviewQueue.close와 큐 초기화 실패 정리가 renderer 수명을 소유합니다. 종료는 입구를 닫은 뒤 워커를 인터럽트하고 기존 작업/응답의 캐시 lease 수명을 유지합니다. close는 반복해도 안전합니다.

**wait-timeout은 native open 자체의 실행 기한이 아닙니다.** 이미 JDK/Windows 안으로 진입한 open은 반환할 때까지 슬롯을 보유합니다. 종료 시 슬롯을 미리 반환하거나 대체 워커를 만들어 실제 동시 실행 한도를 넘기지 않습니다. close는 native 반환을 기다리지 않지만 큐는 기존5초 종료 대기와 작업의 writer lease 보존을 유지합니다. native I/O/디코더 하드 종료와 OS 프로세스 격리는 별도 과제입니다.

## 검증

설치 예제와 기본 application.yml에 같은 값을 명시합니다. 실제 config/imagegate.yml은 수정하지 않았습니다. 기존 항목 없는 YAML 바인딩, 활성 설정 바인딩/잘못된 값, 비활성 병렬 호환, 허가1/2, 대기 초과 후 회복·실패 반환, 실제 대기 중 인터럽트·종료 깨우기, 사전/늦은 취소·스트림 닫기, 활성/비활성 픽셀·썸네일·SHA·권한 검사, 고정 오류/명시적 재시도·큐의 close 소유권을 테스트합니다.

운영 구현의 합성 성능 비교:

~~~powershell
.\gradlew.bat --no-daemon --console=plain originalOpenAdmissionTest -PopenAdmissionRuntime=true
.\gradlew.bat --no-daemon --console=plain originalOpenAdmissionTest -PopenAdmissionRuntime=true -PopenAdmissionFirstLimit=1
~~~

runtime 모드는 실제 OriginalOpenAdmission 정책을 허가1/2로 바꿉니다. 테스트 계수 도구는4슬롯으로2워커보다 크게 두어 추가 제한을 걸지 않습니다. 같은 실제 렌더러/원본 SHA/PNG/썸네일/큐 ready 검증을 사용하고 native open 동시성은 선택 한도 이하인지 확인합니다. schema2의 runtimeAdmission=true와 runtime-latest.json으로 이전 실험 결과와 구분합니다. testCounterWaitMs는 테스트 계수 도구의 대기이고 admissionAndOpenOverheadMs는 HASH_OPEN에서 native open을 뺀 대기·계측 오버헤드입니다. 성능 판단은 대기를 포함하는 firstReadyMs/allReadyMs로 합니다. 현재 호스트의 합성 서버 준비 시간이며 HTTP/브라우저/운영 P95가 아닙니다.

## 운영 정책 측정 결과 (2026-10-26)

독립 JVM 두 실행(51s/44s)은 시작 허가 순서를2→1로 뒤집고 실행 내부에서도 순서를 교차했습니다. 매 실행 원본8개/배치8개/미리보기·썸네일 각16개를 처리했습니다.

| 최초 처리 배치 평균 | 실행1 허가2 | 실행1 허가1 | 실행2 허가2 | 실행2 허가1 |
| --- | ---: | ---: | ---: | ---: |
| 첫 미리보기 ready | 5659.1ms | 2192.6ms | 4894.4ms | 2059.6ms |
| 미리보기2개 모두 ready | 5672.6ms | 3737.0ms | 4916.5ms | 3319.1ms |

두 실행 모두 허가1에서 대기를 포함한 최초/전체 ready 시간이 짧았습니다. 첫 결과 평균은2.06~2.19초 대4.89~5.66초, 두 결과 완료는3.32~3.74초 대4.92~5.67초였습니다. 원본16개 SHA, 미리보기/썸네일 각32개 크기,16배치 캐시 writer 재확보·잔재0을 검증했습니다. native open 계수의 peak는 선택 한도 이하였습니다. 작은 표본/현재 호스트의 합성 데이터이며 개선율을 일반 운영 환경에 보장하지 않습니다. 기본 비활성은 유지합니다.

보고서: [운영 실행1](../build/reports/open-admission/runtime-run1.json), [순서 역전 운영 실행2](../build/reports/open-admission/runtime-run2.json).

## 최종 회귀 결과

JDK21 build -Pimg2tileIntegration -PffmpegIntegration을4m53s에 통과했습니다. Java548개/86개 suite의 실패·오류·건너뜀은0입니다. 전체 결과는 build/reports/original-open-policy-full/xml 및 html에 보존했습니다. 마지막 테스트 비교 조건 보강 후 최신 소스로 픽셀 직접 비교·원본 무결성·권한과 운영 허가 경계를 포함한11개를27s에 추가 통과했습니다.

실제 img2tile·FFmpeg 통합과 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다. 실행 JAR 해시 IO smoke·테스트/fixture 항목0개 및 뷰어 JS 구문 검증을 확인했습니다. 최종 재배포 준비 판정은 별도로 distributionReady=false를 유지하며 설치 ZIP은 생성하지 않았습니다.

후속 HTTP 미리보기 접수·상태·명시적 재시도와 권한/비공개 응답 검증은 아래 HTTP 경로 검증 문서를 참고하세요.

## HTTP 경로 검증

2026-10-27 실제 루프백 Tomcat/HttpClient에서 대기 초과·명시적 재시도·워커 중단 회복·권한 철회·실패 응답 비공개·GET/HEAD를 검증했습니다. 테스트 경계와 결과는 [ORIGINAL_OPEN_HTTP_ACCEPTANCE.md](ORIGINAL_OPEN_HTTP_ACCEPTANCE.md)를 참고하세요.
