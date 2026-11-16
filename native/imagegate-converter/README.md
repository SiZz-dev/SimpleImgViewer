# ImageGate libvips worker — experimental native build

2026-11-12: 부모 v2에 입력 없는 --capabilities 계약을 추가하고 LibvipsSandboxClient로 Java→부모→contained-v3 실제 TIFF probe/PNG/JPEG를 검증했습니다. 새 런타임은 .tools/native-sandbox/java-adapter-v2이며109개 회귀/build가 통과했습니다. 기존 Java v2 client/기본 경로·factory는 유지합니다. [Java 어댑터 계약](../../docs/LIBVIPS_JAVA_SANDBOX_ADAPTER.md)이 최신 후속이며 승인 설정·권한/캐시·게시·native 배포 검토는 남아 있습니다.

부모 실행기 최신 후속은 imagegate-lpac-launcher-v2의 [프로필 회수·저장 용량 감시](../../docs/LIBVIPS_PROFILE_LIFECYCLE.md)다. contained-v3의 capability0과 Java v2 client는 유지한다. 기본 sandbox 빌드/시험 경로는 .tools/native-sandbox/lifecycle-v2-final이며 기존 출력 폴더를 덮어쓰지 않는다. OS 하드 quota·Java/factory 연결·native 배포 승인은 완료하지 않았다.

2026-11-11 후속. **최소 libvips8.18.7과 워커를 실제 컴파일했고 합성 TIFF 실변환 시험을 통과했다. 운영/배포 승인은 미완료다.** 초기8.18.0 API 후보에서 안정판8.18.7로 변경했다. 작업 폴더의 .tools에만 포터블 도구·SDK·소스를 준비했으며 시스템 PATH/설치 설정·기존 img2tile은 변경하지 않았다. Spring factory에는 아직 연결하지 않았다.

현재 Java client가 검사하는 BUILD는 **imagegate-libvips-worker-winjob-v2**다. libvips 초기화 전에 Windows Job Object로 커밋 메모리512MiB·사용자 CPU30초·활성 프로세스1을 적용한다. Java는 원본 경로 없는 --capabilities 시작 검사로 BUILD/자원 정책을 대조한 뒤만 미디어 요청을 보낸다. 현재 검증 런타임은 .tools/native-worker/winjob-v2-verified/bin이다. 자원/공급망 검증의 정확한 범위와 잔여 작업은 [자원 경계](../../docs/LIBVIPS_RESOURCE_BOUNDARY.md)를 우선한다.

## 소스 기준과 의존성

별도 imagegate-contained-worker / imagegate-sandbox-launcher를 추가했다. 생산 capability0의 LPAC, 부모 소유 Job, 복사본 ACL을 적용하며 v2 client에 자동 연결하지 않았다. 최신 범위와 프로필 회수 잔여 항목은 [파일/네트워크 경계](../../docs/LIBVIPS_FILE_NETWORK_BOUNDARY.md)를 따른다. 기존 v2 후보 해시 정책이 새 EXE를 승인하는 것은 아니다.

- libvips **8.18.7**을 고정한다. CMake/pkg-config 및 런타임의 다른 버전은 거절한다. 실행 성공을 배포 승인이나 전체 취약점 검토 완료로 간주하지 않는다.
- `libvips-8.18.7.args`의 실제 configure에서 TIFF/JPEG/PNG/zlib 활성, modules/Magick/HEIF/OpenJPEG/직접 WebP 로더 등 선택하지 않은 외부 형식 비활성을 확인했다. 내장 범용 연산/일부 로더 코드가 모두 제거되었다는 의미는 아니다. 워커는 명시적 TIFF loader만 호출하며 임의 operation/파일명 옵션을 받지 않는다.
- [build-inputs.json](build-inputs.json)에 공식 다운로드 URL·태그/commit·관측/게시 해시를 구분하여 기록했다. LLVM-MinGW20261104/clang23.1.5, Meson1.12.1, CMake4.4.4, Ninja1.13.2, pkgconf3.0.10을 사용했다. libvips와 pkgconf는 소스 빌드하고, 코덱/GLib DLL은 공식 Windows SDK에서 가져왔다. 모든 전이 구성요소의 소스 재빌드/재배포 고지·SBOM·취약점 승인이 완료된 것은 아니다.
- 의존성 자동 다운로드, 사전 빌드 DLL 포함, ImageMagick/GraphicsMagick 연계는 없다. CMake는 설치된 `pkg-config vips=8.18.7`만 사용한다.
- wrapper의 직접 작성 소스는 루트 BSD-3-Clause LICENSE를 따른다. libvips 및 전이 의존성에는 각 라이선스가 별도로 적용된다.

공식 SDK는 vips/GLib 헤더만 제공하므로 codec 공개 헤더를 따로 준비했다. SDK와 대응하는 TIFF4.7.2, libpng1.6.58, Expat2.8.5, MozJPEG commit0826579 및 zlib 호환 API를 사용한다. TIFF public config는 현재 Windows compiler로 생성하고 JPEG public config는 SDK ABI62/8비트/Windows boolean 규약으로 생성한다. 별도 SDK DLL의 전체 빌드 설정을 재현했다는 증거는 아니다. proxy-libintl 공개 헤더는 NLS 비활성 구성용이며 새 동적 gettext provider를 추가하지 않았다.

준비된 소스·도구에 대해 실제 실행한 스크립트는 다음과 같다. PythonExecutable은 설치된 Python3 경로로 지정한다. 필요한 archive/GLib 생성 스크립트는 build-inputs.json 기준으로 별도 준비한다. prepare의 다운로드는 명시적 -Download일 때만 수행한다.

```powershell
./scripts/prepare-libvips-headers.ps1 `
  -CompilerRoot .tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64 `
  -TiffSource .tools/native-sources/libtiff-v4.7.2
./scripts/build-libvips-worker.ps1 `
  -PythonExecutable '<Python3 executable>' `
  -CompilerRoot .tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64 `
  -SdkRoot .tools/native-sdk/vips-dev-8.18 `
  -VipsSource .tools/native-sources/vips-8.18.7 `
  -PkgconfSource .tools/native-sources/pkgconf-3.0.10 `
  -EnumScript .tools/native-sources/glib-mkenums-2.90.0.py
```

최종 설치 ZIP 생성 명령이 아니다. 기본 산출물은 .tools/native-worker/bin이다. 기존 산출물을 덮어쓰지 않으며 재검증에는 -RuntimeDirectory로 .tools/native-worker 아래의 새 폴더를 지정한다. SDK의 .pc는 빌드 사본만 재배치하고, 누락된 비활성 -lintl과 zlib import 이름을 해당 SDK 규약으로 정정한다. upstream CLI/fuzzer는 빌드하지 않고 library target과 전용 worker만 빌드한다. 실제 PE import를 순회하여 필요한 DLL만 모으며 시스템 DLL/부모 PATH의 임의 파일을 복사하지 않는다.

## 실제 검증

- `test --tests '*LibvipsWorkerClientTests' --tests '*LibvipsWorkerIntegrationTests' --tests '*TilePyramidValidatorContractTests' -PlibvipsIntegration`:48개/3 suites 실패·오류·건너뜀0,50초. 이 중 실제 native3개, 합성 producer 프로토콜38개, 공통 계약7개다.
- 실제 native 시험은 한글·공백·`[page=99];`가 들어간 이름의160×96 TIFF2페이지를 사용했다. page2 probe, PNG 전체30,720픽셀(두 페이지) 일치, JPEG80×48 축소 및 대표 색상, 잘못된 page/회색 프로필/손상 TIFF 거절과 원본 SHA 보존을 확인했다. img2tile을 호출하지 않는다. 광범위한 TIFF·색상·성능·운영 보안 인수는 아니다.
- 후속 `build -x test`는27초 성공. 기존 Java 라이선스16개 규칙/63개 구성요소/53개 JAR 대조 통과이며 네이티브 DLL 라이선스 승인과는 구분한다. 전체848개 기존 회귀와 HTTP/Chrome은 이번 단계에서 재실행하지 않았다.
- private runtime은 EXE1개와 DLL15개, 합계9,684,480바이트다. TIFF DLL의 전이 의존성 때문에 WebP/SharpYUV DLL이 포함되지만 직접 WebP 입력을 지원하는 것은 아니다. Magick/HEIF/OpenJPEG/기존 img2tile DLL은 포함하지 않았다. 상세 SHA는 build/reports/libvips-native/runtime-inventory.json에 기록한다.
- 첫 재빌드에서 libvips DLL의 PE 타임스탬프4바이트 차이를 확인했다. libvips와 워커에 --no-insert-timestamp를 적용한 뒤 두 새 runtime의16개 SHA가 모두 일치했고, EXE/DLL을 강제로 다시 링크한 바이트도 일치했다. 동일 build 디렉터리의 증분/재링크 검증이며 다른 PC/clean build 재현성까지 입증하지 않는다. 최종 .tools/native-worker/deterministic-2/bin 실행 파일로 실제3개 시험을24초에 다시 통과했다. 전체48개를 반복 실행한 것은 아니다.

Gradle은 -PlibvipsIntegration 없이 libvips 태그를 제외한다. 포함할 때 실행 파일이 없으면 실패하며 가짜 성공/다른 엔진 우회는 없다. IMAGEGATE_LIBVIPS_WORKER로 시험 실행 파일을 지정할 수 있다. 실행 파일/DLL/표본은 저장소에 커밋하지 않는다.

## worker protocol v1

미디어 요청 전의 --capabilities는 경로·입력 없이 호출하고, Job 적용 후 libvips 초기화 전 고정 BUILD/정책 JSON만 stdout에 출력한다. 이때 출력 잘림·추가 진단·낡은 BUILD·다른 자원 정책·작업 파일 생성·비정상 종료는 거절한다. 이 검사는 신뢰된 설치본의 계약 확인이며, 악성 실행 파일에 대한 OS attestation이나 코드 서명 검증은 아니다.

고정 인자 쌍9개를 사용한다. 임의 옵션/추가 키/중복 키를 거절하며 셸이나 libvips CLI의 파일명 옵션 파서를 호출하지 않는다.

```text
--protocol 1 --operation probe|preview --input <absolute-original>
--work <absolute-new-job> --page <1-based-page> --job <UUID>
--max-width <1..4096> --max-height <1..4096> --format none|png|jpeg
```

`probe`는 format=none, `preview`는 png/jpeg다. working directory는 work와 같아야 하고 시작 시 비어 있어야 한다. Java가 승인된 원본 경로와 새 전용 폴더를 생성하고 권한·버전·최종 게시를 소유한다. 원본100MiB/40MP, 페이지10000, 미리보기32MiB를 초기 한도로 둔다. OS 메모리/CPU/디스크 강제 제한을 대신하지 않는다.

처음에는 classic TIFF, RGB UCHAR/sRGB, 방향1, ICC 없음만 허용한다. 이는 전체 TIFF/압축/색공간 지원 선언이 아니다. BigTIFF·HEIC·JP2·타일·색상/방향 보정은 아직 구현하지 않았다. probe도 libtiff 파싱을 하므로 신뢰된 합성 표본 외 운영 투입은 OS 격리 검증 이후로 제한한다.

성공 결과는 work/result.json에 저장한다. 기존 ProcessExecutor는 stdout/stderr를 합쳐 최대8192바이트 수집하므로 진단 출력은 파싱하지 않는다. 라이브러리 경고에는 경로가 포함될 수 있으며 로그/API로 그대로 반환하지 않는다. 이 결정은 기존 설계의 stdout JSON 제안을 구체화한 변경이며, 공통 실행기 동작은 바꾸지 않는다.

```json
{"protocolVersion":1,"jobId":"00000000-0000-0000-0000-000000000001","operation":"preview","engineBuildId":"imagegate-libvips-worker-winjob-v2","profileId":"tiff-rgb8-o1-v1","libvipsVersion":"8.18.7","pageCount":2,"page":1,"sourceWidth":20,"sourceHeight":10,"outputWidth":10,"outputHeight":5,"outputFile":"preview.png","outputFormat":"png"}
```

JSON 키는 위14개만 허용한다. probe는 outputWidth/Height=0, outputFile="", outputFormat="none"이다. 성공 폴더에는 result.json과 선택한 preview.png/preview.jpg만 존재해야 한다. 원시 descriptor/절대 출력 경로/외부 URL은 결과에 포함하지 않는다.

| 종료 코드 | 의미 | Java 분류 |
| --- | --- | --- |
| 0 | 성공 후보. JSON과 이미지 검증이 추가로 필요 | 검증 후만 성공 |
| 20 | 인자/입력/페이지/입력 한도 오류 | INVALID_INPUT |
| 21 | 승인하지 않은 이미지 프로필 | UNSUPPORTED_PROFILE |
| 22 | 변환/출력 실패 | INVALID_OUTPUT |
| 23 | 엔진/버전/금지 loader 문제 | UNAVAILABLE |
| 그 외 | 예상하지 못한 실행 실패 | PROCESS_FAILED |

timeout/BUSY/취소는 기존 제한 실행기의 분류를 유지한다. 결과가 불완전하거나 프로세스가 실패하면 게시하지 않는다. 작업 폴더의 수명·실패 정리는 호출 서비스가 소유한다. 재시도에는 새로운 작업 폴더/UUID를 사용한다.

## Java 경계와 검증의 한계

conversion/service/libvips.LibvipsWorkerClient는 아직 ImageConverter 구현이나 Spring bean이 아니다. 원본/작업/실행 경로 분리, TIFF 서명, 새 작업 폴더, 모듈 snapshot 전후 확인, 종료 코드 매핑을 담당한다. conversion/util/libvips.LibvipsWorkerProtocol은64KiB 결과 파일·엄격한 JSON/타입/작업 바인딩·페이지/치수/비율·고정 파일명을 검사한다. 미리보기는32MiB/4096치수 이내에서 JDK reader로 실제 디코딩한다. 좌표 편집 가능 판정을 하지 않는다.

현재 snapshot은 실행 파일과 인접 DLL만 식별한다. 하위 플러그인이나 별도 데이터 파일을 사용하는 설치본은 지원/승인하지 않는다. 최소 빌드 인벤토리와 엔진 identity 확장 검증이 후속 단계다. 원본 SHA/최신 파일 권한 재검사·PNG 재인코딩·원자 게시·캐시 키 연결은 기존 서비스에 연결할 때 적용해야 한다.

다음 작업은 OS 강제 자원/파일·네트워크 격리, native 공급망/고지·취약점 검토, 더 다양한 TIFF 프로필·기하 검증과 기존 ImageConverter/원본 검사/캐시 정책 연결이다. 서버 연결과 운영 활성화는 구분하며 현재는 여전히 기존 변환 경로를 유지한다.
