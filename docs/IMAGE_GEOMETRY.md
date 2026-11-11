# 원본 좌표계·EXIF 방향 보정

## 현재 구현

JDK 21 표준 ImageIO의 JPEG/PNG/classic TIFF는 방향 메타데이터를 제한적으로 읽고,
회전·반전 1~8을 적용한 정방향 PNG 미리보기를 만듭니다.
GIF/BMP의 디코딩된 래스터는 방향 1로 취급합니다.
원본 파일은 수정하지 않습니다. 새 PNG에는 원본 EXIF·GPS·썸네일을 복사하지 않습니다.
회전을 위해 원본 크기의 추가 비트맵을 만들지 않고, 미리보기 크기의 출력에 방향 변환과 축소를 함께 적용합니다.

근거 규격: [CIPA Exif 규격 목록](https://www.cipa.jp/e/std/std-sec.html),
[W3C PNG eXIf 규격](https://www.w3.org/TR/png-3/#eXIf).
PNG eXIf는 JPEG APP1의 Exif 접두어 없이 TIFF 헤더부터 읽으며, 청크의 CRC를 확인합니다.

JPEG APP1 Exif의 IFD0, PNG eXIf의 IFD0, TIFF의 요청 페이지 IFD에서 Orientation(274)을 읽습니다.
방향 태그가 없으면 해당 지원 형식은 1로 간주합니다.
중복 EXIF/방향 태그, 잘못된 방향·타입·개수, 깨진 범위나 CRC는 검증 실패로 처리합니다.
Exif 하위 IFD·썸네일·GPS·XMP·XML을 탐색하지 않습니다. XMP 방향을 별도로 합성하지 않습니다.
BigTIFF·알 수 없는 형식/디코더의 방향은 확인된 것으로 표시하지 않습니다.

외부 img2tile의 원본 크기·방향 보정/미보정 규약은 아직 검증되지 않았습니다.
외부 결과에 방향을 한 번 더 적용하거나 출력 크기로 원본 크기를 추측하지 않습니다.
그 결과는 표시용 ready PNG가 될 수 있지만 원본 좌표와 editingReady는 제공하지 않습니다.
HEIF/JPEG2000 등의 원본 좌표는 어댑터가 검증된 메타데이터·변환 규약을 제공할 때 추가합니다.

## 좌표 정의

- encodedWidth/encodedHeight: 파일에서 디코딩한 원본 래스터의 크기.
- exifOrientation: 검증한 방향 값 1~8.
- coordinateWidth/coordinateHeight: 방향을 보정한 원본 래스터의 가로·세로.
  5~8은 encodedHeight/encodedWidth 순서가 됩니다.
- displayWidth/displayHeight: 브라우저에 전달하는 PNG 미리보기 크기.
- coordinateSpaceId: original:<mediaVersion>:<page>:upright-v1.
  원본 버전·페이지에 귀속되며 미리보기 크기·사용자·캐시 키에 따라 바뀌지 않습니다.

좌상단 (0,0), 오른쪽 아래 가장자리 (coordinateWidth,coordinateHeight)의 픽셀 경계 좌표를 사용합니다.
정수 픽셀 인덱스와 구분합니다. 픽셀 중심은 (x+0.5,y+0.5)입니다.
범위 검증·자르기·저장 API는 향후 이 계약을 사용합니다.

페이지 응답의 행렬은 {a,b,c,d,e,f}이며 다음 식을 적용합니다.

~~~text
x' = a*x + c*y + e
y' = b*x + d*y + f
~~~

previewToOriginal: PNG 내부 픽셀 경계 → 방향 보정 원본 좌표.
originalToEncoded: 방향 보정 원본 좌표 → 파일에 저장된 원본 래스터 좌표.
출력 크기 반올림으로 가로·세로 비율이 약간 달라질 수 있으므로 두 축의 비율을 각각 사용합니다.
CSS 확대/이동/사용자 회전은 포함하지 않습니다. 화면 클릭 좌표에는 먼저 뷰포트 변환의 역변환을 적용해야 합니다.

예: encoded=4000x3000, 방향 6이면 coordinate=3000x4000.
미리보기 1200x1600의 (600,800)은 원본 정방향 (1500,2000)에 대응합니다.
originalToEncoded는 이 점을 원본 래스터 (2000,1500)으로 대응시킵니다.

원본 방향 매핑을 확인할 수 없으면:
- originalCoordinatesAvailable=false, editingReady=false.
- encodedWidth/Height, exifOrientation, 두 원본 변환 행렬은 null.
- 좌표계 ID는 preview:<sourceId>, coordinateWidth/Height는 미리보기 크기입니다.

준비 전에는 좌표계·크기·소스·행렬이 null입니다.
editingReady=true는 원본 편집 좌표가 준비되었다는 뜻이며, 저장 API 구현이나 사용자 편집 권한을 뜻하지 않습니다.
UI는 사용자 permissions, 설치 FeaturePolicy와 함께 검사해야 합니다.
편집·메모·스탬프 저장·자르기/합성 엔드포인트는 후속 단계입니다.
GIF는 현재 선택 프레임의 정지 래스터 좌표이며 애니메이션 합성/반출 계약이 아닙니다.

## 보안·성능 상한

파일은 권한을 확인한 비공개 원본 경로에서 읽고 기존 SHA-256 무결성 검사를 통과해야 합니다.
원본 픽셀 상한을 확인한 뒤 디코딩하며 기존 미리보기 큐/파일 크기/출력 크기 상한을 유지합니다.

추가 메타데이터 처리 상한:
- JPEG: 헤더 탐색 16MiB, 마커 4096개, 연속 채움 바이트 16개.
- PNG: 청크 4096개, eXIf 데이터 1MiB. 다른 큰 청크는 할당 없이 범위 확인 후 건너뜁니다.
- classic TIFF: IFD 당 4096개, 요청 페이지까지 총 262144개 항목, 페이지 1~10000.
- IFD 오프셋은 파일 범위를 검사하고 방문한 IFD의 순환을 거부합니다.

이 상한은 새로운 방향 해석기의 상한입니다. JDK 디코더 전체의 OS 자원 격리·강제 제한 시간을 보장하지 않습니다.
원본 픽셀 제한의 외부 모듈 적용 한계와 캐시 용량 정책 잔여는 PREVIEW_PIPELINE.md를 따릅니다.

## 캐시와 배포

검증된 원본 크기·방향을 PNG와 같은 ready 상태 레코드에 원자적으로 게시하고 재기동 후 복원합니다.
잘못된 캐시 방향·크기는 신뢰하지 않습니다.
출력 알고리즘 키를 preview-png-orientation-v2로 변경하여 이전 미보정 캐시와 섞이지 않도록 했습니다.
이전 파일의 GET은 새 키에 결과가 없으면 not_requested가 됩니다.
POST previews 또는 세션 생성으로 필요한 페이지만 다시 접수합니다.
원본의 version/page 기반 좌표계 ID는 새 미리보기 크기에도 유지됩니다.

## 검증 범위

6색 비대칭 래스터로 JPEG/PNG/TIFF 각각의 8방향 픽셀 배치와 축소 결과를 확인합니다.
TIFF의 페이지별 방향, 행렬 역변환의 모서리·소수 좌표, 방향 손상/중복·IFD 순환·범위·CRC 거부,
캐시 재기동과 좌표 메타데이터 손상, HTTP 페이지 응답·권한 검사도 검증합니다.
실제 img2tile 결과가 미확인 원본 좌표를 주장하지 않는지 기존 통합 테스트에서 확인합니다.
설치 ZIP은 생성하지 않습니다.

EXIF 정규화는 표시 방향을 맞추는 서버 처리이며 features.image.rotation의 사용자 회전 도구 사용 여부와 별개입니다.
img2tile 연동은 Java ProcessBuilder에서 입력·출력·페이지·크기를 명령행 인자로 전달하는 기존 방식을 유지합니다. 원본 좌표 확인에는 별도로 검증된 결과 정보가 필요합니다.

최종 개발 검증: JDK 21 build 및 실제 img2tile 통합 테스트를 포함한 118개 테스트 통과(실패/오류/건너뜀 0). 설치 ZIP 미생성.

## 타일 출력의 제한된 관측

2026-10-27 별도 native 타일 모드의 합성 RGB TIFF 방향1/6에서 입력 페이지의 encoded raster 픽셀과 전체 해상도 타일이 일치함을 확인했습니다. 방향6도 upright 크기로 회전하지 않았습니다. 이 결과로 기존 축소 미리보기의 geometry=null 정책이나 HEIC/JP2 등의 원본 좌표 신뢰를 바꾸지 않습니다. 타일별 raw-raster 경계와 기존 upright 편집 좌표를 분리한 계약은 [TILE_PYRAMID_CONTRACT.md](TILE_PYRAMID_CONTRACT.md)를 참고하세요.
