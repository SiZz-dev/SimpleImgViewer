# 백업 manifest 대조 (읽기 전용)

2026-10-23 추가. 이 명령은 서비스/변환/편집 writer를 중지한 비공개 원본·주석·스탬프 저장소의 파일 목록·크기·SHA-256을 외부 manifest와 비교합니다. 백업 생성·복사·복원·manifest 생성/서명·파일 수정/삭제는 수행하지 않습니다. Spring 서버, 설치 YAML 자동 import, 외부 모듈/이미지 디코더를 실행하지 않습니다.

## 실행

JDK21로 build한 실행 JAR을 사용합니다. 경로는 설치 환경에 맞게 지정하며 예시 경로를 자동 생성하지 않습니다.

~~~powershell
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --verify-backup-manifest --help
java -jar build/libs/imagegate-0.1.0-SNAPSHOT.jar --maintenance --verify-backup-manifest --originals-root D:\Recovery\originals --annotations-root D:\Recovery\annotations --stamps-root D:\Recovery\stamps --manifest D:\ProtectedBackup\inventory.tsv --manifest-sha256 TRUSTED_LOWERCASE_SHA256 --workers-stopped
~~~

--manifest-sha256은 별도 신뢰 경로에서 확보한 manifest 파일 자체의 SHA-256(소문자 64자리)입니다. 바이트를 읽어 그 값과 먼저 비교합니다. 점검 대상과 함께 변조될 수 있는 manifest에서 즉석으로 구한 해시를 신뢰 근거로 사용하지 마세요. 도구는 서명·인증서·외부 백업 시스템의 출처를 검증하지 않습니다. 보호된 백업 제품/서명/불변 저장과 운영자의 신뢰 해시 전달 절차가 별도로 필요합니다.

세 root는 서로 중첩하지 않는 기존 절대 normalized/canonical 디렉터리여야 합니다. manifest는 세 root 밖의 기존 canonical 일반 파일이어야 합니다. 링크/정션·경로 별칭은 허용하지 않습니다. 같은 저장소를 쓰는 모든 인스턴스·하위 변환기·외부 writer를 중지해야 합니다. --workers-stopped는 운영자의 확인이며 프로세스 자동 종료/중지 탐지가 아닙니다.

## manifest 계약 v1

UTF-8(BOM 없음), 탭 구분 5열, LF 또는 CRLF입니다. 헤더 뒤에 빈 줄을 넣지 않으며 마지막 줄바꿈 1개는 선택입니다. 각 행의 값은 다음과 같습니다.

~~~text
IMAGEGATE-MANIFEST	1
D	originals	.	0	-
D	annotations	.	0	-
D	stamps	.	0	-
~~~

위 예시는 세 저장소가 모두 비어 있을 때만 일치하는 최소 목록입니다. 실제 백업 제품/외부 어댑터는 모든 디렉터리와 파일을 나열해야 합니다. 표기의 열 경계는 실제 TAB 문자입니다.

| 열 | 계약 |
| --- | --- |
| type | D: 디렉터리, F: 일반 파일 |
| area | originals / annotations / stamps |
| relative | 해당 root 기준 상대 경로. root 자체는 .이며 D만 허용 |
| size | D는 0, F는 실제 바이트 크기(0~1 TiB), 부호/앞자리0 없음 |
| sha256 | D는 -, F는 실제 파일 전체 바이트의 소문자 SHA-256 64자리 |

각 영역의 root 행 3개와 모든 부모 디렉터리 행이 필수입니다. 중복 경로를 거절합니다. 상대 경로는 / 구분 최대8개 segment·전체1024자이고 각 segment는 소문자 영문/숫자/밑줄/하이픈/점 1~128자입니다. .과 .. segment, 마지막 점, Windows 예약 장치명·드라이브/UNC·역슬래시·공백·대문자는 거절합니다. 현재 해시/UUID 기반 내부 저장 경로를 대상으로 한 계약이며 일반 파일시스템 백업 포맷이 아닙니다.

기존 .quota-writer.lock / writer.lock도 파일 목록에 포함합니다(존재한다면 크기0/빈 파일 SHA-256). 백업/복원 도중 marker를 임의로 생략/생성한 경우 목록 불일치로 보고합니다. 잠금 파일을 삭제하여 실행 중 검사 제한을 우회하지 않습니다.

manifest는 최대4 MiB/10,000항목/행2048자입니다. 파싱은 해시 일치 후에 수행합니다. 목록의 이름이 실제 경로 접근을 지시하지 않으며, 실제 root부터 제한된 탐색으로 발견한 항목과 대조합니다. 파일명·경로·해시는 manifest에 포함되므로 문서 자체도 비공개로 보호해야 합니다.

## 대조와 상한

- 발견한 항목의 유형/크기를 비교하고 일치하는 일반 파일만 스트리밍 SHA-256으로 대조합니다. 예상하지 않은 파일의 본문은 열지 않습니다.
- 파일/디렉터리 및 manifest의 fileKey·크기·생성/수정 시각과 canonical 경계를 전후에 확인합니다. manifest 원시 바이트도 마지막에 다시 비교합니다.
- 기존 marker만 READ/shared로 잠그고 새 파일/폴더를 생성하지 않습니다. marker가 없는 경우에도 중지 확인이 필요하며 점검 중 marker 출현은 실패입니다.
- --max-entries 기본10,000/최대100,000: root·파일·디렉터리 방문 수입니다. --max-bytes 기본1 GiB/최대1 TiB: 파일 해시를 위한 본문 바이트 예산입니다. manifest 읽기/재읽기는 별도 고정 상한4 MiB씩입니다. 파일 성장 검출용 EOF probe는 hashedBytes에 포함하지 않습니다.
- 결과는 최대10,000항목이며, 탐색 깊이/결과/열거/해시 상한 도달은 부분 검사입니다. 부분 결과에서 누락 항목 전체를 추론하지 않습니다. 동일 중지 상태에서 충분한 상한으로 다시 검사해야 합니다.
- 파일시스템 원자 스냅샷이나 적대적 writer의 시간/내용 복원까지 탐지하는 격리 도구가 아닙니다. 전용 비공개 경로와 중지한 안정 저장소가 필수입니다.

## 결과와 종료 코드

JSON: schemaVersion, generatedAt(UTC), scanned, hashedBytes, limitReached, matched, issues, items. 각 항목은 manifestLine(1부터 세는 행 번호, 외부 추가 항목은 null)과 outcome만 포함합니다. 파일명·tenant/user·미디어UUID·메모·기대/실제 해시·절대 경로·원시 예외는 출력하지 않습니다. 행 번호도 원본 manifest와 결합하면 식별 가능하므로 보고서를 보호하세요.

| outcome | 의미 |
| --- | --- |
| MATCHED | 디렉터리 유형 또는 파일 유형/크기/SHA-256 일치 |
| MISSING_ENTRY | 완전한 탐색에서 manifest 항목이 발견되지 않음 |
| UNEXPECTED_ENTRY | 실제 항목이 manifest에 없음 |
| TYPE_MISMATCH / SIZE_MISMATCH / CHECKSUM_MISMATCH | 유형/크기/내용 불일치 |
| UNSAFE_OR_UNREADABLE | 링크/정션/특수 파일/읽을 수 없는 경계 |
| DEPTH_LIMIT | 탐색 깊이 상한(부분 검사) |

0: 명시한 manifest와 완전히 일치, 2: 잘못된 CLI 옵션, 3: 불일치/안전 경계 문제/부분 검사, 4: 기존 writer 잠금 사용 중, 5: manifest 형식/해시/접근 실패·저장소/manifest 변경·사용 불가. 전역 실패에는 JSON 성공 보고서를 출력하지 않고 고정 진단만 stderr에 기록합니다.

종료0은 신뢰 manifest와의 바이트/목록 일치입니다. 원본 카탈로그·주석 구조/현재 버전 참조·실제 이미지 디코딩/SSO·업무 ACL을 검증하지 않으므로 [원본 검사](ORIGINAL_INTEGRITY.md), [참조 검사](EDITING_REFERENCE_INSPECTION.md), [복구 인수](BACKUP_RECOVERY.md)를 함께 수행합니다. converted 캐시·운영 설정/비밀·감사 로그·OS ACL/키는 이 세 저장소 대조 대상이 아닙니다.

## 검증

테스트 전용 BackupManifestFixture가 실제 LocalMediaStore/AnnotationStore/StampStore에서 합성 원본·공유 소유 스탬프·메모·빈 주석 페이지를 만들고 writer 종료 후 manifest를 작성합니다. 생성기는 src/test 전용이며 실행 JAR/운영 API에 포함하지 않습니다. 정상 바이트 보존·빈 페이지/전체 미디어 누락·동일 크기 스탬프 변조·추가/크기/유형·악성/중복/상한/부모 누락 입력·잠금·변경·Windows 정션·CLI 상태/비노출을 검증합니다. 실제 업무 자료에는 실행하지 않습니다.

2026-10-23 검증: JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. manifest 신규16개(저장소11/CLI5), 전체 Java393개 통과(실패/오류/건너뜀0). 실제 img2tile·FFmpeg 및 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 검사 통과. 실행 JAR의 도움말·정상/추가 파일/manifest 해시 실패 종료 코드와 합성 바이트 보존·잠금 파일 미생성 확인. 테스트/fixture 클래스 실행 JAR 항목0개. 실제 업무 자료 점검/복구/삭제·UI/성능 재측정·설치 ZIP 생성은 수행하지 않았으며 distributionReady=false 유지.
