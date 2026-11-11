# 형식별 실제 지원 범위

2026-10-29. 확장자를 지원한다는 표현 대신 등록 후보 분류·미리보기·원본 좌표 편집·타일·재생을 구분합니다. 아래는 현재 코드 경로와 합성 회귀 기준이며 모든 업무 파일 변형을 인수한 목록은 아닙니다. 실제 파일별 인수는 [운영 인수 A03](OPERATIONAL_ACCEPTANCE.md)을 따릅니다.

## 이미지 지원표

| 입력 | 등록 헤더 후보 | 미리보기 경로 | 원본 좌표 편집 | 고해상도 타일 | 아직 보장하지 않는 범위 |
| --- | --- | --- | --- | --- | --- |
| PNG | PNG signature | JDK ImageIO→새 PNG | 검증된 방향/기하일 때 가능 | 없음 | APNG 전체 애니메이션·모든 색상/비트 깊이·ICC 표시 일치 |
| JPEG/JPG | FF D8 FF | JDK ImageIO→새 PNG | 검증된 EXIF 방향1~8/기하일 때 가능 | 없음 | 모든 CMYK/ICC 변형·손상 복구 |
| GIF | GIF87a/89a | JDK ImageIO의 요청 이미지→새 PNG | 디코딩된 래스터의 방향1 | 없음 | 원본 애니메이션 재생·프레임 타이밍/합성 인수 |
| BMP | BM | JDK ImageIO→새 PNG | 디코딩된 래스터의 방향1 | 없음 | 모든 압축·색상 변형 |
| TIFF/TIF | classic/BigTIFF 후보 | 활성 img2tile이 해당 MIME을 선택하면 외부 변환, 그 외 JDK reader가 지원하는 입력 | JDK 경로의 검증된 방향/기하. 외부 미리보기는 원칙적으로 기하 미확인; 승인된 타일 매니페스트와 비율 대조에 성공한 경우만 서버 기하 연결 | classic TIFF RGB8/3samples/방향1/비압축/contiguous 한정, 별도 활성화 필요 | BigTIFF·압축/색공간/방향 변형 전체 및 승인 범위 밖 타일 |
| HEIC/HEIF | ftyp major brand heic/heix/hevc/hevx/mif1/msf1 | 활성 img2tile 어댑터가 선택되면 외부 PNG를 다시 JDK 검증/재인코딩 | 미확인 좌표 편집 차단 | 없음 | 실제 업무 샘플·브랜드/컨테이너 변형·방향/색상 규약 인수 |
| JPEG2000 JP2 | JP2 signature | 동일 img2tile. 사전 허용 프로필은3성분 unsigned8/16비트 sRGB·비서브샘플·원점0·40MP 이하. RGB 전체/절반 축소 합성 검증 통과 | 미확인 좌표 편집 차단 | 없음 | 회색은 모듈 표본 오류로 차단. ICC/팔레트/알파/가변 정밀도·다른 색공간·미확인 확장도 거절. 업무 샘플/방향·좌표 인수 잔여 |
| JPEG2000 원시 J2K/J2C | FF4FFF51 codestream 후보 | image/j2c로 등록되지만 현재 img2tile은 유효 합성 표본도 native exit1로 거절 | 차단 | 없음 | 확장자를 j2c/jpc/jp2로 바꿔도 같은 거절. 실제 지원으로 분류하지 않음 |
| WebP | RIFF/WEBP | 현재 기본 JDK21 배포에는 디코더를 추가하지 않음 | 없음 | 없음 | 헤더 등록 가능이 실제 열람 지원을 뜻하지 않음 |
| SVG/AVIF/PDF/기타 | 현재 후보 목록 밖 | 제공하지 않음 | 없음 | 없음 | 임의 MIME·파일명만으로 형식을 추가하지 않음 |

JDK 경로도 픽셀·파일·출력 예산과 디코더 검증을 통과해야 합니다. 성공한 미리보기는 새 RGBA PNG로 만들며 원본 메타데이터를 복사하지 않습니다. 입력 후보 분류는 MediaHeaderDetector, 선택/디코딩은 ImagePreviewRenderer, 외부 지원 MIME 선언은 Img2TileConverter가 담당합니다. ftyp compatible brand 전체 탐색이나 모든 확장자 변형 인식을 구현한 것은 아닙니다.

외부 모듈 선택은 MIME 기준입니다. 활성 img2tile이 TIFF를 선택했다가 실패하면 JDK로 자동 우회하지 않습니다. 모듈이 비활성인 경우에 처음부터 JDK TIFF 경로를 선택하는 것과 실패 후 우회는 구분합니다. ImageMagick/libvips를 실행하는 구현이나 새 디코더 의존성은 추가하지 않았습니다.

후속 [JPEG2000 실변환 검증](JPEG2000_NATIVE_COMPATIBILITY.md)에서 JP2 성공과 원시 J2K 미지원 차이를 확인했습니다. HEIC 합성 표본 생성 경로의 현재 제약도 같은 문서에 기록했으며 HEIC 유효 파일 시험을 완료로 표시하지 않습니다.
회색 JP2는 native가 성공을 반환해도 원시 표본이 잘못되는 것을 확인하여 사전 프로필 검사에서 차단합니다. 현재 미승인 구성은 CONVERSION_UNSUPPORTED_PROFILE, 허용 구성의 구조 불일치는 CONVERSION_INVALID_INPUT으로 구분하며 캐시 정책은 jp2-rgb-v2입니다. 고정 안내·버튼과 API 계약은 [실패 안내](PREVIEW_FAILURES.md)를 따릅니다.16비트 입력의 열람 성공을16비트 정밀도의 출력 보존으로 해석하지 않습니다.

원본 좌표가 확인돼도 설치 기능·현재 사용자 권한·페이지/버전·편집 예산을 모두 충족해야 crop/주석/스탬프/반출을 사용할 수 있습니다. 타일을 지원하지 않는 입력도 예산 안에서 미리보기로 열람할 수 있으나, 원본 해상도 확대 품질은 별개입니다. 상세: [기하](IMAGE_GEOMETRY.md), [타일 UI와 좌표 연결](TILE_VIEWER_UI.md), [타일 승인 프로필](TILE_QUEUE.md).

## 영상·음성 지원표

| 입력 | 등록 후보 | 재생 경로 | 현재 제한 |
| --- | --- | --- | --- |
| MP4 | 지정 ftyp major brand | FFprobe/FFmpeg mov demuxer→WebM(VP8, 음성이 있으면 Opus) | 컨테이너 내부 실제 코덱과 설치된 승인 빌드의 디코딩 성공이 필요 |
| MPEG/MPG | MPEG PS/ES signature 또는188바이트 간격 TS sync 후보 | FFprobe/FFmpeg mpeg/mpegvideo/mpegts→WebM | 파일 확장자만으로 PS/ES/TS 전체 코덱 지원을 확정하지 않음 |
| MP3 | ID3 또는 MPEG audio frame 후보 | FFprobe/FFmpeg mp3→MP3 열람본 | ID3만 있는 손상 파일이나 모든 frame 변형이 지원된다는 뜻 아님 |
| WAV | RIFF/WAVE | FFprobe/FFmpeg wav→MP3 열람본 | WAV 내부 PCM/압축 코덱·채널/샘플율 예산을 실제 확인 |

재생본의 Range/HEAD와 seek bar를 제공합니다. 첫 일반 영상/첫 오디오 트랙을 선택하며, 복수 카메라/음성 선택·제조사 전용 암호화/GPS·자막은 구현 범위 밖입니다. 표지 영상은 주 영상으로 선택하지 않습니다. 최대 길이·해상도·스트림/채널/샘플율 및 출력/시간 예산, 설치 활성화와 승인된 모듈 조건은 [재생 계약](PLAYBACK.md)을 따릅니다. 원본은 보존하고 재생본은 손실 변환된 열람용 사본으로 취급합니다.

## 미지원·손상 입력의 공개 실패 계약

| 상황 | 결과 | 노출/재시도 정책 |
| --- | --- | --- |
| 헤더 후보 없음 | 등록 API가 UNSUPPORTED_MEDIA로 거절 | 이름/Content-Type으로 우회하지 않음 |
| 등록 후보지만 설정된 JDK 디코더 없음 | 비동기 failed / DECODER_UNAVAILABLE | source/thumbnail 없음, editingReady=false, 원본 좌표 없음. 콘텐츠 제공 거절 |
| 디코더가 선택됐지만 손상/픽셀 예산 초과 등으로 실패 | 비동기 failed / PREVIEW_FAILED 등 고정 분류 | 원시 예외/경로/명령을 응답하지 않음. 정상 파일의 성공과 구분 |
| 선택한 외부 변환 실패 | failed / CONVERSION_고정코드 | JDK/다른 외부 모듈로 자동 대체하지 않음 |
| 실패 상태 조회·retry 없는 접수 | 기존 실패 반환 | 조회/폴링으로 새 변환을 시작하지 않음. 명시적 재시도도 현재 권한·기능·용량 검사 필요 |

헤더만 있는 합성 서명은 실패 경계를 검증하기 위한 데이터입니다. HEIC/JP2/WebP의 유효한 파일 호환성이나 악성 파일 전체 방어를 입증하는 표본으로 집계하지 않습니다.

## 회귀 검증과 재현

새 ImageFormatCompatibilityTests는 실제 JDK writer로 만든 PNG/JPEG/GIF/BMP/TIFF를 등록→실제 renderer→queue/cache 게시→PNG 재조회까지 통과시켜 크기·디코딩 픽셀·기하·원본 바이트 보존을 대조합니다. JPEG는 손실 인코딩 뒤의 입력 디코딩 픽셀과 비교하며, 원래 생성 래스터와의 무손실 일치를 주장하지 않습니다. JDK로 디코딩 가능한 정상 TIFF라도 선택한 외부 모듈의 실패를 고정 코드로 유지하고 게시하지 않는 경계도 검사합니다.

MediaPreviewApiTests의 새4개 경우는 외부 변환 비활성 상태에서 WebP/HEIF/JP2/J2K 합성 서명을 등록하고 실패 코드·source/thumbnail 비노출·페이지 편집 차단·콘텐츠 거절·retry 없는 접수의 실패 유지를 검증합니다. 기존 테스트는 손상 PNG·픽셀 초과·TIFF 다중 페이지·권한/CSRF를 별도로 검사합니다.

```powershell
.\gradlew.bat test --tests '*ImageFormatCompatibilityTests' --tests '*MediaPreviewApiTests' -Pimg2tileIntegration
```

이 집중 실행은 실제 img2tile 호환성 시험을 선택하는 명령은 아닙니다. 실제 모듈 회귀는 전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration에서 실행합니다. 기존 Img2TileIntegrationTests/FfmpegIntegrationTests와 방향/타일/편집 테스트 결과는 각 기능 문서와 함께 해석합니다. 실제 업무 샘플·모든 브라우저·SSO·운영 부하는 별도 인수 대상이며 설치 ZIP은 생성하지 않습니다.

## 이번 검증 결과

2026-10-29 집중19개가53초에 통과했고, 최종 전체 build는5분58초에 성공했습니다. Java760개/110 suites에서 실패·오류·건너뜀은0개입니다. 신규10개(실제 JDK 이미지5종·선택 native 실패1개·HTTP 미지원4개)를 포함하며 실제 img2tile/FFmpeg/native 브라우저 회귀도 통과했습니다. 일반 UI34개 시나리오는 이번 변경에서 별도 재실행하지 않았습니다.

라이선스16개 규칙/63개 식별 구성요소/53개 포함 JAR 대조, 관련 로컬 문서 링크72개와 실행 JAR의 테스트·기준 구현·fixture 포함0개를 확인했습니다. build/reports/format-compatibility/verification.json에 집계했습니다. 운영 코드·외부 모듈·실제 설치 YAML·업무 자료는 변경하지 않았으며 추가 디코더/외부 라이브러리·설치 ZIP은 없습니다. distributionReady=false를 유지합니다.
