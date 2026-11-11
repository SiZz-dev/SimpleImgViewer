# 메모·사용자 스탬프

## 구현 범위

원본 좌표가 검증된 이미지 페이지에서 원하는 위치를 클릭해 메모 또는 업로드한 스탬프를 생성합니다.
클릭으로 선택하고 드래그로 이동하며 선택 핸들 또는 우측 너비·높이 입력으로 크기를 조절합니다.
메모 내용 수정·삭제, 페이지별 목록, 서버 저장과 새로고침 복원을 제공합니다.
회전·확대·이동 후에도 정방향 원본 좌표를 사용합니다. 밝기는 기본 이미지의 현재 보기에만 적용합니다.

JDK 디코더로 원본 크기·방향을 검증한 JPEG/PNG/classic TIFF/GIF/BMP 페이지에서만 생성할 수 있습니다.
외부 img2tile 결과의 원본 매핑이 확인되지 않았으면 열람은 가능하지만 편집을 비활성화합니다.
원본 파일은 변경하지 않습니다. 원본 영역 자르기·PNG 저장은 CROP_EXPORT.md에 구현했습니다. 메모/스탬프·밝기·회전 합성 반출은 EDITED_EXPORT.md에 구현했습니다. 실행 취소/다시 실행은 후속 작업입니다.
현재 돋보기는 기본 이미지만 확대하며 메모·스탬프 합성 확대는 제공하지 않습니다.

## API

모든 경로는 인증된 ViewerPrincipal을 요구합니다. 변경 요청에는 실제 CSRF 헤더가 필요합니다.
권한 없는 파일은 404, 설치 정책으로 비활성화된 행위는 403입니다.
응답과 PNG 콘텐츠에는 Cache-Control: no-store를 적용합니다.

| 메서드·경로 | 동작 |
| --- | --- |
| GET /api/v1/media/{mediaId}/pages/{page}/annotations | 페이지 문서 조회 |
| POST 같은 경로 | 메모 또는 스탬프 추가, 201 |
| PUT 같은 경로/{annotationId} | 위치·크기·텍스트 변경, 200 |
| DELETE 같은 경로/{annotationId} | 항목 삭제 후 최신 문서 반환, 200 |
| GET 같은 경로/{annotationId}/stamp/content | 파일 열람 권한으로 삽입된 스탬프 PNG 조회 |
| GET /api/v1/stamps | 현재 사용자 개인 스탬프 목록 |
| POST /api/v1/media/{mediaId}/stamps | file multipart 업로드, 파일 STAMP 권한 필요, 201 |
| GET /api/v1/stamps/{stampId}/content | 현재 사용자 개인 스탬프 PNG 조회 |

페이지 미리보기를 현재 사용자 권한으로 접수하고 준비 완료한 뒤 문서를 조회합니다.
문서에는 mediaVersion, page, coordinateSpaceId, revision, items가 있습니다.
항목에는 id, type(note/stamp), x, y, width, height, text, stampId, stampUrl이 있습니다.
스탬프 소유자의 내부 식별자나 저장 경로는 응답하지 않습니다.

POST/PUT 필수 요청:

~~~json
{
  "mediaVersion": "원본 버전 UUID",
  "coordinateSpaceId": "페이지 API가 반환한 정방향 원본 좌표계 ID",
  "expectedRevision": 0,
  "type": "note",
  "x": 100,
  "y": 80,
  "width": 300,
  "height": 120,
  "text": "검토 메모"
}
~~~

스탬프는 type=stamp, stampId=현재 사용자가 업로드한 스탬프 UUID를 전달하고 text는 생략합니다.
NOTE↔STAMP 유형 변경은 금지합니다. DELETE는 mediaVersion, coordinateSpaceId, expectedRevision을 JSON으로 보냅니다.
모든 수치는 유한해야 하고 영역은 원본 페이지 안에 있어야 합니다. 너비·높이는 최소 1px입니다.
요청마다 최신 VIEW와 ANNOTATE/STAMP 권한을 검사합니다.

revision은 페이지 단위 변경 번호입니다. 원본 버전·좌표계·revision 불일치는 EDIT_CONFLICT(409)이며 덮어쓰지 않습니다.
UI는 최신 문서를 다시 가져오고 메모 초안을 유지해 사용자가 다시 저장할 수 있게 합니다.
미리보기/원본 좌표 미확인은 EDIT_NOT_READY(409), 입력 오류는 INVALID_EDIT(400)입니다.
기타 코드: EDIT_REQUEST_TOO_LARGE(413), STAMP_TOO_LARGE(413), UNSUPPORTED_STAMP(415),
INVALID_STAMP(422), EDIT_LIMIT_REACHED(429), EDIT_STORAGE_UNAVAILABLE(503).
임의 필드·경로·외부 스탬프 URL을 요청으로 받지 않습니다.

## 업로드·보안 경계

스탬프는 실제 JPEG/PNG만 JDK 디코더로 검증합니다. 파일 확장자나 클라이언트 MIME만 신뢰하지 않습니다.
최대 입력 5MiB, 각 변 2048px, 최대 4,194,304픽셀이며 설치 YAML로 더 낮출 수 있습니다.
EXIF 방향을 정규화하고 PNG로 다시 인코딩해 원본 메타데이터를 전달하지 않습니다.
SVG·HTML은 받지 않습니다. 원본 크기 PNG는 서버 내부에 최대 16MiB로 보관하며,
브라우저에는 긴 변 256px 이하의 PNG만 제공합니다. 추후 원본 해상도 반출에 내부 PNG를 사용할 수 있습니다.

개인 라이브러리는 업로더에게만 보입니다. 파일에 삽입한 스탬프는 해당 파일의 열람자에게 공개됩니다.
공유 열람자가 업로더의 개인 라이브러리에 접근할 수는 없습니다.
메모는 textContent로 렌더링하고 HTML을 실행하지 않습니다.
파일/페이지 전환·세션 만료·권한 회수 시 주석·초안·개인 라이브러리를 적절히 비웁니다.
현재 SSO 제공자는 미연동입니다. 테스트 인증을 제품에 추가하지 않았습니다.

## 저장 및 설치 옵션

imagegate.storage.annotations-directory와 stamps-directory를 추가했습니다.
기본은 storage.root 아래 annotations, stamps이며 절대 경로도 가능합니다.
원본·변환·주석·스탬프 네 경로는 서로 같거나 포함될 수 없습니다.
실제 경로의 별칭/심볼릭 링크를 거부하며 운영 계정만 파일을 변경할 수 있게 OS ACL을 설정해야 합니다.

주석은 조직/파일/원본 버전/페이지별 properties 스냅샷, 스탬프는 조직/사용자/UUID별 불변 PNG와 메타데이터로 저장합니다.
조직·사용자 디렉터리 이름은 대소문자를 구분하는 식별자의 SHA-256입니다.
원자적 교체와 revision 비교를 사용하고 빈 문서도 삭제 revision을 보존합니다.
저장소별 writer.lock으로 중복 프로세스 쓰기를 차단합니다. 현재 구성은 단일 인스턴스용입니다.
같은 저장소를 여러 서버가 동시에 쓰는 분산 운영은 지원하지 않습니다.

imagegate.editing 기본 상한:

| 설정 | 기본값 / 최대값 |
| --- | --- |
| max-annotations-per-page | 100 |
| max-note-length | 2000자 |
| max-stamps-per-user | 100 |
| max-stamp-upload-bytes | 5242880 |
| max-stamp-pixels | 4194304 |

주석 변경 JSON은 별도로 16KiB 상한을 적용합니다.
기능 사용 여부는 기존 features.image.annotations/stamps/stamp-upload와 파일 권한을 함께 적용합니다.
브라우저 bootstrap limits에도 주석 수·메모 길이·개인 스탬프 수·업로드 바이트 상한을 제공합니다.
라이브러리 영구 삭제·전체 조직 할당량·보존 기간·운영 감사 이력·분산 저장소는 후속 작업입니다.
추가 런타임 의존성이나 외부 변환기를 사용하지 않았습니다.

## 검증

EditingApiTests: CRUD·원본 보존·버전/좌표/동시 변경 충돌·입력 제한·권한/조직 격리·CSRF·공유 스탬프와 개인 라이브러리 분리.
AnnotationStoreTests: 재시작 복원·삭제 revision 보존·동시 쓰기 충돌·페이지/조직/버전 격리·손상 데이터·단일 writer.
StampSanitizerTests: 투명도·원본 크기 보존·256px 미리보기·픽셀 상한의 디코딩 전 차단.
브라우저 시나리오: 클릭 배치·회전 좌표·이동·크기·새로고침 복원·페이지 분리·충돌 초안·XSS·삭제·읽기 전용·권한 회수.
브라우저는 실제 UI와 합성 API로 검사하며 실제 SSO 종단간 시험을 대신하지 않습니다.
