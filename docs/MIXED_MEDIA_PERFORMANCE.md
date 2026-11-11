# 대형 TIFF·영상·음성 혼합 부하 검증

JDK21 / Spring Boot4.0.1 / Gradle8.14.5를 유지합니다. `mixedMediaPerformanceTest`는 실제 설치 img2tile/FFmpeg와 임시 loopback Tomcat, JDK HTTP/1.1 클라이언트를 사용하는 선택 실행 작업입니다. 일반 build/test에는 포함하지 않습니다. 운영 코드·설치 YAML·외부 모듈·업무 자료는 변경하지 않습니다.

## 실행

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat mixedMediaPerformanceTest
.\gradlew.bat mixedMediaPerformanceTest -PmixedRequests=400
```

설치 경로 기본값은 img2tile `D:/DG_module/img2tile/img2tile.exe`, FFmpeg/FFprobe `D:/DG_module/ffmpeg/bin/`입니다. 기존 IMG2TILE_EXECUTABLE/FFMPEG_EXECUTABLE/FFPROBE_EXECUTABLE 환경 변수로 테스트 설치를 지정할 수 있습니다. 재생 도구의 현재 SHA-256을 **테스트에 한정해** 시작 설정으로 고정합니다. 운영 설치 승인/해시 설정을 변경하거나 대신하지 않습니다. 운영의 native snapshot/변경 감지/프로필 검증은 실제 실행 경로에서 그대로 적용합니다.

`mixedRequests`는 단계별100~1000건, 5의 배수이며 기본200건입니다. `mixedMaxP95Ms`는 기본0(관측만), 양수를 지정하면 각 종류/단계의 P95 상한을 테스트 판정에 추가합니다. 이 임의 수치를 제품 SLA나 운영 인수 기준으로 취급하지 않습니다. `mixedNativeSlots`는1~2이며 기본2로 프로젝트 기본 conversion.max-concurrent-jobs와 같습니다. 미리보기/타일이 공유하는 실행 슬롯이며, 1개를 지정한 과부하 관측에서 새 준비가 실패할 수 있습니다. 자동 재접수나 용량 우회를 추가하지 않습니다. 테스트 JVM 최대 힙은1GiB이며 native 프로세스의 메모리 상한은 아닙니다.

## 자료와 측정 순서

- 합성6000×4000(24MP) RGB8/방향1/비압축 TIFF와4초 MP4/WAV를 생성하고 격리 저장소에 등록합니다. 소스 생성/등록과 서버 기동은 지연 표본에서 제외합니다.
- 실제 준비 API로 미리보기·원본 해상도 PNG 타일·브라우저용 WebM/MP3 재생본을 만들고 첫 콘텐츠까지의 시간을 별도로 기록합니다. 준비 시간은 종류별 단일 관측값이며 P95를 계산하지 않습니다.
- 준비된 TIFF의 PNG 미리보기·단일 원본 타일·원본 좌표 page JSON과 영상/음성의4KiB Range GET을 동일 비율로 요청합니다. 초기 연결/JIT 워밍업 요청은 표본에 포함하지 않습니다.
- 준비된 결과의 동시1/4 기준선, 새 TIFF의 미리보기/타일 및 새 영상/음성 변환을 접수하는 동안 동시4 조회, 준비 완료 후 동시8 조회의4개 단계를 측정합니다. 같은 동시4 기준선과 변환 중 조회를 비교하여 동시성 증가 자체와 변환 경쟁을 구분합니다. 변환 중 단계는 설정된 img2tile/FFmpeg/FFprobe 실행 파일 경로와 일치하는 실제 자식 프로세스가 관측 구간에 존재했는지도 확인합니다.
- 응답 상태/MIME/길이/no-store/nosniff, PNG 서명, Range206/Content-Range/길이, page의 원본 좌표 확인을 검사합니다. 단계별/종류별 P50/P95/최대/오류/바이트와 전체 단계 처리량을 기록합니다. P95는 정렬된 표본의 nearest-rank이며 기본 종류당40건으로 소규모 관측입니다.
- 각 콘텐츠/좌표 API의 익명401·다른 사용자404와 CSRF 없는 준비 요청403을 부하 전후에 확인합니다. 이 의도된 거절 요청은 측정 오류율의 분모에 넣지 않습니다. 인증은 테스트의 임의 헤더 fixture이며 실제 SSO 시간을 측정하지 않습니다.

## 자원과 해석 범위

20ms 간격 JVM 힙 사용량, 200ms 간격 자식 프로세스 수를 관측하고 JVM CPU·GC 횟수/시간 차이를 기록합니다. 전체 자식 수와 설정 경로가 일치하는 native 수를 구분하여 테스트 보조 프로세스를 변환 슬롯으로 세지 않습니다. 프로세스 조사는 힙 관측보다 낮은 빈도로 수행하지만 측정 도구 자체의 비용은 남아 있습니다. 힙/CPU에는 같은 JVM의 서버·합성 fixture·HTTP 클라이언트와 응답 버퍼가 포함됩니다. 각 행의 자원 값은 해당 전체 단계에 속하므로 종류별로 더하지 않습니다. 별도로 준비 대기 시간을 포함한 전체 실행 구간의 자원도 기록합니다. 짧은 자식 프로세스는 표본 사이에서 누락될 수 있습니다. 마지막에 등록 원본6개의 SHA-256을 다시 대조하고 테스트 작업 공간의 파일 크기 합계를 기록합니다.

RSS/native heap/native CPU·디스크 IOPS·GPU·브라우저 디코딩/스크롤·네트워크 실사용자 지연을 측정하지 않습니다. OS 페이지 캐시는 비우지 않으며, 감사 로그/주기 유지보수는 비활성으로 고정합니다. 이 결과로 실제 업무 TIFF/SSO/ACL·프록시/TLS·장시간 부하·다중 JVM·OS 자원 격리를 승인하지 않습니다. MP4/WAV를 혼합 부하 대표로 사용하며 MPEG/MP3 입력의 실제 모듈 호환성은 기존 FFmpeg 통합 테스트로 별도 검증합니다.

TIFF는 압축이 잘되는 체커보드 패턴입니다. 같은 단일 타일과 파일 시작4KiB Range를 반복하는 hot-cache 관측이므로 실제 viewport 이동·여러 타일 동시 수신·지속 재생 비트레이트·복잡한 스캔 영상의 성능으로 확대 해석하지 않습니다. 단계 순서는 고정되어 있고 실행 간 편차가 있어 단일 실행의 차이를 정밀한 인과 추정이나 용량 보장으로 사용하지 않습니다.

결과는 `build/reports/mixed-media-performance/latest.json`과 `latest.md`, JUnit 결과는 `build/test-results/mixedMediaPerformanceTest/`에 기록합니다. JSON은 파일 경로·원본 식별자·토큰을 포함하지 않습니다. 합성 원본/파생/작업 자료는 `build/mixed-media-work/run-*` 아래에만 생성되어 Gradle clean 시 함께 정리됩니다. 설치 ZIP 생성은 수행하지 않습니다.

## 용량 1개 예비 관측

2026-10-28 공유 native 슬롯을1개로 제한한 예비 실행에서는 기준선/변환 중 기존 파일 조회400건이 모두 성공했지만, 동시 접수한 새 타일 준비가 TILE_FAILED로 종료하여 전체 실험은 실패했습니다. 실행기의 슬롯 접수는 대기 없는 BUSY 거절이며, 이것을 자동 재시도하거나 정책을 우회하지 않았습니다. 슬롯1의 실패 보고서는 `capacity-one-observation.json`에 보존했습니다. 이 예비 실행의 프로세스 표본 방식은 최종 방식과 다르므로 지연 수치 비교에는 사용하지 않습니다. 기본값2의 성공을 임의의 작업 수/파일/실제 운영 부하에 대한 용량 보장으로 확장하지 않습니다.

## 최종 검증 결과 (2026-10-28)

`mixedMediaPerformanceTest -PmixedRequests=400`이1분46초에 성공했습니다. 실제 img2tile/FFmpeg와 합성24MP TIFF/MP4/WAV로4개 단계/총1,600건을 측정했고 오류는0건입니다. 종류별/단계별80표본이며 HTTP 응답 전체 수신 시간을 기록합니다. 보안 거절22건과 등록 원본6개의 SHA-256 재대조도 통과했습니다.

| 종류 | 동시4 기준선 P95 ms | 변환 중 동시4 P95 ms | 준비 완료 동시8 P95 ms |
| --- | ---: | ---: | ---: |
| 미리보기 PNG | 39.26 | 82.06 | 82.22 |
| 원본 타일 PNG | 40.17 | 100.85 | 90.35 |
| 좌표 page JSON | 42.74 | 91.72 | 90.03 |
| 영상 Range | 29.27 | 81.32 | 106.97 |
| 음성 Range | 32.29 | 68.57 | 84.64 |

전체 표본 JVM 힙 최고치는249.8MiB, GC 39회/244ms입니다. 이는 native 메모리를 포함하는 프로세스 RSS가 아닙니다. 변환 중 실제 승인 실행 파일의 동시 관측 최고치는3개였습니다. 작업 공간은 해당 실행 기준211.5MiB입니다.

초기 준비: 미리보기 PNG 1.92초, 원본 타일 PNG 21.70초, 영상 Range 1.97초, 음성 Range 1.68초. 각 종류1회 관측이며 소스 생성/등록은 제외하고 준비 접수~상태 확인~첫 콘텐츠 수신을 포함합니다.

조회 부하와 동시 준비: 미리보기 PNG 5.56초, 음성 Range 6.81초, 영상 Range 9.22초, 원본 타일 PNG 24.07초. 각 종류1회 관측이며 소스 생성/등록은 제외하고 준비 접수~상태 확인~첫 콘텐츠 수신을 포함합니다.

일반 `build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration`도5분43초에 성공했습니다. Java711개/106개 suite 실패·오류·건너뜀0, 실제 native/브라우저 회귀와 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선 검증을 통과했습니다. 성능 테스트는 일반 테스트 집계와 분리합니다.

다음은 타일 준비의 native 실행/검증/정규화/게시 단계별 시간 분석입니다. 실제 업무 자료/SSO/운영 부하·OS 격리·최종 배포 인수는 계속 남아 있습니다. 설치 ZIP은 생성하지 않았습니다.

실행 JAR의 테스트/인증 fixture 포함 항목은0개로 확인했습니다. 통합 검증 결과는 `build/reports/mixed-media-performance/verification.json`에 기록했습니다.

후속 타일 게시 최적화의 회귀 결과는 TILE_PREPARATION_PROFILE.md를 따릅니다. 위 표는 최초 혼합 부하 단계의 기록이며, `latest.json`/`latest.md`는 가장 최근 실행으로 덮어씁니다. 후속 최적화의1,600건 오류0 결과는 `build/reports/tile-profile/mixed-optimized.json`에 별도로 고정했습니다. 실행 간 수치로 HTTP 성능 개선을 주장하지 않습니다.
