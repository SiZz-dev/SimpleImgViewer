# Node/sharp 계층형 런타임 재사용 시험

2026-11-13. `IMAGEGATE_SHARED_NODE_LPAC_PROBE`에서만 활성화하는 테스트 전용 구현이다. 서버 LPAC 운영 어댑터·설치 설정은 변경하지 않았다. 기존 FFmpeg flat snapshot과 별개로 Node/sharp 디렉터리 구조를 보호한다.

## 포함 범위와 실행 경계

기존에 버전·고지·lockfile·파일 해시를 확인한 Node 24.22.1/sharp 0.35.5 closure를 새 snapshot에 한 번 복사한다. 112개 파일·23개 디렉터리·114,560,141바이트다. Node 실행 파일, sharp JS/ESM/addon/DLL, 승인된 의존 패키지, LICENSE/NOTICE 및 실제 이미지 워커와 필수 util, 신뢰된 시험 진입점만 포함한다. 원본 이미지·스탬프·job JSON·설치 설정·비밀정보는 snapshot에 넣지 않는다. 원본 설치와 stage ACL은 바꾸지 않는다.

snapshot에는 관리 주체의 권한과 ALL_RESTRICTED_APPLICATION_PACKAGES의 읽기/실행만 부여한다. 다른 LPAC도 공개 코드/바이너리를 읽을 수 있는 설계이므로 승인된 비밀정보 없는 inventory로 한정한다. 작업 root/input/out은 계속 고유 SID로 분리하고 AR 권한을 주지 않는다. 기존 registryRead 시험 capability와 preserve-symlinks/main 실행 조건을 유지하며 네트워크 capability를 추가하지 않았다.

파일 상대 경로를 UTF-8 순서로 정렬해 디렉터리는 `D\t경로\n`, 파일은 `F\t경로\t크기\tSHA-256\n`로 기록한 전체 digest를 고정한다. 빈 디렉터리도 digest에 포함하므로 승인되지 않은 검색 경로를 추가하는 변경을 탐지한다. root의 고정 진입 파일, node_modules/backend 하위 허용 확장자, 최대 512개 항목·합계 256MiB, Node EXE 256MiB/일반 파일 32MiB, 정규 로컬 NTFS 경로·reparse 거절·단일 링크를 검사한다.

매 실행마다 root와 **모든 하위 디렉터리**, 모든 파일 핸들을 FILE_SHARE_READ로 유지한다. 같은 파일 핸들로 SHA-256을 계산해 부모가 고정한 digest와 대조한 뒤 프로필/작업 폴더를 생성한다. 자식 종료·출력 검증·프로필 회수까지 핸들을 유지한다. 각 동시 작업이 독립적으로 pin하므로 하나를 종료해도 다른 작업의 보호는 해제되지 않는다. 변경/미등록 파일·빈 디렉터리·하드링크를 발견하면 복사 방식으로 우회하지 않고 거절한다.

관리 주체가 유휴 상태에서 파일을 변경하는 것을 OS에서 영구 금지하지는 않는다. 다음 실행의 digest 검사로 거절한다. 동일 사용자/관리자가 실행기와 신뢰된 digest 전달까지 장악한 상황의 방어를 주장하지 않는다. 버전 게시·전체 용량 관리·회수는 이후 운영 관리자가 담당해야 한다.

## 재현

기존 `build/node-sharp-lpac/runtime.json`의 승인된 stage가 필요하다. source 변경 시 stage부터 다시 구성한다.

```powershell
.\scripts\build-shared-node-lpac.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-shared-node-lpac.mjs
.\scripts\build-stream-lpac.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-stream-lpac.mjs
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-shared-runtime-acl.mjs --node
```

소스/헤더/빌드 스크립트·고정 컴파일러·EXE 및 원본 backend 소스·lockfile 해시를 검증한다. 결과는 `build/reports/shared-node-lpac/verification.json`과 `acl.json`에 기록한다. 바이너리·합성 입력·실제 업무 자료는 저장소에 추가하지 않으며 설치 ZIP을 만들지 않는다.

## 결과

9개 재사용 시나리오가 통과했다.

- 복사/재사용을 번갈아 각 3회 실행해 실제 image-worker.mjs의 TIFF 미리보기 PNG 해시가 같음을 확인했다. 재사용 작업의 bin에는 Node/JS/모듈 복사본이 없다.
- 서로 다른 TIFF 1·2페이지의 동시 미리보기 요청이 성공하고 결과가 구분됐다.
- 독립 loopback Node 앱에 실제 multipart 스탬프 업로드(201)를 요청했다. 정규화된 저장 PNG를 작업별 복사본에 바인딩해 밝기·스탬프·자르기·회전 export가 복사 방식과 사실 JSON 및 PNG 해시까지 같았다.
- 다른 작업의 입력/출력 경로를 사용한 워커 요청을 거절했다. 공유 LICENSE/하위 JS의 r+ 열기와 실행 중 하위 디렉터리 rename을 부모 소유자에게도 거절했다.
- 두 Node 대기 gate가 모두 시작된 뒤 하나를 종료해도 다른 PID와 파일 pin이 유지됐다. 마지막 종료 후 프로필 회수·lease 0 및 부모 소유자의 LICENSE r+ 성공을 확인했다. 이는 실제 워커 import 전 결정적 대기 상태의 수명 시험이며 코덱 내부의 특정 순간 중단 인수가 아니다.
- 워커 JS 변조, 미등록 빈 디렉터리, 추가 JS, 다중 하드링크를 실행 전에 거절했다. 시험 변경을 복구한 뒤 정상 변환과 전체 snapshot 해시/구조·원본 입력/스탬프 해시 보존을 확인했다.

별도 native LPAC 프로브 5개 대조군도 통과했다. 파일 pin을 해제한 상태에서 LICENSE·하위 JS·하위 디렉터리는 읽기 성공(0), 쓰기/삭제/DACL/소유자 변경 권한 요청은 접근 거절(5)이었다. 다른 작업의 입력과 출력 JSON은 **읽기부터 모두 5**였다. 이 직접 핸들 권한 시험은 잘못된 이미지 포맷이나 워커의 사전 검증 때문에 실패한 경우와 구분해 작업별 ACL을 확인한다. 부모 소유자의 파일 r+ 열기는 허용됐고 내용을 바꾸지 않았다.

## 성능과 다음 단계

최초 snapshot 준비는 1817ms였다. 작은 합성 TIFF의 각 3회 중앙값은 복사 2760ms, 재사용 1298ms였다. 작업별 약 109MiB 런타임 쓰기가 없어졌으며 이 표본의 중앙값은 약 53% 감소했다. 입력·job/stamp 복사, 프로필 생성, 매 실행 전체 파일 해시 읽기는 남는다. 개발 호스트·OS 캐시·백신의 영향을 받는 작은 시험이므로 현재 운영 뷰어 속도 개선이나 대형 이미지 SLA·최대 처리량을 보장하는 수치가 아니다.

[버전 게시·교체·안전한 회수 관리](RUNTIME_VERSION_LIFECYCLE.md)는 후속 컴포넌트에서 구현했다. 다음 단계는 기존 저장소 소유권·작업 잠금/retired·원본 버전/권한 재확인·캐시 게시와의 결합이다. 실제 메모/폰트·복수 스탬프·Node 부모 종료 및 최소 지원 OS 인수 후 운영 LPAC 어댑터를 연결해야 한다. 미완성 설치 활성화 옵션을 추가하지 않는다.

최종 회귀는 Node/sharp 재사용 9개 및 별도 ACL 5개, 기존 이미지 작업 8개, sharp 5형식/출력 20개 비교, FFmpeg 복사/재사용 각 13개, 스트림 16개, 기본 Node LPAC/생산 guard·기존 native 접근 제어·공유 FFmpeg ACL이 통과했다. Node/실제 모듈 65개는 실패·건너뜀 없이 통과했으며 Chrome 13개 시나리오, React production 빌드와 npm 90개 라이선스 기준선도 통과했다. 별도 회귀와 개발 표본을 운영 배포 인수 완료로 합산하지 않는다.
