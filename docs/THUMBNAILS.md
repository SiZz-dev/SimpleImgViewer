# 전용 이미지 썸네일

## 처리와 설치 옵션

이미지 변환 워커가 방향 보정·크기 제한을 완료한 미리보기 래스터에서 작은 PNG를 추가 생성합니다.
원본을 썸네일용으로 다시 디코딩하거나 별도 외부 프로그램을 한 번 더 실행하지 않습니다.
본 미리보기와 함께 게시하므로 최초 준비 과정에 작은 PNG 인코딩 비용이 추가됩니다.

~~~yaml
imagegate:
  preview:
    thumbnail-enabled: true
    thumbnail-max-edge: 256
~~~

config/imagegate.example.yml 및 기본 application.yml에 반영했습니다.
최대 변 길이는 64~512, 기본 256입니다. 비율·알파 채널·미리보기의 정방향을 유지하고 확대하지 않습니다.
원본/미리보기가 작으면 더 작은 썸네일을 제공합니다. 썸네일 PNG 상한은 2MiB입니다.
false이면 썸네일을 생성·광고하지 않으며 UI는 파일 아이콘을 표시합니다.
영상/음성 포스터·파형 생성은 이번 이미지 썸네일 범위에 포함하지 않습니다.

썸네일 정책과 v3-thumbnail 출력 알고리즘 식별자를 캐시 키에 포함했습니다.
기존 v2 캐시는 새 처리 결과로 재사용하지 않습니다. 다음 명시적 준비/파일 선택 시 생성하며 GET 조회는 변환을 접수하지 않습니다.
이전 버전 디스크 항목은 기존 preview.cache 보관/용량 정책에 따라 정리됩니다.

## API

완료된 PreviewResponse에는 기존 source와 별도로 thumbnail이 있습니다.
페이지 응답에도 thumbnail을 추가하고, 미디어·세션의 sources에는 role=preview와 role=thumbnail을 구분합니다.
기존 본문 source와 sources[0] 의미는 유지합니다. 클라이언트는 role과 page를 기준으로 선택해야 합니다.

~~~json
{
  "sourceId": "정확한 64자리 소문자 SHA-256 키",
  "role": "thumbnail",
  "page": 1,
  "mimeType": "image/png",
  "width": 256,
  "height": 160,
  "url": "/api/v1/media/{mediaId}/sources/{sourceId}/thumbnail?page=1"
}
~~~

GET/HEAD /api/v1/media/{mediaId}/sources/{sourceId}/thumbnail?page=1로 제공합니다.
현재 조직/사용자/파일 VIEW·버전·설치 정책을 검사하며 sourceId 자체는 권한이 아닙니다.
익명은 401, 다른 사용자/조직/파일/페이지·미준비·비활성 썸네일은 404입니다.
PNG, Content-Length, no-store, nosniff를 적용하고 HEAD는 본문을 보내지 않습니다.
GET은 검증된 캐시만 읽고 디코딩/변환을 시작하지 않습니다. 원본/다른 소스로 자동 우회하지 않습니다.
썸네일은 편집·자르기 좌표나 원본 크기 판단에 사용하지 않습니다.

## 저장과 동시성

같은 비공개 캐시 키 디렉터리에 미리보기/썸네일을 서로 다른 불변 UUID PNG로 두고
두 파일을 검증·이동한 뒤 하나의 상태 파일을 원자적으로 게시합니다.
상태에는 썸네일 이름·픽셀 크기·바이트·수정 시각을 저장하고 API에는 경로를 노출하지 않습니다.
선언된 썸네일이 누락·변경되면 CACHE_INVALID로 처리해 ready를 광고하지 않습니다.
중단/실패 작업의 임시·알 수 없는 파일은 기존 보수적 운영 정리 지침을 따릅니다.

변환 접수와 PNG/썸네일 GET·HEAD는 같은 캐시 수명(lease)을 공유합니다.
사용 중이면 어느 쪽도 자동 정리하지 않습니다. preview.cache.max-bytes에 두 PNG의 바이트를 합산합니다.
퇴거 시 두 파일·상태·빈 디렉터리만 함께 제거하며 원본/주석/스탬프는 유지합니다.
정리 목표는 전체 디스크의 하드 할당량이 아닙니다. 상세 내용은 CACHE_MAINTENANCE.md를 참고하세요.

## 좌측 목록

목록은 page=1, role=thumbnail, PNG, 최대 512px 및 정확히 일치하는 같은 출처 API URL을 확인합니다.
준비된 전용 썸네일만 IntersectionObserver와 lazy/low priority로 지연 표시합니다.
화면 밖 항목의 src를 제거하고 파일 전환/권한 상실 시 기존 목록 정리 흐름을 유지합니다.
썸네일이 없거나 잘못된 URL이면 파일 아이콘을 표시합니다. 본문 미리보기를 대신 다운로드하지 않습니다.
목록 노출만으로 모든 이미지의 변환을 일괄 접수하지 않습니다.

## 검증

JDK 래스터 크기/비율/업스케일 방지·알파·설정 사용 여부/크기·정책별 캐시 분리,
실제 API의 전송량/PNG·HEAD·조직/사용자/페이지·권한 회수 차단,
두 파일의 할당량 합산·사용 중 보호·짝 퇴거/재생성·누락 파일 차단을 확인합니다.
Chrome은 30개 목록의 전용 256px 로드·화면 밖 해제·목록용 본문 다운로드 없음,
누락/외부 썸네일의 안전한 자리표시를 기존 편집/재생 시나리오와 함께 검사합니다.
성능 작업은 warm-thumbnail을 추가해 동일 샘플의 본문/썸네일 바이트와 요청 시간을 비교합니다.
MockMvc 전송 시간의 한계는 PERFORMANCE.md를 따릅니다.
설치 ZIP은 최종 기능 구현과 전체 배포 검증 이후에만 생성합니다.

최신 검증: JDK 21 전체 build -Pimg2tileIntegration -PffmpegIntegration 성공, Java 215개 통과. Chrome 16개 시나리오 및 별도 실제 img2tile 성능 시나리오(30단계/663표본) 통과. 전송량 비교는 [THUMBNAIL_PERFORMANCE.md](THUMBNAIL_PERFORMANCE.md)를 참고하세요. 설치 ZIP 미생성.
