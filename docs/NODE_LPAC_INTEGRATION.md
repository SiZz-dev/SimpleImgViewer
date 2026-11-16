# Node LPAC 연동 선행 검증

2026-11-13. 이 문서는 테스트 전용 Node 기본 런타임과 sharp 실제 변환의 호환성·접근 제어 결과다. 서버의 이미지 처리나 FFmpeg의 LPAC 전환 완료를 뜻하지 않는다. 현재 서버는 기존 Windows Job 수명·자원 경계를 사용하며 LPAC 선택 옵션을 노출하지 않았다.

## 확인한 조건

공식 ZIP과 전체 파일을 대조한 Node 24.22.1 실행 파일과 LICENSE를 복사해 사용한다. 작업마다 별도 AppContainer 프로필/SID와 입력·런타임·출력 복사본을 생성한다. 원본 런타임/입력 ACL, 방화벽, 서비스, 루프백 예외는 변경하지 않는다. 기존 LPAC 실행기의 Job·파일/프로필 감시와 종료 후 정리를 사용한다. 아래 기본 런타임 시험과 sharp 시험은 별도 실행·보고한다.

| 시험 | 현재 Windows 호스트의 관찰 |
| --- | --- |
| capability 0개 Node | WSAStartup 10107로 본문 진입 전 종료. TCP connect 차단 시험의 성공으로 집계하지 않음 |
| registryRead 시험 capability 1개 | Node 기동 및 아래 접근 제어 통과. internet/privateNetwork capability 없음 |
| 기본 경로 처리 | D:\\ 메타데이터 lstat가 EPERM으로 실패 |
| preserve-symlinks/main 옵션 | 루트 디렉터리 권한 추가 없이 고정 복사본의 CJS 실행 성공 |
| 입력 읽기 / 출력 쓰기 | 허용 |
| 입력 수정 / 합성 외부 파일 읽기·쓰기 | EPERM으로 거절 |
| 로드되지 않은 런타임 LICENSE 수정 | LPAC에서 거절. 동일 파일을 부모 소유자로 열면 성공해 실행 중 EXE 파일 잠금과 구분 |
| 차등 ACL canary | ALL_APPLICATION_PACKAGES 읽기 거절, ALL_RESTRICTED_APPLICATION_PACKAGES 읽기 허용 |
| 직접 IPv4 TCP 루프백 | Node net.connect가 EACCES, 별도 수신기의 연결 수 0 |
| 시험 매크로 없는 C++ 실행기 | node.exe 선택을 stage=program에서 거절 |
| 기존 capability 0 native 접근 프로브 | 입력/외부/런타임 쓰기·DACL 변경·부모 프로세스 접근 거절. 원본 ACL 불변, 잔여 lease 0 |

registryRead는 현재 호스트에서 Node 부팅을 위해 관찰된 시험 조건이다. 이를 모든 Node/Windows 조합의 보편 요구사항이나 생산 권한 승인으로 해석하지 않는다. DNS·IPv6·UDP·서비스/브로커 경유 IPC, 모든 레지스트리 쓰기 거절을 검증한 결과도 아니다. 파일 복사본의 정규 경로·reparse 거절을 부모에서 수행하는 전제로 preserve-symlinks 옵션을 사용했다. 생산 경로에 그대로 확대 적용하지 않는다.

## 실행과 증거

프로젝트에 준비된 검증 LLVM 도구를 사용하며 자동 다운로드하지 않는다. 빌드 입력 소스·헤더·빌드 스크립트와 컴파일러·EXE 해시를 기록한다. 동일 빌드 폴더를 덮어쓰지 않으며 반복 시험은 생성된 manifest로 실행한다.

```powershell
# 공식 Node ZIP/설치 바이트 검증. 다운로드가 필요한 경우에만 별도 -Download 사용.
.\scripts\install-node-runtime.ps1
# 소스 변경 후 새 검증 실행기 생성
.\scripts\build-node-lpac-probe.ps1
# 준비된 실행기로 반복 검증
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-node-lpac.mjs
# Node 시험 매크로가 없는 기존 native LPAC 경계의 회귀
.\scripts\test-node-lpac-legacy.ps1
```

주요 결과는 `build/reports/node-lpac/verification.json`과 `legacy-control.json`이다. `productionEnabled=false`, `testOnly=true`를 명시한다. 부모는 정규·단일 링크·8192바이트 이하 결과 파일과 진단 JSON을 대조한다. 자식이 종료 직전 출력한 진단이 유실될 수 있던 Peek/Wait 순서도 재검사하도록 보완했다. 기존 Node/모듈 테스트 65개, Chrome 13개 시나리오 및 React 빌드·npm 라이선스 기준선 검증은 별도 회귀 결과이며 실제 LPAC 이미지 처리 성공 건수로 합산하지 않는다.

## 다음 연동 단계

1. 아래 실제 워커 시험을 바탕으로 복수 스탬프·메모 렌더링, Node 부모 종료·실제 작업 잠금/retired 표식과의 결합을 검증한다.
2. 런타임 복사·재사용 비용과 동시 작업 용량을 측정한다.
3. [FFmpeg/FFprobe 선행 시험](FFMPEG_LPAC_INTEGRATION.md)의 결과를 바탕으로 stdout/stderr 분리·초기화 정책 및 생산 어댑터를 검증한다.
4. capability 범위·출력 검증·사용자 권한 재확인·원본 버전·중단/프로필 잔재 회수·최소 지원 OS 인수를 완료한 뒤 생산 어댑터를 연결한다. 실패 시 비격리 직접 실행으로 자동 우회하지 않는다.

공식 API 근거: [LPAC 프로세스 생성](https://learn.microsoft.com/en-us/windows/win32/secauthz/implementing-an-appcontainer), [생성 속성](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute), [프로필 제거](https://learn.microsoft.com/en-us/windows/win32/api/userenv/nf-userenv-deleteappcontainerprofile). 이는 OS 전체 우회 불가나 전체 제품 보안·배포 승인에 대한 증명은 아니다. 새 바이너리·합성 자료는 Git에 추가하지 않으며 설치 ZIP을 생성하지 않는다.

## sharp 실제 변환 선행 시험

`stage-sharp-lpac.mjs`는 설치된 sharp 0.35.5, @img/colour 1.1.0, detect-libc 2.1.2, semver 7.8.6, @img/sharp-win32-x64 0.35.5를 링크 없는 평탄한 node_modules 복사본으로 만든다. Node 실행 파일, 신뢰된 CJS 시험 워커, LICENSE/NOTICE 등을 포함해 94개 파일·114,329,596바이트다. 파일별 SHA-256·크기·패키지 라이선스·lockfile 해시를 기록하고 시험 전에 미등록 파일과 변경을 거절한다. Windows addon의 Apache-2.0 AND LGPL-3.0-or-later 조건은 계속 적용되며, 고지 보존만으로 최종 배포 의무 이행을 선언하지 않는다.

별도 `IMAGEGATE_NODE_SHARP_LPAC_PROBE` 매크로에서만 계층형 복사를 허용한다. 최대 512개 항목·합계 256MiB, 일반 파일 32MiB, Node 실행 파일 256MiB이며 정규 로컬 NTFS 경로·단일 링크·reparse 거절을 유지한다. 생산 빌드의 고정 flat inventory와 node.exe 거절은 유지한다. 신뢰된 워커를 진입점으로 실행하며 입력 이미지를 JavaScript로 실행하지 않는다. CommonJS package.json을 복사본에 두어 Node가 상위 프로젝트 package.json을 조회하다 EPERM으로 실패하던 문제를 해결했다. 원본 경로 권한을 넓히지 않았다.

```powershell
.\scripts\install-node-runtime.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/stage-sharp-lpac.mjs
.\scripts\build-sharp-lpac-probe.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-sharp-lpac.mjs
```

결과는 `build/reports/node-sharp-lpac/verification.json`이다. 합성 PNG/JPEG/WebP/GIF/TIFF 5개에서 미리보기·썸네일·자르기·회전/밝기/합성 스탬프의 출력 20개를 같은 런타임의 일반 실행과 SHA-256으로 대조했다. 모든 바이트가 같고 입력 수정·런타임 LICENSE 수정·합성 외부 파일 읽기/쓰기가 거절됐으며 원본/런타임 해시 불변·부모 소유자 쓰기 대조군·잔여 lease 0을 확인했다. 네트워크 차단은 앞의 기본 Node 별도 시험 결과이며 sharp 워커의 네트워크 시험으로 합산하지 않는다.

이 시험은 최소 변환 워커이며 생산 image-worker.mjs/job.json, 사용자 업로드 스탬프, 메모, 다중 페이지, FFmpeg, 악성 코덱 자료 전체 인수가 아니다. 각 작업에서 약 109MiB 런타임을 다시 복사하므로 측정 시간은 복사·프로필 생성·변환·검증 비용을 포함한다. 재사용·동시 작업 성능 결론을 내리지 않는다. `testOnly=true`, `productionEnabled=false`를 유지한다.

## 실제 이미지 작업 JSON·업로드 스탬프·중단/회수 후속 시험

후속 런타임에는 서버의 `backend/conversion/util/image-worker.mjs`, BMP 처리, 파일 검증, 예외 의존 파일 및 sharp ESM 진입점도 복사했다. 현재 inventory는 112개 파일·114,560,141바이트다. 원본 backend 파일과 복사본의 SHA-256을 시험 시작 시 대조하며 워커 코드를 바꿔 실행하지 않는다. 시험 진입 wrapper는 일반 모드에서 작업 JSON·입력·스탬프의 r+ 열기가 거절되는지 확인하고 실제 워커를 import한다. 일반 실행 대조군에서는 해당 ACL 시험을 생략한다.

```powershell
& '.tools/runtimes/node-24.22.1/node.exe' scripts/stage-sharp-lpac.mjs
.\scripts\build-image-job-lpac.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-image-job-lpac.mjs
```

`IMAGEGATE_NODE_SHARP_LPAC_PROBE`에서만 작업 템플릿과 단일 스탬프 복사본을 받는다. 신뢰된 시험 harness가 만든 JSON의 고정 토큰을 새 root/input 및 root/out 경로로 치환한다. 원본 이미지와 스탬프는 기존 단일 링크·reparse 거절 복사 함수를 사용하고 각각 읽기 권한을 부여한다. job.json도 파일 쓰기 권한을 주지 않는다. 이는 신뢰된 로컬 시험 CLI이며 외부 요청으로 템플릿이나 실행 파일을 선택하는 API가 아니다. out 디렉터리에는 Modify가 있으므로 job.json에 대한 r+ 거절을 디렉터리 내 삭제/재생성까지 불가능하다는 주장으로 확대하지 않는다.

독립 loopback 포트의 실제 Node 앱에 CSRF·세션을 사용해 multipart 스탬프 업로드를 요청한다. 201 응답과 저장된 정규화 PNG/조회 응답의 해시 일치를 확인한 뒤 그 저장 파일을 LPAC 입력에 복사한다. 기존 8080 서버와 업무 저장소는 사용하지 않는다.

`build/reports/node-image-job-lpac/verification.json`에 다음 8개 시나리오를 기록한다.

- 두 페이지 합성 TIFF의 1·2페이지 미리보기/썸네일과 실제 업로드 스탬프·밝기·자르기·회전 export: 일반 실행과 사실 JSON 및 출력 PNG 5개 해시 일치. 두 페이지의 출력이 서로 다른지도 확인.
- 범위 밖 페이지, 원본 호스트 입력 경로, 원본 호스트 스탬프 경로: 실패 응답과 이미지 출력 없음.
- 결정적 대기 wrapper에서 실행기 강제 종료: 실행 중 회수는 busy=1로 거절, Job 핸들 소멸 후 자식 종료, recover로 프로필 1개 회수·lease 0, 기존 root 재실행 거절.
- 벽시계 1초 초과: Job 종료·wall-timeout 거절·lease 0.

중단 시험은 실제 워커 import 직전의 동일 Node LPAC 프로세스를 기다리게 하는 방식이다. 코덱 내부의 특정 순간이나 실제 변환 중 파일 쓰기 도중 강제 종료 인수로 표시하지 않는다. recover는 보안 프로필/lease만 회수하고 job.json·ready.json 등 작업 복사본은 검사용으로 보존한다. 기존 서버의 WorkDirectories/retired·캐시 게시·사용자 권한 재확인과 결합한 생산 회수 경로는 남아 있다. 아직 생산 LPAC 활성화 옵션을 추가하지 않았으며 FFmpeg, 복수 스탬프, 메모/폰트 및 Node 부모 종료 인수도 별도 후속이다.

후속 최종 검증: 위 8개 시나리오 통과, 별도 sharp 5형식/20개 출력 비교 통과, 기본 Node LPAC/생산 guard 및 기존 native LPAC 접근 제어 회귀 통과. Node/실제 모듈 테스트 65개는 실패·건너뜀 없이 통과했고 Chrome 13개 시나리오, React production 빌드와 npm 90개 라이선스 기준선도 통과했다. 설치 ZIP은 생성하지 않았다. 다음 작업은 FFmpeg/FFprobe의 LPAC 런타임 구성과 실제 영상·음성 변환 인수다.

사용자 요청에 따라 클라이언트 IP 허용 목록 기능은 추후 과제로 보류했다. 기존 server.allowedHosts는 HTTP Host 대상 주소 검증으로 유지한다.

후속 회귀: Node/실제 모듈 65개 통과·실패/건너뜀 0, Chrome 13개 시나리오 통과, React production 빌드와 npm 90개 라이선스 기준선 통과. 현재 소스로 재빌드한 기본 Node LPAC 프로브와 생산 guard, 기존 capability 0 native 접근 프로브도 통과했다. 기본 Node capability 0의 기동 실패는 앞 표와 같이 별도 관찰로 남긴다. sharp 순차 시험의 작업별 측정값은 약 3.5~6.1초였으며 다른 회귀 작업과 함께 실행한 개발 호스트 결과로, 복사/검증까지 포함하므로 서비스 응답 속도나 순수 코덱 성능으로 해석하지 않는다.
