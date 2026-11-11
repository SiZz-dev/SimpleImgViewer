# 뷰어 세션·페이지 API

## 구현 범위

세션은 선택한 파일 순서, 첫 파일·페이지 또는 재생 시작 희망 시간, 다크/라이트 테마,
좌측 패널 열림 상태를 잠시 보관합니다. 인증 로그인 세션과는 다른 뷰어 상태입니다.
선택한 '포커스 · 썸네일 탐색' 시안을 기준으로 /viewer에 기본 UI를 연결했습니다. 연결 방법과 구현 범위는 VIEWER_UI.md를 참고하세요.

- POST /api/v1/viewer/sessions: 인증·CSRF·JSON 필수. 201 + Location.
- GET /api/v1/viewer/sessions/{sessionId}: 소유자·만료·모든 파일 권한·버전 검사 후 200.
- DELETE /api/v1/viewer/sessions/{sessionId}: 인증·CSRF 필수. 소유자 삭제 후 204.
- GET /api/v1/media/{mediaId}/pages/{page}: 현재 사용자에게 허용된 이미지 페이지 미리보기 정보.

파일은 media API에서 등록한 ID로 지정합니다. 서버 경로·임의 URL은 입력으로 받지 않습니다.
요청 파일 중 하나라도 권한이 없으면 전체 생성/조회를 404로 거부합니다.
모든 파일의 접근을 확인하기 전에는 세션을 생성하거나 변환 작업을 접수하지 않습니다.
세션은 소유자와 정확한 조직 ID에 귀속됩니다. 알 수 없는 세션·다른 사용자·만료된 세션 모두 404입니다.
파일 권한이 회수된 뒤에도 소유자는 자신의 세션을 삭제할 수 있습니다.

최소 요청:

~~~json
{"mediaIds":["등록한-미디어-UUID"]}
~~~

선택 요청:

~~~json
{
  "mediaIds": ["첫번째-미디어-UUID", "두번째-미디어-UUID"],
  "initialMediaId": "두번째-미디어-UUID",
  "initialPage": 2,
  "theme": "light",
  "leftPanelOpen": false
}
~~~

mediaIds 1~100개, 중복 불가. 기본 선택은 첫 파일, 테마 dark, 좌측 패널 열림 true.
이미지 기본 페이지 1이며 요청 범위는 1~10000입니다. 이미지에 재생 시간, 영상/음성에 페이지를 전달하면 400입니다.
영상/음성의 기본 시작 희망 시간은 0이며 유한한 0 이상 숫자만 허용합니다.
알 수 없는 JSON 필드도 400으로 거부합니다.
요청 본문 크기/빈도 제한은 운영 ingress 설정에 함께 적용해야 합니다.

## 지연 처리와 위치 검증

생성 시 선택한 이미지 페이지 한 개만 기존 미리보기 큐에 접수합니다.
나머지 파일·페이지를 한꺼번에 변환하지 않습니다. 기존 캐시는 재사용합니다.
이미 처리 실패한 페이지는 자동 재시도하지 않습니다. 기존 POST previews?retry=true로 재시도합니다.
큐 용량 초과/저장소 오류로 접수 자체가 실패하면 새 세션을 제거하고 429/503을 반환합니다.

응답: sessionId, createdAt, expiresAt, selectedMediaId, initialPage, initialTimeSeconds,
positionState, theme, leftPanelOpen, media[].
media[]는 요청 순서를 유지하고 현재 권한·미리보기 정보를 포함합니다.
선택 이미지의 media 항목은 선택 페이지를, 나머지 항목은 첫 페이지의 상태를 나타냅니다.
각 statusUrl에는 해당 page 쿼리를 포함합니다.

positionState:
- pending: 페이지 디코딩 검증 전 또는 재생 길이 미확인.
- validated: 선택 이미지 페이지의 ready PNG가 실제 생성됨.
- failed: 선택 이미지 페이지 처리 실패.

이미지의 전체 페이지 수와 영상/음성 길이는 아직 추측해 제공하지 않습니다.
영상/음성은 FFprobe 단계 전까지 pending이며 재생 소스를 반환하지 않습니다.
세션 생성이 미확인 위치의 재생/편집을 허용하는 것은 아닙니다.
GET은 변환 작업을 시작하지 않습니다. 클라이언트는 미리보기 POST와 상태 URL을 사용합니다.

## 페이지 정보와 좌표계

GET pages/{page}는 기존 캐시 상태만 조회합니다. 없으면 not_requested이며 GET이 큐를 채우지 않습니다.
이미지 외 유형은 415입니다. 상태별 소스 반환·권한 검사·no-store 정책은 기존 미리보기 API와 같습니다.

응답: mediaId, mediaVersion, page, state, errorCode, coordinateSpaceId,
displayWidth, displayHeight, originalCoordinatesAvailable, editingReady, source.

ready일 때 displayWidth/Height는 해당 PNG의 실제 크기입니다. coordinateSpaceId는 원본 좌표가 검증되면 original:<version>:<page>:upright-v1, 미확인이면 preview:<sourceId>입니다.
준비 전에는 좌표계·크기·소스가 null입니다.
검증된 JDK 형식은 EXIF 방향 보정과 원본 좌표를 제공하며 originalCoordinatesAvailable/editingReady가 true입니다.
외부 변환기 등 원본 매핑 미확인 형식은 두 값이 false이고 표시 좌표만 제공합니다.
추가된 원본 크기·방향·변환 행렬과 좌표 정의는 [원본 좌표계](IMAGE_GEOMETRY.md)를 따릅니다.
자르기·어노테이션·스탬프 저장 API는 후속 단계입니다.
permissions는 사용자 정책, editingReady는 해당 페이지의 편집 준비 여부이므로 UI는 두 값을 함께 적용해야 합니다.

## 보존·배포와 설정

imagegate.viewer:

| 항목 | 기본 / 허용 범위 |
| --- | --- |
| session-ttl | 30m / 1m~2h |
| max-sessions | 1000 / 1~10000 |
| max-sessions-per-user | 20 / 1~100, 전체 상한 이하 |

고정 만료이며 조회로 연장되지 않습니다.
현재 저장소는 메모리 기반이며 서버 재시작 시 세션은 사라집니다.
동일 인스턴스의 동기화된 저장소에서 개수 제한·만료 정리를 처리합니다.
다중 인스턴스/공유 세션/세션 갱신은 후속 구현 대상입니다.
사용자별 상한은 정확한 조직+사용자 조합입니다. 무제한 파일 목록이나 전체 원본을 세션에 저장하지 않습니다.

세션은 생성 시 원본 버전을 고정합니다. 조회 시 버전이 바뀌면 409 SESSION_MEDIA_CHANGED로 새 세션 생성을 요구합니다.
캐시·세션에 권한 허용 결과를 장기 보관하지 않으며, 모든 파일/페이지/소스 요청에서 최신 정책을 확인합니다.
만료 세션으로 원본 경로나 우회 URL을 발급하지 않습니다.
최종 콘텐츠 API는 세션 ID와 독립적으로 파일 권한을 검사합니다.
실제 SSO/로그인 공급자는 여전히 업무시스템 연동이 필요합니다.

## 오류

SESSION_NOT_FOUND(404), SESSION_LIMIT_REACHED(429), SESSION_MEDIA_CHANGED(409),
INVALID_VIEWER_POSITION(400). 파일 관련 MEDIA_NOT_FOUND 등은 기존 미디어 오류 규칙을 적용합니다.
JSON·상태·페이지·콘텐츠 응답은 no-store이며 내부 소유자/조직/저장 경로를 반환하지 않습니다.

## 개발 검증

2026-10-21: JDK 21 전체 build 및 실제 img2tile 통합 검증을 포함한 79개 테스트 통과(실패/오류/건너뜀 0).
세션 소유자·조직·권한 회수·변경 버전·만료·상한·입력 검증·CSRF·접수 실패 정리를 검사했습니다.
실제 img2tile 워커는 3회 반복 통과했으며 과거 일시 실패의 원인이 확인되었다고 간주하지 않습니다.
설치 ZIP은 생성하지 않았습니다.
