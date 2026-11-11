# 처리 중 권한 회수와 최종 게시·응답 검사

후속 게시/저장 실패·JVM 강제 종료 후의 이전 세대/lease 보존과 복구 검증은 [PUBLICATION_FAILURE_RECOVERY.md](PUBLICATION_FAILURE_RECOVERY.md)를 참고합니다.

2026-10-29. 처리 시작 때의 권한만으로 완료 결과를 내보내지 않도록 최종 검사 시점을 보완했습니다. 실제 로그인/전달 grant 수신을 구현한 단계는 아니며, 기존 MediaAuthorizationPolicy/MediaService에 대해 latch로 순서를 고정한 합성 동시성 회귀입니다.

## 재현한 반출 경합과 수정

CropService는 래스터를 만든 뒤 반출 권한과 영역 자르기 권한을 확인하고 주석 최종 스냅샷을 다시 읽었습니다. 그러나 이 읽기 동안 권한이 회수되면 이후 응답 callback이 실행될 수 있었습니다. 주석 최종 읽기를 latch로 멈춘 뒤 EXPORT_EDITED 또는 영역 반출의 CROP_AND_SAVE를 회수한2개 시험에서, 수정 전에는 예외 없이 writer가 호출되어 시험이 실패했습니다.

출력 스트림을 연 다음 writer 호출 직전에 현재 원본 레코드/요청 행위, 영역 반출이면 CROP_AND_SAVE도 다시 검사합니다. 최종 스냅샷 읽기 뒤 발생한 회수를 이 검사에서 거절합니다. 응답 callback에 진입하지 않으므로 해당 시험에서는 이미지 응답을 시작하지 않습니다. 거절 후 출력/작업 폴더 정리와 동시성 슬롯 반환, 권한 복구 후 정상 반출을 확인했습니다. 기존 원본 해시·좌표/버전·주석 리비전 검사와 복사 중 슬롯 보유는 유지합니다.

## 미리보기·재생의 마지막 ready 기록

| 경로 | 최종 검사 위치 | 거절 결과 |
| --- | --- | --- |
| 미리보기 | PNG/thumbnail을 비공개 result 이름으로 옮기고 기존 해시/속성 기록을 만든 뒤, ready metadata 저장 직전 | PREVIEW_FAILED, ready/thumbnail 미게시, 이번 게시 시도에서 이동한 result 파일 정리 |
| 재생 | 재생본을 비공개 result 이름으로 옮기고 메타데이터를 만든 뒤, ready metadata 저장 직전 | PLAYBACK_FAILED, ready 미게시, 이번 재생본 정리 |
| crop/합성 반출 | 최종 주석 확인과 출력 스트림 open 뒤, 동기 writer 호출 직전 | 현재 권한/버전 오류 전파, writer 미호출, 작업/슬롯 정리 |

PreviewQueue는 PreviewCache.publish의 PublicationCheck에 renderer.verifyPublication을 연결합니다. 운영 ImagePreviewRenderer는 기존 requireAccess를 재사용해 현재 actor/tenant·원본 레코드/버전·VIEW·IMAGE_VIEW 설치 기능을 확인합니다. 재생도 PlaybackCache의 PublicationCheck에서 PlaybackService.access로 같은 현재 원본/actor/VIEW·영상/음성 기능을 확인합니다. 기존 실행 시작/완료 검사와 파일/해시·출력 예산 검사는 제거하지 않습니다.

캐시는 정책을 해석하지 않고 service가 준 callback을 호출합니다. renderer의 기본 hook 및 기존 캐시 publish overload는 신뢰된 fixture/직접 호출의 호환성을 위한 것이며, 생산 renderer는 최종 검사를 구현해야 합니다. 기본 Spring 구성은 실제 ImagePreviewRenderer를 사용합니다. HTTP/YAML로 검사를 끄거나 caller가 검사 함수를 주입하는 기능은 없습니다. 타일의 기존 게시 gate와 승인 프로필은 변경하지 않습니다.

최종 검사 또는 메타데이터 저장이 실패하면 이번 시도에서 실제로 이동한 파일만 정리하고 원래 예외를 전파합니다. 정리 I/O 실패는 suppressed 예외로 보존합니다. 이전 세대/다른 작업 파일을 삭제하지 않습니다. 원본·사용자 스탬프·주석은 건드리지 않으며 작업 lease와 writer/슬롯 수명도 기존 구조를 유지합니다. OS 오류로 정리가 실패해도 ready로 승인하지 않으며 잔재는 기존 중지 점검/정리 절차 대상입니다.

## 동시성 회귀

- CropServiceTests2개: 최종 주석 읽기를 멈추고 EXPORT_EDITED 또는 영역 CROP_AND_SAVE를 회수. writer 미호출·작업 폴더 비움·슬롯 회복/재사용 확인.
- PreviewPublicationAuthorizationTests3개: 실제 JDK PNG renderer와 캐시를 사용하고 result가 비공개로 이동한 것을 확인한 뒤 최종 검사에서 멈춤. VIEW 회수·원본 레코드 버전 변경·설치 이미지 기능 비활성화를 각각 적용해 실패 상태/thumbnail 없음·result 정리·원본 바이트 보존·명시적 재시도 복구 확인.
- PlaybackServiceTests2개: 합성 엔진이 만든1024바이트 재생본이 result로 이동한 것을 감지한 정책 fixture에서 멈춤. VIEW 또는 설치 재생 기능을 회수하여 ready 기록/비공개 result가 남지 않는지와 재접수 복구 확인.

미리보기의 실제 파일 저장소에 합성 PNG를 등록하되 현재 카탈로그 버전 변경은 MediaStore fixture로 재현합니다. 재생 엔진과 반출의 스냅샷/권한 공급자는 이 시험에서 합성 fixture입니다. 실제 원격 grant 전파나 native 디코더 정지 시험으로 집계하지 않습니다. 임의 sleep으로 권한 회수 시점을 추측하지 않고 CountDownLatch와 제한 시간을 사용합니다.

```powershell
.\gradlew.bat test --tests '*CropServiceTests' --tests '*PreviewPublicationAuthorizationTests' --tests '*PlaybackServiceTests' --tests '*PreviewQueueTests' -Pimg2tileIntegration
```

집중36개가47초에 통과했습니다. 기존 주석 리비전 충돌·전송 실패/슬롯·큐 종료/캐시 재사용 회귀도 포함합니다. 신규 동시성 경우는7개입니다.

## 보장 범위와 운영 연동

검사 이후 ready metadata 기록/응답 쓰기 사이까지 외부 권한 저장소와 원자적으로 잠그는 것은 아닙니다. 실제 연계 권한의 최신성은 공급자와 회수 전파 정책에 달려 있습니다. 이 변경은 검사 이전에 발생해 확인 가능한 회수를 차단하고 마지막 검사까지의 처리 구간을 줄입니다. 이미 시작한 전송을 계속 감시·강제 중단하거나 클라이언트가 받은 바이트를 회수하지 않습니다.

최종 검사는 기존 큐/캐시 게시 잠금 안에서 수행될 수 있으므로 연동 policy는 빠르고 시간/자원이 제한된 조회를 제공해야 합니다. 검증된 전달 grant의 서버 조회와 원격 조회의 timeout/회수 일관성은 [연계 계약](LINKED_AUTHORIZATION.md) 확정 항목입니다. 무제한 원격 호출을 추가하지 않았습니다. 추가 검사는 준비된 파일을 게시할 때1회이며, 현재 미리보기·타일·재생의 매 바이트/청크마다 새 ACL 호출을 넣지 않았습니다. 운영 성능 SLA나 분산 권한의 즉시 회수를 입증한 결과로 해석하지 않습니다.

캐시 포맷·키·공개 API 코드/응답 형식·설치 YAML·native 실행 파일/DLL·ImageMagick 제외 정책은 유지합니다. [이전 행위별 검사](LINKED_ACTION_PERMISSIONS.md), [실패 안내](PREVIEW_FAILURES.md), [운영 인수](OPERATIONAL_ACCEPTANCE.md)와 함께 확인합니다. 설치 ZIP은 생성하지 않습니다.

## 최종 검증

전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration이6분2초에 성공했습니다. Java831개/116 suites에서 실패·오류·건너뜀은0개입니다. 실제 img2tile/FFmpeg/native 브라우저·라이선스16개 규칙/63개 구성요소/53개 포함 JAR 대조도 통과했습니다. 일반 UI40개는 이전 단계 결과이며 이번 서버 게시 변경에서 별도 재실행하지 않았습니다.

실행 JAR의 PublicationCheck2개 포함·테스트/grant fixture0개, 타일 정규화/게시/재조회 smoke와 관련3개 문서 로컬 링크7개를 확인했습니다. 집계는 build/reports/publication-authorization/verification.json입니다. 실제 로그인·권한 수신/회수 전파·OS 격리·업무 부하/최종 배포 인수는 미완료이며 distributionReady=false입니다. 실제 설치 YAML·native 모듈·업무 자료·외부 의존성을 변경하지 않았고 설치 ZIP은 생성하지 않았습니다.
