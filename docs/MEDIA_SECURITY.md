# 미디어 등록·조회 및 접근 통제

## 구현 API

| API | 동작 |
| --- | --- |
| POST /api/v1/media | multipart file 스트림을 제한된 원본 저장소에 등록, 201 + Location 반환 |
| GET /api/v1/media/{mediaId} | 최신 접근 정책 확인 후 메타데이터 반환 |
| POST /api/v1/media/{mediaId}/previews | 이미지 페이지 변환 접수·실패 재시도 |
| GET /api/v1/media/{mediaId}/status | 현재 사용자 범위의 미리보기 상태 |
| GET /api/v1/media/{mediaId}/sources/{sourceId}/content | 검증된 PNG 미리보기만 제공 |

모든 미디어 API는 인증된 ViewerPrincipal이 필요하며 POST는 CSRF 검증도 필요합니다.
등록된 이미지는 첫 페이지를 제한된 큐에 접수하고, 디코딩 검증이 끝난 PNG만 ready 소스로 반환합니다.
원본 직접 다운로드와 실제 업무 공유 ACL 관리는 미구현입니다. 재생 API/Range는 후속 구현을 완료했으며 [재생 계약](PLAYBACK.md), 타일 API는 [타일 계약](TILE_HTTP_API.md)을 참고하세요.
뷰어 세션과 페이지 정보 GET은 [세션·페이지 API](VIEWER_SESSIONS.md)를 참고하세요. 세션 생성·조회에서도 모든 파일의 최신 권한을 검사합니다.
상태·중복 방지·캐시·명시적 재시도는 [미리보기 파이프라인](PREVIEW_PIPELINE.md)을 참고하세요.

## 인증 연동 경계

2026-10-29 확인: 로그인 방식은 미정이며 권한은 추후 연계 시스템에서 전달받습니다. 전달 필드·신뢰 경계·서버 SPI와 합성 계약 검증 범위는 [LINKED_AUTHORIZATION.md](LINKED_AUTHORIZATION.md)를 따릅니다. 실제 수신/서명 검증·SSO 연결은 아직 구현하지 않았습니다.

- 아직 실제 로그인/SSO/JWT 검증 공급자는 연결하지 않았습니다.
- 외부 업무시스템은 검증된 사용자와 조직 정보로 security.domain.ViewerPrincipal을 만들고,
  신뢰 가능한 AuthenticationProvider/필터를 통해 SecurityContext에 인증 객체를 설정해야 합니다.
- 브라우저가 보낸 사용자 ID·조직 ID·권한·임의 헤더를 인증 객체로 전환하면 안 됩니다.
- 임의 X-Tenant-Id/X-User-Id 헤더, 일반 문자열 principal, 비인증 principal은 사용하지 않습니다.
- 개발용 로그인·기본 계정·비밀번호·헤더 인증 우회는 추가하지 않았습니다.
- 테스트의 authentication(...)은 Spring Security 테스트 전용이며 운영 기능이 아닙니다.
- UserDetailsService의 기본 계정 생성은 차단하며, 실제 공급자는 후속 통합 시 구성합니다.

## 파일 권한

- 기본 정책: 동일 tenantId + 원본 ownerId 사용자만 접근.
- 미존재 파일과 타인/타 조직 파일 모두 MEDIA_NOT_FOUND(404)로 반환.
- MediaAuthorizationPolicy SPI를 실제 업무 건 ACL 정책으로 교체할 수 있습니다.
- 각 조회마다 저장소·정책을 다시 검사. 이전 요청의 허용 결과를 캐시하지 않습니다.
- 설치 FeaturePolicy도 함께 적용. 꺼진 미디어 종류는 등록과 조회를 거부합니다.
- 응답 permissions는 사용자의 권한과 설치 정책의 교집합이며 UI 안내용입니다.
  구현된 편집·반출 API도 매번 권한·유형·준비 상태·버전을 검증합니다.
- 영상/음성에는 이미지 전용 어노테이션·스탬프·자르기·편집 반출 권한을 표시하지 않습니다.
- 메타데이터와 오류 응답은 Cache-Control: no-store. 저장 경로·조직/사용자 ID·원시 명령을 반환하지 않습니다.

## 원본 저장소

~~~text
originals-directory/
  tenant-<SHA256 of tenantId>/
    <server-generated media UUID>/
      original.<detected canonical extension>
      metadata.properties
~~~

조직 디렉터리는 tenantId의 해시를 사용해 Windows 대소문자 경로 충돌을 피합니다.
원본 파일명·mediaId·버전·내용 SHA-256·크기·유형·소유자·생성 시각은 비공개 카탈로그에 기록합니다.
metadata.properties는 시스템 설치 설정이 아니라 서버 전용 저장 데이터이며 직접 수정하지 않습니다.
MediaStore SPI를 DB/오브젝트 스토리지 기반 구현으로 교체할 수 있습니다.

- 저장 파일명과 UUID는 서버 생성. 업로드 파일명의 경로 부분은 제거하고 제어문자·길이를 검사.
- 전달된 Content-Type이나 확장자 대신 제한된 헤더를 읽어 후보 형식 분류.
- 입력 스트림을 32KiB 버퍼로 복사하며 100MiB 상한 적용. 파일 전체를 JVM 메모리에 올리지 않음.
- 임시 작업 디렉터리에 기록한 뒤 동일 파일시스템의 atomic directory move로 게시.
  원자적 이동을 지원하지 않는 저장소는 자동으로 약한 이동 방식으로 우회하지 않음.
- 실패한 등록은 임시 파일을 정리. 미완성 파일은 카탈로그 조회 대상이 되지 않음.
- 파일 경로 조작·심볼릭 링크/별칭·카탈로그 크기/파일명/ID 불일치 등을 검사.
- 저장소는 서비스 계정만 수정 가능해야 함. 이 검사가 악성 OS 사용자와의 파일 교체 경합을 완전히 막지는 않음.

## 지원 분류와 후속 검사

현재 헤더 후보 분류: PNG/JPEG/GIF/BMP/WebP/TIFF/HEIF/JPEG2000, MP4/MPEG, MP3/WAV.
각 후보의 실제 디코더/외부 모듈·편집/타일·재생 경계와 실패 계약은 [형식별 지원표](FORMAT_SUPPORT.md)를 참고하세요.
이는 전체 포맷·코덱·페이지·프레임·악성 여부 검증이 아닙니다.
검증/변환 워커가 실제 디코더로 확인하기 전에는 ready로 표시하거나 콘텐츠를 제공하지 않습니다.
HEIF/MP4 브랜드와 MP3 변형 등은 실제 파일 지원표를 추가해야 합니다.
영상/음성 HTTP Range와 승인 TIFF 이미지 타일은 구현했습니다. 음성 파형은 미구현이며 실제 파일별 지원과 업무 인증/ACL·운영 검증은 [운영 인수 계획](OPERATIONAL_ACCEPTANCE.md)에서 구분합니다.

## 운영 연결 전 남은 항목

- 실제 업무시스템 인증·세션/SSO 통합, 공유 파일의 업무 건 ACL, 로그아웃/권한 회수 연동.
- 사용자·조직별 저장 용량/등록 빈도·동시 업로드 제한, 악성 파일 검사와 요청/임시 디스크 제한.
- 중단 작업의 자동 재개/분산 큐, 보존 기간·용량 정리, 백업·장애 복구, 필요 시 DB 카탈로그.
- 검증 워커의 OS 메모리·CPU·네트워크 격리, JDK 디코더의 강제 제한 시간, 실제 샘플 검증.
- 운영 파일 접근 감사 이력·관측·보안 점검.

기본 /viewer 셸과 정확한 정적 CSS/JS GET만 공개했습니다. 파일 데이터와 CSRF 부트스트랩은 인증된 ViewerPrincipal이 필요합니다.
실제 로그인 공급자는 미연동 상태이며 UI를 열 수 있다는 사실이 파일 접근 권한을 의미하지 않습니다. 상세는 VIEWER_UI.md를 참고하세요.
