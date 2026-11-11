# 변환 용량 부족 분류와 수동 재시도

2026-10-28. 공유 native 실행기의 BUSY와 준비 대기열 접수 거절을 구분합니다. 설치 동시 실행 수·큐 크기·타임아웃/저장 예산·사용자별 파일 권한은 변경하지 않았습니다. 새 외부 의존성/프로그램·공개 재시도 API·YAML 설정을 추가하지 않습니다.

## API/상태 계약

| 상황 | 최초 준비 응답 | 후속 상태 | 재시도 |
| --- | --- | --- | --- |
| 타일 접수 한도 초과 | HTTP429 TILE_QUEUE_FULL | 새 작업을 시작하지 않으며 기존 상태 유지 | 명시적 재요청 필요 |
| 타일 실행기에서 최종 접수 거절 | HTTP429 TILE_QUEUE_FULL | failed / TILE_QUEUE_FULL | 명시적 retry=true 필요 |
| 접수한 타일 작업이 공유 실행 용량을 얻지 못함 | 최초 HTTP202 | 상태 GET은 HTTP200, failed / CONVERSION_BUSY, pyramid=null | 명시적 retry=true 필요 |
| 미리보기 접수 한도 초과 | HTTP429 PREVIEW_QUEUE_FULL | 기존 계약 유지 | 명시적 재요청 필요 |
| 접수한 미리보기가 실행 용량을 얻지 못함 | 최초 HTTP202 | 상태 GET은 HTTP200, failed / CONVERSION_BUSY | 명시적 retry=true 필요 |

접수 완료 뒤의 비동기 실패를 이전 HTTP202의429로 바꾸지 않습니다. cached failed 상태의 retry=false POST와 상태 GET은 작업을 다시 실행하지 않습니다. retry=true에도 인증/현재 파일 VIEW 권한·설치 정책·CSRF·현재 버전/페이지의 키·용량 검사를 다시 적용합니다. 준비 완료 캐시는 기존처럼 조회할 수 있으며 failed 상태가 콘텐츠/편집 권한을 부여하지 않습니다.

TileQueue는 정확한 ConversionException.Code.BUSY만 CONVERSION_BUSY로 분류합니다. 예외 메시지/프로세스 출력/경로를 해석하거나 응답에 반영하지 않습니다. 이미 확정된 취소/종료/기한 오류는 덮어쓰지 않습니다. 다른 실패/미지원 형식도 기존 분류를 유지합니다. 미리보기의 기존 CONVERSION_BUSY 코드와 맞춘 변경입니다.

논리 접수 한도 검사 뒤 ThreadPoolExecutor가 거절하는 경우에는 Job을 실패로 종료하고 lease/active 등록을 해제합니다. queued 상태를 남기거나 내부 RejectedExecutionException을 HTTP500으로 노출하지 않습니다. 이 경로의 failed/TILE_QUEUE_FULL 기록도 일반 실패와 같은 수동 재시도/보존 정책을 따릅니다.

TileJobStore의 고정 오류 허용 목록에 두 코드를 추가했습니다. BUSY 실패는 재시작 시 INTERRUPTED로 변환하거나 자동 재접수하지 않습니다. 알 수 없는 오류 필드는 계속 거절합니다. 기존 상태의 포맷/키·public PNG/generation 계약은 변경하지 않습니다.

## 화면 동작

미리보기와 원본 화질 타일 모두 변환 요청이 많을 때와 대기열이 가득 찼을 때 다른 고정 안내문을 표시합니다. 예외 내용은 화면에 직접 표시하지 않습니다. 타일 준비 실패는 현재 권한이 유효한 미리보기를 유지하며 ‘다시 준비’로만 retry=true를 전송합니다. 준비/폴링 중 재시도 클릭은 중복 요청을 만들지 않습니다. 자동 재접수·새 무제한 대기열·숨긴 모듈 우회를 추가하지 않았습니다.

미리보기 실패 시 ‘다시 시도’는 기존 select 경로에서 새 요청을 전송합니다. 실패 상태가 보인 직후에도 이전 작업의 비공개 정리가 끝나지 않았으면 기존 중복 접수 방지 정책이 우선하므로 잠시 후 다시 시도해야 할 수 있습니다. 타일/미리보기의401/403/404, 파일 버전/페이지 전환과 늦은 응답 격리는 기존 정책을 유지합니다.

## 운영 관측

기존 고정 감사 이벤트의 code에 CONVERSION_BUSY 또는 TILE_QUEUE_FULL을 기록하며 명령/예외 문구를 추가하지 않습니다. 미리보기 지표는 outcome=conversion_busy를 별도로 집계합니다. imagegate.preview.jobs.completed/duration의 고정 시계열2개가 추가되어 활성 모드의 meter는32→34개(이름11개)입니다. 기존 conversion_failed 집계에서 실제 용량 부족이 분리되므로 해당 outcome만 보던 대시보드는 함께 조회해야 합니다. 설치 기본 metrics 비활성 정책은 유지합니다.

## 검증

서비스/저장소는 BUSY의 고정 코드·재시작 보존·조회/일반 POST 비재접수·명시적 재시도·실행기 거절 시 상태/lease 정리를 검증합니다. HTTP 테스트는 접수429와 비동기 failed/CONVERSION_BUSY를 구분하고 CSRF/다른 사용자/권한 철회 후 retry를 거절합니다.

Windows의 NativeCapacityIntegrationTests는 실제 BoundedProcessExecutor(1)를 미리보기/타일이 같은 img2tile 인스턴스로 공유합니다. 제어된 Java 자식 프로세스로 유일한 허가를 점유한 동안 두 큐의 BUSY를 확인하고, 점유 프로세스 종료/허가 반환 뒤 실제 내부 img2tile을 순서대로 명시적 재시도해 정상 게시합니다. 실험용 자식 프로세스/인증 fixture를 운영 JAR에 포함하지 않습니다.

```powershell
.\gradlew.bat test -Pimg2tileIntegration --tests '*NativeCapacityIntegrationTests'
```

Chrome 시나리오는 타일 BUSY/접수429/영속 실행기 거절 안내, 미리보기 BUSY/접수429 안내, 자동 POST 없음, 진행 중 중복 클릭 거절, 수동 복구를 확인합니다. 기존 회전·편집·재생·권한/버전·늦은 응답 격리도 함께 실행합니다. 합성 자료/테스트 인증의 검증이며 실제 SSO/업무 부하·OS 격리/최종 배포 인수는 별도입니다. 설치 ZIP은 최종 단계에만 생성합니다.

## 최종 결과

2026-10-28 전체 `build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration`이6분에 성공했습니다. Java732개/108개 suite 실패·오류·건너뜀0, Chrome34개 시나리오 통과입니다. 신규 Java7개는 BUSY 영속/명시적 재시도, 실행기 접수 거절 정리, 미리보기 재시도, 재시작 보존, 고정 지표 집계, 실제 HTTP 재시도 보안, 실제 공유 허가/내부 모듈 복구를 포함합니다.

라이선스16개 규칙·63개 식별 구성요소/53개 포함 JAR 기준선과 최종 실행 JAR7개 뷰어 자산의 소스 byte 일치, 테스트/인증 fixture 포함 항목0개도 확인했습니다. 통합 보고서는 `build/reports/native-capacity/verification.json`입니다. 실제 설치 YAML·업무 자료·모듈/권한 정책을 변경하지 않았고 설치 ZIP은 생성하지 않았습니다.
