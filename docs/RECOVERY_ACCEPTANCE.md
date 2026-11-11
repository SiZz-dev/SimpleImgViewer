# 복구 오프라인 통합 점검

--maintenance --verify-recovery는 중지한 격리 복원본에 대해 manifest 목록/해시 대조·원본 카탈로그/내용·편집 참조 검사를 순서대로 조정합니다. 운영 복구 승인, 백업 복사/복원 자동화, 데이터 수정/삭제나 서비스 시작을 수행하지 않습니다. Spring/설치 YAML 자동 import/변환 모듈·이미지 디코더를 실행하지 않습니다.

## 실행

~~~powershell
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --verify-recovery --help
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --verify-recovery --originals-root D:\Recovery\originals --annotations-root D:\Recovery\annotations --stamps-root D:\Recovery\stamps --manifest D:\ProtectedBackup\inventory.tsv --manifest-sha256 TRUSTED_LOWERCASE_SHA256 --workers-stopped
~~~

세 경로·외부 manifest·별도 신뢰 manifest 해시·중지 확인은 필수입니다. [manifest 계약](BACKUP_MANIFEST_VERIFICATION.md)의 UTF-8 TSV v1/전체 부모 목록/최대4 MiB·10,000항목 및 절대 canonical·독립 root 규칙을 그대로 적용합니다. 실제 경로와 SHA-256은 설치 환경에 맞게 지정하며 파일/폴더를 자동 생성하지 않습니다. 서명·출처는 승인된 백업 시스템에서 검증해야 합니다. 점검 대상과 함께 변조될 수 있는 해시값만으로 신뢰를 부여하지 않습니다.

## 검사 순서와 판정

| 단계 | 검사 |
| --- | --- |
| MANIFEST_BEFORE | 전체 파일/디렉터리 목록·유형·크기·SHA-256이 보호된 manifest와 일치 |
| ORIGINALS | 원본 카탈로그 필드/식별/헤더·등록 SHA-256과 실제 바이트 대조 |
| EDITING_REFERENCES | 주석 구조/현재 원본 버전/좌표계·기록된 소유자 스탬프 참조 |
| MANIFEST_AFTER | 같은 manifest와 전체 목록/해시를 다시 대조 |

각 단계 결과는 PASSED(완료/문제 없음), FINDINGS(불일치), PARTIAL(상한 도달), NOT_RUN(앞 단계가 통과하지 않아 미실행) 중 하나입니다. 첫 FINDINGS/PARTIAL에서 중단하고 뒤 단계는 NOT_RUN으로 남깁니다. 실행하지 않은 검사를 통과로 간주하지 않습니다. PARTIAL은 알려진 문제 유무와 관계없이 완료가 아닙니다.

네 단계 모두 PASSED일 때만 offlineChecksPassed=true 및 종료0을 반환합니다. manifest가 일치해도 그 안의 원본 카탈로그나 주석 참조가 잘못된 경우 통과하지 않습니다. 시작 manifest 이후 빈 주석 파일 누락·스탬프 본문 변경도 종료 대조에서 탐지할 수 있습니다.

출력 JSON은 schemaVersion, generatedAt(UTC 시작 시각), offlineChecksPassed, stages입니다. 단계별 name/status/scanned/bytes/passedItems/issues의 집계만 포함합니다. 원본 UUID·경로·파일명·해시·메모·tenant/user·개별 참조는 출력하지 않습니다. 상세 원인은 각 단독 점검 명령을 같은 중지 상태로 실행해 확인할 수 있습니다.

scanned와 passedItems는 단계마다 단위가 다릅니다. manifest는 방문 항목/일치한 파일·디렉터리, ORIGINALS는 열거 항목/검증 원본, EDITING_REFERENCES는 보수적 경로 작업 횟수/유효 주석 페이지와 스탬프 개수 합계입니다. 서로 합산해 업무 미디어 수로 해석하지 않습니다. bytes는 manifest/원본 단계의 본문 해시 바이트이며 참조 단계에서는 제한 metadata 읽기 바이트입니다.

## 잠금 수명과 일관성

ReadOnlyStorageScope는 세 root의 canonical 디렉터리 경계를 확인하고 기존 .quota-writer.lock / writer.lock만 READ/shared로 한 번 획득합니다. 처음부터 마지막 대조가 끝날 때까지 보유하고 각 단계 전후 root 속성과 marker를 재확인합니다. 기존 단독 manifest/참조 점검도 같은 범위 구현을 사용합니다. 이미 소유한 범위를 내부 검사에 빌려주므로 같은 JVM에서 겹친 FileLock을 재획득하지 않습니다. 범위는 지정된 세 root와 생성 스레드에 묶이며, 닫혔거나 다른 경로/스레드의 범위는 사용할 수 없습니다.

일부 잠금 획득 실패·검사 실패·부분 검사에도 이미 획득한 잠금을 해제합니다. 새 잠금 파일/디렉터리는 만들지 않습니다. marker가 없으면 잠금으로 writer를 막을 수 없으므로 --workers-stopped의 운영자 확인이 계속 필수입니다. 특히 quota 비활성 원본 writer 및 비협조적 외부 프로세스를 운영자가 모두 중지해야 합니다.

이 기능은 파일시스템 원자 스냅샷이나 실행 중 백업 모델이 아닙니다. 속성/내용을 되돌리는 적대적 writer까지 격리하지 않으며 전용 비공개 경로·중지한 안정 저장소가 필요합니다. 서비스 트래픽을 열거나 writer를 실행해 결과를 얻지 않습니다.

## 옵션/상한

| 옵션 | 기본값 | 범위/적용 |
| --- | --- | --- |
| --max-entries | 10000 | 1~100000, 각 단계별 열거/작업 상한 |
| --max-bytes | 1073741824 | 1~1 TiB, 각 단계별 본문 해시 또는 metadata 바이트 상한 |
| --max-originals | 1000 | 1~10000, ORIGINALS 결과 수 상한 |
| --max-editing-items | 1000 | 1~10000, EDITING_REFERENCES 결과 수 상한 |

manifest 고정 결과/항목 상한10,000, 참조 metadata 파일별/키별 상한 등 기존 도구의 내부 제한도 유지합니다. 기본 상한에서 일부 자료만 검사했다면 PARTIAL이며 충분한 상한/지원 범위로 다시 검사해야 합니다.

각 단계는 독립 예산을 사용합니다. 성공 시 원본/스탬프/주석 본문을 manifest에서 두 번 읽고 원본을 별도로 다시 해시합니다. 보고서에 계수하는 해시/참조 metadata 바이트의 단계별 예산 합계는 최대4×max-bytes입니다. 원본 카탈로그의 파일별16 KiB 제한 읽기/재대조, manifest 원시 파일의 고정 읽기/재읽기·성장 검출 EOF probe는 이 계수 밖의 추가 I/O이므로 총 디스크 읽기 상한으로 해석하지 않습니다. 오프라인 점검 창의 I/O 시간에 반영하세요. 뷰잉 경로나 자동 백그라운드 작업에는 연결하지 않습니다.

## 종료 코드

- 0: 네 오프라인 검사 모두 완료·문제 없음.
- 2: 누락/중복/알 수 없는 옵션, 잘못된 경로/해시/상한, 중지 확인 누락.
- 3: FINDINGS 또는 PARTIAL, JSON에 단계 요약 포함.
- 4: 기존 writer 잠금 사용 중, 고정 STORAGE_IN_USE 진단.
- 5: manifest 형식/신뢰 해시/접근 실패, root/marker/파일 변경, 검사 불가. 고정 RECOVERY_INSPECTION_UNAVAILABLE 진단.

전역 실패에는 이전 단계의 통과 결과를 성공 JSON으로 출력하지 않습니다. 예외/경로/입력 원문을 stderr에 노출하지 않습니다. --apply/--delete 같은 변경 옵션은 거절합니다.

## 운영 인수와의 관계

offlineChecksPassed=true는 명시한 신뢰 manifest와 현재 저장 형식/참조에 대한 검사 결과입니다. 업무에 필요한 전체 백업이 선택됐는지, 서명/암호화 키·OS ACL·SSO/공유 ACL·실제 PNG/외부 이미지 디코딩·영상/음성/seek·반출·감사 로그·RPO/RTO를 검증하거나 운영 전환을 승인하지 않습니다. 비어 있는 세 저장소와 빈 자료를 명시한 신뢰 manifest는 통과할 수 있으므로 기대 업무 범위는 외부 백업 기록과 확인해야 합니다.

[백업/복구 인수 절차](BACKUP_RECOVERY.md)에 따라 위 오프라인 통과 후 별도 격리 환경의 실제 인증·권한·렌더링/재생 인수를 수행하고 담당자가 전환 여부를 결정합니다. 설정/비밀/감사/캐시/모듈·JDK 복구는 세 저장소 바이트 대조와 별도입니다.

## 합성 검증

실제 저장소로 만든 공유 스탬프/메모/빈 페이지의 네 단계 정상 통과와 무변경, 다른 경로 복원, manifest는 일치하지만 원본/주석 의미가 잘못된 세트 거절, 빈 페이지 누락, manifest/원본/참조 상한, 단계 간 모든 기존 잠금 유지/종료 해제, 마지막 단계 스탬프 변조 검출, manifest 변경/실행 writer/미존재 root, 닫힌/잘못된 경로/다른 스레드 범위 거절과 CLI 상태/비노출을 검증합니다. 실제 업무 자료는 점검·복구·삭제하지 않습니다.

2026-10-23 검증: JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. 신규20개(통합 서비스11/CLI5/범위4), 전체 Java413개 통과(실패/오류/건너뜀0). 실제 img2tile·FFmpeg 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 검증 통과. 실행 JAR에서 도움말·빈 합성 세트 네 단계 통과·추가 파일 실패 및 후속 중단·신뢰 해시 실패 종료 코드/읽기 전용 바이트 보존을 확인했고 테스트/fixture 클래스 포함0개. 실제 업무 자료 점검/복구/삭제·UI/성능 재측정·설치 ZIP 생성은 수행하지 않음. distributionReady=false 유지.
