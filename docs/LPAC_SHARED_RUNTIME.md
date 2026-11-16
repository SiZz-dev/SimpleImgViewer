# 읽기 전용 FFmpeg 런타임 재사용 선행 시험

2026-11-13. `IMAGEGATE_SHARED_FFMPEG_LPAC_PROBE`에서만 제공하는 테스트 전용 구현이다. Node 서버의 설치 옵션·운영 실행 경로는 변경하지 않았다. Node/sharp 계층형 재사용의 후속 결과는 [별도 시험 기록](LPAC_SHARED_NODE_RUNTIME.md)을 따른다.

## 설계와 경계

기존의 고정 배포 ZIP과 대조한 FFmpeg 런타임 10개 파일(165,936,099바이트)을 새 전용 snapshot에 한 번 복사한다. 실행 파일 2개·DLL 7개·LICENSE.txt만 허용하며 원본 설치·원본 stage의 ACL은 바꾸지 않는다. snapshot 내부에 입력·출력·작업 JSON·업무 설정·자격 증명은 넣지 않는다.

새 snapshot의 파일과 디렉터리는 부모 소유자/SYSTEM/Administrators의 관리 권한과 ALL_RESTRICTED_APPLICATION_PACKAGES(S-1-15-2-2)의 읽기/실행 권한으로 보호한다. 이 AR 권한은 **다른 LPAC도 해당 공개 바이너리를 읽을 수 있게 하는 의도적인 공유**다. 비밀정보나 작업 파일을 이곳으로 옮기지 않는 조건이다. 각 작업 root/input/out은 계속 고유 AppContainer SID로 보호하며 AR 읽기 권한을 추가하지 않는다. 기존 작업별 bin에는 합성 canary만 남는다.

파일명을 ASCII 순서로 정렬한 `이름\t크기\tSHA-256\n` 전체의 SHA-256을 snapshot 식별값으로 고정한다. 신뢰된 시험 부모는 이 값과 복사본의 파일별 해시를 원본 inventory와 대조한다. 실행 시 native 부모가 다시 다음을 검사한다.

- 정확히 10개 파일, 고정 파일명, 정규 로컬 NTFS 경로, reparse 거절, 단일 링크, 파일당 128MiB/합계 256MiB.
- 디렉터리와 모든 파일을 FILE_SHARE_READ만으로 열고 **동일 파일 핸들에서** SHA-256을 계산해 부모가 지정한 식별값과 비교.
- 해당 핸들을 자식 종료·검증·프로필 회수까지 유지. 실행 중 파일 쓰기·삭제/교체를 공유 모드로 제한하고, 동시 작업은 각자 읽기 핸들을 유지.

해시 검사는 프로필/작업 root 생성 전에 수행하며 변경·미등록 파일·다중 링크는 복사 방식으로 우회하지 않고 거절한다. 프로필 이름·입출력 복사본·Job·채널 제한은 작업별로 유지한다. 공유 캐시에 profile SID를 매번 추가/제거하지 않는다.

부모 소유자/관리자는 신뢰된 설치·게시 주체다. 이 주체가 아무 작업도 없을 때 snapshot을 변경하는 것을 OS에서 영구 금지하는 설계가 아니다. 변경된 내용은 다음 실행 시 고정 해시와 불일치해 거절한다. 같은 사용자나 관리자가 실행기·전달 digest·상위 namespace를 모두 장악하는 상황의 방어를 주장하지 않는다. 아직 운영용 버전 게시/회수·전체 저장 공간 한도·Node 부모 종료·업데이트 관리자는 없다.

## 실행

고정 FFmpeg 설치·runtime.json이 이미 검증돼 있어야 한다. 네트워크 다운로드나 설치 ZIP 생성은 하지 않는다.

```powershell
.\scripts\build-shared-ffmpeg-lpac.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-shared-ffmpeg-lpac.mjs
```

빌드는 컴파일러와 소스/헤더/스크립트 지문 및 실행 파일 해시를 기록한다. 같은 폴더를 덮어쓰지 않는다. 실행기는 고정 시험 모드만 받으며 웹 API에 snapshot 경로나 digest 선택 기능을 추가하지 않는다. 결과는 `build/reports/shared-ffmpeg-lpac/verification.json`이다.

## 검증 내용

- 같은 WAV를 복사/재사용으로 번갈아 3회씩 변환해 디코딩 PCM의 정확한 해시 일치를 검사한다. 재사용 작업의 bin에 EXE/DLL/라이선스 복사본이 없는지도 확인한다.
- 서로 다른 440/880Hz WAV의 동시 요청이 모두 성공하고 출력이 구분된다.
- 실제 변환 중 다른 작업의 입력·출력 읽기/덮어쓰기 및 공유 LICENSE 덮어쓰기를 거절한다. 실행 중 부모 소유자의 로드되지 않은 LICENSE r+ 열기도 거절되며 마지막 작업 종료 후에는 허용된다. 이는 파일 핀의 수명을 확인하는 대조군이며 단독 ACL 검증으로 확대하지 않는다.
- 두 장시간 FFmpeg가 실제로 출력 중일 때 하나만 종료한다. 프로필 1개는 회수되고 다른 작업은 busy로 유지되며 출력 크기가 계속 늘어난다. 마지막 작업까지 종료하기 전에는 공유 파일 쓰기 잠금이 유지된다.
- 정지 상태의 LICENSE 변조, 미등록 extra.dll, 다중 하드링크를 실행 전에 거절하고, 시험용 변경을 되돌린 뒤 다시 정상 변환한다. 원본 런타임 해시·snapshot 해시 및 잔여 lease 0을 확인한다.
- 공유 FFprobe의 MP3 JSON을 전체 파싱하고, 실제 MP4→VP8/Opus WebM 변환과 결과 probe·50개 160x96 RGB 프레임을 복사 방식과 비교한다. 영상의 평균 오차 1/최대 32 이하 기준을 사용하며 음성 비교는 바이트 단위로 한다.

별도 `test-shared-runtime-acl.mjs`는 Job 파일 핀이 모두 해제된 뒤 다른 고유 SID의 native LPAC 프로브로 snapshot LICENSE를 연다. GENERIC_READ는 성공(0), FILE_WRITE_DATA·DELETE·WRITE_DAC·WRITE_OWNER는 모두 접근 거절(5)이었다. 같은 시점의 부모 소유자 r+ 열기는 성공했고 내용 해시는 유지됐다. 공유 권한에 대한 이 대조군은 실행 중 공유 모드/EXE 잠금과 독립적이다. 결과는 `build/reports/shared-ffmpeg-lpac/acl.json`이다. 실행에는 `build-stream-lpac.ps1`로 현재 소스의 프로브를 빌드한 뒤 Node로 `scripts/test-shared-runtime-acl.mjs`를 호출한다.

장시간 취소 시험의 hold 모드에만 `-flush_packets 1`을 사용한다. 파일 크기로 진행을 관찰할 때 버퍼 flush 간격에 영향을 받지 않게 하는 시험 조건이며 서버의 변환 인수나 정상 성능 측정의 인수를 바꾸지 않는다. 공유 Job 자원/출력 채널 경계는 기존 [FFmpeg LPAC 시험](FFMPEG_LPAC_INTEGRATION.md), [출력 채널 시험](LPAC_OUTPUT_CHANNELS.md)과 같다.

## 성능 해석과 다음 작업

작업별 런타임 쓰기는 165,936,099바이트에서 0으로 줄지만 입력 복사·프로필 생성·매 실행 전체 런타임 해시 읽기는 남는다. snapshot 최초 생성 비용은 별도로 기록한다. 작은 합성 WAV의 3회 교대 측정 중앙값과 동시 요청 경과 시간을 기록하며, OS 캐시·백신·다른 개발 작업의 영향을 받는 표본이다. 이를 운영 SLA·최대 동시 처리량·실제 블랙박스 영상 성능으로 일반화하지 않는다.

최종 13개 시나리오가 통과했다. 다른 회귀 명령을 함께 실행하지 않은 최종 측정의 최초 snapshot 생성은 1563ms, 복사 방식 3회의 중앙값은 1789ms, 재사용 3회의 중앙값은 1127ms였다. 작업별 실행 시간은 복사 1724/1789/1871ms, 재사용 1711/955/1127ms로 편차가 있다. 중앙값 기준 약 37% 감소는 이 개발 호스트의 작은 합성 오디오 시험 관찰이며 보장 수치가 아니다. 영상의 기능 검증은 별도로 수행했다.

Node/sharp의 파일 inventory와 하위 디렉터리 고정은 [후속 시험](LPAC_SHARED_NODE_RUNTIME.md)에서 검증했다. 다음 작업은 운영 버전 관리자·전체 동시성/용량 한도다. 이후 작업 잠금/retired·원본 버전/사용자 권한 재확인·캐시 게시를 연결해야 LPAC 운영 어댑터를 적용할 수 있다. 현재 설치에 미완성 활성화 옵션을 노출하지 않는다.

최종 회귀: 공유 런타임 13개 시나리오 및 별도 공유 ACL 대조군, 기존 복사 FFmpeg 13개, 출력 채널 16개, 실제 이미지 작업 8개와 sharp 5형식/20개 출력 비교, 기본 Node LPAC/생산 guard 및 기존 native 접근 제어가 통과했다. Node/실제 모듈 65개는 실패·건너뜀 없이 통과했으며 Chrome 13개 시나리오, React production 빌드와 npm 90개 라이선스 기준선도 통과했다. 이 결과는 현재 개발 호스트의 범위이며 운영 전환·최종 배포 승인을 뜻하지 않는다.
