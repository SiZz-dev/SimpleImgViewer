# 패키지 구조 및 관리 규칙

## Node LPAC 설치 선택 후속

conversion/service.RuntimeVerifierPool은 시험 주입용 전체 트리 검증 worker 풀의 고정 코드·입장·FIFO·deadline·종료 수명만 담당한다. conversion/util.runtime-verify-worker가 descriptor를 재검증하고 기존 verifyRuntimeTree를 실행한다. thread exit 전 active 실패를 반환하지 않고 close는 진행 중 검증을 기다린다. application factory/YAML에는 아직 연결하지 않았다. [후보 계약·대조 결과](RUNTIME_VERIFIER_WORKER.md)를 따른다.

scripts/test-runtime-responsiveness는 별도 Node HTTP 클라이언트 fixture와 격리된 LPAC YAML 서버로 유휴/캐시 페이지3개 동시 요청/FFmpeg 검증 포화 부하를 비교한다. 운영 API·설정·검증기에는 계측 경로나 thread를 추가하지 않는다. [응답성 측정 범위와 한계](RUNTIME_RESPONSIVENESS.md)를 따른다.

conversion/util.runtime-inventory는 전체 목록 검사 후 파일2개를 제한 병렬 읽기하며 각1MiB 버퍼로 모든 바이트의 SHA-256을 확인한다. 열기 전 경로·속성/핸들 재검사를 유지하고 실패 시 열린 worker를 모두 종료한 뒤 오류를 반환한다. 내부 readChunkBytes/fileConcurrency/observe는 비교·관측용이며 YAML/HTTP에서 받지 않는다. RuntimeVersions 참조/검증 수명과 완료 검증 캐시 부재는 유지한다. [구간 계측·병렬 읽기 검증](RUNTIME_VERIFICATION_PROFILE.md)을 따른다.

conversion/service.lpac-factory는 검증된 설치 설정에서 고정 이미지·미디어 전송을 만들고 CLI capability/프로필을 확인한다. system/controller.application이 런타임 카탈로그 다음에 이를 초기화하고 ImageEngine/PlaybackService에 주입한다. 설정 검증은 system/config.settings에 두며 HTTP에서 전송 종류·실행기를 선택하지 않는다. 부분 기동 실패 시 풀과 카탈로그 소유권을 해제한다. RuntimeVersions는 메타데이터/참조만 직렬화하고 버전별 진행 중인 전체 검증만 공유한다. 완료 검증 캐시는 두지 않으며 검증 중 임시 참조로 회수 경쟁을 막는다. 설정·실측·인수 범위는 [LPAC_INSTALLATION_LOAD.md](LPAC_INSTALLATION_LOAD.md)를 따른다.


conversion/service.LpacWorkRecovery는 고정 소유 기록·요청 무효화·잔여물 회수 정책을 담당하고 LpacTransport의 풀 소유권/실행 직렬화 안에서만 사용한다. 회수 대상은 생성한 UUID 작업뿐이며 요청 안의 host/runtime 경로를 해석하지 않는다. native sandbox_lifecycle.inspect_residue는 고정 레이아웃의 canonical/ADS/링크·파일 상한만 검사하고 삭제는 하지 않는다. Node는 요청 쓰기 장벽과 named Job 확인 후 개별 삭제를 수행한다. [회수 계약](LPAC_WORK_RECOVERY.md)을 따른다.

LPAC 폰트 승인은 conversion/service.note-font가 고정 OSS 정책과 snapshot 자산을 대조하고 ManagedRuntimes가 지원 표시를 제공한다. native는 고정 fonts 목록/환경·파일 pin, worker는 상수 family·텍스트 escape만 담당한다. API로 폰트 경로를 받지 않는다. 저장 공간 Scan의 ADS 검사는 같은 파일 핸들을 사용하며 일시적인 이름 변경/소멸만 전체 목록의 제한 재검사로 처리한다. [폰트·캐시 경계](LPAC_NOTE_FONT.md)를 따른다.

LPAC 작업 풀·제한 실행·종료·고정 목록 정리는 conversion/service.LpacTransport가 공유하고, 이미지와 미디어 정책은 각각 LpacImageTransport/LpacMediaTransport가 담당한다. PlaybackService.runProfile은 고정 프로필만 전달하고 기존 권한/원본/결과/게시 검사를 유지한다. native의 공통 제한 파서는 lpac-request.h, 프로그램/인자 선택은 각 전용 CLI가 담당한다. [미디어 전송 계약](LPAC_MEDIA_TRANSPORT.md)을 따른다.

conversion/service.LpacImageTransport는 ImageEngine 내부 주입용 이미지 실행·작업 풀 소유/예약·반출·종료를 담당하고 conversion/util.lpac-image-request는 고정 바이너리 인코딩만 담당한다. native/process-job/lpac-image는 실제 워커 하나만 고르는 별도 진입점이며 기존 SandboxSpec의 bound 전용 경로가 복수 입력/읽기 전용 호스트 핸들/부모 파이프를 유지한다. application factory/YAML에는 연결하지 않았다. [이미지 LPAC 전송](LPAC_IMAGE_TRANSPORT.md)을 따른다.

LPAC 작업 바인딩은 conversion/util/lpac-bindings가 호스트 입출력과 자식 JSON의 경로 계약만 담당한다. 재생 실행 규칙은 playback/util/ffmpeg-profile에서 만들고 PlaybackService와 바인딩이 공유한다. 파일 복사·ACL·native 자식 수명·사용자 권한·최종 게시를 문자열 검사로 대체하지 않는다. [계약과 남은 연결](LPAC_JOB_BINDINGS.md)을 따른다.

## 현재 Node 구조

backend는 기능(system/security/media/viewer/editing/conversion/playback)을 먼저 나누고 각 기능의 controller/service/repository/util/config/domain/exception 역할을 구분한다. controller는 요청·응답, service는 업무·정책·수명, repository는 영속 상태, util은 기술 보조, config는 설치 설정을 담당한다. 없는 역할을 위해 빈 디렉터리나 가짜 기능을 만들지 않는다. 테스트도 대상 기능·역할 구조에 맞춘다.

frontend/src/viewer는 React 화면과 편집 상태를 담당한다. system/controller.application이 서버와 기능을 조립하고 API는 각 기능 controller가 등록한다. 설치 스키마는 config/simpleimgviewer.example.yml이며 실제 설정·업무 데이터·네이티브 바이너리는 Git에서 제외한다.

Java/Spring Boot/Gradle 소스와 전용 실행·시험 도구는 제거했다. docs의 기존 Java 설계·명령은 역사적 기록이며 현재 실행 규칙이 아니다. [제거 범위·보관과 복원](LEGACY_SOURCE_REMOVAL.md), [이관 범위와 남은 기능](NODE_REACT_MIGRATION.md)을 따른다. 현재 검증은 Node 테스트·React 빌드·실제 브라우저/네이티브 시험으로 수행한다.
