# JPEG2000 실제 모듈 호환성 검증

입력·기대/실제 출력과 module fingerprint/해시를 남기는 선택 재현 도구는 [NATIVE_MODULE_HANDOFF.md](NATIVE_MODULE_HANDOFF.md)를 참고합니다.

현재 미지원/잘못된 입력 코드 구분과 캐시 정책 jp2-rgb-v2는 [PREVIEW_FAILURES.md](PREVIEW_FAILURES.md)를 우선합니다. 아래 v1 코드·캐시 및 검증 집계는 해당 단계의 기록으로 보존합니다.

2026-10-29. 업무 파일이나 다운로드한 미디어 대신 합성 RGB8 래스터를 설치된 FFmpeg로 무손실 JPEG2000으로 만들고, 실제 img2tile의 변환 결과를 확인했습니다. **JP2 컨테이너는 성공했고 원시 J2K 코드스트림은 현재 모듈에서 거절됐습니다.** HEIC의 유효 표본 실변환은 아직 완료하지 않았습니다.

## 합성 표본과 비교 방법

Jpeg2000IntegrationTests는 비정사각128×96 RGB8 이미지에 좌표별 비대칭 RGB 패턴을 그립니다. RGB 값은 R=2x, G=2y, B=(3x+5y) mod256입니다. 모든 픽셀을 비교하므로 예기치 않은 회전·반전·채널 교환·손실을 검출합니다. EXIF/ICC/투명도/고비트/명시적 회전 속성을 넣은 표본은 아닙니다.

현재 회귀는 설치된 FFmpeg의 libopenjpeg 인코더를 rgb24/irreversible=0(가역 변환)로 사용하고 format=jp2 또는 j2k를 명시합니다. 초기 FFmpeg 내장 jpeg2000 인코더는 IHDR 비트 깊이 값을 코드스트림보다1 크게 기록하는 것을 발견해 표본 생성에서 교체했습니다. 이 불일치는 현재 입력 프로필에서 거절합니다. FFmpeg 디코더로 PNG를 다시 만들어 생성 래스터 전체와 일치하는지 확인한 뒤 img2tile로 변환합니다. 같은 FFmpeg 실행 파일의 인코딩/디코딩 대조만으로 모든 표준 호환성을 입증하지 않으며, 실제 img2tile 결과를 별도로 전 픽셀 대조합니다.

네이티브 호출은 기존 BoundedProcessExecutor의 무셸 인자·시간/출력/동시성 제한을 사용합니다. FFmpeg 생성 도구와 img2tile의 NativeModuleSnapshot을 전후 확인하고, 원본/저장 원본 SHA와 img2tile 캐시 식별자도 대조합니다. 정상 변환은 직접 adapter뿐 아니라 실제 등록→renderer→queue/cache 게시→PNG 재조회까지 검증합니다. 합성 표본·작업 폴더는 테스트 임시 경로에만 생성하고 실행 파일/DLL/실제 업무 자료는 복사하지 않습니다.

## 확인 결과

| 입력 | 확인 결과 | 지원 범위 해석 |
| --- | --- | --- |
| JP2 / 무손실 RGB8 / 128×96 / 메타데이터 없음 | 직접 변환·worker 게시·PNG 재조회 모두12,288픽셀 정확히 일치. 출력128×96, 원본 불변, 완료 캐시 재사용 | 이 합성 프로필의 미리보기 지원 확인. 일반 방향/색상/압축·대형/다중 이미지 전체 인수 아님 |
| J2K 원시 코드스트림 | FFmpeg 재디코딩은 정확한 픽셀 일치. 현재 img2tile은 PROCESS_FAILED / native exit1 | 헤더 분류/어댑터 MIME 선언이 실제 native 지원을 보장하지 않음 |
| 같은 원시 코드스트림을 .j2k/.j2c/.jpc/.jp2로 저장 | 네 경우 모두 native exit1로 거절 | 확장자 변경으로 해결되지 않음. JP2 컨테이너로 단순 이름 변경하거나 자동 변환기로 우회하지 않음 |
| 원시 J2K worker 경로 | failed / CONVERSION_PROCESS_FAILED, geometry/thumbnail 없음, 콘텐츠 제공 거절·retry 없는 접수는 기존 실패 유지 | 비공개 실패 산출물을 ready 콘텐츠로 제공하지 않음 |
| 생성한 JP2/J2K를64바이트에서 잘라 native에 전달 | ConversionException, 검증된 preview 반환 없음, 원본 해시 불변 | 이 특정 잘림 경계의 거절 확인. 모든 손상/부분 복구를 동일하게 거절한다고 일반화하지 않음 |

정상 JP2 미리보기의 geometry는 여전히 null입니다. 출력 크기가 원본과 같거나 패턴이 정방향으로 보인다는 이유로 원본 좌표 규약을 발급하지 않습니다. JP2 편집·crop·타일 지원은 이 단계에서 활성화하지 않았습니다. 원시 J2K 지원 개선은 모듈의 명시적 지원/규약을 확인한 뒤 별도 변경과 검증이 필요하며, native가 성공하도록 바뀌면 현재 미지원 회귀의 기대값도 의도적으로 갱신해야 합니다.

## HEIC 표본 생성 경로 확인

설치 FFmpeg의 인코더 목록에는 HEVC용 libkvazaar가 있지만 muxer 목록에 HEIC/HEIF 출력은 없었습니다. HEVC 비트스트림 생성 가능과 HEIF 컨테이너 생성 가능은 구분합니다. AVIF 출력으로 HEIC 지원을 대체 판정하지 않습니다.

img2tile 폴더의 libheif는 heif_get_version() 결과1.0.0이며, HEVC에 대한 heif_have_encoder_for_format(1)은0을 반환했습니다. 설치 DLL의 버전/인코더 가용성만 읽었고 미디어 디코딩이나 설치 변경은 수행하지 않았습니다. [libheif 공식 인코딩 API](https://github.com/strukturag/libheif/blob/master/libheif/api/libheif/heif_encoding.h)는 이 함수를 인코더 가용성 확인으로 정의하며, 인코더가 있더라도 세부 기능은 별도 확인하도록 명시합니다. 이 결과는 HEIC 디코딩 불가를 뜻하지 않으며 공급망/취약점 검토 완료도 뜻하지 않습니다.

기존 heic2jpg 설치 폴더에 있는 미디어는 출처·합성 여부를 확인하지 않았으므로 열거나 시험에 사용하지 않았습니다. Python 환경의 pillow_heif도 없었습니다. 새 패키지·바이너리/인코더·출처 불명 표본을 추가하지 않았습니다. 신뢰할 수 있는 합성 HEIC 생성 도구 또는 출처/사용 조건과 기대 픽셀이 확인된 합성 표본을 확보한 뒤 픽셀·방향/회전·실패 경계를 시험해야 합니다. **HEIC 실파일 호환성과 원본 좌표 편집은 계속 미인수입니다.**

## 재현

```powershell
.\gradlew.bat test --tests '*Jpeg2000IntegrationTests' -Pimg2tileIntegration -PffmpegIntegration
```

Windows와 실제 설치된 두 모듈이 필요합니다. IMG2TILE_EXECUTABLE/FFMPEG_EXECUTABLE을 지정하거나 기존 기본 경로를 사용합니다. 테스트에는 img2tile·ffmpeg 태그를 모두 적용하므로 일반 test/build에서는 제외되고 해당 통합 플래그가 모두 있는 경우만 포함됩니다. 새 런타임 디코더·모듈 선택 옵션·설치 YAML 변경은 없습니다. 전체 회귀에는 -PnativeViewerIntegration도 포함합니다. 설치 ZIP은 생성하지 않습니다.

형식별 현재 범위는 [FORMAT_SUPPORT.md](FORMAT_SUPPORT.md), 실환경 인수는 [OPERATIONAL_ACCEPTANCE.md](OPERATIONAL_ACCEPTANCE.md)를 따릅니다.

## 첫 단계 검증 기록

집중7개가47초에 통과했습니다. 이후 전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration이5분51초에 성공했고 Java767개/111 suites의 실패·오류·건너뜀은0개입니다. 실제 img2tile/FFmpeg/native 브라우저와 라이선스16개 규칙·63개 식별 구성요소·53개 포함 JAR 대조를 포함합니다. 일반 UI34개 시나리오는 이번 단계에서 별도 재실행하지 않았습니다.

관련3개 문서의 로컬 링크10개와 실행 JAR의 테스트·기준 구현·fixture 포함0개를 확인했습니다. 집계는 build/reports/jpeg2000-native/verification.json에 기록했습니다. HEIC 유효 파일 검증 미완료와 최종 배포 준비 미완료(distributionReady=false)를 유지합니다. 운영 코드·실제 설치 YAML·실행 모듈·업무 자료는 변경하지 않았고 새 의존성·설치 ZIP은 추가하지 않았습니다.

## 후속 RGB/회색·8/16비트·축소 검증과 게시 보호

같은 img2tile 실행 파일로 RGB/회색 ×8/16비트 ×128×96/64×48의8개 경우를 시험했습니다. 입력은16개의 비대칭 상수 블록이며,16비트 값은8비트 표본×257로 전체 범위에 배치했습니다. libopenjpeg로 만든 JP2를 FFmpeg에서 같은 원시 픽셀 형식으로 재디코딩해 바이트 전체가 원시 입력과 일치하는지 확인합니다. 이는16비트 전체 정밀도·HDR/ICC 표시나 모든 값의 양자화 규칙 검증을 뜻하지 않습니다.

| 경우 | 실측과 현재 처리 |
| --- | --- |
| RGB8/RGB16, 원본 크기 | 전체12,288픽셀과 기대8비트 RGB 값 일치, worker 게시/재조회도 직접 변환과 전체 일치 |
| RGB8/RGB16, 절반 크기 | 64×48 및16개 블록 내부 색상 일치, worker 결과와 직접 변환은 전체3,072픽셀 일치. 경계 보간 필터의 특정 수학식까지 보장하지 않음 |
| 회색8/16, 원본/절반 크기 | 원래 모듈은 종료0을 반환하지만 첫 행 두 번째 블록의 원시 표본이 기대17 대신0. 다른 블록에서도 공간/표본 불일치. 표준 헤더를 만드는 OpenJPEG로 재생성해도 재현 |

회색 오류는 JDK getRGB의 회색→RGB 색공간 처리만의 차이가 아닙니다. PNG Raster의 원시 표본 자체가 다릅니다. 테스트 전용 native 관찰 경로는 보호된 합성 파일·검증한 리소스·BoundedProcessExecutor·모듈 전후 snapshot으로 이를 재현하고, 실제 adapter/queue 경로는 아래 입력 정책으로 거절되는지 따로 검사합니다. 이 직접 관찰 경로는 src/test에만 있으며 운영에 우회 옵션을 만들지 않습니다. 내부 모듈을 수정한 경우 이 관찰 기대값과 허용 프로필을 함께 재검토해야 합니다.

운영 Img2TileConverter에 Jp2InputProfile의 제한된 읽기 전용 검사를 추가했습니다. JP2 signature가 있는 입력에만 적용하며, 현재 검증된 **3성분·동일한 unsigned8/16비트·열거형 sRGB·비서브샘플·원점0·최대40MP**를 허용합니다. IHDR/colr와 코드스트림 SOC/SIZ의 크기·성분/정밀도를 대조합니다. 회색/알파·팔레트/채널 매핑·ICC/다른 색공간·가변 정밀도·불일치/미확인 확장은 승인하지 않습니다. 일반 JP2의 모든 유효 변형을 허용하는 표준 디코더가 아니라 검증된 native 입력 범위를 제한하는 정책입니다. 필드 구조는 [OpenJPEG 공식 JP2 reader](https://github.com/uclouvain/openjpeg/blob/master/src/lib/openjp2/jp2.c)를 참고했으며 새 라이브러리나 소스 의존성은 추가하지 않았습니다.

검사는 top/child 각각64개 box, JP2 header payload64KiB, 입력100MiB의 경계를 적용합니다. 일반/확장/끝까지 길이 box의 범위와 순서를 검사하고 고정 소량의 헤더만 읽으며 코드스트림 전체를 메모리에 올리지 않습니다. 이 검사가 entropy/모든 JPEG2000 악성 입력 검증이나 native OS 메모리/CPU 격리를 대신하지는 않습니다. native 산출물·시간/바이트·모듈·권한 검사는 계속 적용합니다.

미승인 프로필은 작업 디렉터리 생성/native 실행 전에 INVALID_INPUT, worker에서는 CONVERSION_INVALID_INPUT으로 실패합니다. source/thumbnail/원본 좌표를 게시하지 않고, retry 없는 접수는 실패를 유지합니다. 자동 변환기 대체·HEIC/타일/편집 활성화나 허용 범위를 넓히는 YAML 스위치는 추가하지 않았습니다.

converter.cacheIdentity에 jp2-rgb-v1을 결합해 이전의 잘못된 완료 PNG와 thumbnail을 재사용하지 않도록 했습니다. 설치 변경 없이 새 키를 사용하며 이전 파일을 직접 삭제하지 않습니다. 이 식별자는 img2tile이 있는 미리보기 큐 전체의 키에 반영되므로 TIFF 등 기존 미리보기도 최초1회 새 준비가 필요할 수 있습니다. 타일 moduleIdentity/승인 TIFF 범위는 바꾸지 않습니다. 회귀는 이전 엔진 키로 합성 완료 캐시를 만든 뒤 새 큐가 재사용하지 않고 예전 sourceId 접근도 거절하는지 확인합니다.

```powershell
.\gradlew.bat test --tests '*Jp2InputProfileTests' --tests '*Jpeg2000*IntegrationTests' --tests '*Img2TileConverterTests' -Pimg2tileIntegration -PffmpegIntegration
```

단위 프로필 fixture는 entropy 없는 합성 box/SIZ만으로 헤더 경계를 검사합니다. 실제 파일 호환성은 별도의 native 통합 시험에서 검증하며 둘을 혼용하지 않습니다. 운영 코드의 임시 진단 출력은 제거했습니다.

## 후속 최종 검증

2026-10-29 집중52개가1분16초에 통과한 뒤, 헤더 비트 깊이 불일치1개와 이전 캐시 분리 검사를 보완하고 전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration을 실행했습니다.6분21초에 성공했으며 Java803개/113 suites에서 실패·오류·건너뜀은0개입니다. 신규36개는 프로필27개·변형8개·캐시 식별자1개이며 이전 캐시 비재사용은 변형 테스트 안에서도 확인합니다.

실제 img2tile/FFmpeg/native 브라우저와 라이선스16개 규칙·63개 구성요소·53개 포함 JAR 대조를 통과했습니다. 실행 JAR의 입력 검사 포함·테스트/fixture0개, 타일 정규화/게시/재조회 smoke, 관련3개 문서의 로컬 링크10개도 확인했습니다. 일반 UI34개 시나리오는 별도 재실행하지 않았습니다. 집계는 build/reports/jpeg2000-variants/verification.json입니다. 새 의존성·실제 설치 YAML·native 실행 파일/DLL·업무 자료 변경 및 설치 ZIP 생성은 없습니다. 회색 지원은 모듈 수정·재검증 전까지 차단하며 distributionReady=false입니다.
