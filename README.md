# SimpleImgViewer

이미지·동영상·음성을 열람하고 메모·스탬프·자르기·편집본 저장을 제공하는 웹 뷰어입니다. 뷰잉 속도와 보안을 우선합니다.

**이 프로젝트는 순수 바이브코딩으로 개발을 진행하고 있습니다.**

2026-11-12 사용자 요청에 따라 기본 실행 경로를 **Node.js + React**로 전환했습니다. 새 서버는 Java나 Spring Boot에 요청을 전달하지 않으며, React 화면과 API를 한 서버에서 제공합니다. 2026-11-16 기존 Java/Gradle 소스·빌드 설정은 로컬 보관 후 제거했습니다. [제거 범위와 복원 안내](docs/LEGACY_SOURCE_REMOVAL.md)를 참고하세요. 기존 구현의 모든 운영·보안 검증을 새 스택에 그대로 적용한 상태는 아닙니다. [이관 범위와 남은 항목](docs/NODE_REACT_MIGRATION.md)을 참고하세요.

## 기술 구성

| 영역 | 구성 |
| --- | --- |
| 서버 | Node.js24, Express5, JavaScript ES modules |
| 화면 | React19, Vite8, HTML/CSS |
| 이미지 | 별도 Node 자식 프로세스의 sharp/libvips, 입력 픽셀·시간·동시 처리 제한 |
| 영상·음성 | 별도 FFmpeg/FFprobe 프로세스, 고정 EXE 해시·인접 DLL·LGPL shared 옵션 검사 |
| 설정·저장 | YAML, 서비스 계정 전용 로컬 파일 저장소, UUID 기반 경로 |
| 의존성 | 정확한 직접 버전과 pnpm lockfile 고정, LICENSE/NOTICE 식별·수집 |

새 Node 경로에는 ImageMagick 의존성·모듈 선택·자동 우회가 없습니다. 네이티브 파일·실제 업무 미디어·비밀정보·node_modules는 Git에 포함하지 않습니다. 프로젝트 소스에는 [BSD-3-Clause](LICENSE), 외부 구성요소에는 각각의 조건이 적용됩니다.

## 설치 (새 PC)

Windows x64 기준입니다. Node.js24.22.1과 pnpm11.25.0을 **프로젝트 전용**으로 `.tools`에 설치하며 시스템 Node·PATH는 바꾸지 않습니다. 시스템에 다른 버전 Node(예: 26)가 있어도 `engines`(>=24 <25)와 맞지 않으므로 사용하지 않습니다. Node25 이상에는 corepack이 기본 포함되지 않아 pnpm도 별도로 설치합니다.

`.tools`, `build`, `node_modules`, `dist`는 Git에서 제외되므로 새로 클론한 PC에서는 아래 단계를 순서대로 한 번 실행합니다. 모든 명령은 저장소 루트의 PowerShell에서 실행합니다. 다운로드는 공식 주소만 사용하며 크기·SHA-256을 대조하고 불일치 시 중단합니다.

### 1. 클론과 줄바꿈

`.gitattributes`가 모든 텍스트 파일을 LF로 체크아웃합니다. `pnpm-lock.yaml`과 네이티브 소스의 SHA-256을 라이선스 기준선·빌드 기록과 대조하므로 CRLF로 바뀌면 React 빌드가 `baseline mismatch`로 중단됩니다. 이 설정(`fcd982b`) 이전에 Windows에서 클론해 CRLF로 받은 경우 변경 사항이 없는 상태에서 다시 체크아웃합니다.

```powershell
git ls-files -z | ForEach-Object { $_ -split "`0" } | Where-Object { $_ } | ForEach-Object { Remove-Item -LiteralPath $_ }
git checkout -- .
```

### 2. Node.js와 pnpm

```powershell
# 공식 Node ZIP을 내려받아 SHA-256 대조 후 .tools/runtimes/node-24.22.1에 설치
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/install-node-runtime.ps1 -Download
# 현재 터미널에서 프로젝트 Node 사용
$env:PATH = (Resolve-Path '.tools/runtimes/node-24.22.1').Path + ';' + $env:PATH
# pnpm을 .tools/pnpm에 설치(IntelliJ 패키지 관리자 경로로도 사용)
npm install --prefix .tools/pnpm --no-save --no-package-lock --no-audit --no-fund pnpm@11.25.0
$env:PATH = (Resolve-Path '.tools/pnpm/node_modules/.bin').Path + ';' + $env:PATH
# lockfile 그대로 의존성 설치
pnpm install --frozen-lockfile
```

새 터미널을 열 때마다 두 `$env:PATH` 줄을 다시 실행합니다. pnpm 업데이트 안내가 나와도 lockfile 고정 버전인 11.25.0을 유지합니다.

### 3. LLVM-MinGW 컴파일러

네이티브 실행기 빌드에 쓰는 고정 컴파일러입니다. 빌드 스크립트는 자동으로 내려받지 않으므로 직접 설치합니다. URL과 해시는 [build-inputs.json](native/imagegate-converter/build-inputs.json)과 같습니다(약 191MB).

```powershell
$name = 'llvm-mingw-20261104-ucrt-x86_64'
$zip = ".tools/module-downloads/$name.zip"
New-Item -ItemType Directory -Force .tools/module-downloads, .tools/native-toolchain | Out-Null
Invoke-WebRequest -UseBasicParsing "https://github.com/mstorsjo/llvm-mingw/releases/download/20261104/$name.zip" -OutFile $zip
if ((Get-FileHash $zip -Algorithm SHA256).Hash -ne '552191b5754c49a5a20723276a6fc56541ea1922c4c50b7b669022333fc1b40f') { throw 'SHA-256 불일치' }
Expand-Archive $zip .tools/native-toolchain
```

`.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64/bin/x86_64-w64-mingw32-clang++.exe --version`이 clang 23.1.5를 표시하면 됩니다.

### 4. Windows Job 실행기

이미지·영상·음성 처리 프로세스를 Windows Job으로 제한하는 필수 실행기입니다. 없으면 이미지 API가 `PROCESS_JOB_CONFIGURATION_REQUIRED`로 거절합니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/build-process-job.ps1 -TestTools
```

`.tools/process-job/<소스 SHA-256>`에 빌드하고 개발 설정이 읽는 `build/process-job/launcher.json`을 만듭니다. `native/process-job/main.cpp`가 바뀌면 다시 실행합니다. 자세한 내용은 [Job 실행기](native/process-job/README.md)를 참고하세요.

### 5. FFmpeg 개발 모듈

영상·음성 재생에 필요합니다. 이미지 뷰어만 쓸 때는 생략할 수 있습니다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/install-native-modules.ps1 -Download
# 설치 후 재검증
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/install-native-modules.ps1 -VerifyOnly
```

FFmpeg8.1.3 LGPL shared와 점검용 libvips8.18.7 CLI를 `.tools/modules`에 설치하고 개발 실행이 읽는 `build/native-modules/imagegate.native.yml`을 만듭니다. 서버의 이미지 처리는 이 CLI가 아니라 고정된 sharp를 사용합니다. 자세한 내용은 [모듈 설치](docs/NATIVE_MODULE_INSTALLATION.md)를 참고하세요.

### 6. 설치 확인

```powershell
node scripts/build-web.mjs                    # 라이선스 기준선 passed:true 후 React 빌드
node --test "backend/test/**/*.test.mjs"      # 네이티브 시험 16개는 건너뜀
$env:SIV_NATIVE_TESTS = '1'; node --test "backend/test/**/*.test.mjs"; Remove-Item Env:SIV_NATIVE_TESTS   # 4·5단계까지 마친 경우 전체 실행
```

2026-11-17 새 클론에서 위 순서로 설치해 기본 129개 통과·16개 건너뜀, `SIV_NATIVE_TESTS=1` 145개 전체 통과를 확인했습니다.

### 7. IntelliJ 설정

Node.js 플러그인이 필요합니다. 설치를 마친 뒤 프로젝트를 엽니다. `.idea`는 Git에서 제외되므로 PC마다 한 번 설정합니다.

- **Settings → Languages & Frameworks → Node.js**
  - Node interpreter: `<저장소>/.tools/runtimes/node-24.22.1/node.exe` (버전 24.22.1 표시 확인)
  - Package manager: `<저장소>/.tools/pnpm/node_modules/pnpm` (기본값 `pnpm`은 PATH에 없어 동작하지 않음)
- 처음 열 때 IntelliJ가 Java 모듈·JDK를 자동으로 잡을 수 있습니다. 이 프로젝트는 Java를 사용하지 않으므로 JDK 선택 안내는 무시합니다. `.tools`, `.runtime`, `build`, `dist`는 **Mark Directory as → Excluded**로 인덱싱에서 제외하면 가볍습니다.

## 실행

IntelliJ에서는 **SimpleImgViewer** 실행 구성 하나를 사용합니다. [.run/SimpleImgViewer.run.xml](.run/SimpleImgViewer.run.xml)은 포터블 Node로 `scripts/dev.mjs`를 실행하며 React를 빌드한 뒤8080 서버 하나를 시작합니다. 네이티브 모듈 설정은 시작할 때 읽으므로 4·5단계 이후에는 실행 중인 서버를 다시 시작합니다. IDE에 예전 구성이 남으면 프로젝트를 다시 엽니다.

포터블 Node는 시스템 PATH에 자동 등록되지 않습니다. IntelliJ에서 `Specify a Node.js runtime correctly` 오류가 보이면 **Run → Edit Configurations → SimpleImgViewer → Node runtime → …**에서 프로젝트의 `.tools/runtimes/node-24.22.1/node.exe`를 선택하고 버전 `24.22.1`이 표시되는지 확인합니다. 공유 실행 XML의 `path-to-node`는 IDE가 저장한 확장자 없는 `…/node` 형태를 유지합니다. 여기에 `.exe`를 직접 덧붙이면 일부 IntelliJ 버전에서 `node.exe.exe`로 해석됩니다.

터미널에서도 다음처럼 개발 실행할 수 있습니다(설치 2단계의 `$env:PATH` 설정 후).

```powershell
node scripts/dev.mjs
```

빌드된 화면으로 일반 실행하려면 `scripts/start.ps1` 또는 `node scripts/start.mjs`를 사용합니다. 이 경로는 자동 로그인을 켜지 않습니다. POSIX 셸용 `scripts/start.sh`도 Node24를 실행하며 JAR/JDK를 사용하지 않습니다.

- 화면: [http://localhost:8080/viewer](http://localhost:8080/viewer)
- 상태 확인: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
- 화면 수정 후 개발 실행을 재시작하면 다시 빌드합니다. 별도 Vite 서버나 두 번째 포트는 사용하지 않습니다.

개발 실행은 기존 요청에 따라 모든 접속자를 동일한 개발 관리자로 처리합니다. 일반 `node backend/main.mjs` 실행은 자동 로그인을 제공하지 않고 미연계 API 접근을401로 거절합니다. 운영 SSO/파일 권한 공급자는 후속 연동 대상입니다.

## 기능

- 기본 다크·라이트 테마, 한 줄 헤더, 접이식 좌측 파일 목록, 중앙 뷰어, 우측 도구, 푸터 없음
- 이미지 확대·축소·맞춤·회전·밝기·돋보기·페이지 이동
- 이미지 클릭 위치의 메모·사용자 업로드 스탬프, 드래그 이동, 크기·내용 수정·삭제
- revision 검사, 실행 취소/다시 실행, 원본 좌표의 영역 선택·PNG 자르기와 합성 PNG 저장
- MP4/MPEG·MP3/WAV 입력, WebM/MP3 열람용 사본, 재생·일시 정지·탐색 막대·배속·음량·전체 화면
- multipart 업로드·허용 URL 수신, 비공개 미리보기·썸네일, 뷰어 세션·새로고침 복원

이미지 입력은 PNG/JPEG/GIF/TIFF/WebP와 제한된 비압축24/32비트 BMP를 처리합니다. 파일별 디코더·픽셀 제한을 적용하며 모든 TIFF·색공간·압축·애니메이션을 보장하지 않습니다. GIF는 선택 프레임을 정지 이미지로 표시합니다. HEIC/JPEG2000·고해상도 타일은 새 Node 경로에서 아직 지원 완료로 표시하지 않습니다.

## 설정·파일 경로

LPAC 격리를 선택하려면 승인된 관리형 런타임과 실행기를 준비한 뒤 YAML의 `isolation.mode: lpac`을 지정합니다. 기본값은 `windows-job`입니다. 개발 Windows x64에서 이미지 편집·미디어 재생·실패 경계와 동시 부하를 검증했으며, 현재 LPAC 최초 변환에는 추가 지연이 있습니다. [설치 조건·설정과 실측 결과](docs/LPAC_INSTALLATION_LOAD.md)를 확인하세요. 다른 Windows 환경의 운영 인수는 남아 있습니다.

관리형 런타임의 전체 무결성 검사를 유지하며 1MiB 읽기·파일2개 제한 병렬 검증을 적용했습니다. 개발 PC의 같은 시험에서 순차 대비 페이지 정보 응답 중앙값은643→595ms, Node/FFmpeg 동시 검증은1155→1008ms였습니다. [메모리 상한·실패 처리·측정 한계](docs/RUNTIME_VERIFICATION_PROFILE.md)를 참고하세요.

동시 열람과 FFmpeg 전체 검증을 겹친 포화 시험에서 일반 API p95는 약8ms였으나 캐시 페이지는 약2.95초로 지연됐습니다. [응답성 검증 결과와 다음 비교 과제](docs/RUNTIME_RESPONSIVENESS.md)에 조건과 한계를 정리했습니다.

이미 승인 런타임을 게시·활성화했다면 YAML의 `runtimeVersions.verification.mode: worker`로 검증 thread1개를 선택할 수 있습니다. 기본값은 `main`이며 LPAC·인증 선택과 독립적입니다. [설정·기동/종료·오류 응답과 실제 검증](docs/RUNTIME_VERIFIER_CONFIGURATION.md)을 확인하세요.

[config/simpleimgviewer.example.yml](config/simpleimgviewer.example.yml)을 참고해 `config/simpleimgviewer.yml`을 생성하거나 `SIMPLEIMGVIEWER_CONFIG`로 다른 파일을 지정합니다. 실제 설정은 Git에서 제외합니다. 기능 사용 여부·파일 생성 경로·업로드/픽셀/동시 처리/시간 제한·URL 허용 목록·FFmpeg 경로와 해시를 설정합니다. `--dev`는 YAML이나 사용자 헤더로 켤 수 없습니다.

개발 모드에서 별도 playback 설정이 없으면 설치 도구가 생성한 `build/native-modules/imagegate.native.yml`의 검증된 FFmpeg 경로/해시를 읽습니다. `clean`으로 이 파일을 삭제했다면 설치 도구를 다시 실행해야 합니다. sharp/libvips는 Node 의존성으로 준비되며 web SDK의 CLI를 자동 실행하지 않습니다.

새 저장소 기본값은 `.runtime/node/media`입니다. 기존 Java 저장소는 자동 변경하거나 이관하지 않습니다. 개발 TestFiles는 `.runtime/development/url-files/TestFiles`에 둡니다. URL 진입은 `/viewer#url=<인코딩된 URL>`이며 개발 허용 예시는 `http://192.168.0.86:8080/viewer/resources/TestFiles/`입니다. 다른 파일 서버는 YAML에 origin/경로를 명시합니다.

원본 본문 합계는 `storage.maxOriginalBytes`(기본 10GiB)로 제한합니다. 시작 시 `originalScanLimit`(기본 10000개) 안에서 실제 `originals/<UUID>/source` 바이트를 집계하며, 메타데이터 기록이 실패한 원본도 포함하고 자동 삭제하지 않습니다. 알 수 없는 파일·링크나 탐색 한도 초과 시에는 시작을 거절하고 점검을 요구합니다. 설정을 기존 사용량보다 낮춰도 정상 원본을 지우지 않으며 새 수신만 차단합니다.

일반 업로드와 URL 수신은 시작 전에 `upload.maxBytes` 전체를 원본 한도와 디스크 여유 공간에 예약합니다. 저장 후 실제 원본 크기를 반영하고, 입력 임시 파일 정리가 끝나면 남은 예약을 반환합니다. 스탬프 입력도 같은 디스크 예약을 사용하지만 원본 한도에는 포함하지 않습니다. 동시에 진행하는 요청은 예약을 직렬화해 같은 여유 공간을 중복 사용하지 않습니다. 남은 원본 한도가 최대 업로드 크기보다 작으면 작은 파일도 보수적으로 507 `ORIGINAL_CAPACITY_REACHED`로 거절하므로 설치 시 두 값을 함께 조정합니다. 사용자가 보내는 Content-Length만으로 예약량을 줄이지 않습니다. 디스크 검사는 `upload.minFreeBytes`와 모든 진행 중인 입력 예약량을 합산합니다. 예약은 새 입력 전송에 적용하며, 변환 출력·다른 프로그램의 쓰기까지 통제하는 OS 디스크 quota나 조직별 권한/용량 정책은 아닙니다. 임시 파일 삭제 자체가 실패하면 해당 예약은 서버를 재시작할 때까지 보수적으로 유지합니다.

뷰어 세션은 기본 30분 후 만료됩니다. 서버 시작과 새 세션 생성 시 만료된 세션 JSON을 정리하며, `sessions.cleanupEnabled`, `ttlMs`, `maxEntries`, `scanLimit`으로 사용 여부·유효 시간·생성 한도·탐색 한도를 설정합니다. 손상되거나 알 수 없는 파일은 보존하고 용량 한도에 포함합니다. 탐색 한도 초과 시 작업을 중단합니다. 원본·주석·스탬프·변환 캐시·업로드 임시 파일은 이 세션 정리 대상이 아닙니다.

업로드·URL 수신·스탬프 입력은 `multipart/transfers` 안의 UUID `.part` 파일로 추적합니다. 정상 완료·실패·연결 중단 시 쓰기 작업이 끝난 뒤 삭제하고 소유권을 해제합니다. 시작 시 저장소 잠금을 획득한 뒤 기본 1시간이 지난 잔재만 회수합니다. `temporary.recoveryEnabled`, `minAgeMs`, `scanLimit`으로 회수 여부·최소 경과 시간·탐색 한도를 설정합니다. 최근 파일·알 수 없는 이름·디렉터리/링크·기존 `multipart` 바로 아래 파일은 보존합니다.

이미지 미리보기·자르기·편집본·스탬프의 작업 파일은 `derived/image-work/<UUID>`에 둡니다. 이미지 워커가 작업 잠금을 직접 보유하므로 부모 서버 종료 후에도 살아 있는 워커의 파일은 회수하지 않습니다. 시작 시 같은 `temporary` 정책으로 오래된 작업의 고정 출력 파일만 삭제하고, 종료 상태가 기록된 `lease.sqlite`와 폴더는 보존해 늦게 재개한 워커의 재쓰기를 차단합니다. 정상 완료된 작업은 캐시로 게시하거나 작업 폴더까지 정리합니다. 알 수 없는 파일과 링크는 보존하며 재귀 삭제하지 않습니다. 회수 후 남기는 작은 잠금 메타데이터의 별도 보관 정책은 아직 미구현입니다.

이미지 워커와 FFmpeg/FFprobe는 Windows Job 전용 실행기를 거치며 부모 서버 강제 종료·실행기 강제 종료 시 함께 종료됩니다. 정상 서버 종료·시간 초과 시에는 실제 자식의 종료를 기다린 후 파일을 정리합니다. 이미지와 FFmpeg 작업은 각각 `derived/image-work/<UUID>`와 `derived/playback-work/<UUID>`에 두며 실행기와 실제 워커가 `lease.sqlite` 파일의 배타 핸들을 보유합니다. 시작 시 오래된 작업의 잠금을 획득한 경우만 `retired` 표식을 기록하고 고정 출력을 회수합니다. 표식과 잠금 파일은 남겨 늦게 시작한 실행기의 재쓰기를 거절합니다. 기존 `.playback-*`와 이전 변환 작업 폴더는 자동 삭제하지 않습니다.

완성된 이미지·재생 캐시는 `cache` 설정으로 관리합니다. 기본 한도는 2GiB, 미사용 기간은 7일입니다. 서버 시작 전 구조와 파일을 검증한 캐시만 기간 또는 최근 사용 순서에 따라 정리하고, 원본·주석·스탬프·작업 폴더·알 수 없는 파일/링크는 보존합니다. 실행 중에는 읽거나 전송하는 캐시를 삭제하지 않습니다. 새 캐시 저장은 직렬화해 남은 한도를 검사하며 초과하면 HTTP 507 `CACHE_CAPACITY_REACHED`로 거절하고 작업 출력은 정리합니다. `cache.cleanupOnStartup: false`면 시작 정리를 끄지만 새 저장의 용량 검사는 유지합니다. 공간 확보는 설정 조정 또는 서버를 중지한 상태의 점검 후 재시작으로 수행합니다. 이 한도는 정상 관리 캐시에만 적용하며 원본·임시 파일·손상/미확인 캐시를 포함한 전체 디스크·조직별 quota는 아닙니다.

이미지·영상·음성 변환 실행에는 Windows 10/Server 2016 이상 x64와 Job 실행기의 경로·SHA-256이 필요합니다. 개발 모드는 `build/process-job/launcher.json`을 읽어 현재 C++ 소스 지문과 대조하며 processing/playback에 각각 적용합니다. 일반 실행은 YAML의 `processing.jobLauncher`/`jobLauncherSha256`과 `playback.jobLauncher`/`jobLauncherSha256`을 명시합니다. 실행기 누락·변조·미지원 환경에서는 직접 워커 실행으로 우회하지 않고 차단합니다. 현재 PC에는 실행기를 빌드했습니다. 이미지 API 테스트도 준비된 Job 실행기가 필요하며 추가 네이티브 프로브/FFmpeg 시험은 SIV_NATIVE_TESTS=1로 실행합니다. 빌드 도구·고지 범위와 검증 명령은 [Job 실행기 안내](native/process-job/README.md)를 참고하세요.

FFmpeg/FFprobe의 각 호출에는 `playback.jobMemoryMiB`(기본 1024, 범위 64~4096), `jobCpuSeconds`(기본 120, 범위 1~600), `jobCpuRatePercent`(기본 100, 범위 1~100)을 적용합니다. 이미지 워커는 같은 이름의 `processing` 옵션으로 기본 512MiB·사용자 CPU 30초·CPU rate 100%를 적용하며 V8 힙 128MiB·픽셀·벽시계 제한도 유지합니다. 커밋 메모리 한도를 넘는 추가 할당은 OS가 거절하고, 누적 사용자 모드 CPU 시간을 초과한 프로세스는 OS가 주기적으로 검사해 종료합니다. CPU 시간은 대기·입출력을 포함한 벽시계 시간 제한과 다르며 종료 시점에 검사 간격만큼 지연될 수 있습니다. CPU 사용률은 머신의 프로세서 주기 전체를 기준으로 한 hard cap이며 기본 100은 별도 비율 감속을 추가하지 않습니다. 설정 실패·0·범위 초과를 우회하지 않습니다. 파일·네트워크 접근을 제한하는 LPAC 격리는 아직 연결하지 않았습니다.

LPAC은 [Node/sharp 런타임 선행 검증](docs/NODE_LPAC_INTEGRATION.md)을 추가했습니다. 기본 Node 시험에서 입력/출력 허용, 외부 파일·런타임 수정 거절과 직접 TCP 루프백 연결 거절을 확인했으며 registryRead 시험 capability가 필요했습니다. sharp JS/addon/DLL·고지를 해시 목록으로 복사하고, 별도 시험 워커에서 PNG/JPEG/WebP/GIF/TIFF 출력 20개가 일반 실행과 동일함을 검증했습니다. 후속 시험에서는 서버의 실제 이미지 워커와 작업 JSON·실제 API 업로드 스탬프를 연결해 다중 페이지·합성 출력 5개의 일치, 호스트 경로/잘못된 페이지 거절 및 실행기 종료 후 자식 종료·보안 프로필 회수를 검증합니다. FFmpeg와 실제 작업 잠금·게시/권한 검증을 결합하는 생산 어댑터는 후속 단계입니다. 현재 설치에 LPAC 활성화 옵션을 추가하지 않았습니다. 클라이언트 IP 허용 목록은 사용자 요청에 따라 추후 기능으로 보류합니다.

새 Node 서버는 내장 `node:sqlite`의 배타 트랜잭션으로 같은 로컬 저장소의 중복 실행을 차단하며, 충돌 시 `STORAGE_IN_USE`로 시작을 거절합니다. 강제 종료 시 OS가 잠금을 해제하므로 PID 파일을 지우지 않습니다. `multipart/transfers/owner.sqlite`와 관련 파일을 실행 중 삭제·교체하지 마세요. 이 잠금은 새 코드끼리 적용되므로 기존 Java/이전 Node 서버를 먼저 중지하고 전환해야 합니다. 네트워크 공유 저장소는 지원 대상으로 검증하지 않았습니다. 기존 다중 서버 운영·백업 일관성 인수는 완료되지 않았습니다.

```text
backend/
  system/{controller,config,util}       서버 조립·설정·오류
  security/service                    개발 인증·세션·CSRF
  media/{controller,service,repository,util}
  viewer/{controller,service}          bootstrap·뷰어 세션
  editing/{controller,service}         메모·스탬프·반출
  conversion/{service,util}            별도 이미지 워커
  playback/{controller,service}        FFmpeg·구간 전송
  test/                               Node API·보안·미디어 테스트
frontend/src/viewer/                   React 화면·CSS
scripts/                              실행·설치·빌드·검증
config/                               설치 예제·의존성 정책
native/                               Node에서 사용하는 Windows 격리 실행기 소스
.tools/, .runtime/, dist/              로컬 도구·데이터·산출물, Git 제외
```

## 검증과 라이선스

```powershell
$env:SIV_NATIVE_TESTS = '1'
node --test "backend/test/**/*.test.mjs"
node scripts/build-web.mjs
node scripts/audit-node-licenses.mjs
```

전체 Node·실제 네이티브 테스트140개와 기본 서버 Chrome13개 시나리오를 검증합니다. worker 선택 LPAC 서버의 Chrome6개·미디어/실패 인수는 `scripts/test-runtime-verifier-installation.mjs`를 사용합니다. 실제 브라우저 도구에는 Playwright/Chrome 경로를 지정합니다. 이전 Java 시험 수를 현재 통과 수에 합산하지 않습니다. [기존 문서의 역사적 범위](docs/LEGACY_SOURCE_REMOVAL.md)를 참고하세요.

라이선스 식별 결과는 `build/reports/node-licenses/inventory.json`과 고지 원문에 기록합니다. sharp Windows 패키지에는 Apache-2.0과 LGPL-3.0-or-later 조건이 함께 적용되고, 빌드 도구에는 MPL-2.0 구성요소도 있습니다. 원문·정확한 전이 소스·교체/소스 제공 조건과 native 취약점 검토가 남아 있으므로 `distributionReady=false`를 유지합니다. 라이선스 선언 식별을 최종 재배포 승인으로 취급하지 않습니다.

## 남은 작업

Windows Job을 기본으로 사용하며 승인된 관리형 런타임에서 YAML로 LPAC을 선택할 수 있습니다. [설치와 부하 검증](docs/LPAC_INSTALLATION_LOAD.md), [폰트](docs/LPAC_NOTE_FONT.md), [중단 작업 회수](docs/LPAC_WORK_RECOVERY.md), [전체 검증과 제한 병렬 읽기](docs/RUNTIME_VERIFICATION_PROFILE.md)를 참고하세요.

[Worker Thread·FIFO 비교](docs/RUNTIME_VERIFIER_WORKER.md)와 [명시적 YAML 선택·서버 수명 연결](docs/RUNTIME_VERIFIER_CONFIGURATION.md)을 완료했습니다. 추가 메모리와 단독 부하 지연이 있어 기본 검증은 main을 유지합니다. 다음 작업은 worker 선택 상태의 장시간 혼합 부하·종료 반복 검증입니다. HEIC/JPEG2000·고해상도 타일, 업무 표본·전용 블랙박스 데이터, 운영 인증·파일별 권한, 운영 Windows/서비스 계정 인수와 최종 배포 검토는 남아 있습니다.

최종 기능·전체 운영 검증 및 라이선스 배포 검토 전에는 설치 ZIP을 생성하지 않습니다. 상세 이력은 [Node/React 이관 기록](docs/NODE_REACT_MIGRATION.md)을 따릅니다.
