# FFmpeg·FFprobe LPAC 전송

현재 상태: [YAML 선택·서버 연결·실제 Chrome 및 혼합 부하 검증](LPAC_INSTALLATION_LOAD.md)을 추가했다. 아래의 서버 선택 미구현 설명은 선행 단계 기록이다. 기본 Windows Job은 유지하며 다른 Windows 환경의 운영 인수는 남아 있다.

2026-11-16 후속: 이미지와 같은 [소유 기록 기반 회수](LPAC_WORK_RECOVERY.md)를 연결했다. 정상 정리에도 요청 쓰기 장벽과 무효화를 적용하며, 시작 시와 유휴 주기에 실패 잔여물을 검사한다. 미확인 항목·기존 미등록 폴더는 보존한다.

2026-11-16 후속: 이미지 경로의 [폰트·메모 인수](LPAC_NOTE_FONT.md)를 완료했다. 공통 저장 공간 검사는 같은 핸들 기반 ADS 검사와 임시 이름 변경의 제한 재검사로 보완했다. 실패 작업 회수와 일반 서버 선택 인수는 계속 남아 있다.

2026-11-16. PlaybackService의 내부 주입 지점에 LpacMediaTransport를 연결했다. MP4·MPEG·MP3·WAV의 원본 FFprobe, FFmpeg 변환, 결과 FFprobe와 최종 게시를 기존 한 런타임 참조에 묶는다. 일반 서버 factory/YAML에는 아직 선택 옵션을 제공하지 않는다. 기본 실행은 기존 Windows Job이며, 설치 ZIP과 새 의존성은 추가하지 않았다.

## 구조와 고정 실행 계약

- conversion/service.LpacTransport는 이미지/미디어 공통 작업 풀 소유권·예약·제한 실행·종료와 고정 목록 정리만 담당한다. 기존 LpacImageTransport는 이미지 정책을 유지한 하위 어댑터다.
- playback/service.LpacMediaTransport는 고정 미디어 프로필, 원본/결과 바인딩, 작업·런타임 검증과 반출을 담당한다. playback/util.lpac-media-request는 바이너리 요청을 만든다.
- PlaybackService.runProfile은 일반 Job 경로에서는 기존 run을 사용하며, LPAC 경로에서는 전송을 호출한다. 이때 run의 임의 argv 호출은 LPAC_PROFILE_REQUIRED로 거절한다. fallback은 하지 않는다.
- native/process-job/lpac-media.cpp는 ffmpeg.exe/ffprobe.exe만 선택하는 별도 CLI다. 실제 실행 인자는 native에서 고정 생성한다. 이미지와 같은 제한 길이 UTF-8 Request 파서를 lpac-request.h에서 공유한다.

요청은 SIVLPAC1 magic, uint32 작업/도구/형식과 자원 값, 길이가 붙은 runtime/digest/use/work/root/input 문자열이다. 최대 1MiB, 잘못된 UTF-8·선택 값·프로필 조합·후행 데이터는 거절한다. 인자 배열·셸·필터·URL을 전달할 수 없다. CLI는 요청 하나 또는 신뢰된 로컬 관리자의 --recover만 받는다.

| 작업 | 허용 프로필 | 결과 |
| --- | --- | --- |
| version / license | ffmpeg 또는 ffprobe | stdout 텍스트, 입력 복사 없음 |
| probe-input | mov, mpeg, wav, mp3 | stdout JSON |
| video | mov, mpeg | VP8/Opus WebM |
| audio | wav, mp3 | MP3 |
| probe-output | matroska,webm 또는 mp3 | stdout JSON |

MP4는 mov demuxer를 사용한다. 프로토콜은 file만, MOV의 외부 참조·절대 경로 참조는 0으로 고정한다. 자막/데이터·원본 메타데이터는 반출하지 않고 비디오 1920×1080 이내, 오디오 2채널/48kHz로 기존 프로필을 유지한다. 인터넷/사설 네트워크/registryRead capability를 추가하지 않는다. 파일/네트워크 경계는 기존 LPAC 토큰·작업별 SID가 담당한다.

## 바인딩·수명·자원

공개 바이너리는 승인된 공유 snapshot에서 전체 digest·10개 파일/루트 pin을 유지하고, 작업마다 runtime을 복사하지 않는다. 원본은 100MiB, 결과 재검사 입력/출력은 256MiB다. 복사 입력은 해당 작업 SID에 읽기만 허용한다. 미디어 빌드의 출력 감시 상한만 256MiB로 확장했으며 기존 이미지/스트림 빌드의 64MiB 상한은 유지한다. 기존 native 시작 여유 공간과 실행 중 공간 감시도 유지한다.

stdout/stderr는 각각 1MiB로 분리해 FFprobe JSON에 오류 경고가 섞이지 않게 한다. 이미지 기본 상한 8KiB는 변경하지 않는다. processing 역할의 메모리·CPU 시간/비율과 요청별 최대 120초 wall 제한을 적용한다. stderr와 staging 경로를 재생 HTTP JSON으로 노출하지 않는다. native 시간 제한은 기존 PLAYBACK_TIMEOUT/504, 유효하지 않은 미디어는 INVALID_MEDIA/422를 유지한다.

모든 실행은 읽기 전용 host lease.sqlite/share-none 및 runtime.use/share-read 핸들을 부모와 실제 자식이 유지한다. 버전 경로는 use 파일 옆 payload로 고정하고, 부모 stdin 연결 종료 시 Job을 종료한다. version/license/초기 probe처럼 출력 작업이 없던 호출도 임시 playback work를 만들어 동일한 잠금 정책을 적용하고 종료 뒤 해제한다. 읽기 전용 공유 runtime과 호스트 원본 ACL은 변경하지 않는다.

프로필/자식이 종료된 뒤 고정 출력 파일·단일 링크·크기를 확인한다. 호스트 작업 잠금을 다시 얻고 active/runtime 상태를 재검사한 뒤 playback 파일만 배타 생성한다. 기존 서비스가 결과 codec/duration, 현재 원본/권한/기능과 최종 게시를 확인한다. 부모에서 다시 미디어를 디코딩하지 않는다. 같은 버전 참조는 이 전체 수명을 포함한다.

정상 작업은 고정 목록만 정리한다. 실패 root와 요청은 보존하고 공통 풀 용량에 포함한다. 자동 재귀 삭제·실패 root의 재시작 회수는 제공하지 않는다. 메모용 폰트 및 일반 서버 선택 설정도 별도 인수 단계다.

## 검증과 재현

```powershell
.\scripts\build-lpac-media.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-lpac-media-transport.mjs
# 공통 계층 변경의 이미지 회귀
.\scripts\build-lpac-image.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-lpac-image-transport.mjs
$env:SIV_NATIVE_TESTS='1'
& '.tools/runtimes/node-24.22.1/node.exe' --test 'backend/test/**/*.test.mjs'
```

기존 승인 stage와 shared runtime 준비 도구가 필요하다. source/compiler/공유 Request 헤더의 지문별 새 실행 파일을 빌드한다. 합성 자료와 격리된 테스트 HTTP 라우터를 사용하며 운영 인증 연결의 인수를 대신하지 않는다. 보고서는 build/reports/lpac-media-transport/verification.json에 기록한다.

미디어 인수는 두 도구의 LGPL/shared/codec 승인, 4개 형식의 실제 probe→convert→probe와 기존 결과의 디코딩 일치·Range 206, 게시 직전 원본 변경 거절, raw argv 우회 거절, 정상 목록 정리/기능 접근, 잘못된 미디어 422, 진행 중 버전 전환과 이전 URL, 16개 스트림의 8KiB 초과 JSON, 실제 wall 제한과 close 후 작업/사용 표식·미사용 LICENSE 파일 pin 해제를 포함한다. 버전 전환 시험은 같은 승인 바이트를 별도 버전 ID로 게시해 진행 중 참조가 바뀌지 않는지 확인한다. 서로 다른 digest 전환/이전 URL 정책은 기존 관리형 서비스 시험의 별도 범위다.

현재 일반 서버 실행을 LPAC으로 전환했다는 의미는 아니다. 다음은 승인 폰트·메모, 실패 작업의 안전한 회수, 동시 부하/복사 비용·Windows 지원 환경 인수와 설치 설정 연결이다. 최종 배포 라이선스 인수와 ZIP은 완료 후에 진행한다.

검증 결과는 미디어 LPAC 13개, 공통 계층 변경 후 이미지 LPAC 11개, 전체 Node 97개(실패/건너뜀 0), 공통 스트림 16개, Chrome 13개 시나리오 통과다. React production 빌드·90개 의존성 라이선스 기준선도 통과했으며 distributionReady=false를 유지한다.
