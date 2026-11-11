# 영상·음성 재생

## 현재 구현

입력 기준은 MP4/MPEG 영상과 MP3/WAV 음성입니다.
FFprobe로 실제 컨테이너·스트림·재생 시간을 확인하고 FFmpeg로 웹 재생본을 생성합니다.
영상은 WebM(VP8, 오디오가 있으면 Opus), 음성은 MP3로 제공합니다.
현재 Chrome에서 실제 재생·일시 정지·시작 위치·탐색 막대·배속·음량·음소거·영상 전체 화면을 검증했습니다.
브라우저의 canPlayType과 실제 로드 실패도 처리합니다. 모든 브라우저/전용 블랙박스 형식의 호환성을 보장하지 않습니다.

원본은 변경하지 않습니다. 재생본은 손실 압축된 열람용 사본이며 증거용 원본 대체물이 아닙니다.
최대 8개 스트림 중 첫 일반 영상 트랙과 첫 오디오 트랙을 사용합니다.
영상 표지/첨부 영상은 주 영상으로 선택하지 않습니다.
멀티 카메라·복수 음성 트랙 선택·자막·전용 블랙박스 GPS/센서/제조사 암호화 데이터는 후속 작업입니다.
영상은 원본보다 확대하지 않고 최대 1920×1080, 짝수 크기로 정규화합니다.
원본 재생 시간 최대 2시간, 원본 영상 최대 8,294,400픽셀·60fps, 오디오는 최대 8채널/192kHz를 허용하고 재생본을 48kHz 스테레오로 만듭니다.

이미지 도구는 재생 중 비활성/숨김 처리하고 우측에는 원본 형식·재생 형식·길이·코덱·재생 해상도·재생본 크기를 표시합니다.
탐색·배속 설치 정책을 구분합니다. 키보드는 Space 재생/일시 정지, 좌우 방향키 5초 탐색입니다.
파일/페이지 전환·권한 회수·세션 종료 시 pause, src 제거, load로 기존 미디어 요청과 표시를 정리합니다.
자동 재생하지 않습니다. 실제 SSO 공급자는 여전히 미연동이며 임시 로그인 우회는 제공하지 않습니다.

## 처리 흐름과 속도

파일 등록은 헤더 후보 분류만 수행합니다. 등록 시 모든 영상의 변환을 일괄 접수하지 않습니다.
선택한 파일의 뷰어 세션 생성 또는 명시적인 POST에서만 재생 준비를 접수합니다.
GET은 상태/캐시만 조회하며 변환을 시작하지 않습니다.
별도의 제한 큐·워커를 사용하고 같은 원본 버전/처리 정책은 중복 준비하지 않습니다.
현재 조직·미디어 ID·원본 버전·원본 SHA-256·모듈 해시/버전·처리 설정으로 캐시 키를 계산합니다.
개인 워터마크가 없는 공통 재생본을 공유하되 모든 상태/구간/콘텐츠 요청에 현재 조직·파일 VIEW 권한을 재검사합니다.

원본 SHA-256과 작업 전후 권한을 검사합니다. 대기/변환 중 권한이 회수되면 결과를 게시하지 않습니다.
완성 파일을 원자적으로 게시하고 준비 완료 상태만 제공합니다.
상태·검증된 재생본은 재시작 후 재사용합니다. 중단된 queued/running 작업은 INTERRUPTED 실패로 표시하고 명시적으로 재시도합니다.
재생 캐시는 보관 기간/용량 기준에 따라 자동 정리합니다. 상세 동작은 CACHE_MAINTENANCE.md를 참고하세요.
이미지 미리보기도 별도 preview.cache 정책으로 정리합니다. 원본/조직별 하드 할당량·분산 큐는 후속 작업입니다.
미디어 캐시의 메타데이터 읽기/교체를 동기화해 Windows의 열린 상태 파일 교체 경쟁을 막습니다.

기본 워커 1, 대기 8, 동시 HTTP 스트림 32입니다.
작업/스트림이 가득 차면 대기 요청을 무제한 쌓지 않고 429를 반환합니다.
변환 시 제한 시간·로그 상한·환경변수 제거·자식 프로세스 종료는 기존 BoundedProcessExecutor를 사용합니다.
제한 시간은 각 FFprobe/FFmpeg 프로세스에 적용되며 작업 전체 누적 제한이 아닙니다.
UI는 1~5초 간격으로 상태를 확인하고 10분 뒤 지연 안내를 표시합니다. 서버 작업을 실패 처리하는 것은 아닙니다.

## API

| 경로 | 동작 |
| --- | --- |
| POST /api/v1/media/{mediaId}/playback?retry=false | 명시적 준비, CSRF 필요. queued/running/disabled는 202, 이미 ready면 200 |
| GET 같은 경로 | 현재 상태. GET은 작업 접수 없음 |
| GET /api/v1/media/{mediaId}/playback/sources/{sourceId}/content | 검증된 재생본의 전체/단일 바이트 구간 |
| HEAD 같은 콘텐츠 경로 | 동일 권한·헤더·구간 검증, 본문 없음 |

상태 응답: mediaVersion, sourceId, state, errorCode, durationSeconds, mimeType,
width, height, videoCodec, audioCodec, sizeBytes, url, seekable.
state는 not_requested/queued/running/ready/failed/disabled입니다.
ready일 때만 보호된 같은 출처 콘텐츠 URL을 제공합니다.
플레이어는 세션 파일의 mediaVersion과 상태 응답 버전·예상 콘텐츠 경로·형식·길이를 확인합니다.
외부 URL·임의 sourceId·원본 경로를 요청으로 받지 않습니다.

GET /media/{id}와 뷰어 세션의 미디어 요약에도 playback 정보를 포함합니다.
영상/음성의 statusUrl은 /playback이며 이미지 preview와 재생 정보를 구분합니다.
원본 헤더가 형식과 유사해도 ready로 처리하지 않습니다. 모듈 비활성 상태는 disabled입니다.

초기 재생 위치는 유한한 0 이상 값이며 검증된 길이보다 작아야 합니다.
준비 전에는 세션 위치를 pending으로 유지하고, 준비 완료 후 세션 GET과 플레이어에서 범위를 확인합니다.
범위를 벗어나면 INVALID_VIEWER_POSITION(400)이며 자동으로 0초로 변경해 재생하지 않습니다.

## HTTP 구간 계약

정상 전체 응답 200, 구간 응답 206/Content-Range, 잘못되거나 만족할 수 없는 구간은 416/bytes */전체길이입니다.
bytes=10-99, bytes=100-, bytes=-100을 지원하고 끝이 길이를 초과하면 마지막 바이트까지 제한합니다.
단일 구간만 지원하며 다중 구간은 416으로 거부합니다.
Accept-Ranges: bytes, Content-Length, Content-Type, ETag, Cache-Control: no-store를 제공합니다.
If-Range가 현재 ETag와 다르면 구간을 무시하고 200 전체 응답을 제공합니다. 날짜 기반 If-Range 일치는 제공하지 않습니다.
HEAD는 같은 헤더를 제공하되 본문을 전송하지 않습니다.
파일 전체를 byte[]·Base64 JSON으로 만들지 않고 제한된 버퍼로 복사합니다.
모든 Range/HEAD와 따뜻한 캐시에도 최신 파일 권한을 확인합니다.
다른 조직/사용자·권한 회수·잘못된 소스는 404로 처리합니다.

설치의 seek=false는 플레이어 탐색 UI 정책입니다.
일반 재생에도 브라우저가 Range를 사용하므로 Range 전송 자체를 차단하는 DRM 정책은 아닙니다.
시청 가능한 미디어의 저장을 브라우저 UI만으로 완전히 방지하지 않습니다.

## 설치 YAML

기본은 비활성입니다. 현재 확인한 설치 위치는 D:/DG_module/ffmpeg/bin의 ffmpeg.exe와 ffprobe.exe입니다.
두 실행 파일과 배포본 DLL을 같은 구성으로 유지해야 합니다.

~~~yaml
imagegate:
  playback:
    enabled: true
    ffmpeg: D:/DG_module/ffmpeg/bin/ffmpeg.exe
    ffprobe: D:/DG_module/ffmpeg/bin/ffprobe.exe
    ffmpeg-sha256: c75913d9c74a3c44aef4234c75c5554a6620ef653a77a8df2f483cfd1bd7483b
    ffprobe-sha256: 10c0bc52dbc56bc71a2180bc01c24cd4439ea4b810e51a956daa5c64921b7911
    timeout: 120s
    workers: 1
    queue-capacity: 8
    max-duration-seconds: 7200
    max-source-pixels: 8294400
    max-output-bytes: 268435456
    max-streams: 32
    cache-version: playback-v1
    cache:
      enabled: true
      retention: 7d
      cleanup-interval: 5m
      max-bytes: 10737418240
~~~

config/imagegate.example.yml에 반영했습니다.
운영 설정은 config/imagegate.yml 또는 IMAGEGATE_CONFIG로 지정하고 재시작합니다.
실제 config/imagegate.yml은 저장소에서 제외하며 이번 작업에서는 생성하지 않았습니다.
해시는 현재 확인한 설치본에만 해당합니다. 다른 빌드로 교체할 때 다시 확인해야 합니다.
사용 여부는 별도로 features.video/audio.enabled, seek, playback-speed도 적용합니다.

설정 상한: timeout 양수~5분, workers 1~2, queue-capacity 1~64,
max-duration-seconds 1~7200, max-source-pixels 1~8294400,
max-output-bytes 1024~536870912, max-streams 1~256.
변환 결과가 바이트 상한에 도달하거나 원본/결과 길이가 허용 오차보다 다르면 잘린 결과를 게시하지 않습니다.

저장은 storage.converted-directory/playback-v1, 도구 점검은 converted-directory/playback-tool-check를 사용합니다.
사용자 파일명을 외부 명령이나 저장 경로에 사용하지 않습니다.
프로세스에 브라우저 옵션·프로토콜 URL·셸 문자열을 전달하지 않습니다.
허용 입력 demuxer와 file 프로토콜만 지정하며 MP4 외부 참조도 비활성화합니다.
프로세스 제한은 OS 격리를 대체하지 않습니다. 전용 계정/파일 ACL과 OS CPU·메모리·네트워크 격리는 배포 검증에 남아 있습니다.

## 모듈 라이선스 확인

현재 설치본은 FFmpeg N-127790-g56dbd4cd37-20261020이며 실제 -L 결과는 LGPL 3 이상입니다.
실행 파일 SHA-256, LGPL 고지, --enable-shared, 필수 libvpx/libopus/libmp3lame 활성 여부,
GPL/nonfree 미활성 및 FFmpeg/FFprobe 버전 일치를 시작 시 확인합니다.
조건 불일치 시 활성화된 서버는 시작 실패하며 다른 프로그램으로 자동 우회하지 않습니다.
이 검사는 배포 라이선스 전체의 법률 검토를 대체하지 않습니다.
DLL 전체의 개별 해시 고정은 구현하지 않았으므로 모듈 폴더는 운영 계정 기준으로 신뢰할 수 있게 보호해야 합니다.
외부 바이너리/DLL은 JAR·저장소·설치 ZIP에 넣지 않았습니다.

[FFmpeg 공식 다운로드](https://ffmpeg.org/download.html),
[FFmpeg 라이선스 안내](https://ffmpeg.org/legal.html),
[BtbN 빌드 설명](https://github.com/BtbN/FFmpeg-Builds)을 기준으로 검토했습니다.
BtbN 빌드 스크립트의 MIT와 FFmpeg 바이너리의 LGPL을 혼동하지 않습니다.
모듈을 별도 배포할 때는 정확히 대응하는 소스·고지·각 포함 라이브러리 조건을 확인해야 합니다.
코덱 특허 조건은 오픈소스 라이선스와 별도 검토 대상입니다.

## 검증

~~~powershell
$env:JAVA_HOME = 'C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat build -Pimg2tileIntegration -PffmpegIntegration
~~~

FFMPEG_EXECUTABLE/FFPROBE_EXECUTABLE로 실제 통합 테스트의 모듈 경로를 바꿀 수 있습니다.
기본 테스트는 FFmpeg 외부 실행을 요구하지 않고 -PffmpegIntegration에서 실제 테스트를 포함합니다.
실제 설치본으로 3초 합성 MP4/MPEG/MP3/WAV를 만들고 변환·재생 시간·코덱·원본 보존·잘못된 해시/손상 파일 거부를 확인합니다.
생성한 build/playback-fixtures/video.webm와 audio.mp3로 브라우저에서 실제 디코딩/재생/탐색을 검사합니다.
따라서 브라우저 테스트 전에 위 실제 통합 테스트를 실행해야 합니다.

브라우저: 기존 10개 시나리오와 실제 WebM/MP3 재생·seek·배속·음량·음소거·전체 화면·전환 정리,
탐색/배속 비활성·권한 상실 정리, 초기 시간 오류, 외부 미디어 URL 차단을 검사합니다.
루프백 합성 API를 사용하며 실제 SSO·서버 HTTP 경로 전체의 종단간 검증은 아닙니다.
서버 HTTP Range/HEAD·권한·CSRF는 별도 API 테스트로 확인합니다.
큐/중복·재시작 캐시·명시적 재시도·처리 중 권한 회수·동시 스트림 상한은 서비스 테스트로 확인합니다.
업무 영상의 최초 재생 P95·동시 부하·장시간 녹음·실제 블랙박스 트랙별 호환성은 잔여 검증입니다.
설치 ZIP은 최종 기능 및 전체 배포 검증 이후에만 생성합니다.

최신 개발 검증: JDK 21 전체 빌드와 Java 테스트 190개(실패/오류/건너뜀 0), Chrome 브라우저 시나리오 14개 통과. 실제 Spring 모듈 구성·WAV 등록·명시적 준비·네이티브 정규화·인증 Range·ready 메타데이터 통합도 확인했습니다. 이는 실제 SSO 공급자 연동 검증을 대체하지 않습니다. 설치 ZIP은 생성하지 않았습니다.

[EXTERNAL_PROCESS_SECURITY.md](EXTERNAL_PROCESS_SECURITY.md)에 img2tile·FFmpeg 공통 실행 경로/환경/수명 제한과 별도 OS 격리 인수 범위를 정의합니다.

[NATIVE_MODULE_APPROVAL.md](NATIVE_MODULE_APPROVAL.md)에 시작 시 고정 EXE/DLL/resource 기준선·새 작업 전후 내용 검사·변경 후 인스턴스 차단과 캐시 식별/운영 재승인 절차를 정의합니다. 시작 로컬 기준선은 공급자 서명/외부 bundle 승인을 대신하지 않습니다.

## 재생 준비의 공통 기한과 취소

playback.timeout은 FfmpegEngine.prepare 한 번의 전체 예산입니다. 입력 FFprobe,
FFmpeg 변환, 출력 FFprobe에 각각 같은 timeout을 다시 주지 않고 단조 시계의 잔여 시간만 전달합니다.
시작/종료 모듈 내용 검사, native 실행 전후, JSON 분석과 결과 검증 후에도 기한과 interrupt를 확인합니다.
기한 소진 시 PROCESS_TIMEOUT으로 실패하고 결과를 반환하지 않습니다. 작업별 새 기한을 사용하며
시간 초과나 취소 자체가 정상 모듈의 승인 기준선을 무효화하지는 않습니다.

스레드 interrupt는 플래그를 유지한 InterruptedIOException으로 중단합니다.
외부 프로세스 실행 중 취소/만료 정리는 공통 BoundedProcessExecutor가 담당합니다.
서비스는 결과 반환 후 권한을 다시 확인하고 게시 직전에 closing/interrupt를 검사합니다.
부분 결과는 게시하지 않고 작업 디렉터리를 정리하며 재시도는 기존 명시적 요청 정책을 따릅니다.
기동 시 버전/라이선스 검사는 기존 호출별 10초 제한으로 별도 수행합니다.

이 기한은 대기열 체류, 서비스의 원본 SHA 검증·권한 조회·캐시 게시에는 적용하지 않습니다.
동기 파일 I/O나 JVM/OS의 프로세스 시작이 멈춘 동안 강제로 반환시키는 하드 실시간 보장은 아니며,
제어가 돌아오는 경계에서 만료를 검사합니다. OS 자원/프로세스 격리 인수는 별도입니다.

이번 단계 검증: 2026-10-23 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(4m29s). 전체 Java470개/76개 suite, 실패·오류·건너뜀0. 신규13개(공통 기한 util3/FFmpeg 기한·취소8/서비스 게시·재시도2), 실제 img2tile·FFmpeg 통합 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. 실행 JAR에서 공통 기한·만료·새 작업 예산·취소 플래그 보존 smoke 통과, 테스트/fixture runtime JAR 항목0개. 실제 모듈·설치 YAML·업무 파일 변경 및 설치 ZIP 생성 없음. UI/운영 P95 재측정과 최종 배포 승인은 별도이며 distributionReady=false 유지.

## 서비스 종료와 캐시 점유

재생 서비스는 종료 시작과 동시에 새 준비 요청/전송을 거절하고 유지보수·변환 워커에 중단을 요청합니다.
shutdownNow로 회수된 대기 작업은 실행하지 않고 failed/INTERRUPTED를 기록한 뒤 각 작업의 캐시 lease를 해제합니다.
워커에 이미 전달된 작업도 시작 경계와 게시 직전에 종료 여부를 확인합니다.
엔진이 interrupt를 소비하고 결과를 반환하더라도 종료 중에는 결과를 게시하지 않습니다.
실행 중 작업은 실패 기록·자신의 작업 파일 정리 후 lease를 해제합니다.
중단된 항목은 재시작 뒤에도 명시적 재시도만 허용합니다.

PlaybackCache.close는 새 lease·결과 게시·유지보수 삭제를 차단합니다.
기존 작업이 실패 상태를 기록하거나 이미 시작한 전송이 파일을 읽는 동안은 저장소 writer lock을 유지합니다.
같은 키의 중복 lease도 각각 해제되어야 하며 마지막 lease가 끝나면 추가 close 호출 없이 잠금을 해제합니다.
완전히 닫힌 이전 캐시 인스턴스는 새 writer가 열린 뒤 파일을 읽거나 쓰지 못합니다.
전송 콜백의 정상 종료·오류 종료 모두 lease 해제 대상입니다.

서비스는 기존과 같이 워커/유지보수 종료를 각각 최대 5초 기다립니다(워커 대기 초과 시 유지보수 대기 생략).
기다리는 호출자의 interrupt 플래그는 보존합니다.
대기가 끝나거나 호출자가 중단되어도 아직 사용 중인 파일의 잠금을 강제로 풀지 않습니다.
늦게 끝나는 워커/전송의 마지막 lease가 잠금을 해제합니다.
응답 콜백이나 파일 I/O가 반환하지 않으면 잠금도 유지됩니다. 5초는 프로세스 전체 종료의 하드 기한이 아니며,
강제 JVM 종료·전원 장애 뒤 잔재 처리는 별도 복구 절차를 적용합니다.

이번 종료 처리 단계 검증: 2026-10-23 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공(4m39s). 전체 Java478개/76개 suite, 실패·오류·건너뜀0. 신규8개(캐시 종료3/서비스 종료·재시작5), 실제 img2tile·FFmpeg 통합 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. 최종 실행 JAR에서 새 lease 거절·중복 lease별 잠금 유지·마지막 해제 후 재오픈·중단 상태 보존·이전 writer 쓰기 차단 smoke 통과, 테스트/fixture runtime JAR 항목0개. 실제 모듈·설치 YAML·업무 파일 변경 및 설치 ZIP 생성 없음. UI/운영 P95 재측정·최종 배포 승인은 별도이며 distributionReady=false 유지.
