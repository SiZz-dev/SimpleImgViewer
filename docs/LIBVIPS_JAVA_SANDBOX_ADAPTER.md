# libvips 격리 실행기의 Java 어댑터

2026-11-12. LibvipsSandboxClient를 추가하여 Java에서 신뢰된 부모 실행기와 capability0 LPAC 워커를 호출했다. 개발 검증 단계이며 Spring ImageConverter factory에 등록하지 않았다. SDK vips.exe나 기존 v2 워커로 자동 우회하지 않는다. 설치 ZIP 생성·운영 활성화·native 재배포 승인은 포함하지 않는다.

## 실행 계약과 시작 검사

- 입력 경로 없이 부모의 --capabilities를 호출한다. launcher-v2/contained-v3,512MiB·30초 CPU·프로세스1 Job, capability0 LPAC, 출력64MiB·프로필16MiB 감시,20000ms 자식 기한의 고정 메타데이터를 정확히 대조한다. 이 호출은 프로필을 만들거나 디코더를 실행하지 않는다. 빈 시작 검사 폴더에 예상하지 않은 파일이 생겨도 거절하고 파일을 보존한다.
- 이 메타데이터는 신뢰된 설치본의 계약 확인이며 OS attestation이 아니다. 부모 EXE·contained EXE·15개 인접 DLL의 NativeModuleSnapshot 지문을 호출자가 제공한 기대값과 비교한다. 기대값이 없거나 다르면 미디어 요청 전에 거절한다. 원본/파생 저장소와 실행 파일/워커 폴더의 중첩과 링크/별칭을 거절한다.
- 워커 폴더는 기존 최소 빌드의 고정16파일만 허용한다. 공식 web SDK 전체를 이 폴더로 지정할 수 없다. 각 요청의 전후에 파일 목록과 전체 내용을 재검증하고 cacheIdentity에 부모/워커 BUILD·형식 프로필·전체 모듈 지문을 포함한다. 같은 크기·수정 시간을 복원한 DLL 변경도 거절한다.
- 이전 부모 실행기 v2에는 --capabilities가 없으므로 새 어댑터 시작 검사에 실패한다. BUILD 이름을 유지한 호환 메타데이터 확장이지만 새 EXE의 SHA는 다르며 기존 native 후보 승인 목록에 자동 편입되지 않는다. 기존 v2 워커 client/기본 결과 검증은 별도로 유지한다.

## 입력과 실행

LibvipsSandboxClient 생성자는 launcher/runtime/originals/derived의 정규 절대 경로, 신뢰 기대 지문, ProcessExecutor를 받는다. 메서드는 probe(input,newJob,page), preview(input,newJob,page,width,height,PNG|JPEG), cacheIdentity()다. API 요청의 경로나 옵션을 직접 넘기는 진입점이 아니다.

원본은 지정 originals 아래 정규 파일·classic TIFF 서명·8바이트~100MiB여야 한다. 페이지1~10000과 치수1~4096을 제한한다. 새 작업 폴더는 derived의 직계 자식이어야 하며 존재하는 폴더를 재사용하지 않는다. Java가 폴더를 먼저 만들지 않고 부모가 ACL을 적용해 bin/input/out을 생성한다. 부모가 입력/런타임의 hard link·대체 스트림을 추가 검사하고 복사본 ACL을 제한한다.

고정 부모 인자만 전달하며 shell·임의 프로그램·네트워크 capability·자원 상향 옵션이 없다. 부모가 자식을20000ms로 제한하고 Java 실행기는 부모 정리 여유를 포함해30초로 제한한다. 기존 제한 실행기의 동시 접수/BUSY/취소/시간 초과 정책을 사용한다. 부모의 작업 원장·프로필 회수·Job 생성 시 배치·부모 종료 후 자식 정리는 [생명주기 계약](LIBVIPS_PROFILE_LIFECYCLE.md)을 유지한다.

성공 시 부모 envelope와 out/result.json을 각각 검증한다. JSON 중복 키·추가 필드·잘못된 타입·뒤따르는 데이터·출력 잘림·다른 부모/워커 BUILD·작업 UUID·페이지·기하·예상 외 파일을 거절한다. LibvipsWorkerProtocol.contained()가 contained-v3 결과만 수신하며 기존 기본 생성자의 winjob-v2 검증을 완화하지 않았다. PNG/JPEG는32MiB 이내에서 기존 JDK 이미지 검증기를 사용해 실제 디코딩·치수를 다시 검사한다.

| 부모/자식 결과 | Java 분류 |
| --- | --- |
| 부모0, childExit0, 검증된 결과 | 성공 후보 |
| 부모22, childExit20/21/22/23 | INVALID_INPUT / UNSUPPORTED_PROFILE / INVALID_OUTPUT / UNAVAILABLE |
| 부모22, 기타 자식 코드 | PROCESS_FAILED |
| 부모23, wall-timeout | TIMEOUT |
| 부모23, 기타 고정 실패 envelope | UNAVAILABLE |
| 모순된 상태/잘못된 envelope·이미지 | INVALID_OUTPUT |

원시 native 진단·경로·stage 문구를 API 오류 메시지에 담지 않는다. 실패 폴더/원본을 자동 삭제하거나 결과를 게시하지 않는다.

## 실제 검증과 재현

새 실행기를 기존 설치본과 분리된 .tools/native-sandbox/java-adapter-v2에 빌드했다.

```powershell
./scripts/build-libvips-sandbox.ps1 -OutputDirectory .tools/native-sandbox/java-adapter-v2
$env:JAVA_HOME = 'C:\Program Files\Java\openjdk\jdk-21.0.2'
$env:IMAGEGATE_SANDBOX_HOME = (Resolve-Path '.tools/native-sandbox/java-adapter-v2').Path
$env:IMAGEGATE_LIBVIPS_WORKER = (Resolve-Path '.tools/native-worker/winjob-v2-verified/bin/imagegate-converter.exe').Path
$env:IMAGEGATE_JOB_PROBE = (Resolve-Path '.tools/native-resource-probe/bin/imagegate-job-probe.exe').Path
./gradlew.bat test --tests '*Libvips*' --tests '*TilePyramidValidatorContractTests' `
  --tests '*Img2TileIntegrationTests' build `
  -PlibvipsSandboxIntegration -PlibvipsIntegration -Pimg2tileIntegration --no-daemon
```

최종109개·9 suites가1분18초에 실패/오류/건너뜀0으로 통과했다. 새 합성 계약30개·실제 Java/LPAC2개와 기존77개 회귀를 포함한다. Windows unsigned32비트 충돌 코드의 PROCESS_FAILED 매핑과 범위 밖 숫자 거절도 검증했다. 실제160×96 RGB TIFF의 probe,80×48 PNG/JPEG 미리보기·대표 색상·원본 바이트/ACL 보존·정상 종료 후 lease0개와 잘못된 페이지의 INVALID_INPUT 매핑을 확인했다. 기존 native 시험은 강제 종료 후 회수·실행 중 작업 보호·파일/네트워크 격리·용량 감시·CPU/메모리·기존 img2tile 회귀를 포함한다. 네트워크 시험 토큰과 생산 capability0의 차이는 기존 문서의 제한을 유지한다.

합성 ZIP 방어11개와 Java 라이선스16규칙·63구성요소·53포함 JAR 대조도 통과했다. 전체946개·실제 FFmpeg·HTTP/브라우저는 이번 어댑터 단계에서 반복 실행하지 않았다. 결과와 소스/모듈 지문은 build/reports/libvips-java-adapter/verification.json을 따른다. 시험의 기대 지문 계산은 시험 fixture의 설치 식별이며 운영 설치를 자동 승인하는 방식이 아니다.

## 남은 연결 작업

다음은 검증된 설치 설정/승인 지문 주입과 ImageConverter 어댑터를 추가하고, 기존 사용자 파일 권한·원본 버전/SHA·캐시 키·PNG 재인코딩/원자 게시·게시 직전 권한 재검사에 연결하는 것이다. 그 전까지 libvips 모듈 선택은 계속 fail-closed다. 작동하지 않는 운영 활성화 YAML 옵션은 추가하지 않았다.

작업별 원본/런타임 복사 비용, 누적 작업 폴더의 안전 정리, 서버 전체 예산, 저장소 OS 하드 경계, 서비스 계정/대상 Windows에서의 취소·장애·성능 인수도 남아 있다.64MiB/16MiB 감시는 주기적 탐지이며 OS 디스크 quota가 아니다. TIFF의 승인 범위는 RGB8/sRGB·방향1·ICC 없음이며 HEIC/JP2/타일/편집 프로필을 추가하지 않았다. 전체 native 소스/고지/취약점·배포 검토도 별도 후속이다.
