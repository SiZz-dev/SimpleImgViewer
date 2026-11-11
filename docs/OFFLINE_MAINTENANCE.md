# 오프라인 캐시 잔재 점검·정리

서버 비정상 종료 후 남은 알려진 임시 항목을 다룹니다. 일반 캐시 보관/용량 퇴거와 별도 도구이며 자동 기동/스케줄러/웹 API로 노출하지 않습니다. 실행 중인 서버에서 사용하지 않습니다. 원본·메모·사용자 스탬프·crop 임시 폴더를 탐색하거나 삭제하지 않습니다.

## 실행

아래에서 실제 설치의 imagegate.storage.converted-directory가 가리키는 절대 경로를 직접 지정합니다. 상대 경로가 설정되어 있으면 storage.root 기준으로 확인하세요. CLI는 설치 YAML/환경 변수를 자동으로 읽지 않으며, 실수로 다른 저장소를 선택하지 않도록 경로를 명시적으로 받습니다. --maintenance는 첫 번째 애플리케이션 인자여야 합니다. 이 모드에서는 Spring 컨텍스트·서버·변환기를 시작하지 않습니다.

~~~powershell
# 안전한 사용법 확인. 저장소에 접근하지 않음
java -jar ./lib/imagegate-0.1.0-SNAPSHOT.jar --maintenance --help

# ImageGate와 남은 img2tile/FFmpeg/FFprobe 프로세스를 먼저 중지한 뒤 점검
java -jar ./lib/imagegate-0.1.0-SNAPSHOT.jar --maintenance --converted-root D:/ImageGateData/converted

# 같은 경로의 기본 점검 결과를 검토한 후, 운영자가 명시적으로 정리할 때만
java -jar ./lib/imagegate-0.1.0-SNAPSHOT.jar --maintenance --converted-root D:/ImageGateData/converted --apply --workers-stopped
~~~

개발 JAR은 build/libs/imagegate-0.1.0-SNAPSHOT.jar입니다. 위 lib 예시는 최종 설치 시 경로 예시이며 설치 ZIP을 생성했다는 의미가 아닙니다. --workers-stopped는 운영자의 중지 확인 표시이고 실제 OS 전체 프로세스 검색/종료를 대신하지 않습니다. 이름만 보고 프로세스를 강제로 종료하지 않습니다.

기본은 dry-run으로 후보만 JSON으로 출력합니다. 실제 원본이나 현재 설치 데이터에 대해 개발 중 apply를 실행하지 않았으며, 삭제 검증은 JUnit 임시 디렉터리의 합성 파일에서만 수행했습니다.

## 잠금과 경계

- converted root와 캐시 경로가 실제 canonical 절대 경로인지 확인합니다. 심볼릭 링크·정션을 따라 다른 위치로 진입하지 않습니다.
- 기존 previews-v1/worker.lock, playback-v1/writer.lock을 모두 확보한 뒤 점검합니다. 서버나 다른 도구가 사용 중이면 중단합니다. 기존 잠금 파일이 없거나 부정확하면 새로 만들지 않고 거절합니다. 캐시가 한 종류만 있으면 그 캐시만 검사하고 둘 다 없으면 거절합니다.
- 잠금은 후보 수집·직전 재검증·개별 파일 삭제가 끝날 때까지 유지합니다. 두 번째 캐시가 잠겼으면 첫 번째 잠금도 해제하고 삭제하지 않습니다.
- 키는64자리 소문자 hex 디렉터리만 처리합니다. state.properties의 키/상태/페이지/알려진 필드와 현재 결과의 파일명·크기·수정 시각 참조를 검사합니다. 상태가 없거나 필수 참조가 잘못되거나 새로운 알 수 없는 필드가 있으면 해당 키를 보존합니다.

## 정리할 수 있는 항목

| 위치 | 대상 |
| --- | --- |
| 이미지 키 바로 아래 | 현재 artifact/thumbnailArtifact가 참조하지 않는 result-UUID.png, state-UUID.tmp |
| 재생 키 바로 아래 | 현재 artifact가 참조하지 않는 result-UUID.webm/mp3, write-UUID.tmp |
| 이미지 work-UUID | preview.png, thumbnail.png, native/preview.png 또는 preview.jpg, native/resized.tmp, native/resource/cmyk.icc 및 libc9i.xml만 허용 |
| 재생 work-UUID | playback.webm 또는 playback.mp3만 허용 |

위 UUID는 정확한 소문자 UUID 형식이어야 합니다. 작업 폴더는 고정된 깊이/파일명 목록으로만 검사합니다. 알려지지 않은 파일·하위 폴더가 하나라도 있으면 작업 폴더 전체를 보존합니다. 파일/폴더 시각이 최근이거나 미래이면 삭제하지 않습니다. 현재 게시 파일과 state.properties, writer/worker.lock, 캐시 키 디렉터리 자체는 삭제하지 않습니다. 중단 queued/running 상태를 임의로 ready/failed로 바꾸거나 변환을 재개하지 않습니다. 이후 서버에서 기존의 권한 확인 후 명시적 재시도 절차를 사용합니다.

새로 참조된 결과나 선택 후 교체/변경된 파일은 삭제 직전 재검증에서 보존합니다. 파일시스템이 fileKey를 제공하면 이를 비교합니다. Windows 등 fileKey가 없는 경우 생성 시각·크기·수정 시각과32KiB 버퍼의 SHA-256을 비교하며, 디렉터리는 canonical 경로와 생성 시각으로 보완합니다. hash 대상은 파일당512MiB·점검 전체1GiB로 제한합니다. 관련 조건을 확인할 수 없으면 삭제하지 않습니다. 이 제한은 원본·현재 참조 결과의 전체 해시 감사 기능이 아닙니다.

## 상한과 결과

~~~powershell
java -jar ./lib/imagegate-0.1.0-SNAPSHOT.jar --maintenance --converted-root D:/ImageGateData/converted --min-age-hours 48 --max-candidates 100 --max-entries 10000
~~~

- min-age-hours: 기본24, 24~8760(365일). 생성 시각이 아니라 마지막 수정 시각으로 오래됨을 판단합니다. 작업 폴더는 모든 허용 파일과 하위 폴더가 기준보다 오래되어야 합니다.
- max-candidates: 기본100, 1~1000. 후보 수집 메모리와 한 번에 정리하는 단위를 제한합니다.
- max-entries: 기본10000, 1~100000. 디렉터리 항목 열거를 제한합니다. 해시 읽기 상한까지 도달해도 limitReached=true로 표시합니다.

JSON schemaVersion=1, generatedAt(UTC), apply, scanned, limitReached, candidateBytes, deleted(완료 후보 수), deletedBytes(실제로 제거된 일반 파일 바이트), skipped, items를 출력합니다. item은 cache 종류·자동 생성 식별자로 된 상대 경로·종류·DRY_RUN/DELETED/SKIPPED_CHANGED_OR_IO/PARTIAL_DELETE 및 후보/삭제 바이트를 포함합니다. 출력에 원본 이름·업무 내용·절대 설치 경로·인증정보·원시 오류 메시지를 넣지 않습니다. skipped는 최근/불명/손상/변경/IO 사례 수이며 모든 건의 상세 경로를 출력하지 않습니다.

상한에 도달하면 이미 완전히 검증한 후보만 처리하고 부분 결과를 알립니다. 디렉터리 열거 순서가 일정하다는 보장은 없으며 대규모 저장소의 모든 잔재를 한 번에 처리하지 않습니다. 오래된 큰 파일/수집 상한/알 수 없는 형식 때문에 남는 항목은 별도 운영 검토가 필요합니다.

| 종료 코드 | 의미 |
| --- | --- |
| 0 | 이번 제한 범위에서 점검/정상 정리 완료 |
| 2 | 인자·상한 오류 또는 apply의 중지 확인 누락 |
| 3 | 부분 점검/정리, 상한 도달 또는 보존/오류 항목 존재 |
| 4 | 저장소 잠금 사용 중 |
| 5 | 경로·잠금·권한·파일 상태 등으로 수행 불가 |

dry-run이어도 잠금이 필요합니다. 기존 데이터나 잠금 파일 내용은 쓰지 않지만, OS의 읽기 접근 시각 등은 변경될 수 있습니다. 잘못된 경로나 없는 디렉터리/잠금 파일을 만들어 고치지는 않습니다.

## 운영 전제와 한계

이 도구는 서비스 계정만 접근할 수 있는 전용 디렉터리와 협력하는 writer lock을 전제로 합니다. 잠금이 해제됐다는 이유만으로 고아 외부 프로세스가 없음을 보장하지 않습니다. 반드시 해당 서버 및 남은 변환 프로세스를 중지하고 운영 백업·경로를 확인해야 합니다. 파일을 바꿀 수 있는 다른 계정/프로그램이 동시에 개입하면 Java 파일 API의 점검과 삭제 사이 경쟁을 완전히 차단할 수 없습니다. fileKey가 없는 환경에서 동일 내용/동일 속성으로 교체한 객체를 구별하는 강한 OS 식별 보장도 제공하지 않습니다.

트리 전체를 재귀 삭제하는 명령은 사용하지 않으며, 확인한 leaf 파일부터 알려진 빈 폴더만 제거합니다. 작업 폴더 정리는 원자적 트랜잭션이 아닙니다. 중간 파일 잠금/IO 오류가 나면 이미 삭제한 일부 임시 파일을 복원하지 않고 PARTIAL_DELETE와 실제 삭제 바이트를 알립니다. 나머지·새로운 미확인 내용은 보존합니다. 다시 실행할 때에도 먼저 점검하세요.

원본 upload 잔재, crop 작업 폴더, 주석·스탬프 임시 저장, 다른 버전 캐시, 비표준 모듈의 파일은 이 도구의 정리 범위 밖입니다. 이는 백업/복구·원본 무결성 감사·법적 보존·OS 격리·디스크 하드 할당량을 구현한 것이 아닙니다. 일반 완료 캐시 정책은 CACHE_MAINTENANCE.md를 참고하세요.

## 검증

합성 임시 디렉터리에서 기본 비삭제, 현재 본문/썸네일·원본/주석/스탬프 보존, 두 종류 캐시의 허용 잔재 정리, 잠금 경합/해제, 최근/미래/알 수 없는 파일, 손상/새 형식 상태, 상한, 새로 참조된 결과, 같은 크기/mtime의 교체 파일, 선택 후 추가된 내용, Windows 정션 경계, CLI JSON/종료 코드를 검사합니다. 실제 설치 저장소에 apply하지 않습니다. 새 런타임 라이브러리나 외부 관리 프로그램을 의존성으로 추가하지 않았습니다.

2026-10-22 검증: JDK 21의 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java267개(유지보수 신규17개 포함), 실패/오류/건너뜀0. 라이선스16개 규칙과63개 구성요소/53개 포함 JAR 기준선 검사 통과. 생성된 실행 JAR의 --maintenance --help는 종료0이며 Spring/서버/저장소를 시작하지 않고 사용법만 출력함을 확인했습니다. 실제 설치 저장소 apply와 설치 ZIP 생성은 수행하지 않았습니다.
