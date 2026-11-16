# Node.js + React 이관 기록

2026-11-16 검증 전용 Worker Thread 후보 구현·비교: 고정 worker·최대2개 thread·제한 FIFO·deadline·exit 대기·참조/회수 경쟁을 검증했다. 실제 LPAC 서버의 두 차례 혼합 부하에서 thread1 페이지 p95는 약1.3초로 기본 약3.1초보다 짧았다. 메인 FIFO 대조군은 약1.8초였으며, 추가 메모리와 이미지 단독 지연 증가도 확인해 일반 서버 기본값은 유지한다. 최종 Node/네이티브130개(실패/건너뜀0), 기본 Chrome13개·React 빌드·npm90개 라이선스 기준선을 통과했다. [후보 계약·측정 한계](RUNTIME_VERIFIER_WORKER.md)를 따른다. 다음은 thread1의 명시적 설정·서버 수명 연결과 실제 편집·미디어 인수다.

현재 상태(2026-11-16): 사용자 요청으로 Spring Boot/Java/Gradle 소스·빌드·전용 도구를 해시 검증 로컬 보관 후 제거했다. 아래의 Java 소스 보존·Gradle 실행 설명은 선행 단계 기록이다. 현재 실행·설정·복원 범위는 [LEGACY_SOURCE_REMOVAL.md](LEGACY_SOURCE_REMOVAL.md)와 README를 따른다. 이 정리는 모든 기존 기능의 이관 완료나 최종 배포 승인을 의미하지 않는다.

2026-11-16 동시 열람 응답성 검증: 별도 Node HTTP 클라이언트와 monitorEventLoopDelay로 유휴·페이지3개 동시 요청·FFmpeg 전체 검증 포화 상태를 비교했다. 혼합 부하의 health/bootstrap p95는7.61/7.85ms, 캐시 본문157.48ms, 페이지2954.71ms였다. 검증 없는 유휴 기준선과 부하 중 위조 ID/source·CSRF 거절, decoder 재실행0회, 전체 proof 반복·참조 종료를 확인했다. 운영 검증 방식은 유지하며 다음은 제한된 검증 전용 Worker Thread 후보의 시험 비교다. [실측 조건·해석 한계](RUNTIME_RESPONSIVENESS.md)를 따른다.

2026-11-16 무결성 검사 구간 계측·제한 병렬 읽기: 목록/경로·파일 열기·read·SHA·폰트 비용을 분리했다. 전체 바이트 검증을 유지하고 기본 파일 병렬 수2개·버퍼 합계2MiB로 제한했다. 같은 시험의 계측 비활성 manifest 중앙값은 파일1개643ms→2개595ms, Node/FFmpeg 동시 검증은1155ms→1008ms였다. 실패한 worker와 겹친 읽기도 모두 닫힌 후 참조를 반환한다. Node/네이티브120개, Chrome13개·React 빌드·npm90개 라이선스 기준선을 통과했다. [측정 조건·선택 근거](RUNTIME_VERIFICATION_PROFILE.md)를 따르며 다음은 동시 열람 시 이벤트 루프 지연과 일반 API 응답성 검증이다.

2026-11-16 전체 런타임 무결성 검사 속도 개선: 파일 읽기를64KiB에서1MiB로 조정하고 호출당 단일 버퍼를 재사용한다. 같은 파일의 모든 바이트·SHA와 목록/경로/링크 검사를 유지하며 완료 증거 캐시는 추가하지 않았다. 실제 LPAC YAML 서버의6쌍 교차 측정에서 캐시 manifest 중앙값은775→656ms, 전체 트리 검증은683→551ms였다. 같은 크기 경계·꼬리 변조와 누락/미등록/하드링크/부분 회수를 추가 검증했다. 조건과 남은 비용은 [RUNTIME_VERIFICATION_SPEED.md](RUNTIME_VERIFICATION_SPEED.md)를 따른다.

## 2026-11-16 LPAC 설치 선택 후속

일반 application factory에서 `isolation.mode: lpac`을 명시적으로 선택할 수 있도록 연결했다. 기본값은 windows-job이며 승인된 관리형 런타임·실행기 해시·고정 프로필·메모 폰트를 검사한다. 전체 해시 검증의 전역 직렬 병목은 진행 중인 동일 버전 검증만 공유하도록 수정했고 검증/참조 수명은 유지한다. 전체 Node116개, 기본 Chrome13개, LPAC YAML 서버 Chrome6개 및 해시·프로필 거절/소유권 재확보/일반 실행401을 확인했다. React production 빌드와 npm90개 라이선스 기준선도 통과했다. 동일 관리형 런타임의 최초 변환 중앙값은 Windows Job1890ms, LPAC3863ms다. 캐시 manifest 비용과 LPAC 최초 변환 지연 개선, 배포 Windows 환경별 인수는 남아 있다. 아래의 서버 선택 미구현 서술은 각 단계의 선행 기록이며 현재 범위·측정 한계는 [LPAC_INSTALLATION_LOAD.md](LPAC_INSTALLATION_LOAD.md)를 따른다. 설치 ZIP은 생성하지 않았다.


2026-11-16 LPAC 작업 회수 후속 구현: UUID별 최소 소유 기록과 allocated/prepared/retiring 상태, 요청 해시·쓰기 장벽·무효화를 연결했다. 고정 native 잔여물 검사로 named Job/ADS/링크·파일 목록을 확인한 뒤 개별 파일만 지우며, 실제 삭제 중 SIGKILL에서 재개한다. 시작/유휴 주기와 최소 보존 시간·상한·opt-out을 적용하고 기존 미등록/불명확 항목은 보존한다. 전체 Node109개, 실제 회수8개, 이미지11개·폰트9개·미디어13개와 Chrome13개·React 빌드가 통과했다. 다음은 동시 성능·Windows 인수와 서버 선택 설정이며 [현재 계약](LPAC_WORK_RECOVERY.md)을 따른다.

2026-11-16 LPAC 한글 메모 후속 구현: 공식 고정 Noto Sans CJK KR 2.004/OFL 원문·고지를 검증해 선택 snapshot에 포함했다. 전용 Fontconfig는 snapshot만 조회하고 캐시는 작업 프로필 안에 둔다. 폰트/설정 hash·읽기 전용 pin과 미지원 프로필 거절을 유지하며 실제 메모·합성 편집 9개가 통과했다. Fontconfig 캐시 교체와 경합하던 저장 공간 검사는 동일 핸들 ADS 검사와 최대 3회 전체 목록 재검사로 수정했고 5개 회귀/600회 스캔에서 ADS·링크·용량 제한을 확인했다. 전체 Node99개, 이미지 LPAC11개, 스트림16개, Chrome13개·React 빌드가 통과했다. 다음은 실패 작업 회수·동시 성능/Windows 인수·서버 선택이며 [상세 범위](LPAC_NOTE_FONT.md)를 따른다.

2026-11-16 FFmpeg LPAC 전송 후속 구현: PlaybackService.runProfile과 별도 고정 native CLI를 연결했다. version/license/원본 probe/변환/결과 probe에 동일한 버전 참조와 읽기 전용 host/runtime 핸들을 적용한다. stdout/stderr는 미디어만 각각 1MiB, 출력은 기존 재생 계약의 256MiB로 제한하고 이미지 기본 상한은 유지한다. 공통 LpacTransport/Request 파서를 분리했으며 일반 서버 YAML 선택은 아직 제공하지 않는다. [현재 범위와 재현](LPAC_MEDIA_TRANSPORT.md)을 따른다.

2026-11-16 이미지 LPAC 전송 후속 구현: 고정 바이너리 요청과 별도 native CLI를 ImageEngine 내부 주입에 연결했다. 실제 미리보기/복수 스탬프 편집·픽셀 비교, 원본 변경 게시 거절, 읽기 전용 입력과 호스트/공유 코드 경계, 실제 부모 SIGKILL/close/시간 제한·프로필 복구의 11개 시나리오가 통과했다. 전체 Node 94개와 공통 스트림 16개, Chrome 13개, React 빌드·라이선스 기준선이 통과했다. 서버 기본 실행은 유지하며 메모/폰트·FFmpeg 전송·실패 root 회수·동시 성능/Windows 인수 후 서버 선택 옵션을 공개한다. [현재 범위](LPAC_IMAGE_TRANSPORT.md)를 따른다.

2026-11-13 LPAC 입출력 바인딩 선행 구현: 이미지 JSON의 경로 필드만 변경하고 복수 스탬프·원문 메모를 유지하는 불변 계획을 추가했다. playback/util의 고정 FFmpeg 프로필은 현재 재생 서비스와 공유하며 결과 FFprobe를 WebM/MP3로 제한한다. 바인딩 전후 실제 export 픽셀 비교를 포함한 새 시험 6개, 전체 Node 89개, Chrome 13개 시나리오, React 빌드·라이선스 기준선이 통과했다. 네이티브 전송과 수명/프로필·폰트 인수는 아직 남아 있으며 서버 실행 경계는 Windows Job이다. 상세 범위는 [작업 바인딩](LPAC_JOB_BINDINGS.md)을 따른다.

2026-11-12. 사용자가 백엔드를 포함한 Node.js + React 전환과 README의 순수 바이브코딩 개발 표기를 요청했다. 기존 JDK/Spring Boot 유지 지침보다 이 최신 요청을 우선하며 AGENTS.md도 수정했다.

## 기본 실행 경로

- Node24.22.1 / Express5.3.0 / React19.3.1 / Vite8.3.8. 직접 버전과 pnpm11.25.0 lockfile을 고정했다.
- backend/main.mjs가 Node API 서버를 시작한다. scripts/dev.mjs는 React를 빌드한 뒤 같은 서버를 개발 모드로 실행한다. Java 프록시나 별도 Vite 포트를 사용하지 않는다.
- IntelliJ의 SimpleImgViewer 실행 구성 하나를 Node.js 타입으로 바꿨다. 공식 게시 SHA-256이 일치한 포터블 Node를 .tools/runtimes/node-24.22.1에 설치했고 이 경로를 명시했다. Node.js 플러그인은 현재 PC에 설치되어 있다. 실행 구성 파일의 인터프리터 속성은 설치된 플러그인의 path-to-node 직렬화 계약과 대조했다.
- 기존 Java 서버의8080 프로세스는 종료/재시작하지 않았다. 사용자가 해당 실행을 중지하고 새 구성을 실행해야 한다. 새 서버 기동 때 기본 데이터 폴더는 .runtime/node/media이며 기존 업무 파일은 자동 이동하지 않는다.

## 이관한 기능

React는 기존 HTML을 삽입하거나 기존 DOM 스크립트를 호출하지 않는다. React 컴포넌트·상태·이벤트로 파일 목록, 확대/회전/밝기/돋보기, 클릭 메모/스탬프, 드래그 이동·크기/내용 수정·삭제, revision 기반 실행 취소/다시 실행, 원본 좌표 자르기·합성 PNG 다운로드와 video/audio 제어를 구성했다. 기본 다크/라이트, 한 줄 헤더, 좌/우 접이식 패널, 푸터 없음 구조를 유지한다.

Node API는 multipart 업로드, 미디어 메타데이터·페이지·비공개 소스/썸네일, 뷰어 세션, 허용 URL 수신, 메모/스탬프/편집 반출, FFmpeg 준비·상태·GET/HEAD 단일 Range를 제공한다. 경로와 mediaId/page/sourceId, mediaVersion/coordinateSpaceId/expectedRevision 등 주요 식별 필드를 유지하되 기존 Spring API의 모든 DTO·상태/에러·비동기 계약을 그대로 재현한 것은 아니다. 새 React 화면과 함께 검증한 API가 이 단계의 계약이다. 과거 API_CONTRACT.md의 전체 호환 승인을 의미하지 않는다.

이미지는 별도 Node 자식 프로세스에서 sharp/libvips로 처리한다. 파일 서명 검사 후 알려진 입력 형식만 디코더로 전달하며 페이지·픽셀·시간·동시 처리·출력 크기를 제한한다. BMP는 직접 작성한 BI_RGB24/32비트 제한 디코더를 사용한다. 외부 SVG 업로드는 차단한다. 메모 반출 시 SVG 텍스트는 서버가 고정 템플릿과 이스케이프된 문구로만 생성한다. 임의 SVG나 외부 참조를 요청으로 받지 않는다.

FFmpeg는 shell 없이 절대 경로로 실행한다. EXE 기대 해시, 인접 EXE/DLL 목록과 내용, ffmpeg/ffprobe 동일 빌드·LGPL shared·GPL/nonfree 비활성·필수 코덱을 검사한다. 변경을 감지하면 복원해도 재시작 전 차단한다. 입력 프로토콜/컨테이너 allowlist와 MP4 외부 데이터 참조 차단, 스트림·해상도·fps·재생시간 한도, 출력 재분석을 적용한다. 준비된 재생본은 소스 버전·최신 접근·Range를 검증한 뒤 제공한다.

## 인증·설정

자동 로그인은 scripts/dev.mjs 또는 CLI --dev에서만 켠다. YAML이나 클라이언트 사용자 헤더로 활성화하지 않는다. 개발 모드는 기존 요청대로 공유 개발 관리자를 사용한다. 일반 실행은 인증 공급자가 아직 없으므로 API401로 닫힌다. 세션 쿠키 HttpOnly/SameSite, 변경 요청 CSRF, 동일 origin 검사, API/콘텐츠 no-store·CSP·nosniff를 적용한다.

설치는 config/simpleimgviewer.example.yml을 기준으로 하며 실제 config/simpleimgviewer.yml은 Git에서 제외한다. Node용 설정은 기존 imagegate YAML과 다른 스키마다. 개발 FFmpeg 경로/해시는 기존 설치 도구의 생성 YAML을 호환해서 읽는다. 모든 이미지/영상 설치 플래그가 파일별 권한을 대신하지 않는다.

URL 수신은 명시 IP origin/디렉터리만 허용한다. DNS 이름·사용자 정보·모호한 경로·리디렉션·압축 응답을 거절하고 요청 쿠키/Authorization을 넘기지 않는다. 전체 수신 기한·바이트 한도와 고정 임시 파일 정리를 적용한다. 후속 작업으로 새 transfers 경로의 업로드 잔재 회수를 추가했으며 기존 Java의 모든 회수 대상과 호환되는 것은 아니다.

## 라이선스와 공급망

설치된 npm90패키지의 package.json과 root LICENSE/NOTICE 원문·해시, pnpm lockfile 해시를 식별했다. 고정 Windows x64 기준선이 달라지거나 미식별 조건이 있으면 React build/dev 시작을 거절한다. 감사 명령은 scripts/audit-node-licenses.mjs이며 결과는 build/reports/node-licenses에 보존한다. 다른 OS/아키텍처의 기준선은 별도 검토해야 한다.

MIT/ISC/Apache/BSD 등의 조건 외에도 Windows sharp 패키지는 Apache-2.0 AND LGPL-3.0-or-later, 빌드 도구의 Lightning CSS는 MPL-2.0을 선언한다. AND를 대체 선택 조건으로 해석하지 않는다. 실제 패키지 원문과 공식 프로젝트 근거를 확인했으며 기본 의존성 선언 식별과 전체 native 정적/전이 구성요소 배포 검토는 구분한다. 정확한 전이 소스·패치·고지·교체/소스 제공과 코덱 특허·native CVE 인수는 남아 있어 distributionReady=false다. 설치 ZIP은 만들지 않았다.

공식 근거: [React 기존 프로젝트 전환](https://react.dev/learn/add-react-to-an-existing-project), [Node 자식 프로세스](https://nodejs.org/api/child_process.html), [sharp 설치/네이티브 의존성](https://sharp.pixelplumbing.com/install/), [FFmpeg 라이선스](https://ffmpeg.org/legal.html), [MPL FAQ](https://www.mozilla.org/en-US/MPL/2.0/FAQ/), [Lightning CSS 원문](https://github.com/parcel-bundler/lightningcss/blob/master/LICENSE).

## 검증

최종 Node API/보안/실제 FFmpeg 테스트17개가 실패·건너뜀0으로 통과했다. React production 빌드, 실제 Node+Chrome11개 시나리오(이미지·메모·스탬프·이력·편집 PNG·테마·MP4 재생/탐색·WAV 음성·URL 진입·모바일), 포터블 Node의 scripts/dev.mjs 실제 기동/React200/FFmpeg 활성화까지 확인했다. npm 감사 API 조회 결과 알려진 취약점0개였으며 native 라이브러리 전체 CVE 인수를 뜻하지 않는다.

숨김 데이터 폴더의 파일 응답 기본 거절을 발견해, 검증된 UUID/페이지/소스/스탬프와 개발 TestFiles의 개별 파일만 dotfiles 허용 옵션으로 제공하도록 수정했다. 정적 dist는 dotfiles 차단을 유지한다. 테스트 저장소도 .private 경로로 바꿔 회귀를 확인했다. 실제 이미지 naturalWidth와 디코딩 결과를 검증하며 DOM 표시만으로 성공 처리하지 않는다.

자동 승인 검토는 host:0.0.0.0의 영구 설정 기록을 모든 인터페이스 노출 위험으로 거절했다. 이 설정은 적용하지 않았으며 기본127.0.0.1과 별도 루프백 시험 서버로 완료했다. LAN 공개가 필요한 경우 설치자가 명시적인 바인딩 주소/allowedHosts를 검토해야 한다. 기존8080 서버·실제 Java 저장소는 변경하지 않았다.

기존 Java946개/격리109개 결과를 Node 테스트로 합산하지 않는다. 이번 이관은 새 Node API·보안 테스트, 설치 FFmpeg의4형식 정규화·Range 시험, React production 빌드와 Java 없는 실제 Node+Chrome 시나리오로 검증한다. 상세 최종 수와 소스 지문은 build/reports/node-react-migration/verification.json을 따른다. 실행 명령:

```powershell
$env:SIV_NATIVE_TESTS='1'
node --test backend/test/*.test.mjs
node scripts/build-web.mjs
node scripts/audit-node-licenses.mjs
# Playwright와 Chrome의 개발 도구 경로를 환경 변수로 지정한 뒤 실행
node scripts/test-node-viewer.mjs
```

## 아직 이관/인수하지 않은 부분

2026-11-12 후속 작업으로 뷰어 세션의 만료 파일 정리를 추가했다. 시작 및 세션 생성 시 UUID 이름·일반 파일·내부 ID와 날짜를 확인한 만료 세션만 삭제하며, 읽기 실패·알 수 없는 파일·디렉터리는 보존한다. 정리 사용 여부·TTL·개수·탐색 상한은 설치 YAML로 설정한다. 프로세스 내부에서 정리와 생성을 직렬화해 동시 요청의 생성 한도 초과를 막는다. 이는 변환/업로드 잔재 회수나 다중 프로세스 writer 잠금을 대체하지 않는다. 추가 검증은 backend/test/sessions.test.mjs에 기록한다.

다음 후속 작업에서 업로드·URL·스탬프 입력의 임시 파일 소유권을 통합했다. 새 multipart/transfers 경로에만 적용하며, 서버 시작 때 내장 SQLite의 배타 트랜잭션을 확보한 후 최소 경과 시간을 넘긴 UUID .part 일반 파일만 회수한다. 링크/폴더/알 수 없는 파일과 기존 multipart 바로 아래 파일은 보존한다. 정리는 시작 시에만 실행하고 서버 실행 중에는 활성 파일을 삭제하는 청소 작업을 실행하지 않는다. 종료는 활성 전송의 소유권 해제를 기다린 후 저장소 잠금을 닫는다. PID 생존 여부를 근거로 잠금 파일을 강제로 제거하지 않는다. 새 Node 서버끼리 중복 실행을 거절하는 로컬 디스크 경계이며 기존 Java/이전 Node 프로세스, 네트워크 공유, 변환 자식 프로세스의 고아 작업은 포함하지 않는다. 내장 API 근거: [Node SQLite](https://nodejs.org/download/release/v24.22.1/docs/api/sqlite.html). 신규 npm 의존성은 없다.

별도 프로세스 강제 종료 후 OS 잠금 해제·잔재 회수, 살아 있는 프로세스의 오래된 전송 파일 보호, 종료 시 활성 전송 대기, 디렉터리 링크 보존, 탐색 상한, 초기화 실패 후 잠금 반환과 중단된 HTTP multipart 회수를 검증했다. 전체 Node/실제 FFmpeg 테스트 27개가 통과했고 React production 빌드 및 npm 90개 라이선스 기준선 검사를 통과했다. 구현 테스트는 backend/test/temporary-files.test.mjs 및 server.test.mjs에 있다. 기존 verification.json은 최초 이관 결과이며 이 후속 변경의 소스 지문을 증명하지 않는다.

- 이미지/미디어 실행의 LPAC 파일·네트워크 격리와 OS 디스크 예산. 이미지 Node 워커와 FFmpeg/FFprobe에 수명·메모리·사용자 CPU 시간·CPU rate Job 제한은 연결했지만 LPAC 인수 완료가 아니다.
- 기존 변환 임시 폴더·프로필 회수, 회수 후 보존한 작업 잠금 메타데이터의 수명 관리, 전체 디스크·조직별 누적 quota와 변환 출력의 용량 예약, 동시 설치·다중 서버 운영/백업 일관성. 새 Node끼리의 로컬 저장소 중복 시작 차단과 새 전송/이미지/FFmpeg 작업 출력의 잔재 회수, 새 FFmpeg Job 경계, 정상 캐시 및 원본 본문 한도와 입력 전송 예약은 구현했지만 전체 범위를 대체하지 않는다.
- HEIC/JPEG2000·고해상도 타일·다양한 방향/압축/색공간의 운영 표본 인수, 다중 트랙/전용 블랙박스 데이터.
- 기존 Java 오프라인 유지보수·감사/지표·복구/성능 도구의 Node 이관과 새 스택의 전체 운영 인수. 기존 데이터·세션·주석 저장 형식은 자동 이관하지 않는다.
- 실제 SSO/연계 파일 권한 공급자와 운영 활성화, 모든 배포 고지/전이 소스·취약점 검토.

다음 작업은 Node 네이티브 실행의 OS 강제 경계와 잔재 회수, 누적 저장소 정책을 우선한다. 개발 모드·별도 테스트 포트에서 검증한 결과를 업무 운영 준비 완료로 표시하지 않는다.

2026-11-12 이미지 변환 작업 수명 관리 후속 작업: preview/export/stamp 입력을 derived/image-work UUID 폴더로 통합했다. 이미지 워커가 SQLite 작업 잠금을 보유한 채 변환하고, 부모 IPC 단절 시 종료한다. 초기 모듈 로딩 전에 IPC가 끊긴 경우도 시작 시 확인한다. 시작 시 오래된 작업의 잠금을 획득한 경우만 종료 상태를 커밋한 뒤 고정 출력 파일을 회수한다. 잠금이 바쁜 작업, 링크·알 수 없는 파일은 보존한다. 강제 종료 잔재 회수 후에는 잠금 파일과 종료 상태를 제거하지 않아 늦게 재개한 워커가 작업을 재실행할 수 없다. 정상 작업은 워커의 close 이후 정리/게시하며 이미지/FFmpeg 서비스 종료도 자식 close를 기다리고 추가 실행을 거절한다. 프로세스 식별자만으로 임의 프로세스를 종료하지 않는다.

이 작업은 Windows Job Object/LPAC 강제 격리를 대체하지 않는다. FFmpeg의 부모 강제 종료와 이전 작업 폴더는 자동 회수 대상으로 포함하지 않았다. 살아 있는 이미지 워커 잠금 보호, 워커 강제 종료 후 회수, 늦은 실제 워커의 재쓰기 거절, 링크 보존, 초기 IPC 단절, 종료 대기·FFmpeg 시간 초과를 테스트했다. 상세 테스트는 backend/test/work-directories.test.mjs에 있다.

최종 Node/실제 FFmpeg 테스트 33개가 실패·건너뜀 없이 통과했다. 유효 PNG를 전달해도 retired 작업의 실제 이미지 워커가 출력을 재생성하지 못하는지 확인했으며, 캐시 게시 실패 후 정리 경로도 검증했다. React production 빌드·npm 90개 라이선스 기준선과 Chrome 11개 회귀 시나리오가 통과했다. 브라우저 시험의 기존 고정 200/300ms 재생 대기는 초기 재생 지연에 취약해, 제한 시간 내 실제 currentTime 진행을 기다리도록 수정했다. 브라우저 결과는 build/reports/node-react-browser/verification.json에 기록한다.

2026-11-12 FFmpeg Windows Job 연동 후속 작업: 자체 BSD-3-Clause C++ 실행기를 추가했다. Windows 10/Server 2016 이상 x64에서 PROC_THREAD_ATTRIBUTE_JOB_LIST를 통해 생성 시 Job에 배정하고, KILL_ON_JOB_CLOSE·활성 프로세스 1개 제한을 적용한다. Node와의 전용 파이프가 끊기면 Job을 종료하고 자식 종료를 기다린다. 실행기가 강제 종료돼도 마지막 Job 핸들 종료로 FFmpeg/FFprobe가 종료된다. 전용 파이프와 Job 핸들은 미디어 프로세스에 상속하지 않는다. 실행기는 소스/컴파일러/EXE 지문을 기록한 로컬 빌드로 준비했으며, 실행 때 기대 해시를 대조한다. 변경 감지 후 복원해도 해당 서비스 인스턴스에서 계속 차단한다. 누락·미지원 OS·Job 설정 오류 시 직접 실행으로 우회하지 않는다.

실제 Job 소속·NUL 입력·추가 프로세스 차단·인수 보존, 부모/실행기 강제 종료와 정상 종료, 설치된 실제 FFmpeg의 부모 강제 종료 후 종료, 변조 차단을 검증했다. 최종 전체 Node 테스트는 41개 통과·실패/건너뜀 0이며 Chrome 11개 시나리오, React production 빌드와 npm 90개 라이선스 기준선도 통과했다. 네이티브 검증에는 build-process-job.ps1 -TestTools와 SIV_NATIVE_TESTS=1이 필요하다. 일반 코어 테스트는 이 통합 시험을 건너뛴다. 실행기 소스와 범위는 native/process-job/README.md를 따른다. 정적 런타임 라이선스 인수와 LPAC·메모리/CPU 경계, FFmpeg 잔재 폴더의 회수는 여전히 남아 있다. 이번 구현으로 전체 이관·운영/배포 인수가 완료됐다고 표시하지 않는다.

2026-11-12 FFmpeg 잔재 및 정상 캐시 관리 후속 작업: 새 FFmpeg 작업을 derived/playback-work UUID 폴더로 통합하고, Job 실행기와 실제 FFmpeg/FFprobe에 lease.sqlite 공유 불가 핸들을 상속한다. 시작 시 잠금이 열린 작업은 보존하며 잠금을 획득한 오래된 작업의 고정 출력만 회수한다. SQLite 상태 커밋 전에 retired 파일을 생성하고 이미지 워커와 네이티브 실행기 모두 종료 표식을 검사해, 기록 사이에 강제 종료돼도 늦은 작업 재개를 차단한다. 실제 FFmpeg가 출력 중일 때 회수를 시도해 파일이 보존되는지, 부모 강제 종료 후 출력 회수 및 실행 재개 거절까지 검증했다. 예전 .playback-* 폴더와 회수 후의 잠금 메타데이터는 자동 제거하지 않는다.

정상 완료된 이미지/재생 캐시는 cache.cleanupOnStartup/maxBytes/maxAgeMs/scanLimit으로 관리한다. 기본값은 2GiB·미사용 7일·탐색 10000개다. 저장소 소유권을 획득한 뒤 HTTP 수신 전에 구조·파일 검사를 완료하고 만료 또는 최근 사용 순서로 정리한다. 전체 탐색 상한을 넘으면 캐시 삭제 시작 전에 거절한다. 실행 중에는 캐시를 삭제하지 않으며 새 게시를 직렬화해 측정한 정상 캐시 합계가 한도를 넘으면 507 CACHE_CAPACITY_REACHED로 거절한다. 읽는 파일·원본·주석·스탬프·미확인/손상 파일과 링크는 삭제하지 않는다. 원본·임시 파일까지 포함하는 전체 디스크 quota와 조직별 quota는 아니다. 뷰어에는 저장 공간 부족 안내와 원본 보존 안내를 추가했다.

최종 전체 Node/실제 네이티브 테스트 49개가 실패·건너뜀 없이 통과했다. Chrome 12개 회귀 시나리오에는 실제 용량 초과 507 응답·화면 안내·원본 및 기존 캐시 보존 검증을 추가했다. React production 빌드와 npm 90개 라이선스 기준선도 통과했다. cache-storage.test.mjs와 server.test.mjs의 기간/LRU/동시 게시 한도/원본 보존 시험 및 windows-job.test.mjs의 실제 FFmpeg 작업 잠금 시험이 이번 검증 범위다. 전체 이관·배포 승인은 아직 아니다.

2026-11-12 원본 본문 용량 및 입력 예약 후속 작업: storage.maxOriginalBytes 기본 10GiB와 originalScanLimit 기본 10000개를 추가했다. 저장소 소유권을 획득한 뒤 실제 source 바이트를 집계하며 record.json이 없는 고아 원본도 계수한다. 원본은 자동 삭제하지 않는다. 미확인 파일·링크 또는 탐색 상한 초과 시 시작을 거절한다. 기존 사용량이 한도를 넘으면 새 수신을 거절하되 정상 저장 파일의 열람을 막는 조건으로 사용하지 않는다.

업로드·URL 수신은 입력 시작 전에 허용 최대 크기를 원본 한도와 디스크 여유 공간에 예약하고, 스탬프 입력은 공통 디스크 예약에만 포함한다. 예약은 직렬화해 동시 요청이 같은 여유 공간을 중복 계산하지 못하게 한다. Content-Length만으로 예약량을 축소하지 않는다. 원본 rename 직후 실제 크기를 반영하므로 이후 메타데이터 기록 실패도 원본 계수에서 빠지지 않는다. 입력 임시 파일 정리가 성공한 뒤 예약을 반환하며 삭제 실패는 보수적으로 예약을 유지한다. 원본 한도가 최대 입력 크기보다 작게 남으면 작은 파일도 507로 거절하는 정책이므로 두 설정을 함께 조정한다. 이는 새 입력에 대한 애플리케이션 예약이며 변환 출력·타 프로그램 쓰기를 통제하는 OS 디스크 quota나 조직별 권한 정책은 아니다.

최종 Node/실제 모듈 테스트 58개가 실패·건너뜀 없이 통과했다. 동시 원본 예약과 원본/스탬프 간 디스크 공유 예약, 실제 API의 업로드·URL 사전 거절, 실패·연결 중단 반환, 메타데이터 실패 후 원본 계수 및 재시작 집계를 검증했다. Chrome 13개 회귀 시나리오에는 실제 원본 한도 507 안내·신규 원본 미생성·예약 반환 검증을 추가했다. React production 빌드와 npm 90개 라이선스 기준선도 통과했다. 테스트는 upload-capacity.test.mjs, server.test.mjs 및 scripts/test-node-viewer.mjs에 있다. 전체 이관·운영/배포 인수는 여전히 별도다.

2026-11-13 FFmpeg/FFprobe 자원 제한 후속 작업: 각 네이티브 호출에 프로세스/Job 커밋 메모리, 누적 사용자 모드 CPU 시간 및 CPU rate hard cap을 추가했다. playback.jobMemoryMiB 기본 1024(64~4096), jobCpuSeconds 기본 120(1~600), jobCpuRatePercent 기본 100(1~100)이다. C++ 실행기에서도 필수 옵션과 범위를 별도 검증하고, 모든 Job 제한 등록을 완료한 뒤 프로세스를 생성한다. 누락·설정 실패·잘못된 값을 직접 실행으로 우회하지 않는다. 메모리 초과는 OS의 추가 커밋 거절이며 모든 할당 실패를 즉시 강제 종료로 해석하지 않는다. 사용자 CPU 시간은 OS의 주기적 검사로 프로세스를 종료하며 별도 벽시계 timeout과 다르다. CPU rate 기본 100은 머신 전체 주기에 대한 상한으로 별도 비율 감속을 추가하지 않는다.

실제 64MiB Job에서 추가 메모리 커밋이 거절되고, OS가 CPU rate 25%를 등록한 것을 프로브로 확인했다. 실제 누적 사용자 CPU 예산 1초가 등록된 연산 프로세스가 벽시계 timeout 전에 종료되는 것도 확인했다. 최종 Node/실제 모듈 테스트 61개가 실패·건너뜀 없이 통과했고, Chrome 13개 회귀 시나리오와 React production 빌드·npm 90개 라이선스 기준선도 통과했다. 소스 변경 지문에 맞는 실행기를 새 경로로 로컬 빌드했다. 설치 옵션은 Node 설정 예제와 보존 중인 imagegate.example.yml의 Node 안내에 반영했다. 이미지 sharp 워커의 OS 메모리/CPU 경계와 LPAC 파일·네트워크 격리는 아직 연결하지 않았으며, 다음 작업은 이미지 워커에 OS 자원 제한을 연결하는 것이다. 기존 Java 소스 보존과 최종 배포 전 검증 방침은 유지한다.

2026-11-13 이미지 Node 워커 OS 자원 제한 후속 작업: 미리보기/스탬프/편집 반출을 필수 Windows Job 실행기로 연결했다. processing.jobLauncher/jobLauncherSha256과 기본 jobMemoryMiB 512, jobCpuSeconds 30, jobCpuRatePercent 100을 추가했으며 개발 모드는 동일 검증 manifest를 processing/playback에 각각 적용한다. 직접 fork IPC 경로와 자동 우회는 제거했다. 작업의 최대 1MiB JSON은 private job.json으로 전달하고 결과 stdout/stderr는 각각 8192바이트로 제한한다. 메모 내용을 명령행으로 넘기지 않으며 job.json은 실제 프로세스 close 이후 삭제하고 게시 전에 제거한다. 실패·강제 종료 잔재 회수 대상에도 포함했다. 실행기와 실제 Node 워커는 작업 잠금 핸들을 보유하므로 JS 이벤트 루프가 네이티브 처리 중이어도 부모 종료 시 OS Job 경계가 적용된다. 기존 V8 힙·픽셀·벽시계·동시 처리 제한은 유지한다.

실제 유효 4000x4000 BMP는 64MiB 제한에서 거절되고 512MiB에서 정상 변환됐다. 실행 중 종료 시 프로세스 종료 대기와 job.json 삭제, 실제 이미지 워커가 입력 대기 중일 때 부모를 강제 종료한 후 워커 종료·작업 잠금 해제·manifest 회수, 실행기 누락/잘못된 한도 차단을 검증했다. 최종 전체 Node/실제 모듈 테스트는 65개 통과·실패/건너뜀 0이며 Chrome 13개 회귀 시나리오·React production 빌드·npm 90개 라이선스 기준선도 통과했다. 이미지 API 테스트에는 빌드한 Job 실행기가 필요하다. 테스트는 image-job.test.mjs 및 work-directories.test.mjs에 기록했다. LPAC 파일·네트워크 격리와 전체 이관/배포 인수는 아직 남아 있으며 다음 작업은 해당 격리 경계의 연동이다.

2026-11-13 LPAC 선행 검증: Node 기본 런타임을 복사한 별도 LPAC 시험 실행기를 추가했다. capability 0개에서는 현재 호스트의 Node 24.22.1이 WSAStartup 10107로 본문 전 종료됐다. registryRead 시험 capability 1개와 preserve-symlinks/main 옵션에서는 루트 ACL을 넓히지 않고 실행됐다. 입력 읽기·출력 쓰기는 허용하고 입력 수정·외부 파일 읽기/쓰기·로드되지 않은 런타임 LICENSE 수정과 ALL_APPLICATION_PACKAGES canary 읽기는 거절했다. ALL_RESTRICTED_APPLICATION_PACKAGES canary는 읽혔다. 직접 IPv4 TCP 루프백은 EACCES와 수신 연결 0건을 확인했다. 동일 unloaded 파일을 부모 소유자로 열면 쓰기 접근이 성공해 로드된 EXE/DLL 잠금과 구분했다.

시험 매크로가 없는 실행기는 node.exe 선택을 거절했고, 새로 컴파일한 기존 capability 0 native 접근 프로브는 원본 ACL 불변·접근 제어·lease 정리를 통과했다. 종료 직전 진단을 놓칠 수 있던 Peek/Wait 순서도 종료 후 재확인하도록 보완했다. 결과 파일과 진단을 대조하며, 입력 소스/헤더/빌드 스크립트·컴파일러·EXE 지문 및 Node LICENSE를 보존한다. 기본 Node/실제 모듈 테스트 65개, Chrome 13개 시나리오, React 빌드와 npm 라이선스 기준선도 통과했다. 이 회귀 수를 실제 LPAC 이미지 변환 성공 건수로 합산하지 않는다.

기본 Node 결과는 testOnly=true/productionEnabled=false이며 생산 경로·설치 활성화 옵션을 추가하지 않았다. 상세 재현·한계는 docs/NODE_LPAC_INTEGRATION.md와 build/reports/node-lpac/verification.json, legacy-control.json을 따른다.

2026-11-13 sharp LPAC 후속 시험: JS/addon/DLL과 고지를 94개 파일의 해시 inventory로 구성했다. 고정 CJS 워커와 복사본 package.json으로 상위 프로젝트 경로 조회 없이 sharp 0.35.5/libvips 8.18.7을 실행했다. 합성 PNG/JPEG/WebP/GIF/TIFF의 미리보기·썸네일·자르기·회전/밝기/합성 스탬프 출력 20개가 일반 실행과 바이트 단위로 같았다. 입력/런타임 수정·외부 파일 읽기/쓰기 거절, 원본/런타임 해시 보존, 부모 소유자 쓰기 대조군 및 lease 0을 확인했다. 파일·링크·경로·전체 크기를 제한하는 계층형 copier는 시험 매크로에만 존재한다. 생산 서버는 변경하지 않았다. 다음 작업은 생산 job JSON과 업로드 스탬프 바인딩 및 다중 페이지·중단/회수 인수이며, FFmpeg LPAC 및 런타임 재사용/동시 작업 성능도 남아 있다. 클라이언트 IP 허용 목록은 사용자 요청에 따라 추후 기능으로 보류했다.

2026-11-13 실제 이미지 워커 LPAC 인수: ESM 의존 파일까지 포함한 112개 파일 inventory를 구성했다. 실제 multipart API로 업로드·정규화한 스탬프 저장 파일과 작업 JSON을 격리 복사본에 바인딩했다. 서버 image-worker.mjs를 그대로 import해 TIFF 1·2페이지와 편집 export 출력 5개 및 사실 JSON이 일반 실행과 동일함을 확인한다. 잘못된 페이지·호스트 입력/스탬프 경로는 거절한다. 시험 진입점에서 manifest/input/stamp 쓰기 열기 거절을 확인하며, 결정적 대기 상태의 실행기 종료 후 자식 종료·보안 프로필/lease 회수·기존 root 재실행 거절과 벽시계 timeout을 별도로 검증한다. 작업 복사본은 임의 삭제하지 않고 보존한다. 생산 경로·설정은 변경하지 않았으며 다음 단계는 FFmpeg/FFprobe LPAC 실제 재생 변환 검증이다. 복수 스탬프·메모/폰트·Node 부모 종료·기존 작업 잠금/retired·캐시 게시/권한과의 결합 및 성능 인수는 이후 생산 연결 전에 완료해야 한다. 재현과 한계는 docs/NODE_LPAC_INTEGRATION.md를 따른다.

2026-11-13 FFmpeg/FFprobe LPAC 후속 시험: 고정 배포 ZIP과 전체 설치 파일을 재대조하고 EXE 2개·DLL 7개·LICENSE의 10개 파일 runtime을 만들었다. capability 0개로 MP4/MPEG/MP3/WAV를 probe/변환/재probe했고, 일반 실행과 디코딩 RGB/PCM 해시가 같았다. 파일 접근 차단·변환 중 강제 종료 후 자식 종료/프로필 회수·timeout·생산 guard를 포함해 13개 시나리오가 통과했다. capability 0개의 네트워크 I/O 실패와 registryRead 대조군의 실제 -10013 연결 거절을 구분했다. file probe에서 관찰한 초기화 경고는 아직 내부 원인을 확정하지 않았고 생산 어댑터는 연결하지 않았다. 다음 작업은 stdout/stderr 분리와 초기화 정책 검증이다. PowerShell 버전별 정렬 차이로 생긴 명세 비교 오류도 정규화로 수정했고 합성 설치 경계 15개가 통과했다. 상세 범위·재현·배포/성능 한계는 docs/FFMPEG_LPAC_INTEGRATION.md를 따른다.

2026-11-13 출력 채널 후속 구현: 공통 LPAC 실행기의 stdout/stderr를 별도 비상속 읽기 파이프로 분리했다. 채널마다 8192바이트를 수집하고 초과 시 Job 종료·exit 24로 거절한다. 두 파이프를 번갈아 읽고 자식 종료 후에도 둘 다 확인한다. 실제 프로브 16개 실행으로 바이너리 바이트·정확한 경계·동시 flood·최종 출력·비정상 종료 및 lease 0을 확인했다. FFprobe 시험에서 경고 문구를 제거하던 처리를 삭제하고 stdout 전체 JSON만 파싱하며 stderr 경고를 비공개로 분리한다. 경고의 내부 발생 원인이나 서버 LPAC 운영 연결 완료를 뜻하지 않는다. 다음 작업은 불변 런타임 재사용과 동시 작업 성능 검증이다. 상세 계약·재현은 docs/LPAC_OUTPUT_CHANNELS.md를 따른다.

2026-11-13 FFmpeg 공유 런타임 후속 구현: 비밀정보 없는 10개 파일을 새 snapshot에 한 번 복사하고 AR SID에 읽기/실행만 부여하는 시험 경계를 추가했다. native 부모가 동일 파일 핸들로 전체 해시를 검증하고 실행 동안 파일/디렉터리를 pin한다. 작업 root/input/out의 고유 SID는 유지하며 job별 런타임 복사는 생략한다. 잘못된 해시·미등록 파일·다중 링크는 작업 생성 전에 거절하고 복사 방식으로 우회하지 않는다. 동시 변환·작업 간 파일 차단·개별 종료/핀 수명과 복사 대비 시간을 검증하는 도구를 추가했다. Node/sharp 재사용·운영 어댑터/설정은 아직 연결하지 않았다. 세부 조건·한계는 docs/LPAC_SHARED_RUNTIME.md를 따른다.

2026-11-13 Node/sharp 공유 런타임 후속 구현: 112개 파일·23개 디렉터리의 구조까지 digest로 고정하고 파일과 모든 하위 디렉터리 핸들을 실행 동안 유지한다. 승인된 비밀정보 없는 코드만 AR 읽기/실행으로 공유하며 입력·스탬프·job/out의 고유 SID를 유지한다. 실제 워커 미리보기·다중 페이지 동시 요청·실제 업로드 스탬프 export·개별 종료/핀 수명·변조/빈 디렉터리/추가 JS/하드링크 거절의 9개 시나리오가 통과했다. 별도 native ACL 대조군 5개에서 공유 코드/디렉터리의 읽기만 허용되고 작업별 입력/출력은 읽기부터 거절됐다. 개발용 각 3회 중앙값은 복사 2760ms, 재사용 1298ms이며 작업별 약 109MiB 복사를 없앴다. 운영 경로·설정은 변경하지 않았다. 다음 작업은 런타임 버전 게시·교체·회수 관리이며 세부 재현·측정 한계는 docs/LPAC_SHARED_NODE_RUNTIME.md를 따른다.

2026-11-13 런타임 버전 관리 후속 구현: 기능/역할 구조에 SQLite 기반 RuntimeCatalog와 게시/활성화/참조/복구·목록 기반 회수 서비스를 추가했다. 준비 실패/프로세스 중단은 격리하고, 활성/참조/네이티브 pin이 남은 세대는 회수하지 않는다. deleting 커밋 후 승인 파일만 삭제해 중단 시 재개하며 retired tombstone을 보존한다. 파일시스템/실제 SIGKILL을 포함한 새 시험 13개와 실제 Node/FFmpeg 전환·서로 다른 digest의 롤백·관리자 재개방 중 pin 보호·112/10개 파일 물리 회수 인수 4개를 수행한다. Node 테스트 탐색을 기능별 하위 폴더까지 확장했다. 기본 서버와 설치 설정은 아직 변경하지 않았다. 다음 작업은 실제 이미지/재생 서비스의 참조·작업 잠금/게시 검증을 결합한 운영 어댑터이며 상세 계약은 docs/RUNTIME_VERSION_LIFECYCLE.md를 따른다.

2026-11-13 실제 서비스 버전 참조 후속 연결: 기본 꺼짐인 runtimeVersions 설정과 ManagedRuntimes를 application factory에 연결했다. 미리보기/스탬프/export/재생이 실제 프로세스 종료·최종 게시까지 한 버전을 유지하며 Windows Job 실행기가 고정 runtime.use 핸들을 자식에 상속한다. 회수는 쓰기 핸들로 교차 프로세스 사용을 확인한다. 캐시 v2는 runtime identity와 원본에 바인딩하고 이전 URL/Range 읽기는 재변환 없이 검증한다. WorkDirectories의 최종 이동 callback에서 접근/원본/모듈을 재검사한다. 실제 연결 중 Low snapshot이 Job 워커의 출력을 차단하던 문제는 새 읽기 전용 snapshot의 Medium 게시로 해결했고 업무 저장소 ACL은 낮추지 않았다. 관리형 Low 자식은 시작 전에 거절한다. 이 연결은 기존 Windows Job 경계이며 LPAC을 켜지 않는다. 다음 작업은 LPAC 전송의 운영 계약·입출력 바인딩·수명 연결이며 상세 범위는 docs/MANAGED_RUNTIME_EXECUTION.md를 따른다.
