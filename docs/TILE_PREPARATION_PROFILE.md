# 타일 준비 단계 측정과 중복 작업 최적화

2026-10-28. 실제 img2tile과 합성6000×4000 RGB8/방향1 TIFF로 준비 과정을 측정했습니다. 새 외부 의존성·ImageMagick·설치 옵션을 추가하지 않았고 실제 설치 YAML/업무 자료/모듈은 변경하지 않았습니다.

## 측정 도구

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat tilePreparationProfileTest -PtileProfileLabel=optimized
```

선택 작업 tilePreparationProfileTest는 일반 build/test에서 제외됩니다. tileProfileRounds는1~5(기본3), tileProfileLabel은 baseline/optimized/io-detail/png-before/png-after입니다. label은 보고서 이름만 선택하며 구현을 과거 버전으로 전환하지 않습니다. baseline 보고서는 변경 전 구현에서 기록했습니다. 이후 같은 label로 실행하면 해당 보고서를 덮어씁니다. 파일 경계·읽기·쓰기·force/close는 TILE_IO_PROFILE.md, PNG 내부 단계/후보 비교는 PNG_CODEC_PROFILE.md를 따릅니다.

소스 생성·원본 등록·생성자 초기화는 측정에서 제외하고 NativeTileRenderer.render 전체를 측정합니다. 원본 해시·메타데이터/프로필·모듈 확인, 작업 소유권 생성, native 어댑터, 최초 출력 검증, 타일별 정규화·공개 PNG 검사·동기화 저장, 원본 출력 재검사, 공개 generation 재검사, 최종 원본/모듈/권한 검사와 원자 게시, 소유 작업 정리를 구분합니다. native 구간은 어댑터 내부의 리소스 준비/모듈 검증도 포함하며 OS 프로세스 실행 시간만 의미하지 않습니다.

TileTiming은 명시적으로 생성자에 전달하는 신뢰된 프로세스 내부 관측자입니다. 기본 생성자/운영 Spring 설정은 null이며 비활성 span을 재사용합니다. 경로·사용자·파일 식별자/출력 본문을 전달하지 않고 고정 enum과 경과 시간만 전달합니다. 관측자의 RuntimeException은 작업 결과/업무 예외를 대체하지 않으며 기존 interrupt를 보존합니다. 새로운 공개 HTTP/metrics API나 YAML 설정은 없습니다.

COMMIT은 ORIGINAL_HASH_AFTER/MODULE_AFTER/ACCESS_AFTER를 포함합니다. 이 중첩 구간을 중복 합산하지 않습니다. 각 단계 중앙값을 합해 전체 중앙값으로 취급하지 않습니다. 큐 대기/HTTP/브라우저·실 SSO/동시 부하·OS cold-cache는 측정하지 않으며 기존 혼합 HTTP 부하 실험과 수치를 직접 비교하지 않습니다. 고정 순서의 전후3회 소규모 측정이고 OS 캐시·JIT·디스크 편차가 남습니다.

## 적용한 변경

TilePngVerifier.decode가 구조·CRC·256 이하 치수/2MiB 입력 상한·실제 래스터를 확인하고, 검증한 래스터의 소유권을 호출자에게 전달합니다. TilePngSanitizer는 이를 재사용하여 같은 입력의 중복 디코딩을 제거합니다. 새 RGBA8 래스터 생성, 메타데이터 제거, 출력 PNG 구조/CRC/디코딩 검사와 저장소의 독립 공개 PNG 검사는 유지합니다. 실패 경로는 디코딩한 래스터를 해제하며 성공한 호출자가 flush합니다.

Img2TilePyramidValidator는 최초의 전체 검증에서 비공개 생성자를 가진 PublicationProof를 발급합니다. 이 불변 증거는 발급 validator 인스턴스·정규 출력 경로·신뢰 Source·불변 manifest·전체 inventory 속성에 묶이며 외부 API/영속 저장소로 전달하지 않습니다. 최초 DZI XML/프로필/CRC/디코딩/예산/전후 경계와 SHA 검사는 그대로 수행합니다.

게시 직전에는 같은 증거의 inventory/파일 정체성과 DZI·**모든 native PNG 바이트의 SHA**를 다시 읽고 대조한 뒤 inventory를 재검사합니다. 이미 전체 검증한 바이트와 정확히 일치할 때만 이전 구조·디코딩 판정을 재사용합니다. 다른 validator/경로/Source의 증거, 같은 크기/mtime 복구를 이용한 변조, 파일 교체·추가·누락·정션은 거절합니다. 임의 manifest만 받는 기존 verifyUnchanged 계약은 전체 재검증을 유지합니다.

publisher의 타일별 원본 해시 검사·정규화, cache의 공개 PNG 검사/저장 후 generation 해시 검사·CREATE_NEW/force(true)/원자 이동, 최종 원본 SHA·버전/권한·모듈 확인과 큐 commit gate는 유지합니다. native 생산자가 종료되고 작업 출력의 배타 소유권을 유지해야 한다는 기존 전제도 유지합니다. 기존 공개 PNG 형식/캐시 정책/키·generation 계약은 바뀌지 않습니다.

## 측정 결과

| 구간 | 변경 전 중앙값 | 변경 후 중앙값 | 관측 변화 |
| --- | ---: | ---: | ---: |
| 전체 render | 16.16s | 15.74s | -2.6% |
| native 어댑터 | 0.99s | 1.01s | +1.8% |
| 최초 출력 검증 | 3.43s | 3.13s | -8.7% |
| 타일 정규화 | 4.98s | 3.87s | -22.3% |
| 게시 전 native 재검사 | 2.26s | 1.18s | -47.9% |
| 타일 동기화 저장 | 2.02s | 2.06s | +2.1% |
| 공개 generation 재검사 | 1.51s | 1.60s | +5.9% |

전체 표본은 변경 전19.74/16.13/16.16초, 변경 후17.55/15.74/13.90초입니다. 큰 전체 속도 향상을 입증한 결과로 간주하지 않습니다. 의도적으로 변경하지 않은 구간도 변동하므로 단계별 변화율 역시 정밀한 인과 추정은 아닙니다. 중복 작업 감소와 보안 동등성은 별도 코드/회귀 검사로 확인합니다.

각 실행에서521개 타일·전체32,000,189픽셀을 다시 디코딩한 해시가6회 모두 동일했습니다. 전체 출력 바이트 수와 원본 SHA도 일치합니다. 원본은 합성 체커보드이며 실제 복잡한 업무 스캔의 인수가 아닙니다.

보고서는 build/reports/tile-profile/baseline.json, optimized.json, comparison.json입니다. 합성 자료는 build/tile-profile-work/run-*에 생성되며 Gradle clean 대상입니다. 최초 출력 검증·파일 경계/저장 관련 비용은 추가 분석 대상으로 유지합니다. 다음 단계는 공유 실행 슬롯 포화 시 오류 구분·수동 재시도 안내 보완입니다. 실제 업무 인수도 계속 필요합니다. 설치 ZIP은 최종 배포 단계에만 생성합니다.

## 회귀 검증

전체 `build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration`이5분36초에 성공했습니다. Java725개/107개 suite 실패·오류·건너뜀0이며, 실제 내부 모듈/FFmpeg·HTTP·Chrome 통합과 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다. 일반 테스트 집계는 선택 성능 프로파일과 분리합니다.

추가한 회귀14개는 게시 증거의 소유 validator/경로/Source 경계, 동일 크기/mtime 복구 변조·파일 교체·시각 변경·추가/누락·Windows 정션, 디코딩 상한·유효 CRC를 가진 손상 래스터, 진단의 예외/interrupt/단일 보고 및 진단 실패 중 정상 게시를 포함합니다. 기존 권한/버전/원본/모듈/설치 기능 변경 거절도 진단 실패 조건에서 검증했습니다.

최적화 후 실제 혼합 HTTP 부하도1분34초에 통과했습니다. 24MP TIFF·MP4/WAV 동시 변환과 조회1,600건 오류0, 보안 거절22건/원본6개 SHA 검사 통과입니다. 변환 중 동시4의 P95는 미리보기108.27ms/타일140.43ms/page141.66ms/영상103.45ms/음성116.57ms였습니다. 이전 실행과 환경·타이밍 편차가 있고 HTTP 지연 개선을 입증한 결과는 아닙니다. 자세한 후속 결과는 `build/reports/tile-profile/mixed-optimized.json`에 고정했습니다. 통합 결과는 `build/reports/tile-profile/verification.json`입니다.

최종 스트림 종료 실패/취소 경로의 래스터 소유권을 보완한 뒤 PNG·validator·cache·renderer 집중 회귀77개와 assemble도46초에 통과했습니다. 최종 실행 JAR의 타일 정규화/게시/재열기 smoke 통과, 테스트/인증 fixture 포함 항목0개 확인을 마쳤습니다.
