# LPAC 작업 입출력 바인딩

2026-11-16 후속: 이미지 바인딩을 소비하는 별도 네이티브 전송과 ImageEngine 내부 주입 연결을 추가했다. 실제 LPAC 11개 시나리오가 통과했다. 아래는 선행 계약 기록이며, 현재 실행 범위·메모 제한·남은 회수/미디어 연결은 [이미지 전송](LPAC_IMAGE_TRANSPORT.md)을 따른다. 일반 서버의 LPAC 설정은 아직 제공하지 않는다.

2026-11-13. 운영 어댑터 연결의 첫 단계로 작업별 입력·출력 계획을 구현했다. `backend/conversion/util/lpac-bindings.mjs`는 신뢰된 서버가 만든 작업을 고정 내부 계약으로 바꾼다. 이 계획으로 LPAC 자식을 실행하는 전송 계층은 아직 연결하지 않았다. 현재 실행은 Windows Job 경계를 유지하며 LPAC 설정·새 의존성·설치 ZIP은 추가하지 않았다.

## 이미지 계약

`imageLpacBinding(job, absoluteSandboxRoot, {maxPixels})`는 호출자 객체를 변경하지 않고 깊은 읽기 전용 객체를 돌려준다.

- `schemaVersion: 1`, `kind: image`, 작업 root.
- `inputs`: 호스트 원본 경로, 복사 파일명, 크기 상한. 원본 source.cjs는 100MiB, 스탬프 stamp-000.png 등은 각각 2MiB다. 같은 스탬프 경로의 반복 배치는 복사 입력을 공유한다.
- `document`: 자식 JSON. 경로 필드만 input/out으로 변경하며 메모 문자열은 유지한다.
- `outputs`: 고정 파일명, 호스트 작업 파일 경로, 파일별 32MiB 상한. preview.png/thumbnail.png 또는 export.png만 허용한다.
- `manifest`: out/job.json. stdout/stderr 상한은 각각 8KiB다.

복수 스탬프·메모·밝기·회전·crop을 표현한다. 최대 200개 항목/합계 16M 레이어 픽셀, 항목별 4M 픽셀, crop 8M 픽셀, 메모 500자와 설치 maxPixels 상한을 적용한다. 알려지지 않은 필드·잘못된 출력명·상대 경로·Windows UNC/장치/ADS·sandbox와 호스트 경로의 중첩·원본/출력 별칭을 거절한다.

util은 문자열/구조를 검사한다. 실제 파일·최종 경로·reparse·하드링크·단일 핸들 복사/해시·ACL·프로필·사용 중 핸들은 네이티브 소비자가 재검증해야 한다. 계획만으로 접근이 격리되거나 권한이 부여되지 않는다. 호스트 경로가 포함된 inputs/outputs는 부모용이며 자식에게는 document만 전달해야 한다.

## 미디어 계약과 현재 서비스 반영

`mediaLpacBinding(request, absoluteSandboxRoot)`는 임의 argv 대신 고정 작업을 받는다.

| 작업 | 입력 형식 | 출력 |
| --- | --- | --- |
| probe-input | mov, mpeg, wav, mp3 | stdout JSON |
| video | mov, mpeg | VP8/Opus WebM, 고정 playback |
| audio | wav, mp3 | MP3, 고정 playback |
| probe-output | matroska,webm 또는 mp3 | stdout JSON |
| version / license | ffmpeg 또는 ffprobe, 파일 입력 없음 | stdout 텍스트 |

MP4는 FFmpeg의 mov demuxer를 사용한다. 일반 입력은 100MiB, 결과 재검사 입력/출력은 256MiB, stdout/stderr는 각각 1MiB다. 바인딩은 input/source.media와 out/playback을 사용한다. 네트워크 프로토콜·외부 MOV 참조·임의 프로그램/코덱/필터를 요청할 수 없다.

playback/util/ffmpeg-profile.mjs가 고정 인자를 만들며 현재 PlaybackService도 같은 함수를 사용한다. 운영 서비스의 결과 FFprobe 검사에도 컨테이너 허용 목록과 max_alloc을 적용했다. 기존 버전 참조·작업 잠금·원본/권한 재검사·최종 게시 callback은 유지한다.

## 검증과 후속 작업

backend/test/conversion/util/lpac-bindings.test.mjs의 6개 시험은 경로/구조 거절, 객체 독립성, 메모 보존, 복수 스탬프와 4개 미디어 프로필을 확인한다. 실제 워커는 합성 원본과 스탬프 2개로 메모·밝기·회전·crop·중복 스탬프 export를 호스트 경로와 바인딩 복사 경로에서 실행해 디코딩 픽셀 일치를 확인한다. 이 시험은 Windows Job 실행이며 LPAC 메모/폰트 인수를 대체하지 않는다.

전체 Node 89개 통과·실패/건너뜀 0, Chrome 13개 시나리오, React production 빌드, 90개 패키지 라이선스 기준선이 통과했다. distributionReady=false는 유지한다. 기존 강제 종료 시험은 PID 소멸과 실제 상속 핸들 해제 사이의 간헐 실패를 확인해, 같은 제한 시간 안에 쓰기 핸들 획득까지 기다리도록 보완한 뒤 전체 재검증했다.

다음은 이 계약을 소비하는 네이티브 전송이다. 요청 양쪽 검증, 복수 입력의 읽기 전용 복사, 호스트 작업/런타임 사용 핸들 상속, 부모 종료 시 Job 종료, 출력 검사·제한 반출, 프로필/잔여물 회수와 용량 제한을 연결해야 한다. 메모용 승인 폰트도 인수가 필요하다. 연결과 실제 Windows LPAC 검증 전에는 서버 설정에 LPAC 활성화 옵션을 노출하지 않는다.
