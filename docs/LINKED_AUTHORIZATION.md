# 연계 시스템 인증·전달 권한 계약

후속 스탬프·자르기·합성 반출·재생 캐시의 행위별 검사와 회수 검증은 [LINKED_ACTION_PERMISSIONS.md](LINKED_ACTION_PERMISSIONS.md)를 참고합니다. 실제 권한 수신 기능의 구현 상태와 구분합니다.

2026-10-29 사용자 확인: **로그인 연동 방식은 미정이며, 파일 권한은 추후 연계 시스템에서 전달받습니다.** 이 문서는 전달 자료의 의미와 서버 검증 경계를 정리한 계약 초안입니다. 수신 endpoint·서명 검증·SSO/JWT·grant 저장소를 구현한 상태가 아니며, 브라우저가 보낸 권한을 바로 신뢰하지 않습니다.

## 현재 연결 지점

| 영역 | 기존 코드 | 연동 시 역할 |
| --- | --- | --- |
| 인증 필터 체인 | security/config.SecurityConfiguration | 신뢰하는 인증 공급자/필터 연결. 기본 계정·Basic/form 로그인·임의 헤더 인증 우회 없음 |
| 서버 사용자 | security/domain.ViewerPrincipal, service.CurrentActor | 검증된 tenant/user만 수용. 인증되지 않은 토큰·문자열 principal·익명 토큰 거절 |
| 파일 권한 | media/service.MediaAuthorizationPolicy | 검증된 전달 권한으로 현재 파일/버전/행위 허용 여부 결정. 현재 기본은 동일 조직 소유자 정책 |
| 서버 권한 결합 | media/service.MediaService | 현재 VIEW와 요청 행위, 조직 경계, 설치 기능을 모두 검사. 응답 permissions는 안내용 |
| 준비·게시·재생 | ImagePreviewRenderer, NativeTileRenderer, PlaybackService | 작업 시작/완료 등의 기존 재검사에서 같은 정책 사용. 미리보기/타일 키나 sourceId가 권한을 대신하지 않음 |

실제 공급자 미연동 상태에서 /viewer 셸이 보이더라도 파일 API를 익명으로 열지 않습니다. 연동 코드가 CurrentActor를 통과하는 인증 객체를 만들기 전에 신원·발급자·대상 시스템·수명·회수 상태를 검증해야 합니다. 연계 시스템의 권한은 인증된 사용자에 연결해야 하며, 사용자 인증과 파일 권한 전달 방식은 별도로 확정할 수 있습니다.

## 전달 자료 초안

다음은 의미를 합의하기 위한 합성 예시입니다. 현재 POST /viewer/sessions나 미디어 API에 추가한 요청 DTO가 아니며 붙여 넣어 권한을 부여할 수 없습니다. 식별자는 서버 카탈로그와 매핑해야 하고 파일 경로·실행 명령·비밀값은 전달하지 않습니다.

```json
{
  "schemaVersion": 1,
  "grantId": "example-grant-001",
  "issuer": "linked-system-example",
  "audience": "imagegate-example",
  "tenantId": "example-tenant",
  "userId": "example-user",
  "mediaId": "00000000-0000-4000-8000-000000000001",
  "mediaVersion": "00000000-0000-4000-8000-000000000002",
  "permissionVersion": "example-acl-revision-1",
  "actions": ["VIEW", "ANNOTATE"],
  "issuedAt": "2026-10-29T00:00:00Z",
  "notBefore": "2026-10-29T00:00:00Z",
  "expiresAt": "2026-10-29T00:05:00Z"
}
```

예시 날짜는 이미 만료된 고정 설명 자료이며5분을 운영 TTL로 정한 것은 아닙니다. 최종 필드명·필수 여부·버전 형식·수명·시각 오차·재전달/회수 규칙은 연계 규격 확정 시 결정합니다.

| 연결 항목 | 검증 계약 |
| --- | --- |
| issuer / audience | 허용된 발급자와 이 설치 대상인지 신뢰 채널/서명 검증 결과로 확정. 본문 문자열만으로 인증하지 않음 |
| tenantId / userId | 검증된 현재 ViewerPrincipal과 일치. 다른 조직·사용자용 전달 자료를 재사용하지 않음 |
| mediaId / mediaVersion | 서버 카탈로그의 현재 파일·원본 버전과 일치. 업무 문서 ID를 쓰면 서버 측 매핑 필요 |
| actions | 고정 행위 목록. 누락·미확인·해석 불가 항목으로 허용을 확대하지 않음. VIEW 없이는 편집/반출도 불가 |
| grantId / permissionVersion | 중복 전달·권한 갱신/회수·재전달 정책의 식별 근거. 값이 있다는 사실만으로 재사용/위조 방지가 구현되지는 않음 |
| issuedAt / notBefore / expiresAt | 서버 기준 유효 구간. expiresAt 시점에는 거절. 시각 오차·최대 TTL은 미정 |

권한 행위는 VIEW, ANNOTATE, STAMP, CROP_AND_SAVE, EXPORT_EDITED를 각각 구분합니다. MediaAction의 DOWNLOAD_ORIGINAL/PRINT 존재는 원본 다운로드/인쇄 기능 구현 완료를 뜻하지 않으며, 최종 연계 허용 목록에 자동으로 포함하지 않습니다. 별도 반출 권한이 없는 열람 권한으로 crop/export를 허용하지 않습니다. 저장/주석/스탬프의 소유자 정책·공유 범위와 업무 자료 보존 정책은 별도로 확정해야 합니다.

파일별 grant는 원본 등록 권한을 정의하지 않습니다. 현재 등록은 인증·설치 기능·업로드 제한을 검사하며, 연계 사용자의 등록 허용/금지와 외부 업무 파일 등록 주체는 별도 계약이 필요합니다. 이 단계에서 등록 권한을 전달받아 적용한 것으로 표시하지 않습니다.

## 전달 경로에서 남은 결정

서버 간 보호된 호출로 전달하는 방식과 브라우저가 서명된 자료를 중계하는 방식 등은 아직 선택하지 않았습니다. 실제 신뢰 채널·키/자격증명 관리·발급자 검증·재전달 방지·회수 방법을 정하기 전까지 임의 수신 API를 추가하지 않습니다. 원문 권한 JSON/쿼리·X-Permissions·X-User-Id 같은 헤더는 허용 근거가 아닙니다. 다른 애플리케이션과 쿠키 이름이 같다는 이유로 인증 세션이 공유되는 것으로 취급하지 않습니다.

누락·만료·회수·파일/버전 불일치·정책 조회 실패 시 허용으로 전환하거나 소유자 정책으로 우회하지 않는 계약이 필요합니다. 실제 연동 정책을 선택하면 그 정책의 거절을 존중해야 합니다. 조회 timeout·응답 상한·동시성·검증 결과 저장 및 회수 전파 지연은 운영 속도/보안 목표와 함께 확정합니다. 전달 권한을 요청마다 검사할지 검증된 서버 상태에서 조회할지, TTL 동안의 회수를 어떻게 처리할지도 미정이며 무기한 허용 캐시를 만들지 않습니다.

동일 사용자라도 파일/버전별 권한을 구분하고 설치 기능과의 교집합을 사용합니다. 뷰어 세션 생성 때의 허용만으로 이후 API를 허용하지 않습니다. 이미 클라이언트에 전달한 바이트를 회수할 수 없으므로 원본/재생 스트림 시작 전·시작 후의 재검사/회수 시점과 허용할 지연도 실환경 인수 항목입니다. 현재 시험은 전송 시작 전 권한 회수를 확인하며 진행 중 전송의 즉시 중단을 입증하지 않습니다.

## 이번 서버 계약 검증

LinkedPermissionContractTests는 기존 MediaAuthorizationPolicy에 **테스트 전용 서버 grant**를 연결합니다. 요청의 사용자/권한 필드를 읽는 필터나 실제 grant 수신 코드는 없습니다. 합성 PNG를 실제 등록/준비한 뒤 다음을 HTTP controller/서비스/저장소 경로에서 검증합니다.

- 다른 소유자에게 VIEW만 부여하면 미리보기는 가능하고 주석/반출 권한을 상속하지 않음. X-Permissions 헤더로 확대 불가.
- VIEW+ANNOTATE에서 주석 생성 가능, CSRF 누락 거절. ANNOTATE만 회수하면 읽기는 유지하고 후속 생성 거절.
- 유효기간 끝에서 기존 PNG URL·상태·뷰어 세션·변환 접수 거절.
- 다른 tenant/user/media/version 또는 VIEW 없는 편집 권한은 거절.
- 전달 crop 권한이 있어도 설치 crop=false이면 응답 권한 false와 실제 요청403.
- 사용자 지정 정책이 소유자를 거절하면 기본 소유자 정책으로 자동 우회하지 않음.

CurrentActorTests는 인증된 것처럼 표시된 AnonymousAuthenticationToken에 ViewerPrincipal을 넣어도 거절되는지 보완했습니다. 합성 grant의 만료/행위 처리는 테스트 fixture에만 있으므로 운영 grant 검증기 구현이나 실제 SSO/연계 서버 인수 완료로 집계하지 않습니다.

```powershell
.\gradlew.bat test --tests '*LinkedPermissionContractTests' --tests '*CurrentActorTests' -Pimg2tileIntegration
```

집중13개(공유 권한 API10개·인증 주체3개)가37초에 통과했습니다. 실제 권한 전달 경로 구현은 공급자/신뢰 경계가 정해진 다음 단계이며 [운영 인수 A02](OPERATIONAL_ACCEPTANCE.md), [미디어 보안](MEDIA_SECURITY.md)을 따릅니다. 이번 단계는 운영 인증 동작·설치 YAML·업무 자료·외부 의존성·설치 ZIP을 변경하지 않습니다.

## 최종 검증

전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration이6분27초에 성공했습니다. Java816개/114 suites의 실패·오류·건너뜀은0개입니다. 신규11개는 API 계약10개와 typed 익명 토큰 거절1개입니다. 실제 img2tile/FFmpeg/native 브라우저·라이선스16개 규칙/63개 구성요소/53개 포함 JAR 대조도 통과했습니다. 일반 UI40개 시나리오는 이전 단계 결과이며 이번 서버 계약 단계에서 별도 재실행하지 않았습니다.

테스트용 grant/policy를 포함한 fixture의 실행 JAR 항목0개와 관련4개 문서의 로컬 링크42개를 확인했습니다. 집계는 build/reports/linked-authorization/verification.json입니다. 실제 SSO·서명 검증·권한 수신/회수 전파·원격 조회 장애·전송 중 회수·운영 배포는 미인수이며 distributionReady=false를 유지합니다.
