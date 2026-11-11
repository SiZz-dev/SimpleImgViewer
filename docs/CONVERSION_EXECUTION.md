# 이미지 변환 실행기

## 구현 범위

- ImageConverter SPI에 img2tile 어댑터 추가. ImageMagick 자동 대체 경로 없음.
- Spring 설정에서 명시적으로 활성화한 경우에만 어댑터 Bean 생성.
- 인증/파일 권한·제한 큐를 거쳐 보호된 미리보기 API에서 사용. 실제 계약은 PREVIEW_PIPELINE.md 참조.
- ProcessBuilder 인자 배열 직접 실행. 셸/cmd/PowerShell을 변환 경로에서 사용하지 않음.
- 요청당 새 출력 폴더, 별도 resource 복사. 외부 모듈 디렉터리는 읽기만 함.
- 프로세스 환경변수 초기화·작업별 TEMP/TMP/TMPDIR/HOME와 Windows USERPROFILE·정규 실행/작업 경로 전후 검사. EXTERNAL_PROCESS_SECURITY.md 참조.
- 동시 실행 기본 2, 허용 범위 1~8. 초과 시 BUSY 반환, 내부 무제한 대기열 없음.
- 프로세스 제한 시간 기본 60초, 양수~5분. 초과 시 프로세스와 관찰된 자식 종료 시도.
- stdout/stderr 통합 파이프를 계속 읽되 보관은 최대 8KiB, 초과 내용 폐기.
- 인터럽트 시 프로세스 정리 후 인터럽트 상태 보존. 오류 발생 후에도 실행 슬롯 반환.

## 경로와 입출력 검증

원본·변환본 경로는 imagegate.storage.originals-directory / converted-directory로 선택합니다.
상대 경로는 storage.root 기준이며 독립적인 절대 경로도 가능합니다. 아래는 기본 배치 예시입니다.

~~~text
storage-root/
  originals/          권한 검사가 끝난 원본
  derived/
    unique-job-id/    매번 새 폴더, 성공 검증 전에는 제공 금지
      resource/
      preview.png    또는 preview.jpg
      doc.inf
~~~

요청의 입력은 originals 실제 경로 아래, 출력의 기존 부모는 derived 실제 경로 아래인지 검사합니다.
출력 폴더는 이미 존재하면 실패시킵니다. 클라이언트로부터 파일 경로를 받는 API를 만들지 않습니다.
스토리지 디렉터리는 서비스 계정만 수정할 수 있어야 합니다. 경로 검사만으로 파일 교체 경합을 막을 수는 없습니다.

- 입력: 100MiB 이하, 0바이트 거부, TIFF/HEIF/JP2/codestream 헤더 후보 확인.
  이 검사는 파일 전체 유효성 판정이 아니며 최종 디코딩은 제한된 변환기에서 수행합니다.
- 페이지: API의 1 기반 페이지 번호에서 1을 빼 CLI에 전달.
- preview: 가로·세로 각각 최대 4096px. maxWidth/maxHeight는 원본 편집·반출 해상도 제한과 별개.
- 출력: 최대 32MiB, 일반 파일·실제 경로 범위 검사, PNG/JPEG 실제 포맷과 크기를 읽은 뒤 디코딩.
- 종료 코드가 0이어도 출력이 없거나 손상되었거나 유형/크기가 다르면 실패.
- 종료 코드가 실패이면 이미지가 생겨도 실패. 내부 경로·원시 명령·출력은 사용자 오류에 포함하지 않음.
- CLI는 긴 변 최대값으로 축소하고, 필요하면 JDK ImageIO로 요청한 가로·세로 경계에 맞춰 추가 축소.
  비율 유지, 확대하지 않는 추가 보정. PNG 투명도를 유지하고 JPEG는 RGB로 처리.
- doc.inf는 페이지 수 정보로 사용하지 않음. 미디어 메타데이터 추출은 후속 단계.

## 설정

기본은 비활성입니다. 다음은 개발자가 내부 사용을 명시적으로 활성화하는 예시입니다.

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Java\openjdk\jdk-21.0.2'
$env:IMAGEGATE_CONVERSION_ENABLED = 'true'
$env:IMG2TILE_EXECUTABLE = 'D:\DG_module\img2tile\img2tile.exe'
$env:IMAGEGATE_STORAGE_ROOT = 'D:\SiZz-tWorkspace\ImageGate\.runtime\media'
.\gradlew.bat bootRun
~~~

외부 변환 활성화는 공개 접근 권한을 부여하지 않습니다. 현재 미리보기 서비스의 인증·파일 권한/설치 정책과 제한 큐를 통해 실행합니다.

## 테스트

~~~powershell
# 일반 테스트: 외부 img2tile 불필요
.\gradlew.bat build

# Windows + 내부 모듈을 이용한 실제 통합 테스트 포함
.\gradlew.bat build -Pimg2tileIntegration
~~~

실제 통합 테스트는 @TempDir에서 합성 TIFF 두 페이지를 생성하고
PNG/JPG·크기·색상·페이지 선택·공백 경로·범위 오류를 확인합니다.
모듈의 실행 파일·DLL·실제 업무 자료를 소스 저장소에 넣지 않습니다.

## 운영에서 추가할 제한

이 실행기는 OS 샌드박스가 아닙니다.

- 별도 서비스 계정 또는 컨테이너/Windows Job Object 등으로 파일·네트워크·CPU·메모리·디스크 제한.
- 자식 프로세스는 주기적으로 관찰해 종료하지만, 샘플링 사이에 분리된 프로세스까지 완전 추적하는 보장은 없음.
  운영은 프로세스 그룹/Job Object 등으로 전체 트리를 통제해야 함.
- 로그 보관량은 제한되지만 native 프로그램의 출력 파일 전체 크기를 실행 중 강제 제한하는 것은 OS quota 담당.
- 현재 실패 폴더는 진단을 위해 남을 수 있음. 운영 연결 전 보존 기간·용량 상한·안전한 정리 작업 구현.
- ImageIO 후처리는 픽셀/파일 크기 제한을 적용하지만 프로세스 시간 제한은 외부 실행 구간에 적용.
  작업 전체 기한은 작업 큐/워커 단계에서 추가.
- 현재 제한 큐·중복 접수 보호·캐시·검증 후 발행·감사 이벤트를 구현했습니다. 실제 업무 파일의 형식/색상별 인수·OS 하드 격리·공유 인증/권한은 잔여입니다.
- 서버가 검증된 최종 결과만 sourceId로 제공하도록 구현. 저장소 전체를 정적 경로로 노출하지 않음.

설치 옵션의 전체 설명은 [설치 안내](INSTALLATION.md), 구조 규칙은 [패키지 관리](ARCHITECTURE.md)를 참고하세요.

공통 실행기의 구체적 환경/경로/수명 제한과 미구현 OS 경계는 [EXTERNAL_PROCESS_SECURITY.md](EXTERNAL_PROCESS_SECURITY.md)를 참고하세요.

[NATIVE_MODULE_APPROVAL.md](NATIVE_MODULE_APPROVAL.md)에 시작 시 고정 EXE/DLL/resource 기준선·새 작업 전후 내용 검사·변경 후 인스턴스 차단과 캐시 식별/운영 재승인 절차를 정의합니다. 시작 로컬 기준선은 공급자 서명/외부 bundle 승인을 대신하지 않습니다.

## 타일 모드의 별도 계약 확인

2026-10-27 설치 EXE 내장 도움말의 -otilesize/-otype_tile/-oname 옵션을 확인하고 -oscaleonly 없는 합성 TIFF 타일 실행을 검증했습니다. mipmap.dzi/mipmap_files/레벨별 PNG가 생성되지만 선언 overlap1과 실제 overlap0 경계가 다르고 orientation6은 encoded raster를 유지합니다. 기존 축소 미리보기 어댑터를 타일 제공 어댑터로 변경한 상태가 아닙니다. 검증 결과/보호된 API·캐시 설계는 [TILE_PYRAMID_CONTRACT.md](TILE_PYRAMID_CONTRACT.md)를 참고하세요.


## 타일 준비 연결 추가

Img2TileConverter는 기존 축소 미리보기 외에 TileNativeConverter의 별도 generateTiles 경로도 제공합니다. 기본 비활성 imagegate.tiles의 제한 큐에서만 사용하고, preview의 -oscaleonly와 혼용하지 않습니다. 같은 실행 슬롯/모듈 snapshot을 공유하며 -odir는 resource 작업 폴더와 분리된 output 디렉터리입니다. 원본 프로필·권한·SHA/타일 예산·재인코딩·원자 게시와 상세 설정/한계는 TILE_QUEUE.md를 따릅니다. 공개 타일 HTTP API는 아직 연결하지 않았습니다.
