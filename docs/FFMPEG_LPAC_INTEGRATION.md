# FFmpeg·FFprobe LPAC 선행 검증

2026-11-13. 테스트 전용이며 `productionEnabled=false`, `distributionReady=false`다. 서버의 기존 Windows Job 실행 경로와 설치 설정은 변경하지 않았다. 설치 ZIP도 생성하지 않는다.

## 런타임과 신뢰 경계

현재 설치된 `ffmpeg-8.1.3-27-lgpl-shared`를 사용한다. `stage-ffmpeg-lpac.mjs`는 PowerShell 7의 `install-native-modules.ps1 -VerifyOnly`로 고정 배포 ZIP 해시와 **전체 설치 파일의 ZIP 원본 일치**를 확인한다. 새 다운로드나 원본 ACL 변경은 없다. FFmpeg/FFprobe의 실제 버전·공유 빌드·GPL/nonfree 제외·필요 인코더와 LGPL 문구 검사를 유지한다.

FFmpeg·FFprobe, avcodec/avdevice/avfilter/avformat/avutil/swresample/swscale DLL 및 LICENSE.txt만 링크 없는 별도 소유 폴더에 복사한다. 총 10개 파일·165,936,099바이트다. ffplay는 포함하지 않는다. runtime.json에 파일 크기·SHA-256·정책 해시·원본 ZIP 해시를 기록하고 시험 시작 시 미등록 파일·변경·다중 링크를 거절한다. 고지 보존과 개발 시험 통과가 LGPL 및 제삼자 의존성의 최종 배포 의무 완료를 뜻하지 않는다. 정확한 대응 소스·패치·고지/교체 절차 등 기존 배포 인수 항목은 남아 있다.

`IMAGEGATE_FFMPEG_LPAC_PROBE` 빌드에서만 두 실행 파일과 고정 DLL 목록을 허용한다. 10개 파일 모두 필수이며 파일당 128MiB·합계 256MiB를 넘으면 거절한다. 원본은 정규 로컬 NTFS 경로·reparse 거절·단일 링크 검사를 거쳐 새 SID의 읽기/실행 전용 복사본이 된다. 기존 일반 빌드의 프로그램 whitelist는 확대하지 않았다. 시험 launcher에는 고정 probe/변환/거절 시험만 있으며 임의 인수를 전달하는 CLI나 웹 API는 없다.

LPAC의 Job 자원 조건은 프로세스 1개·커밋 메모리 512MiB·누적 사용자 CPU 30초·기본 벽시계 20초다. 입력 100MiB, 검증 출력 파일 32MiB, 출력 감시 64MiB를 사용한다. 이는 현재 서버의 최대 출력 256MiB나 1024MiB 메모리 설정 전체를 인수한 시험이 아니다. 디스크 감시는 주기적 탐지이며 OS 디스크 quota가 아니다.

입력 이름은 source.media를 사용한다. 기존 이미지 시험용 source.tif를 그대로 사용하면 FFprobe가 MPEG 자료를 image2로 감지해 format whitelist에서 거절하는 문제가 있어 영상/음성 시험에만 중립 확장자를 적용했다.

## 재현

```powershell
& '.tools/runtimes/node-24.22.1/node.exe' scripts/stage-ffmpeg-lpac.mjs
.\scripts\build-ffmpeg-lpac.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-ffmpeg-lpac.mjs
.\scripts\test-native-installation.ps1
```

`build/ffmpeg-lpac/runtime.json`, `launcher.json` 및 `build/reports/ffmpeg-lpac/verification.json`에 상세 결과가 있다. 빌드는 컴파일러/소스/헤더/빌드 스크립트 지문과 EXE 해시를 기록하고 기존 빌드 폴더를 덮어쓰지 않는다. 반복 시험은 기존 manifest를 사용한다.

## 확인한 결과: 13개 시나리오

- MP4/MPEG/MP3/WAV 합성 입력: 각각 별도 LPAC FFprobe → FFmpeg 변환 → LPAC 결과 FFprobe가 성공했다. 영상은 VP8/Opus WebM, 음성은 MP3로 생성했다. 같은 인수의 일반 실행과 디코딩 RGB/PCM을 비교한다. 음성은 SHA-256이 정확히 같아야 하며 영상은 아래의 엄격한 프레임/픽셀 기준을 사용한다. WebM 컨테이너 바이트의 동일성을 주장하지 않는다. 입력 및 런타임 원본 해시도 유지됐다.
- 외부 호스트 파일 읽기/덮어쓰기와 로드되지 않은 runtime LICENSE.txt 덮어쓰기는 Permission denied로 거절됐다. 외부 파일·LICENSE 해시가 유지됐고 부모 소유자의 같은 LICENSE r+ 열기는 허용돼 로드된 바이너리 잠금과 구분했다.
- capability 0개에서 네트워크 열기는 I/O error로 실패했고 수신 연결은 0건이었다. 이는 **소켓 연결 ACL 거절을 입증한 결과로 집계하지 않는다**.
- registryRead만 추가한 시험 대조군에서는 file protocol whitelist가 HTTP를 별도로 거절했다. whitelist를 의도적으로 제외한 시험에서는 실제 TCP 연결이 -10013으로 거절되고 별도 loopback 수신 연결 0건이었다. internet/privateNetwork capability는 주지 않았다. 생산 설정에서 capability를 선택하도록 노출하지 않는다.
- 시험 매크로 없는 빌드는 FFmpeg와 FFprobe 모두 stage=program에서 거절했다.
- 실제 FFmpeg가 -re/stream_loop로 출력에 1024바이트 이상 기록 중인 상태에서 회수는 busy=1로 거절됐다. 실행기를 강제 종료한 뒤 해당 FFmpeg 자식이 종료되고 프로필 1개 회수·lease 0을 확인했다. 부분 출력은 검사용으로 보존했다.
- 별도 실제 변환의 벽시계 1초 초과가 wall-timeout으로 거절되고 lease가 남지 않았다.

직접 IPv4 TCP loopback과 위 파일 시나리오의 결과이며 DNS·IPv6·UDP·브로커 IPC·모든 OS/실제 블랙박스 코덱의 포괄 인수는 아니다. 기존 업무 서버 8080과 업무 자료는 사용하지 않았다.

## 관찰된 경고와 다음 단계

capability 0개의 로컬 파일 probe는 exit 0 및 정상 JSON/변환 결과를 반환하면서 avformat-62.dll에 포함된 `Error in auto_init()` 문구를 출력했다. 경고의 내부 발생 원인은 확정하지 않았다. registryRead 대조군의 probe에서는 이 경고 없이 정상 JSON을 얻었다. 이를 모든 FFmpeg/Windows 조합의 capability 요구사항이나 광범위 레지스트리 권한 승인으로 해석하지 않는다.

후속 구현에서 [stdout/stderr 파이프를 분리](LPAC_OUTPUT_CHANNELS.md)했다. 각 채널은 8192바이트까지 수집하고 어느 쪽이든 초과하면 Job을 종료해 실패로 반환한다. 시험 FFprobe는 stdout JSON 전체를 그대로 파싱하며 경고 문구를 제거하거나 stderr를 JSON 대체값으로 사용하지 않는다. `Error in auto_init()`는 stderr의 비공개 검증 기록으로 남긴다. 정상 종료·크기 제한·유효 JSON/필드/실제 출력 검증을 통과한 경우에만 성공이며, stderr 경고가 있어도 이 조건을 생략하지 않는다. 경고 발생 원인을 해결했다거나 capability 0개의 네트워크 초기화가 성공한다고 표시하지 않는다.

출력 분리 회귀 중 같은 인수의 **일반 실행끼리도** VP8 RGB 해시가 달라지는 결과를 관찰했다. 최초 일반 대조군과 달리 재실행 3개는 서로 같고 LPAC 결과와도 같았다. 두 결과 모두 50프레임이며 평균 RGB 바이트 차이 약 0.204, 최대 13이었다. 증거는 `build/reports/ffmpeg-lpac/vp8-determinism.json`이다. 실시간 VP8/2스레드 합성 시험의 영상 비교는 정확히 50개 160x96 프레임·동일 바이트 수를 요구하고, 전체/프레임별 평균 절대 차이 각각 1 이하·최대 차이 32 이하를 모두 요구하도록 보완했다. 정확한 해시 일치 여부와 양쪽 해시/오차를 결과에 함께 기록한다. 이 수치는 현재 작은 합성 자료의 회귀 기준이며 운영 영상 전체의 일반 화질 기준이 아니다. 음성의 바이트 단위 동일성 조건은 유지한다.

[공유 런타임 후속 시험](LPAC_SHARED_RUNTIME.md)에서는 전체 해시와 실행 중 파일 핀을 유지하면서 FFmpeg 작업별 약 158MiB 복사를 없앴다. 기존 복사 방식도 대조군으로 보존한다. [Node/sharp 재사용](LPAC_SHARED_NODE_RUNTIME.md)도 후속 검증했으며 다음 단계는 운영 버전/용량 관리다. 초기화 정책의 운영 인수, Node 부모 종료, 기존 작업 잠금/retired 표식·캐시 게시/권한 재확인과의 결합을 완료해야 생산 LPAC 어댑터를 적용한다. 실패 시 비격리 실행으로 자동 우회하지 않으며 개발용 측정을 운영 성능 보장으로 표시하지 않는다.

설치 검증 중 PowerShell 5/7의 문장부호 정렬 차이로 동일한 명세의 순서가 달라져 실패하는 문제도 수정했다. 양쪽 명세를 같은 필드·형식·정렬로 정규화해 비교하며 파일 추가/누락/해시 변경을 거절한다. 배포 ZIP과의 전체 내용 대조는 그대로 유지한다. 합성 설치 경계 시험 15개가 통과했다.

최종 회귀: Node/실제 모듈 65개 통과·실패/건너뜀 0, Chrome 13개 시나리오 통과, React production 빌드 및 npm 90개 라이선스 기준선 통과. 현재 공통 소스로 재빌드한 기본 Node LPAC/생산 guard, 기존 native 접근 제어, sharp 5형식/출력 20개 및 실제 이미지 작업/업로드 스탬프/다중 페이지/회수 8개 시나리오도 통과했다. 이 수치를 새 FFmpeg 13개 시나리오와 합쳐 포괄적인 운영 인수 완료로 표시하지 않는다.
