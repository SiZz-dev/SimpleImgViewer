# 주석·스탬프 참조의 오프라인 점검

## 실행과 범위

이 명령은 원본·주석·스탬프 저장소의 현재 메타데이터 구조/참조를 읽기 전용으로 점검합니다. 서버와 모든 저장소 writer를 중지한 뒤 실제 세 경로를 각각 canonical 절대 경로로 지정합니다. IntelliJ의 일반 Spring Boot 실행과 분리되며 설정 YAML을 자동 import하거나 서버/변환기/이미지 디코더를 시작하지 않습니다.

~~~powershell
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --verify-editing-references --help

# 경로는 설치 설정의 실제 값을 확인해서 지정합니다.
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --verify-editing-references --originals-root 'D:\ImageGateData\originals' --annotations-root 'D:\ImageGateData\annotations' --stamps-root 'D:\ImageGateData\stamps' --workers-stopped --max-items 1000 --max-entries 10000 --max-bytes 1073741824
~~~

--maintenance는 첫 인자, --verify-editing-references는 다음 인자입니다. 세 경로는 존재해야 하고 상호 동일/중첩할 수 없습니다. 상위 경로를 포함한 링크/정션 별칭은 실제 대상 경로를 직접 지정해야 합니다. --workers-stopped는 운영자의 중지 확인이며 서버를 자동 중지하지 않습니다. --apply/--delete/복구 옵션, 중복/알 수 없는 옵션, 상대/정규화되지 않은 경로는 거절합니다. 없는 경로나 잠금 파일을 생성하지 않습니다.

| 옵션 | 기본값 | 범위/의미 |
| --- | --- | --- |
| --originals-root / --annotations-root / --stamps-root | 없음 | 필수 기존 저장소 절대 경로 |
| --workers-stopped | 없음 | 필수 중지 확인 |
| --max-items | 1000 | 1~10000, 스탬프/페이지/오류 결과 합계 |
| --max-entries | 10000 | 1~100000, 열거·경로 속성/존재 검사 작업 합계 |
| --max-bytes | 1073741824 | 1~1099511627776(1 TiB), 실제 읽은 메타데이터 바이트 합계 |

스탬프 라이브러리 다음 주석 트리를 고정 깊이로 점검합니다. 열거 순서는 파일시스템에 따라 다르며 같은 경로 재검사·공통 참조의 반복 읽기도 예산에 포함합니다. 상한에 걸리면 limitReached=true인 부분 결과를 반환합니다. metadataBytesRead는 원본/스탬프 이미지 크기가 아니라 카탈로그/주석/스탬프 metadata의 읽기와 변경 확인을 위한 재읽기 합계입니다. 실패한 짧은 읽기도 실제 읽은 만큼 포함합니다. 파일별 상한은 주석2 MiB, 원본 카탈로그16 KiB, 스탬프 metadata8 KiB이고 파일당 서로 다른 Properties 키는2048개까지입니다. 정상 주석은 최대100개 항목입니다.

## 확인하는 참조

- 스탬프 라이브러리: tenant 해시/user 해시/스탬프 UUID 경계, metadata id/이름 형식·정해진 필드·크기, 전체/미리보기 치수 범위, stamp.png와 preview.png의 regular file 존재·논리 크기·허용 파일 조합을 확인합니다.
- 주석 문서: tenant 해시/미디어 UUID/버전 UUID/page 이름, 문서의 버전·page·revision·좌표계 식별자, 항목 id 중복·종류·유한한 좌표·기본 범위·메모 형식/상한·스탬프 참조 필드를 확인합니다. 현재 좌표계 식별자는 original:버전:페이지:upright-v1입니다.
- 원본: 해당 tenant/미디어의 카탈로그 존재, 카탈로그 식별자·tenant 해시·현재 버전/IMAGE 종류·필드 구조·SHA-256 형식과 원본 regular file 존재/크기를 확인합니다. 다른 tenant의 원본을 대체 참조하지 않습니다.
- 스탬프 참조: 주석에 기록된 stampOwner의 user 해시와 stampId로 **같은 tenant**의 라이브러리를 확인합니다. 원본 소유자와 스탬프 소유자가 다를 수 있으므로 두 소유자가 같아야 한다는 조건은 넣지 않습니다. 누락되면 다른 사용자/tenant의 스탬프로 대체하지 않습니다.

현재 참조가 없는 라이브러리 스탬프도 정상 자산일 수 있습니다. 참조 개수로 orphan/삭제 대상이라고 판정하지 않습니다. 메모·스탬프·원본을 생성/복구/수정/삭제하거나 revision/용량 계수를 바꾸지 않습니다. undo/redo는 메모리 기록이며 이 도구의 디스크 검사 대상이 아닙니다.

## 결과와 개인정보

schemaVersion1 보고서는 generatedAt(UTC), scanned, metadataBytesRead, limitReached, validPages, validStamps, issues와 items를 제공합니다. 항목에는 area(annotations/stamps), 검증된 이름 규약의 relativePath, outcome과 정상 페이지의 stampReferences가 있습니다. 예기치 않은 이름은 결과에 그대로 반영하지 않고 relativePath=null로 표시합니다.

| outcome | 의미 |
| --- | --- |
| ANNOTATION_REFERENCES_VALID | 검사한 현재 문서의 구조·원본/버전·기록된 스탬프 참조가 일치함 |
| STAMP_STRUCTURE_VALID | 라이브러리의 메타데이터/필수 파일 구조가 일치함 |
| MISSING_ORIGINAL / MISSING_ORIGINAL_CATALOG | 원본 디렉터리 또는 카탈로그가 없음 |
| ORIGINAL_VERSION_MISMATCH / ORIGINAL_TYPE_MISMATCH | 현재 원본 버전 또는 IMAGE 종류가 주석과 맞지 않음 |
| INVALID_ORIGINAL | 카탈로그/원본 경계·필드/크기 등을 확인할 수 없음 |
| MISSING_STAMP | 기록된 tenant/user/스탬프 디렉터리를 찾을 수 없음 |
| INVALID_STAMP_REFERENCE / INVALID_STAMP | 스탬프 metadata/필수 파일을 확인할 수 없음 |
| UNEXPECTED_STAMP_CONTENT | 스탬프 자산 안에 알려지지 않은 파일/구조가 있음 |
| COORDINATE_SPACE_MISMATCH / INVALID_ANNOTATION | 좌표계 또는 주석 구조/값이 현재 규약과 다름 |
| CHANGED_DURING_SCAN | 후보 또는 검사한 참조/메타데이터가 전후 재검사에서 다름 |
| UNEXPECTED_ENTRY / INVALID_OR_UNREADABLE_PATH / INVALID_OR_UNREADABLE | 알려지지 않은 이름·경로/파일 경계·권한 등의 오류 |

한 후보의 첫 번째 오류를 보고하며 모든 항목/참조의 오류를 한 번에 열거한다고 보장하지 않습니다. 수정 후 재검사가 필요할 수 있지만 이 도구는 수정 기능을 제공하지 않습니다. 부분 결과/오류를 정상으로 간주하지 않습니다.

주석과 카탈로그 파일을 제한된 크기로 읽어 구조/참조를 판독하므로 파일 안의 메모·개인 식별정보가 검사 프로세스 메모리에 일시적으로 들어갑니다. 이 내용과 평문 tenant/user·메모 텍스트·원본/스탬프 이름·SHA-256 값·절대 설치 경로·원시 예외는 결과에 출력하지 않습니다. 이미지 본문을 열거나 디코딩하지 않습니다. 상대 경로의 해시·UUID도 운영 식별 정보이므로 JSON/콘솔을 공개하지 말고 운영자 ACL·보존 정책을 적용합니다.

## 잠금과 변경 검사

원본의 기존 .quota-writer.lock과 주석/스탬프의 기존 writer.lock이 있으면 canonical regular file·크기0을 확인하고 READ/shared OS 잠금을 시도합니다. 사용 중이면 거절하며, 일부 잠금 획득 후 실패해도 이미 획득한 잠금을 해제합니다. 파일이 없거나 접근/지원이 실패한 경우 새 파일을 만들거나 쓰기 잠금으로 우회하지 않습니다. 권한 때문에 존재 여부를 알 수 없어도 실패합니다. 없는 marker가 점검 중 나타나거나 기존 marker/상위 root가 변경되면 전체 검사 실패로 처리합니다.

원본 quota가 비활성이라 marker가 없거나 외부 프로그램이 잠금 규약을 따르지 않으면 실행 중 여부를 자동 증명할 수 없습니다. 모든 writer 중지·서비스 계정/OS ACL·안정된 로컬 저장소가 전제입니다. 하위 디렉터리 변경으로 일관성을 확인할 수 없으면 전체 보고서를 완료 결과로 출력하지 않습니다.

각 후보에서 참조한 파일/부모 경로 속성을 기록하고, 읽은 metadata 바이트를 재대조합니다. 누락으로 관찰한 참조가 검사 중 나타나도 변경으로 처리합니다. 속성 비교에는 파일 종류·크기·수정/생성 시각·지원되는 fileKey를 사용하며 심볼릭 링크/정션을 따라 탐색하지 않습니다. 이는 원자적 전체 스냅샷이나 임의 관리자/동시 변경·네트워크 파일시스템에 대한 격리가 아닙니다.

## 판정의 한계와 후속 절차

validPages/validStamps는 이 문서에 명시한 구조/참조의 확인 수입니다. 원본 SHA-256을 다시 계산하거나 이미지 CRC/픽셀·스탬프 PNG 내용/치수의 실제 일치·실제 전체 페이지 수·이미지 원본 크기에 대한 좌표 경계를 디코딩해 증명하지 않습니다. 설치 기능 설정/실제 업무 SSO·공유 ACL·현재 반출 권한도 이 오프라인 파일 검사로 인증하지 않습니다.

원본 바이트 무결성은 [ORIGINAL_INTEGRITY.md](ORIGINAL_INTEGRITY.md), 중단 staging 목록은 [UPLOAD_RESIDUE_INSPECTION.md](UPLOAD_RESIDUE_INSPECTION.md), 복원 인수는 [BACKUP_RECOVERY.md](BACKUP_RECOVERY.md)를 따릅니다. 정상 구조라도 운영 백업/원본/스탬프 복원 및 실제 인증·렌더링 인수를 대신하지 않습니다. 복구나 정리는 보호된 백업·업무 보존 정책과 별도 승인된 절차로 수행해야 합니다.

| 종료 코드 | 의미 |
| --- | --- |
| 0 | 상한/구조·참조 오류 없이 명시한 범위를 점검함 |
| 2 | 인자/상한/경로 중첩 오류 |
| 3 | 오류 발견 또는 예산 상한으로 부분 점검 |
| 4 | 기존 저장소 잠금 사용 중/충돌 |
| 5 | root/잠금/전역 변경·접근·실행 오류로 완료할 수 없음 |

## 검증 범위

실제 LocalMediaStore/AnnotationStore/StampStore로 만든 합성 자료를 중지한 상태에서 공유 소유자 스탬프·빈 페이지·미사용 라이브러리, 원본/스탬프 누락·버전/tenant/소유자 불일치, 손상된 metadata/Unicode/상한, 변경/각 저장소 writer 잠금·해제, Windows 긴 경로 정션/별칭, 결과 정보 비노출·CLI 인자/종료 코드와 바이트 보존을 검사합니다. 실제 업무 자료·운영 복구/삭제·SSO/ACL·동시 외부 writer/네트워크 저장소 인수는 수행하지 않습니다. 설치 ZIP은 최종 배포 단계까지 만들지 않습니다.

최종 검증(2026-10-22): JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java371개(신규 저장소17개/CLI5개), 실패/오류/건너뜀0. 실제 img2tile·FFmpeg와 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 검사 통과. 실제 실행 JAR의 도움말·빈 저장소0/누락 참조3·합성 문서 유지/불필요한 파일 미생성과 테스트/fixture 클래스 미포함(0개)을 확인했습니다. 실제 업무 자료 점검/복구/삭제, SSO/공유 ACL/운영 부하·브라우저/성능 재측정 및 설치 ZIP 생성은 수행하지 않았습니다.
