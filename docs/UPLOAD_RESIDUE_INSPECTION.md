# 원본 업로드 잔재의 오프라인 점검

## 실행

IntelliJ의 일반 Spring Boot 실행과 분리된 수동 유지보수 명령입니다. 서버와 원본을 수정할 수 있는 외부 프로그램을 먼저 중지하고, 실제 originals 경로를 절대 canonical 경로로 지정합니다. 이번 기능은 읽기 전용 목록이며 삭제/복구/원본 등록/할당량 변경을 수행하지 않습니다. 설치 YAML을 자동으로 읽거나 서버/변환기를 시작하지 않습니다.

~~~powershell
# 도움말: 저장소 접근 없음
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --inspect-upload-residue --help

# 실제 운영 경로는 운영자가 확인하여 지정. 결과는 접근 제한된 콘솔/파일에 보관.
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --inspect-upload-residue --originals-root 'D:\ImageGateData\originals' --workers-stopped --max-items 1000 --max-entries 10000 --max-bytes 1073741824
~~~

--maintenance는 첫 인자, --inspect-upload-residue는 다음 인자입니다. --workers-stopped는 운영자의 중지 확인이며 앱을 자동 중지하지 않습니다. --apply/--delete/알 수 없는 옵션/중복 옵션/상대 경로/정규화되지 않은 경로는 거절합니다. 원본 root 또는 상위 경로가 링크/정션이면 실제 대상의 canonical 경로를 직접 지정합니다. 경로가 없으면 만들지 않고 실패합니다. 실행 JAR은 일반 Gradle build 결과를 사용하며 설치 ZIP은 최종 배포 단계까지 생성하지 않습니다.

| 옵션 | 기본값 | 허용 범위/의미 |
| --- | --- | --- |
| --originals-root | 없음 | 명시적 기존 원본 절대 경로 |
| --workers-stopped | 없음 | 필수 중지 확인 |
| --max-items | 1000 | 1~10000, 결과 항목 수 |
| --max-entries | 10000 | 1~100000, 열거한 root/tenant/upload 하위 항목 수 |
| --max-bytes | 1073741824 | 1~1125899906842624(1 PiB), 알려진 regular file의 관찰 크기 합계 |

max-bytes는 파일 내용을 읽는 I/O 예산이 아니라 속성으로 관찰한 논리 크기의 합계 상한입니다. metadata.properties의 크기도 observedBytes에 포함합니다. 초과할 다음 파일은 합계에 더하지 않고 limitReached=true인 부분 결과로 종료합니다. 알 수 없는 파일·큰 metadata는 구조 오류로 처리하므로 모든 디스크 바이트가 이 합계에 포함된다고 해석하지 않습니다. 상한에 걸린 결과는 잔재 전체 목록이 아닙니다. 파일/디렉터리 나열 순서는 파일시스템에 따라 다릅니다.

## 탐색과 결과

고정 깊이 originals → tenant-SHA256 → .upload-UUID → 알려진 파일만 조사합니다. 일반 UUID 이름의 게시 항목은 이름만 계수하고 내부 파일/카탈로그를 열지 않습니다. publishedNameEntriesSkipped는 이름이 해당 패턴인 항목 수이며 정상 원본 검증 성공 수가 아닙니다. 게시 원본의 SHA-256/카탈로그 검증은 [ORIGINAL_INTEGRITY.md](ORIGINAL_INTEGRITY.md)의 별도 명령을 사용합니다.

파일 본문과 카탈로그 내용은 열지 않습니다. NOFOLLOW 속성·canonical 경계·파일 종류·크기·수정/생성 시각·지원되는 fileKey를 확인합니다. 수정 시각은 장비 시계/복사/관리자 작업으로 달라질 수 있으므로 잔재의 실제 생성 시점이나 중단 시점, 안전한 삭제 가능성을 증명하지 않습니다. 내용 해시나 원자적인 파일시스템 스냅샷을 제공하는 기능도 아닙니다.

| outcome | 의미 |
| --- | --- |
| EMPTY_UPLOAD_DIRECTORY | 알려진 이름의 비어 있는 staging 디렉터리 |
| RECOGNIZED_RESIDUE | 알려진 파일 이름 조합과 경계가 관찰 시점에 일치함 |
| PUBLISHED_ID_COLLISION | 같은 UUID의 게시 이름이 함께 존재함. 내부는 열지 않음 |
| UNEXPECTED_LAYOUT | 알 수 없는 파일/하위 구조·불가능한 조합·큰 metadata 등이 있음 |
| INVALID_OR_UNREADABLE | 파일 종류/링크 경계/속성 접근 등을 확인할 수 없음 |
| CHANGED_DURING_SCAN | 선택한 후보/알려진 파일/상위 경로 속성이 재검사 시 다름 |
| UNEXPECTED_ROOT_ENTRY / UNEXPECTED_TENANT_ENTRY | 예상 이름 규약이 아닌 항목. 이름/내용을 출력하거나 탐색하지 않음 |
| INVALID_OR_CHANGED_TENANT | tenant 경계/열거/최종 변경 검사가 실패함 |

인식하는 layout은 EMPTY, PENDING(pending 하나), RENAMED(original.* 하나), READY_TO_PUBLISH(original.*와 metadata.properties)입니다. 마지막 이름은 파일 조합만 나타냅니다. metadata를 해석하거나 무결성을 검증하지 않으므로 복구 가능한 정상 등록이라고 판단할 수 없습니다. pending+metadata, pending+original, 복수 original, metadata만 있는 조합 등은 인식하지 않습니다. metadata 크기는 기존 카탈로그와 같이 최대16384바이트입니다.

schemaVersion1 보고서는 generatedAt(UTC), scanned, observedBytes, limitReached, publishedNameEntriesSkipped, recognizedResidues와 items를 제공합니다. 인식된 항목에는 상대 경로, outcome/layout, payloadBytes, fileCount, lastModifiedAt가 있습니다. payloadBytes는 pending/original만의 논리 크기이며 metadata를 제외합니다. lastModifiedAt는 staging 및 관찰한 알려진 파일의 수정 시각 중 가장 최근 값입니다. 오류 항목에는 확인되지 않은 숫자/시각을 null로 둡니다. 상대 경로는 엄격한 tenant 해시/.upload UUID 규약을 만족할 때만 출력합니다.

절대 설치 경로·원본 파일명·tenant/user 평문·metadata 내용·본문·토큰·원시 오류는 출력하지 않습니다. 상대 경로의 tenant 해시·UUID·바이트/시각도 운영 식별 정보이므로 결과 JSON과 콘솔 기록을 공개하지 말고 서비스 운영자 권한으로 보관합니다. 로그/보고서의 ACL·보존은 운영 정책을 따릅니다.

## 잠금과 변경 경계

기존 .quota-writer.lock이 있으면 canonical regular file이며 비어 있는지 확인하고 READ 전용 채널로 shared OS 잠금을 시도합니다. quota writer가 잡고 있거나 다른 검사와 잠금이 충돌하면 거절합니다. 파일이 없으면 만들지 않으며 점검 도중 나타나면 결과를 사용할 수 없는 것으로 처리합니다. 검사 종료 시 기존 잠금을 해제하고 잠금 파일은 그대로 둡니다. 잠금 접근/지원이 실패해도 우회하지 않습니다.

quota가 비활성이라 잠금 파일이 없거나, 다른 프로그램이 해당 잠금 규약을 사용하지 않는 경우 실행 중 여부를 자동 증명할 수 없습니다. --workers-stopped와 전용 서비스 계정/ACL·실제 writer 중지는 계속 필수입니다. 여러 노드·네트워크 파일시스템·동시 외부 변경의 안전성을 보장하지 않습니다.

후보 디렉터리와 알려진 각 파일의 속성을 전후 대조하며 tenant/root와 기존 잠금도 다시 확인합니다. 후보 변경은 오류 항목으로, tenant 변경은 해당 tenant의 오류로 보고합니다. root/잠금의 변경·접근 실패 등 전체 신뢰 경계가 깨지면 JSON을 완료 결과로 출력하지 않고 실패합니다. 이미 읽은 속성이 검사 종료 시점의 원자적 전체 스냅샷이라고 해석하지 않습니다. 스캔/결과 상한이나 오류 항목이 있으면 미확인 영역을 정상으로 간주하지 않습니다.

## 종료 코드와 후속 판단

| 코드 | 의미 |
| --- | --- |
| 0 | 상한에 걸리지 않고 잔재/구조 오류 항목이 발견되지 않음. 원본 무결성 인증이 아님 |
| 2 | 옵션 오류. 저장소를 점검하지 않음 |
| 3 | 잔재/오류 발견 또는 상한에 따른 부분 결과 |
| 4 | 기존 quota 잠금 사용 중/충돌 |
| 5 | 경로/권한/전체 변경/잠금 지원 등으로 점검을 완료하지 못함 |

이 결과에는 safeToDelete나 자동 정리 명령이 없습니다. 알려진 staging 구조도 진행 중 요청, 게시 충돌, 업무 복구/보존 대상일 수 있습니다. 운영자는 서버 중지·보호된 백업·원본 무결성/참조·업무 보존 요건을 별도로 확인해야 합니다. 이 명령의 결과만으로 이름/나이/크기 기준 일괄 삭제하지 않습니다.

[ORIGINAL_QUOTA.md](ORIGINAL_QUOTA.md)의 조직별 계수는 중단 staging 본문 바이트를 계속 포함합니다. 이번 명령은 계수 파일을 바꾸거나 용량을 반환하지 않습니다. 기존 캐시 정리 명령의 --apply는 converted 전용이며 원본 staging에 사용할 수 없습니다.

## 검증 범위

합성 임시 저장소에서 네 가지 구조, 게시 이름 제외/충돌, 본문·metadata 바이트 보존, 빈/없는 경로, 알 수 없는 구조, 예산 상한, 파일 크기/시각 변경·디렉터리 교체·새 잠금 출현, 실행 중 quota writer 거절/해제, Windows 정션/링크 경계, CLI 인자/종료 코드/정보 비노출을 검사합니다. 실제 업무 자료 점검·삭제·원본 복구·OS 권한 배포·운영 네트워크 파일시스템 검증은 수행하지 않습니다.

최종 검증(2026-10-22): JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java349개(신규 저장소14개/CLI5개), 실패/오류/건너뜀0. 실제 img2tile·FFmpeg 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 검사 통과. 실제 실행 JAR 도움말·합성 staging 목록/종료3·파일 유지와 테스트 클래스 미포함(0개)을 확인했습니다. 실제 업무 저장소 점검/삭제·원본 복구/할당량 변경·브라우저/업무 성능 측정 및 설치 ZIP 생성은 수행하지 않았습니다.
