# 미리보기 실패 코드와 뷰어 안내

2026-10-29. 미지원 구성과 잘못된 입력·변환 기능 불가·일반 native 실패를 구분합니다. 변환기는 기존 img2tile을 유지하며 자동 대체하지 않습니다.

## API와 화면 계약

기존 state/errorCode/source/thumbnail 필드를 유지하고 CONVERSION_UNSUPPORTED_PROFILE을 추가합니다. 비동기 실패는 기존 상태/페이지 API의200 응답에 state=failed로 반환합니다. 등록 시 헤더 후보조차 없는415 UNSUPPORTED_MEDIA와 구분하며 등록·상태 조회의 HTTP 계약은 바꾸지 않습니다.

| errorCode | 의미 | 뷰어 안내 / 재시도 버튼 |
| --- | --- | --- |
| CONVERSION_UNSUPPORTED_PROFILE | 현재 허용하지 않는 구성 선언: JP2 회색·알파/비승인 정밀도·색공간·채널 매핑 등 | 지원하지 않는 이미지 구성, 지원되는 사본 또는 관리자 문의 / 숨김 |
| DECODER_UNAVAILABLE | 설정된 디코더 없음 | 이미지 형식을 열 수 없음, 지원 형식 문의 / 숨김 |
| CONVERSION_INVALID_INPUT | 잘못된 구조·범위·불일치 등 | 파일 내용/처리 범위 확인 불가, 원본 확인 또는 관리자 문의 / 숨김 |
| CONVERSION_UNAVAILABLE | 모듈/리소스·승인 상태 사용 불가 | 관리자에게 변환 기능 상태 확인 / 숨김 |
| CONVERSION_PROCESS_FAILED | native 실패; 원인은 확정하지 않음 | 변환 실패, 반복 시 원본 확인/관리자 문의 / 수동 버튼 유지 |
| ORIGINAL_OPEN_BUSY / CONVERSION_BUSY | 원본 열기 또는 변환 혼잡 | 잠시 후 재시도 / 수동 버튼 유지 |
| 기타 기존/미확인 코드 | 별도 원인 추정 없음 | 고정 일반 실패 안내 / 수동 버튼 유지 |

미지원 구성 분류는 입력 파일의 유효성 인증이 아닙니다. 전체 디코딩 전에 거절될 수 있습니다. 허용 RGB 구성 안의 IHDR/SIZ 불일치와 box 범위 오류는 INVALID_INPUT, 현재 승인하지 않은 구성 선언은 UNSUPPORTED_PROFILE입니다. 여러 문제가 겹치면 먼저 확인한 거절 사유를 사용합니다. 원시 J2K의 exit1 관측만으로 모든 native exit1을 미지원으로 추정하지 않습니다. 지원 범위는 [FORMAT_SUPPORT.md](FORMAT_SUPPORT.md)를 따릅니다.

실패에는 source/thumbnail이 없고 editingReady/originalCoordinatesAvailable은 false입니다. 이전 PNG가 남아 있어도 현재 상태가 실패이면 본문 제공을 거절합니다. 상태·페이지·콘텐츠·재접수는 현재 사용자/tenant·파일 권한·기능을 재확인하고 POST는 CSRF 검증을 유지합니다. 익명401·타 조직/권한 회수404 계약도 유지합니다.

UI는 코드별 고정 문구만 textContent로 표시합니다. 알 수 없는 코드와 __proto__도 Object.hasOwn으로 확인한 뒤 일반 안내를 사용하며, 원시 예외·명령·경로·native 출력은 표시하지 않습니다. 실패 확인 후 폴링을 끝내고 자동 재접수하지 않습니다. 버튼 숨김은 안내 정책이며 권한 검사를 대신하지 않습니다. API의 명시적 retry=true는 기존 인증/CSRF·권한·기능·용량 검사를 거쳐 허용될 수 있고, 입력/모듈이 그대로이면 같은 실패가 날 수 있습니다. 새로운 retryable 권한 필드는 추가하지 않습니다.

ConversionException→PreviewQueue의 고정 코드가 PreviewCache에 기록되어 재시작과 retry 없는 접수에서도 유지됩니다. 지표는 기존 conversion_failed 분류를 재사용하여 meter/tag 집합을 늘리지 않습니다. 타일·재생 오류 계약은 바꾸지 않습니다.

## 캐시 호환성

JP2 허용 범위는 그대로이고 정책 식별자는 jp2-rgb-v2입니다. v1의 회색 실패가 일반 INVALID_INPUT으로 남아 새 안내를 가리지 않도록 새 키를 사용합니다. 이전 완료 PNG·실패 상태를 재사용하지 않고 파일을 직접 삭제하지 않습니다. img2tile이 활성인 큐의 다른 형식도 최초 준비가 다시 필요할 수 있습니다. 실제 설치 YAML·native 바이너리/DLL·타일 승인 fingerprint는 바꾸지 않습니다.

## 검증

집중 Java77개가2분1초에 통과했습니다. 실제 JP2/회색 거절·헤더 회귀, 새 코드의 queue 영속화/재시작·비자동 접수, API 실패 응답/기존 본문 거절·편집 차단·CSRF·조직/권한 회수를 포함합니다. API fixture는 게시된 PNG의 상태를 실패로 바꿔 남아 있는 본문도 차단되는지 확인합니다.

Chrome은 기존34개+오류6개의40개를 통과했습니다. 고정 문구·버튼 표시·이미지/편집 비노출·자동 재접수 없음과 일반 실패의 클릭 재시도를 확인합니다. 초기 제한 실행은 루프백 접속 차단으로 실패했고, 루프백이 허용된 실행에서 전체40개를 재검증했습니다. 합성 API 브라우저 시험은 업무 SSO 인수를 대신하지 않습니다.

```powershell
.\gradlew.bat test --tests '*Jp2InputProfileTests' --tests '*PreviewQueueTests' --tests '*MediaPreviewApiTests' --tests '*Jpeg2000*IntegrationTests' --tests '*Img2TileConverterTests' -Pimg2tileIntegration -PffmpegIntegration
node scripts/test-viewer.mjs
```

브라우저 환경은 [VIEWER_UI.md](VIEWER_UI.md)를 참고합니다. 실제 업무 SSO/ACL·HEIC 유효 표본·운영 부하/OS 격리·최종 배포 인수는 잔여이며 새 의존성·설치 ZIP은 추가하지 않습니다.

## 최종 검증

전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration이7분46초에 성공했습니다. Java805개/113 suites의 실패·오류·건너뜀은0개입니다. 실제 img2tile/FFmpeg/native 브라우저·라이선스16개 규칙/63개 구성요소/53개 포함 JAR 검사를 포함합니다. Java 신규2개는 queue 영속화/재시작과 API 실패·권한 계약입니다. 기존 프로필/변형 회귀의 기대 코드는 새 구분에 맞춰 갱신했습니다.

편집 기능이 켜진 fixture로 강화한 최종 Chrome40개도 통과했습니다. 실행 JAR의 뷰어 자산9개 byte 일치·테스트/fixture0개, 타일 정규화/게시/재조회 smoke 및 관련4개 문서의 로컬 링크17개를 확인했습니다. 집계는 build/reports/preview-failures/verification.json입니다. 실제 설치 YAML·native 실행 파일/DLL·업무 자료 변경이나 설치 ZIP 생성은 없으며 distributionReady=false입니다.
