# libvips 워커 자원 경계와 네이티브 고지 후보 검증

2026-11-11. 최소 libvips8.18.7 워커의 후속 보안 단계다. Windows 자원 제한과 고지 후보의 기술적 식별을 구현했다. 파일/네트워크 sandbox, 배포 승인, Spring 어댑터 활성화는 아직 완료하지 않았다.

## 자원 정책

후속 LPAC 부모 실행기/contained-v3는 [파일/네트워크 경계](LIBVIPS_FILE_NETWORK_BOUNDARY.md)에 별도로 기록한다. 아래 자기 소유 Job 설명은 v2이며 Java/factory가 v3로 변경되었다는 뜻이 아니다.

native/imagegate-converter/src/windows_job.h의 WindowsJob을 워커가 libvips 초기화/입력 파싱 전에 적용한다. 현재 실험 프로필은 아래 고정값이다. CLI에서 한도를 높이거나 격리를 끄는 옵션은 없다. 설치 YAML과의 예산 연결은 서버 어댑터 단계에서 검증 범위를 정한 뒤 추가한다.

| 항목 | 적용 |
| --- | --- |
| 프로세스/Job 커밋 메모리 | 각각512MiB. RSS/working set 제한을 의미하지 않음 |
| 프로세스/Job 사용자 CPU 시간 | 각각30초. 벽시계 시간/CPU 점유율 제한과 다름 |
| 활성 프로세스 수 |1. 일반 CreateProcess 자식도 동일 Job 제한 적용 |
| Job handle | 비상속·이름 없는 handle을 프로세스 종료까지 유지 |
| 종료 | KILL_ON_JOB_CLOSE 적용. 기존 Java 제한 실행기의 벽시계 timeout/취소도 유지 |
| 적용 실패 | libvips 실행 전 exit23. 제한 없는 자동 실행 금지 |
| 비Windows | 이 빌드 정책을 제공할 수 없어 exit23. 다른 정책으로 자동 우회하지 않음 |

해당 정책을 제공하는 BUILD 식별자는 imagegate-libvips-worker-winjob-v2다. protocolVersion=1의 기존14필드는 유지하되 Java parser는 새 BUILD만 받는다. 예전 v1 워커가 같은 JSON을 반환해도 거절하고, 캐시 identity에도 새 BUILD와 실행 파일/DLL fingerprint가 들어간다.

미디어를 먼저 처리한 뒤 BUILD를 검사하는 것으로 끝내지 않는다. client 생성 시5초 제한의 --capabilities를 새 전용 빈 폴더에서 실행하며 이 명령에는 원본 경로가 없다. 워커는 Job 적용 직후, libvips 초기화 전에 BUILD/자원 정책의 고정 JSON을 출력한다. 잘림/추가 출력/낡은 BUILD/다른 정책/비정상 종료/예상 밖 작업 파일을 거절한다. 설치 EXE/DLL의 snapshot은 실행 전후 검사한다. 성공한 임시 폴더는 빈 상태에서만 삭제하며 알 수 없는 파일은 따라가거나 재귀 삭제하지 않는다. 신뢰된 설치본 계약 확인이지 악성 바이너리의 거짓 응답을 판별하는 attestation은 아니다.

WindowsJob은 프로세스 종료 때까지 handle을 보유한다. 정상 C++ stack unwind에서 마지막 handle을 닫아 스스로를 조기 종료하지 않도록 의도적으로 OS process rundown에 수명을 맡긴다. 전역 서비스/관리자 Job을 수정하지 않는다. 현재 사용자 세션에서 nested Job 할당을 실제 확인했으나 배포 서비스 계정의 환경도 별도 검증해야 한다.

Microsoft 근거: [Job Objects](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects), [메모리 제한 의미](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information), [Job 할당과 nested Job](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject). Job Object를 파일/네트워크 접근 통제로 간주하지 않는다.

## 실제 시험

전용 CMake 옵션 IMAGEGATE_BUILD_RESOURCE_TESTS는 기본OFF다. 명시적 -ResourceTests 빌드에서만 tests/job_probe.cpp를 컴파일한다. probe는 생산 워커와 같은 windows_job.h를 사용하지만 별도 실행 파일이며, worker runtime16개 파일에 포함하지 않는다.

- 메모리: 제한 밖에서64MiB 커밋 성공을 먼저 확인한다. 같은 할당을32MiB Job 안에서 실행하면 거절된다. 물리 메모리64MiB를 채우는 부하 시험은 아니다.
- CPU:1초 사용자 CPU 예산 안에서 시작 marker를 기록한 후 무한 연산한다. 외부12초 timeout보다 먼저 OS가 비정상 종료시키는 것을 확인한다. setup 실패나 정상 종료를 성공으로 집계하지 않는다.
- 프로세스: 제한 밖에서는 같은 probe의 child 실행과 marker 기록이 성공한다.1프로세스 Job 안에서는 child가 marker를 쓰지 못하고 활성 프로세스는1로 유지된다. 모든 IPC/다른 시스템 서비스를 통한 우회까지 증명한 것은 아니다.
- 새 생산 워커로 기존 실제 TIFF2페이지 probe/PNG/JPEG·오류 거절·원본 보존 시험도 통과한다.

실제 실행 파일은 IMAGEGATE_LIBVIPS_WORKER, 테스트 전용 probe는 IMAGEGATE_JOB_PROBE로 지정한다. 전체848개·HTTP/Chrome은 이번 단계에서 재실행하지 않았다.

최종 시작 검사 추가 후는 protocol/시작 검사45 + 실제 TIFF3 + 자원 경계3 + 공통 validator7 + 기존 img2tile 실제4 =62개/5 suites, 실패·오류·건너뜀0(49초)이다. -PlibvipsIntegration -Pimg2tileIntegration과 위 클래스 필터를 함께 사용했다. 메모리 대조군·기존 모듈 회귀를 포함한다. build/reports/libvips-resource/verification.json에 범위를 기록한다.

최종 build -x test는23초에 성공했다. Java 라이선스16개 규칙/63개 구성요소/53개 포함 JAR 대조도 통과했으나 이는 native 라이선스 승인과 구분한다. 실제 설치 YAML/기존 모듈/서버 factory는 변경하지 않았고 설치 ZIP은 생성하지 않았다.

## 네이티브 파일과 고지 후보

config/oss/native-libvips-candidate.json은 현재 BUILD v2 런타임의 EXE/DLL16개 SHA/크기를11개 구성요소에 연결한다. 대표 라이선스는 식별용이며, 내장/전이 코드의 별도 조건을 덮어쓰지 않는다. 공식 고지13개 원문을 URL 또는 검증한 로컬 소스에 연결하고 SHA로 고정했다.

- worker: 프로젝트 BSD-3-Clause.
- libvips/GLib: LGPL 원문. 해당 소스·수정/빌드·라이브러리 교체/고지 조건 검토는 별도다.
- LLVM runtime: LLVM 예외 포함 원문. libc++/libc++abi/libunwind 내부 코드·MinGW startup의 추가 조건은 남아 있다.
- MozJPEG: LICENSE.md와 README.ijg를 함께 수집했다. 단일 BSD 라이선스로 축약하지 않는다.
- WebP/SharpYUV: COPYING과 PATENTS를 함께 수집했다. 특허 전체 검토 완료를 뜻하지 않는다.
- Expat/libffi/libpng/TIFF/zlib-ng: 버전별 원문과 원본 해시를 연결했다.
- GLib의 내장 PCRE2/GVDB 등 별도 고지와 전체 중첩 코드 식별, 모든 NOTICE/저작권 전문, 취약점/특허 검토는 미완료다.

원문 수집 위치는 build/reports/native-libvips-licenses/texts이며 설치 고지 묶음/ZIP으로 배포하지 않는다. 원문 URL·고정 해시는 후보 JSON을 따른다. 원문 수집/해시 일치는 법적 승인이나 supply-chain 서명 검증이 아니다.

```powershell
./scripts/verify-native-libvips.ps1 `
  -RuntimeDirectory .tools/native-worker/winjob-v2-verified/bin -Collect
./scripts/verify-native-libvips.ps1 `
  -RuntimeDirectory .tools/native-worker/winjob-v2-verified/bin
./scripts/test-native-libvips-policy.ps1 `
  -RuntimeDirectory .tools/native-worker/winjob-v2-verified/bin
```

- -Collect는 부족한 고지 파일을 명시적으로 수집한다. 기존 파일을 바꿔 승인하지 않는다. 수집 후에도 고정 SHA를 검사한다.
- 검증은 알 수 없는/누락된 파일·reparse point·크기/SHA 변경·원문 변조를 거절한다. 실패하면 이전 green report 대신 technicalMatch=false를 기록한다.
- normal/동일 크기 DLL 변조/알 수 없는 EXE/누락 DLL/고지 변조/복원 후 성공의6개 실제 파일 복사 시나리오가 통과했다. 원래 워커나 고지는 바꾸지 않았다.
- technicalMatch=true여도 licenseReviewComplete=false, distributionReady=false다. 이 스크립트는 승인 플래그를 켜는 기능이 아니며 일반 Gradle/런타임에서 자동 실행하지 않는다.
- 기존 Gradle Java 라이선스 검증63개 구성요소/53개 JAR와 구분한다. native DLL이 그 검증에 포함된 것처럼 집계하지 않는다.

## 아직 적용하지 않은 경계와 다음 작업

Job Object는 서비스 계정 권한을 줄이지 않는다. 디코더가 악용되면 현재 계정이 읽고 쓸 수 있는 다른 파일과 네트워크에 접근할 가능성은 남아 있다. DLL 로딩과 C/C++ 런타임 초기화도 main 이전이므로 현재 Job 적용 이전이다. 따라서 자원 제한만으로 sandbox 완료나 미신뢰 업무 파일 운영 투입을 승인하지 않는다.

현재 Job은 워커 스스로 만들고 설정 handle도 해당 프로세스가 보유한다. 따라서 임의 코드 실행 취약점에 대한 자원 정책 변조 방지 경계로 평가하지 않는다. 최종 격리 실행기에서는 신뢰된 부모가 Job 설정 권한을 소유하고, 제한 토큰/권한 경계 및 suspended 생성 후 초기화 전 배치를 함께 검토해야 한다.

다음 단계는 제한 토큰/AppContainer 또는 별도 서비스 계정·ACL/네트워크 정책 등 실행 방식의 선택·구현과 읽기 전용 입력/전용 출력 경계의 실제 거절 시험이다. 실제 설치 폴더·방화벽·서비스 계정은 이 단계에서 수정하지 않았다. 이후 검증된 경계와 원본/버전/사용자 권한·캐시 정책을 연결하며, libvips factory 선택은 그 전까지 계속 fail-closed다.

현재 conversion.module=libvips는 여전히 enabled=true로 시작할 수 없다. 기존 img2tile 경로, 실제 config/imagegate.yml, 타일/편집 지원 범위는 변경하지 않았다. 설치 ZIP은 최종 기능·전체 운영 인수 완료 후에만 생성한다.
