# FFmpeg Windows Job 실행기

Node 서버가 이미지 Node 워커와 FFmpeg/FFprobe를 이 실행기를 통해 시작한다. Windows 10/Server 2016 이상 x64 대상이며 프로세스 수명·커밋 메모리·사용자 CPU 시간·CPU rate 경계다. 파일·네트워크 접근을 제한하는 LPAC 격리는 포함하지 않는다.

`PROC_THREAD_ATTRIBUTE_JOB_LIST`로 CreateProcess 시점에 Job에 배정하고 suspended 상태에서 생성한다. Job에는 KILL_ON_JOB_CLOSE와 활성 프로세스 1개 제한을 설정한다. 이후 부모 파이프의 생존을 확인하고 실행을 재개한다. 직접 실행한 뒤 Job에 추가하는 우회는 없다. 파이프 확인은 최대 25ms 간격이며 OS 스케줄링 시간은 별도다. PID 재사용 여부로 다른 프로세스를 종료하지 않는다.

현재 프로토콜은 `--memory-mib N --cpu-seconds N --cpu-rate N [--work PATH] -- EXE ARGS...`이며 자원 옵션 생략을 허용하지 않는다. FFmpeg/FFprobe 설정 기본값은 1024MiB·사용자 CPU 120초·CPU rate 100%다. 메모리는 프로세스/Job 커밋 메모리 한도를 모두 설정하며 초과 할당이 실패한다. 모든 메모리 할당 실패를 즉시 프로세스 강제 종료로 해석하지 않는다. CPU 시간은 사용자 모드 누적 실행 시간이고 OS가 주기적으로 검사해 종료한다. CPU rate는 ENABLE|HARD_CAP이며 percent*100 단위로 설정한다. 기존 Node 벽시계 timeout과 별개다. SetInformationJobObject가 실패하면 프로세스를 생성하지 않는다.

이미지 작업은 processing 설정으로 기본 512MiB·사용자 CPU 30초·CPU rate 100%를 별도 적용한다. Node fork IPC 대신 private 작업 폴더의 최대 1MiB job.json을 인수로 전달하고, 실제 Node CLI 워커가 크기 제한된 JSON 결과만 stdout으로 반환한다. 메모·스탬프 자료를 명령행에 펼치지 않는다. job.json은 워커 close 이후 게시 전에 삭제하며 실패·강제 종료 잔재의 고정 삭제 목록에도 포함한다. 실제 워커도 상속된 작업 파일 핸들을 보유한다. 따라서 부모·실행기 강제 종료와 메모리 제한은 네이티브 sharp 작업 중에도 JS 이벤트 루프의 협조에 의존하지 않는다. V8 힙 128MiB·픽셀·벽시계·동시 처리 제한은 함께 유지한다. 생산 이미지 API 테스트에는 Job 실행기를 미리 빌드해야 한다.

공식 의미: [메모리·시간 한도](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_basic_limit_information), [커밋 메모리](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information), [CPU rate hard cap](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_cpu_rate_control_information).

Node의 stdin 파이프는 전용 생존 신호다. FFmpeg에는 이 파이프 대신 NUL 입력을 주고, stdout/stderr를 필요한 핸들 목록으로 상속한다. 작업 폴더를 지정한 경우 그 폴더의 기존 lease.sqlite를 공유 불가 핸들로 열고, retired 표식이 없음을 확인한 뒤 해당 핸들도 상속한다. 따라서 실행기가 먼저 죽어도 FFmpeg의 실제 종료 전에는 회수 측이 작업 잠금을 열 수 없다. Job 핸들은 상속하지 않는다. Node가 강제 종료되면 파이프 단절을 감지해 Job을 종료하고 실제 프로세스 종료를 기다린다. 실행기 자체가 강제 종료되면 마지막 Job 핸들이 닫혀 연결된 프로세스가 종료된다. 정상 종료는 원래 실행 파일의 종료 코드를 반환한다. 설정·Job 생성·핸들 설정이 실패하면 종료 코드 125로 거절한다.

공식 근거: [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects), [프로세스 생성 속성](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute).

작업 파일 핸들의 공유 불가 모드와 자식 상속 근거는 [CreateFileW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-createfilew)를 따른다. 회수 측 SQLite 연결이 이 핸들과 충돌하면 작업을 보존하며 잠금을 강제 제거하지 않는다.

## 로컬 빌드

관리형 런타임은 선택 인수 `--runtime-use <버전폴더/runtime.use>`를 `--` 앞에 전달한다. 실행 파일은 같은 버전폴더의 payload 바로 아래에 있어야 한다. 경로의 reparse·파일 다중 링크·고정 1바이트 표식을 검사한 뒤 읽기 전용 공유 핸들을 실제 자식에도 상속한다. 회수 관리자는 이 파일에 쓰기 핸들을 얻어야 하므로 실제 자식 종료 전에는 버전을 회수할 수 없다. 새 버전의 snapshot 실행 파일은 Medium 레이블로 게시한다. Native 실행기는 관리형 자식이 Low이면 suspended 상태에서 거절하며 업무 작업 폴더의 무결성/ACL을 낮추지 않는다. 기존 Low snapshot은 새 게시 도구로 다시 게시해야 한다. 이 경계는 LPAC 파일/네트워크 격리를 활성화하지 않는다.

프로젝트 루트에서 실행한다. 기존에 게시 해시를 확인해 설치한 `.tools/native-toolchain/llvm-mingw-20261104-ucrt-x86_64`를 사용한다. 도구 입력 정보는 `native/imagegate-converter/build-inputs.json`에 있다. 자동 네트워크 다운로드와 전역 PATH 변경은 없다.

```powershell
.\scripts\build-process-job.ps1 -TestTools
$env:SIV_NATIVE_TESTS='1'
& '.tools/runtimes/node-24.22.1/node.exe' --test backend/test/*.test.mjs
```

소스 SHA-256을 이름으로 한 `.tools/process-job/<SHA256>`에 빌드하고 EXE·소스·컴파일러 해시 및 PE DLL import 목록을 기록한다. 같은 결과가 있으면 기록된 바이트를 대조해 재사용하며 변경된 실행 파일을 덮어써 승인하지 않는다. 개발 설정은 `build/process-job/launcher.json`을 읽는다. 실제 설치 YAML에는 `playback.jobLauncher`와 `jobLauncherSha256`을 명시한다. 소스 변경 시 실행기를 다시 빌드한다. 실행 시마다 실행기 해시를 대조하며 변경 감지 후에는 파일을 복원해도 해당 서비스 인스턴스에서 계속 차단한다.

## 검증 및 범위

`backend/test/windows-job.test.mjs`는 다음을 실제 Windows 프로세스로 확인한다.

- 프로브의 Job 소속·KILL_ON_JOB_CLOSE·프로세스 1개 제한·NUL 입력.
- 부모 Node 강제 종료, 실행기 강제 종료, 서비스 정상 종료 후 실제 자식 종료.
- 설치된 고정 FFmpeg의 실행 경로를 확인한 뒤 부모 강제 종료 후 종료 여부.
- 한글·공백·따옴표·후행 역슬래시·빈 인수 보존, 추가 자식 생성 거절.
- 부모 파이프 단절 상태의 시작 거절, 설정 누락·해시 변조·복원 후 차단 유지.

Job 동작은 실제 설치 OS와 호스팅 환경에서 추가 인수해야 한다. 기존 Java/이전 Node 직접 실행 프로세스와 이전 작업 폴더는 이 경계를 소급 적용하지 않는다. 시작 전에 이전 서버를 중지하고 전환한다. 새 playback-work 잔재 회수와 정상 캐시의 시작 시 정리는 README의 temporary/cache 정책을 따른다. 회수 표식·잠금 메타데이터는 자동 제거하지 않는다.

프로젝트 C++ 소스는 BSD-3-Clause다. 정적으로 링크하는 LLVM/MinGW 런타임의 고지·소스 조건은 별도로 적용된다. 시스템 DLL만 import한다는 검사는 정적 링크 구성요소의 라이선스 의무가 없다는 뜻이 아니다. 기존 FFmpeg 및 전이 라이선스·고지·취약점 인수도 남아 있어 `distributionReady=false`다. EXE와 실제 설치 YAML은 Git에 추가하지 않는다. 설치 ZIP은 생성하지 않는다.
