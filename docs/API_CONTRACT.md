# Viewer API 계약 초안 v1

현재 기본 서버는 Node.js로 전환했습니다. 이 문서는 보존한 Spring 구현의 상세 계약이며 새 Node API와 모든 DTO·비동기 상태·오류가 동일하다고 가정하지 않습니다. 새 화면과 함께 검증한 엔드포인트 및 미이관 범위는 [Node/React 이관](NODE_REACT_MIGRATION.md)을 참고합니다.

URL 등록: 인증과 CSRF가 필요한 `POST /api/v1/media/import-url`에 `{"url":"http://192.168.0.86:8080/viewer/resources/TestFiles/sample.jpg"}`를 보냅니다. 선택 항목은 `fileName`이며 성공 시 기존 업로드와 같은201 미디어 응답을 반환합니다. URL 허용 목록·제한·오류와 뷰어 진입 방식은 [URL 파일 열기](REMOTE_URL_IMPORT.md)를 따릅니다.

연계 시스템이 추후 전달할 파일 권한의 의미와 인증 경계는 [LINKED_AUTHORIZATION.md](LINKED_AUTHORIZATION.md)에 정리했습니다. 이 초안의 grant 예시는 현재 뷰어/미디어 API가 수신·신뢰하는 요청 필드가 아닙니다.

미리보기의 미지원 구성·잘못된 입력·변환 실패 코드, 고정 안내 및 재시도 계약은 [PREVIEW_FAILURES.md](PREVIEW_FAILURES.md)를 참고합니다. CONVERSION_UNSUPPORTED_PROFILE은 비동기 failed 상태의 errorCode이며 새로운 HTTP 오류 응답 형식이 아닙니다.

상태: 요청 DTO·검증 로직, 파일 등록·메타데이터·이미지 미리보기 접수/상태/PNG 콘텐츠·페이지 조회·뷰어 세션 API 구현.
미디어 API는 검증된 ViewerPrincipal이 필요합니다. 실제 SSO/로그인 공급자는 아직 미연동입니다.
클릭 메모·사용자 스탬프 CRUD/업로드는 [편집 API](ANNOTATIONS_STAMPS.md)에 구현했습니다. 원본 해상도 영역 자르기·PNG 다운로드는 [자르기 API](CROP_EXPORT.md)에 구현했습니다. 편집 합성 PNG 반출도 [편집 반출 API](EDITED_EXPORT.md)에 구현했습니다. 영상·음성 준비/상태/Range 재생은 [재생 API](PLAYBACK.md)에 구현했습니다. 원본 직접 다운로드는 후속 계획입니다. 현재 구현은 MEDIA_SECURITY.md와 PREVIEW_PIPELINE.md를 참고하세요.

## 인증 경계

- 동일 출처의 서버 인증 세션을 우선안으로 설정합니다. 실제 업무시스템 인증 방식은 연동 시 확정합니다.
- 사용자·조직·역할은 검증된 Authentication에서 가져옵니다. 요청 본문·임의 헤더의 사용자/조직/권한은 신뢰하지 않습니다.
- mediaId가 존재하는지와 해당 사용자에게 접근 가능한지를 서버에서 확인합니다.
- 하나라도 권한이 없거나 찾을 수 없는 파일이 포함되면 전체 세션 생성 실패로 처리합니다.
  개별 파일 존재 여부를 노출하지 않도록 일관된 404 응답을 사용합니다.
- 모든 상태 변경 API는 CSRF 검증. 세션 생성이 파일의 접근 권한을 장기간 고정하지 않으며,
  이후 썸네일·타일·Range·메모·스탬프·저장 요청에도 최신 권한 적용.
- 같은 출처 기본, CORS 미개방. 토큰·실제 경로·원본 내용은 로그에 남기지 않습니다.

세션·페이지의 현재 구현 범위와 원본 편집 좌표의 제한은 [세션·페이지 API](VIEWER_SESSIONS.md)를 참고하세요.

## POST /api/v1/viewer/sessions

인증과 CSRF 토큰 필수. 요청은 application/json. 허용되지 않은 필드는 400으로 거부하는 것을 구현 기준으로 합니다.
최대 본문 크기와 요청 빈도는 ingress와 서버 설정에서 제한합니다.

| 필드 | 규칙 |
| --- | --- |
| mediaIds | 필수, 1~100개, 중복 금지, 순서 유지 |
| 각 ID | 영문/숫자로 시작, 영문·숫자·밑줄·하이픈만 허용, 1~128자; 경로·URL 금지 |
| initialMediaId | 선택. mediaIds에 포함. 생략하면 첫 ID |
| initialPage | 이미지에서만 허용, 1부터 시작. 생략하면 1 |
| initialTimeSeconds | 영상·음성에서만 허용, 유한한 0 이상 숫자. 생략하면 0 |
| theme | dark/light, 생략하면 dark |
| leftPanelOpen | boolean, 생략하면 true |

페이지와 시간의 유효 범위는 권한 확인 후 원본 버전의 실제 메타데이터로 검증합니다.
이미지에 시간을 전달하거나 영상·음성에 페이지를 전달하면 400.
재생 시작은 durationSeconds보다 작아야 합니다. 처리 중으로 메타데이터가 없으면 범위를 추측하지 않고
준비 완료 후 검증하며, 처리 중인 파일은 재생하지 않습니다.

최소 요청:

~~~json
{"mediaIds":["img-001","video-001","audio-001"]}
~~~

구현 응답: 201 Created, Location 헤더, 세션 ID·정렬된 파일 요약·선택 ID·보기 옵션.
세션은 요청 사용자에게 귀속되며 파일 권한을 대체하지 않습니다.
대용량 파일·모든 페이지·전체 타일을 이 응답에 넣지 않습니다.

## 조회·등록 API 구현/계획

| 메서드/경로 | 역할 |
| --- | --- |
| POST /api/v1/media/{mediaId}/playback?retry=false | 구현: 선택 영상/음성의 검증·정규화 지연 접수(CSRF 필수) |
| GET /api/v1/media/{mediaId}/playback | 구현: 상태·길이·코덱·보호된 소스 |
| GET/HEAD /api/v1/media/{mediaId}/playback/sources/{sourceId}/content | 구현: 최신 권한 검사·전체/단일 Range(200/206/416) |
| POST /api/v1/media | 구현: 제한 스트림 저장·헤더 후보 분류·이미지 첫 페이지 비동기 접수·ID/status 반환 |
| POST /api/v1/viewer/sessions | 구현: 파일 전체 권한 확인 후 세션 생성, 선택 이미지 페이지만 접수 |
| GET /api/v1/viewer/sessions/{sessionId} | 구현: 소유자·만료·전체 파일 권한·버전 확인 후 세션 조회 |
| DELETE /api/v1/viewer/sessions/{sessionId} | 구현: 소유자 세션 삭제, CSRF 필수 |
| GET /api/v1/media/{mediaId} | 구현: 원본 버전·후보 유형·첫 페이지 상태/소스·권한. 전체 페이지 수/재생시간은 후속 |
| GET /api/v1/media/{mediaId}/pages/{page} | 구현: 현재 페이지 상태·미리보기 표시 좌표·PNG 소스. 검증된 JDK 형식의 원본 좌표/방향 행렬 제공, 전용 썸네일 제공, 외부 원본 매핑은 후속 |
| POST /api/v1/media/{mediaId}/previews?page=1&retry=false | 구현: 이미지 미리보기 접수·명시적 실패 재시도(CSRF 필수) |
| GET /api/v1/media/{mediaId}/status?page=1 | 구현: 미리보기 상태. GET은 작업 접수 없음 |
| GET /api/v1/media/{mediaId}/sources/{sourceId}/content | 구현: 검증된 PNG 미리보기. 파일·사용자·버전·페이지·최신 권한 재검증. 재생 파일은 후속 |

현재 구현의 PreviewResponse와 페이지 쿼리 계약은 [미리보기 파이프라인](PREVIEW_PIPELINE.md)을 따릅니다. 아래 페이지 좌표계·재생 속성은 후속 API의 목표 계약입니다. 원본 좌표 확인 여부와 변환 행렬은 [원본 좌표계](IMAGE_GEOMETRY.md)의 현재 계약을 따릅니다.

## 공통 미디어 정보

필수: mediaId, mediaVersion, mediaType(image/video/audio), fileName, originalMimeType,
status(processing/ready/failed/unsupported), 서버 계산 permissions.
준비 완료: sources[].sourceId/delivery/role/url/mimeType.
이미지 소스: page, width, height. 이미지 페이지: coordinateSpaceId, coordinateWidth, coordinateHeight.
재생: durationSeconds, seekable; 일부 구간만 탐색 가능하면 seekableRanges.
선택: thumbnail 소스 객체, sizeBytes, posterUrl, waveformUrl, 실제 코덱/트랙 정보.
만료형 URL 사용 시 expiresAt 및 갱신 계약 필수. 기본은 인증된 동일 출처 API.

권한은 view/annotate/stamp/cropAndSave/downloadOriginal/exportEdited/print로 분리합니다.
UI 응답 권한은 안내용이며, 최종 작업마다 서버에서 다시 계산합니다.

## 응답·전송 규칙

- 원본 형식과 변환본 Content-Type을 구분합니다. 파일명으로 MIME을 확정하지 않습니다.
- ready 전에는 사용 가능한 소스만 반환. 처리 실패 시 내부 경로/명령/스택 없이 오류 코드와 안내 반환.
- JSON 및 권한 메타데이터는 Cache-Control: no-store를 기본으로 합니다.
- 미디어는 보안 정책에 맞는 private 캐시 또는 no-store. 사용자별 워터마크/권한 범위를 공유 캐시에 섞지 않습니다.
- 영상·음성 seek: HTTP Range, 206/Content-Range, 416/Content-Range 구현 및 테스트.
- 바이너리를 JSON Base64로 전달하지 않습니다. 큰 파일을 JVM 힙에 통째로 적재하지 않습니다.

## 오류 코드 계획

| HTTP | 코드 | 용도 |
| --- | --- | --- |
| 400 | INVALID_REQUEST | 구조·형식·페이지/시간 오류 |
| 401 | AUTHENTICATION_REQUIRED | 미인증 또는 세션 만료 |
| 403 | ACTION_FORBIDDEN | 확인 가능한 리소스에 대한 저장·반출 등 행위 제한, CSRF 거부 |
| 404 | MEDIA_NOT_FOUND | 미존재 또는 조회 권한 없음 |
| 409 | MEDIA_NOT_READY / VERSION_CONFLICT | 준비 전 콘텐츠 요청 또는 편집 버전 충돌 |
| 413 | FILE_TOO_LARGE | 업로드 크기 초과 |
| 415 | UNSUPPORTED_MEDIA | 지원하지 않는 실제 형식 |
| 422 | INVALID_MEDIA | 디코딩 불가·손상 파일 |
| 429 | RATE_LIMITED | 작업·요청 과다 |
| 503 | CONVERTER_UNAVAILABLE | 변환기 미설정·사용 불가 |

오류는 application/problem+json에 code와 추적용 requestId를 확장해 제공하는 것을 기준으로 합니다.

## UI 부트스트랩

GET /api/v1/viewer/bootstrap을 구현했습니다. 검증된 ViewerPrincipal과 no-store 정책을 적용하고 설치 기능·구현된 도구·CSRF 헤더/토큰·처리 상한을 반환합니다.
공개 UI 셸 /viewer에는 파일이나 인증 토큰을 포함하지 않습니다. 실제 계약은 [뷰어 UI](VIEWER_UI.md)를 참고하세요.

## 전용 썸네일 소스

GET/HEAD /api/v1/media/{mediaId}/sources/{sourceId}/thumbnail?page=1을 추가했습니다. PreviewResponse와 페이지 응답의 thumbnail, 미디어/세션 sources의 role=thumbnail로 광고합니다. ready일 때만 제공하며 정확한 URL·PNG·픽셀 상한과 최신 VIEW 권한을 검사합니다. 상세 규격은 [THUMBNAILS.md](THUMBNAILS.md)를 참고하세요.

## 주석 변경 기록

주석 문서의 history는 현재 사용자·페이지의 가능한 취소/다시 실행 수와 유형을 반환합니다. POST .../annotations/history/undo 및 /redo는 mediaVersion·coordinateSpaceId·expectedRevision만 받고 서버 기록으로 복원합니다. CSRF/16KiB/최신 행위 권한/revision 검사를 유지합니다. 상세는 [EDIT_HISTORY.md](EDIT_HISTORY.md)를 참고하세요.

원본 등록 자원 오류: POST /api/v1/media는 429 UPLOAD_BUSY(전체/사용자별 원본 등록 동시 처리 상한), 507 INSUFFICIENT_STORAGE(원본 볼륨 여유 공간 부족), 413 FILE_TOO_LARGE(설치 원본 크기 초과)를 no-store ProblemDetail로 반환합니다. 원인별 내부 경로/용량/개인 식별 정보는 응답에 포함하지 않습니다. 자동 재시도는 수행하지 않으며 사용자 재시도는 새로운 등록 요청입니다. 등록이 커밋된 뒤 후속 처리/응답이 실패하면 중복 등록 가능성이 있으므로 이를 멱등 API로 간주하지 않습니다.

CSRF/인증된 권한 거절은403/no-store와 고정 ACCESS_DENIED Problem JSON으로 직접 응답하며 원시 예외 메시지를 반환하지 않습니다. 컨테이너 /error 재디스패치에 의해403이401로 바뀌지 않도록 처리합니다. 실제 인증이 없는 요청의 인증 요구401 및 파일별 접근 숨김404는 유지합니다. bootstrap의 limits.maxUploadBytes는 설치 upload.max-file-bytes와 일치합니다.

인증/CSRF를 통과해 DispatcherServlet에서 발생한 파일/전체 요청 수신 상한 초과는413 FILE_TOO_LARGE/no-store Problem JSON으로 응답합니다. MultipartExceptionHandler는 컨트롤러 선택 전 파싱 실패를 처리하도록 MaxUploadSizeExceededException만 전역 처리하며 업무 예외의 권한 범위를 넓히지 않습니다. 공통 수신 상한은 원본/스탬프 설치 상한 중 큰 값이며 각 기능의 개별 스트림 상한은 유지합니다. Content-Length가 없는 chunked 요청에도 적용합니다. 프록시/컨테이너가 애플리케이션 이전에 거절하는 응답 형식은 별도 운영 검증 대상입니다.

Multipart 파싱 전 수신 슬롯이 포화되면429 UPLOAD_INGRESS_BUSY/no-store 고정 Problem JSON으로 거절합니다. 원본 복사 단계의429 UPLOAD_BUSY와 구분합니다. 자원 거절은 CSRF 이전이므로 포화 시 비인증/CSRF 누락 요청도429가 먼저 나올 수 있으며 이후 접수된 요청에는 기존 인증/CSRF/파일별 권한을 그대로 적용합니다. 일반 multipart가 아닌 GET/HEAD/Range는 이 슬롯을 사용하지 않습니다.

확인된 multipart 본문 읽기 유휴 만료는408 UPLOAD_READ_TIMEOUT/no-store로 응답하고 연결 종료를 알립니다. Tomcat 내부 sendError의 ERROR 재전달도 서버 내부 multipart 접수 표식·ERROR 상태408·SocketTimeoutException 원인을 모두 확인한 경우에만 고정 응답하며 업무 API나 전체 /error의 인증을 완화하지 않습니다. 클라이언트/프록시가 먼저 끊으면 응답 대신 연결 오류로 관찰될 수 있습니다. 유휴 만료는 전체 전송 시간 마감이 아니며 계속 진행되는 업로드는 총시간이 한도를 넘어도 완료될 수 있습니다.

원본 조직 할당량: imagegate.upload.tenant-original-max-bytes를 활성화하면 신뢰 tenant 내 소유자 전체의 확정 원본 바이트와 진행 중 예약을 합산합니다. 초과 등록은507 TENANT_STORAGE_QUOTA_EXCEEDED/no-store이며 실제 경로·남은 용량을 응답하지 않습니다. 기존 원본 조회/재생·인증/CSRF 계약은 유지합니다. 재시작/잔재·단일 writer 조건과 외부 MediaStore 책임은 ORIGINAL_QUOTA.md를 참고하세요.

미리보기 원본 열기 허가 대기 초과는 페이지 상태 failed/errorCode=ORIGINAL_OPEN_BUSY로 반환합니다. 기존 명시적 retry=true 접수와 파일별 권한 검증을 유지하며 자동 우회/재시도는 하지 않습니다. 설치 정책은 [ORIGINAL_OPEN_POLICY.md](ORIGINAL_OPEN_POLICY.md)를 참고하세요.

원본 열기 대기 초과/명시적 재시도·실패/워커 중단 회복 및 GET/HEAD 비공개 응답의 실제 루프백 HTTP 회귀 범위는 [ORIGINAL_OPEN_HTTP_ACCEPTANCE.md](ORIGINAL_OPEN_HTTP_ACCEPTANCE.md)를 참고하세요. 공개 취소 API나 연결 중단에 따른 백그라운드 작업 자동 취소는 추가하지 않았습니다.

대형 이미지 타일 준비/상태·PNG GET/HEAD API를 구현했습니다. 기본 비활성·승인 TIFF 프로필에 한정하며 실제 호출 계약/오류/권한·수명은 [TILE_HTTP_API.md](TILE_HTTP_API.md)를 따릅니다. 현재 UI의 고해상도 레이어는 후속이며 네이티브 DZI를 직접 제공하지 않습니다.
