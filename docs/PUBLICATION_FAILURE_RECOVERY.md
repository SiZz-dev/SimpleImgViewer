# 게시 실패·강제 종료 후 캐시 정리와 재시작

2026-10-29. [최종 권한 검사](PUBLICATION_AUTHORIZATION.md)에 이어, 미리보기/thumbnail과 재생 캐시의 실패 정리·이전 세대/lease 보존·프로세스 종료 후 복구 경계를 검증했습니다. 모든 입력과 캐시는 JUnit 임시 경로의 합성 자료입니다. 실제 설치 데이터나 실행 중인 사용자 프로세스를 중지하지 않았습니다.

## 저장·검사 실패

| 주입한 상황 | 확인한 결과 |
| --- | --- |
| 최종 PublicationCheck가 IOException 또는 RuntimeException을 던짐 | 같은 예외 객체 전파, 이번 시도의 이동 산출물만 제거. 이전 PNG/thumbnail/재생본과 이전 metadata 바이트 유지 |
| ready metadata의 원자 교체가 실패 | 임시 metadata 파일 정리, 새 산출물 정리. 손상된 metadata 경로에서는 읽기 실패로 닫히며 잘못된 ready를 노출하지 않음 |
| 정리해야 할 새 result 파일을 비어 있지 않은 디렉터리로 교체 후 검사 실패 | 원래 실패 유지, 정리 실패는 suppressed1개. 알 수 없는 하위 파일을 재귀 삭제하지 않고 보존. preview는 다른 새 산출물 정리도 계속 수행 |

미리보기·재생 각각 IOException/RuntimeException/metadata 교체 실패3개와 정리 실패1개를 시험합니다. 이전 정상 세대를 만든 다음 key lease를 유지한 상태로 실패시키고, 원래 바이트와 경로가 계속 유효한지 확인합니다. TTL이 지난 시각으로 maintenance를 실행해도 lease가 있는 항목은 제거되지 않습니다. 실패 뒤 정상 publish도 가능하며 새로운 artifact를 얻어도 이전 reader가 잡은 파일은 남아 있습니다.

metadata 실패는 fixture가 기존 state.properties를 보관하고 그 자리에 marker가 있는 디렉터리를 만들어 atomic replace를 막는 방식입니다. 거절/정리 결과를 확인한 뒤 **fixture가** 이전 파일을 복원해 정상 세대를 대조합니다. 제품이 손상 metadata를 자동으로 복원하는 기능을 구현한 것은 아닙니다. 디스크 가득 참·장치 단절·전원 차단·실제 ACL 고장을 그대로 재현한 시험도 아닙니다.

직접 repository.publish는 기존 metadata 위에 게시를 시도하므로 실패 후 이전 ready가 보존되는 경우를 검사합니다. 실제 queue/service 경로는 먼저 queued/running을 기록하고 실패 상태로 처리합니다. repository 회귀의 이전 ready 보존을 권한 거절 뒤 서비스가 예전 이미지를 자동으로 제공한다는 뜻으로 해석하지 않습니다. 모든 데이터 요청의 현재 권한·상태 검사는 유지됩니다.

## 별도 JVM의 비정상 종료

테스트 전용 PublicationCrashFixture가 별도 Java21 JVM에서 writer 잠금을 확보하고 running 상태와 합성 산출물을 만듭니다. 최종 PublicationCheck에서 Runtime.halt(73)으로 종료시켜, 결과 이동은 끝났지만 ready metadata는 아직 기록되지 않은 시점에 catch/finally/close/shutdown hook을 건너뜁니다. 부모 시험은 기존 BoundedProcessExecutor의 제한 시간/출력·환경 경계를 사용하고 종료 코드73을 확인합니다. 클래스 경로는 프로젝트 main/test 출력 디렉터리이며 새 프로그램·DLL·다운로드 자료를 넣지 않습니다.

| 재시작 단계 | 미리보기 | 재생 |
| --- | --- | --- |
| 자식 종료 후 | 비공개 result2개와 작업 잔재 존재 | 비공개 result1개와 작업 잔재 존재 |
| 다시 queue/service 생성 | writer 잠금 재확보, running을 활성 job 없는 failed/INTERRUPTED로 조회 | writer 잠금 재확보, 동일하게 failed/INTERRUPTED로 조회 |
| 상태/콘텐츠/일반 접수 | ready 제공 거절, retry=false로 renderer 호출0 | ready 제공 거절, retry=false로 engine.prepare0 |
| 명시적 retry=true | 새 renderer1회로 정상 ready | 새 engine.prepare1회로 정상 ready |
| 이후 TTL 정리 | 이전 강제 종료 잔재 때문에 항목 보존/skip | 동일하게 보존/skip |

running 파일 자체를 startup에서 무조건 failed로 재작성하거나 실패 자료를 자동 재접수하지 않습니다. 현재 service가 활성 job 부재를 확인해 INTERRUPTED로 해석하는 기존 계약을 검증했습니다. 재시도는 새 결과를 만들지만 이전 고아 파일과 오래된 작업 디렉터리까지 자동으로 청소했다는 뜻은 아닙니다. 검사 도중 원본 보존용 합성 파일과 재생 원본 바이트도 변경되지 않습니다.

이 시험은 JVM 프로세스의 강제 종료와 OS 잠금 해제를 검증합니다. 머신/디스크 전원 상실 후의 내구성, 실제 native 자식이 계속 살아 있는 상황, OS 전체 장애 복구를 입증하지 않습니다. 운영자가 확인할 수 없는 잔재를 삭제해 복구된 것처럼 만들지 않습니다.

## 운영 절차와 검증 범위

알 수 없는 파일·작업 디렉터리·손상 metadata는 온라인 정리가 보수적으로 보존합니다. 디스크 여유 공간/알림을 확인하고, 서비스/생성 프로세스가 중지된 상태에서 [오프라인 잔재 점검](OFFLINE_MAINTENANCE.md)의 dry-run으로 개별 판정해야 합니다. 이번 잔재가 모두 삭제 가능한 것으로 승인한 결과는 아닙니다. 실제 저장소에 apply를 실행하지 않았으며 원본·주석·스탬프·업무 자료를 변경하지 않았습니다. 정상 TTL/용량/lease 정책은 [캐시 유지보수](CACHE_MAINTENANCE.md)를 따릅니다.

```powershell
.\gradlew.bat test --tests '*PreviewCachePublicationFailureTests' --tests '*PlaybackCachePublicationFailureTests' --tests '*PlaybackServiceTests' -Pimg2tileIntegration
```

집중25개가41초에 통과했습니다. 신규10개는 미리보기 repository5개(실패3/정리1/강제 종료1), 재생 repository4개(실패3/정리1), 재생 service 강제 종료1개입니다. PNG는 실제 ImageIO 산출물이지만 재생 바이트는 codec 검증을 위한 자료가 아닌 합성 fixture이며, 실제 FFmpeg/브라우저 회귀와 구분합니다.

운영 코드의 추가 결함은 발견하지 않아 동작을 변경하지 않았습니다. 캐시 포맷/키·설치 YAML·native 모듈·새 의존성·인증/권한 수신 동작 및 설치 ZIP 생성은 변경하지 않습니다.

## 최종 검증

전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration이6분25초에 성공했습니다. Java841개/118 suites의 실패·오류·건너뜀은0개이며 신규10개를 포함합니다. 실제 img2tile/FFmpeg/native 브라우저·라이선스16개 규칙/63개 구성요소/53개 포함 JAR 대조도 통과했습니다. 일반 UI40개는 이전 단계 결과이며 이번 캐시 실패 검증에서 별도 재실행하지 않았습니다.

실행 JAR의 테스트/강제 종료 fixture 항목0개와 관련3개 문서 로컬 링크9개를 확인했습니다. 집계는 build/reports/publication-failure-recovery/verification.json입니다. 실제 전원/장치 장애·native 잔존 프로세스·업무 자료 복구·인증/권한 수신·최종 배포 인수는 미완료이고 distributionReady=false입니다.
