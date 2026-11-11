# PNG 단계 분석·후보 비교와 전용 픽셀 버퍼

2026-10-28. PNG 정규화의 decode/copy/encode/output-check를 분리하고, 픽셀 복사의 임시 배열과 두 번째 setRGB 단계를 제거했습니다. 기본 PNG 압축과 새 RGBA8 이미지·메타데이터 제거·모든 입출력 검증/예산을 유지합니다. 새 라이브러리·설치 설정·외부 모듈은 추가하지 않습니다.

## 재현

```powershell
.\gradlew.bat pngCodecProfileTest
.\gradlew.bat tilePreparationProfileTest -PtileProfileLabel=png-after
```

두 작업은 일반 build/test에서 제외됩니다. pngCodecProfileTest는512MiB 테스트 JVM에서10종 합성 타일/5후보를 비교합니다. 후보별3회 워밍업 후9표본을 수집하며 매 반복마다 후보 시작 순서를 회전합니다. 출력 픽셀 재대조는 시간 측정 밖에 둡니다. PNG 레벨 후보는 테스트 코드에만 존재하며 운영 설정으로 노출하지 않습니다.

tilePreparationProfileTest의 png-before/png-after는 보고서 이름이며 과거 구현 선택 스위치가 아닙니다. png-before는 변경 전에 기록했습니다. 실제 내부 img2tile/24MP TIFF 각3회, 큐·HTTP/브라우저를 제외한 직접 render 측정입니다. render 안에서만 관측하며 전후 생성자/전체 픽셀 검증 IO는 제외합니다. 부모 NORMALIZE_PNG와 자식4단계를 중복 합산하지 않습니다.

## 후보와 선택 근거

설치 JDK21.0.2의 lib/src.zip 안 `java.desktop/com/sun/imageio/plugins/png/PNGImageWriter.java`를 읽어 기본 level4와 명시적 quality→level 매핑 `9 - round(9*quality)`를 확인했습니다. level3/1/0을 동일한 전용 버퍼·RGBA8·출력 검증 조건에서 비교했습니다. 다른 JDK 버전/벤더에 대한 압축 결과 보장은 아닙니다.

| 256×256 패턴 | 기존 복사 PNG 파이프라인 중앙값 | 전용 버퍼 중앙값 | 두 방식의 출력 바이트 |
| --- | ---: | ---: | ---: |
| 단색 RGB | 6.901ms | 5.805ms | 856 |
| 격자 RGB | 6.785ms | 6.229ms | 6,463 |
| 그라데이션 RGB | 15.678ms | 15.070ms | 209,703 |
| 무작위 RGB | 22.574ms | 22.490ms | 225,260 |
| 무작위 RGBA | 14.903ms | 13.800ms | 262,644 |

표는 테스트용 전체 PNG 파이프라인의 패턴별9표본 중앙값입니다. 실제 네트워크/서버 부하 성능이 아니며 fixture 분포를 실업무 가중치로 간주하지 않습니다. 작은 경계 크기도 포함했고 항상 빨라진 결과는 아닙니다. 예를 들어1×1은0.1063→0.1138ms, 1×256은0.5698→0.6254ms로 관측됐습니다.

빠른 압축은 패턴별 파일 크기 변화가 달랐습니다. 단색은 기본856바이트에서 level3/1의1,819바이트로 커졌고, 격자는6,463→2,715/2,889바이트로 줄었습니다. level0은 단색에서262,604바이트로 증가했습니다. 그라데이션/무작위 자료에서도 차이가 있어, 단일 합성 표본을 근거로 전역 압축 정책을 바꾸지 않았습니다. 압축 후보 시간은 동일한 전용 버퍼 후보끼리 비교해야 복사 변경 효과가 섞이지 않습니다.

## 적용한 변경과 보안

새 BufferedImage(TYPE_INT_ARGB)가 소유한 DataBufferInt 배열에 decoded.getRGB를 직접 채웁니다. 새 이미지의 표준0 offset/width stride에만 적용하며 입력/decoded의 데이터 버퍼를 공유하지 않습니다. getRGB의 기본 straight-alpha sRGB 변환은 유지합니다. Graphics2D 합성이나 premultiplied 변환으로 투명 픽셀의 RGB를 버리지 않습니다.

기존 getRGB(null)이 만들던 임시 int[width×height]와 setRGB 복사를 없앴습니다. 256×256 한 타일은 임시 배열 페이로드262,144바이트를 만들지 않습니다. 측정한521개 타일/32,000,189픽셀의 누적 페이로드는128,000,756바이트(약122.1MiB)입니다. 이는 소스 코드상 제거된 배열 페이로드 합계이며 측정한 RSS/힙 최고치 감소나 전체 프로세스 메모리 상한이 아닙니다. 기존처럼 한 타일씩 처리합니다.

PNG_DECODE는 CRC/구조/256 이하 치수·2MiB 입력 상한/실제 디코딩, PNG_COPY는 새 이미지 할당·정확한 픽셀 복사, PNG_ENCODE는 기본 writer/메타데이터·메모리 스트림·쓰기/flush/close, PNG_OUTPUT_CHECK는 결과 배열/CRC·디코딩·clean-chunk 검사입니다. 관측자는 기본 null이며 RuntimeException이 결과를 바꾸지 않습니다. 출력 크기 제한과 IHDR/IDAT/IEND만 허용하는 정책, cache의 독립 PNG/해시 검사, force(true)/원자 게시/최종 원본·모듈·권한 검사는 유지합니다.

정규 회귀에는10종 RGB/RGBA/단색·격자·그라데이션·무작위/1×1·1×256·256×1·13×17·256×65의 바이트 단위 기준 비교와 입력 보존·진단 실패 검사를 추가했습니다. 별도로 alpha0의 숨은 RGB, alpha1/127/128 등을 정확히 보존하는지 검사합니다. 기존 악성 CRC/잘린 PNG/유효 CRC의 손상 래스터/메타데이터 제거 테스트도 유지합니다.

## 실제 TIFF 전후 관측

| 구간 | 변경 전3회 중앙값 | 변경 후3회 중앙값 |
| --- | ---: | ---: |
| decode | 372.40ms | 416.48ms |
| copy | 1,101.31ms | 963.79ms |
| encode | 1,736.34ms | 2,077.77ms |
| output-check | 319.93ms | 359.93ms |
| NORMALIZE_PNG | 3,536.37ms | 3,830.15ms |
| 전체 render | 16.97s | 18.52s |

픽셀 복사 구간은 줄었지만 **전체 준비 시간은 이 실행에서 개선되지 않았습니다.** 변경하지 않은 최초 검증/정규 경로/인코딩 구간도 증가했고, 고정 순서의3회 소규모 실행으로 변경 효과와 환경 변동을 분리하지 못했습니다. 전체 속도 향상이나 운영 SLA를 주장하지 않습니다. 적용 근거는 정확한 출력과 제거한 임시 할당/복사이며, 실제 부하에서의 전체 성능 판정은 별도입니다.

실제 TIFF6회 모두521개 타일/32,000,189픽셀 해시가 이전 기준과 일치하고 출력 바이트 수/원본 SHA도 유지됐습니다. 10종 후보 비교에서는 전용 버퍼와 기준 복사의 출력 바이트도 매번 동일했습니다.

원시 결과는 build/reports/png-codec/candidates.json, copy-comparison.json, native-comparison.json과 build/reports/tile-profile/png-before.json/png-after.json입니다. 실제 설치 YAML·업무 자료·모듈은 변경하지 않았으며 설치 ZIP은 최종 배포 단계에만 생성합니다.

## 최종 검증과 다음 확인

전체 `build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration`이6분24초에 성공했습니다. Java745개/108개 suite 실패·오류·건너뜀0, 실제 내부 모듈·FFmpeg·HTTP·Chrome 통합과 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다. 정규 회귀 신규11개는10종 타일의 정확한 기준 바이트/픽셀·입력 보존·4단계 진단 실패와 투명도 sentinel 검사를 포함합니다. 선택 후보 성능 검증은 별도 집계입니다.

다음은 같은 JVM/같은 입력에서 기준 복사와 전용 버퍼를 교차 실행하고 전체 지연·GC/할당 효과를 분리하는 통제 비교입니다. 실제 업무 자료·SSO/ACL·운영 부하/OS 격리·최종 배포 인수는 계속 남아 있습니다. 설치 ZIP은 생성하지 않았습니다. 통합 보고서는 `build/reports/png-codec/verification.json`입니다.

최종 실행 JAR의 타일 정규화/게시/재열기 smoke 통과, 테스트/인증 fixture 포함 항목0개도 확인했습니다.
## 후속 교차 비교

동일 JVM에서 기준 복사와 전용 버퍼를 교차 실행한 할당·CPU·GC·전체 경과 비교는 [PNG_CROSSOVER.md](PNG_CROSSOVER.md)를 참조합니다. 이 문서의 별도 실행 관측값은 당시 결과로 보존합니다.
