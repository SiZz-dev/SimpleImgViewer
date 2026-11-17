# 남은 작업·보완 항목

2026-11-17 작성. 기존 AI 에이전트의 작업 기록(README, NODE_REACT_MIGRATION.md, 각 주제 문서, Git 이력)과 이번 코드 분석·실행 결과를 합쳐 정리했습니다. 호출 구조와 영향 범위는 [PROJECT_MAP.md](PROJECT_MAP.md)를 같이 보세요.

항목을 끝내면 체크하고 날짜·검증 결과를 한 줄 남깁니다. 새 항목은 해당 우선순위 표 끝에 추가합니다.

---

## 0. 현재 상태 (2026-11-17 확인)

| 확인 | 결과 |
| --- | --- |
| Node 테스트 (기본) | 140개 중 124 통과, 16 건너뜀(네이티브), 실패 0 |
| Node 테스트 (`SIV_NATIVE_TESTS=1`) | **140/140 통과**, 약 27초 → 11-17 기능 설정 수정 후 142/142 → 세션 갱신·삭제 추가 후 **145/145 통과** |
| React 빌드 (`scripts/build-web.mjs`) | 성공, 라이선스 기준선 npm 90개 통과, `distributionReady=false` |
| API 스모크 (임시 저장소, 개발 모드) | health·bootstrap·업로드 201·페이지 200(최초 349ms / 캐시 12ms)·미리보기/썸네일 200·메모 201·편집본 반출 200·CSRF 누락 403 |
| 실제 브라우저 시나리오 | Playwright 시나리오(`scripts/test-node-viewer.mjs`)는 실행하지 않음. 11-17 기능 설정 수정은 앱 내장 브라우저로 직접 확인(4절 완료 기록) |
| Git | 11-17 미커밋 작업과 이 문서를 `f88d8e0`·`80a806b`로 커밋·푸시함 |

---

## 1. 바로 처리할 것 — 미커밋 작업

이전 에이전트의 마지막 단계("런타임 검증 worker 설치 연결")가 커밋되지 않은 상태입니다. 위 테스트는 이 변경을 포함한 상태에서 통과했습니다.

- 변경: `managed-runtimes.mjs`(worker pool 소유·종료 대기), `runtime-verifier-pool.mjs`, `settings.mjs`(`runtimeVersions.verification` 검증·병합), `application.mjs`(중복 close 방지·listen 실패 정리), example YAML, README/docs, 측정 스크립트 4개
- 새 파일: `backend/test/conversion/service/managed-verifier.test.mjs`, `backend/test/system/config/runtime-verifier-settings.test.mjs`, `docs/RUNTIME_VERIFIER_CONFIGURATION.md`, `scripts/test-runtime-verifier-installation.mjs`
- [x] 내용 확인 후 커밋 — 2026-11-17 `f88d8e0`(worker 연결)·`80a806b`(이 문서) 커밋, main 푸시

---

## 2. 기존 에이전트 작업 이력 요약

| 시기 | 내용 |
| --- | --- |
| 2026-10 | Java/Spring Boot "ImageGate": img2tile·libvips 격리, 연계 권한 설계, 타일/미리보기/원본 정책 등 (DEVELOPMENT_PLAN.md 등, 현재는 기록용) |
| 11-12 | 사용자 요청으로 Node.js + React 전환. 세션 만료 정리, 업로드 임시 파일 소유권(SQLite), 이미지 작업 수명/잠금, FFmpeg Windows Job 실행기, 캐시 기간/용량 관리, 원본 용량 예약 |
| 11-13 | FFmpeg·이미지 워커 Job 메모리/CPU 제한, LPAC 선행 검증(Node/sharp/FFmpeg), 출력 채널 분리, 공유 런타임, 런타임 버전 카탈로그(게시/활성/회수), 서비스 버전 참조 연결, LPAC 입출력 바인딩 |
| 11-16 | 이미지·FFmpeg LPAC 전송, 한글 메모 폰트, LPAC 작업 회수, `isolation.mode: lpac` 설치 선택, 무결성 검사 속도·응답성 측정, 검증 Worker Thread 후보·설치 연결(미커밋), Java/Gradle 소스 제거 후 커밋 |

특징: 작업 대부분이 **보안 격리(Windows Job/LPAC)·런타임 무결성·회수 정책**에 집중되어 있고, 화면 기능은 11-12 이관 이후 거의 바뀌지 않았습니다. 문서에 "업무 자료", "조직별 quota", "SSO", "운영 인수" 같은 기업 운영 전제가 많습니다(→ 5절 결정 항목).

---

## 3. 기존 에이전트가 문서에 남긴 잔여 작업

| # | 항목 | 출처 | 상태 |
| --- | --- | --- | --- |
| A1 | worker 검증 모드의 장시간 혼합 부하·종료 반복 검증(메모리·thread·참조 누적 여부) | RUNTIME_VERIFIER_CONFIGURATION.md, README | 다음 작업으로 지정됨 |
| A2 | LPAC 최초 변환 지연 개선(동일 런타임 중앙값 Windows Job 1890ms vs LPAC 3863ms), 캐시 manifest 비용 | NODE_REACT_MIGRATION.md, LPAC_INSTALLATION_LOAD.md | 미착수 |
| A3 | 운영 인증(SSO)·파일별 권한 공급자 연동 — 현재 일반 실행은 API 전부 401 | README, NODE_REACT_MIGRATION.md | 미착수 |
| A4 | HEIC/JPEG2000, 고해상도 타일(`features.tiles=false`), 다양한 TIFF/색공간/압축 표본 인수 | README, NODE_REACT_MIGRATION.md | 미착수 |
| A5 | 다중 트랙·블랙박스 전용 데이터 | NODE_REACT_MIGRATION.md | 미착수 |
| A6 | 회수 후 남기는 작업 잠금 메타데이터(`lease.sqlite`/`retired`) 보관 정책, 예전 `.playback-*` 폴더 정리 | README, NODE_REACT_MIGRATION.md | 미구현 |
| A7 | 전체 디스크/조직별 quota, 변환 출력 용량 예약, 다중 서버·백업 일관성 | NODE_REACT_MIGRATION.md | 미착수 |
| A8 | Java 시절 오프라인 유지보수·감사/지표·복구/성능 도구의 Node 이관, 기존 데이터(`data/`) 이관 | NODE_REACT_MIGRATION.md | 미착수(자동 이관 없음) |
| A9 | 배포 검토: 전이 소스·고지·교체 조건, native CVE, 코덱 특허 (`distributionReady=false`) | README, NODE_REACT_MIGRATION.md | 미완료 |
| A10 | 운영 Windows·서비스 계정 환경 인수, 다른 OS/아키텍처 라이선스 기준선 | README | 미완료 |
| A11 | 설치 ZIP 생성 — AGENTS.md 규칙상 최종 기능·전체 검증 완료 후에만 | AGENTS.md | 보류(규칙) |
| A12 | 클라이언트 IP 허용 목록 | README | **사용자 요청으로 보류** |
| A13 | img2tile 담당 팀 수정본 수령 후 재검토 (Java 시절 경로, 현재 Node 경로는 img2tile 미사용) | DEVELOPMENT_PLAN.md | 외부 대기 |

---

## 4. 이번 분석에서 새로 찾은 보완 항목

모두 코드에서 직접 확인했습니다. 파일:함수는 PROJECT_MAP.md 절 번호와 함께 적었습니다.

### P1 — 기능 결함·정합성

| # | 항목 | 근거 | 제안 |
| --- | --- | --- | --- |
| B1 | **개발 모드가 아니면 앱을 쓸 수 없음.** `security()`는 운영 모드에서 무조건 401, 개발 모드는 모든 접속자를 같은 `dev-admin`으로 처리하고 `store.require`가 소유자 검사를 건너뜀 | security/service/session.mjs, media/repository/store.mjs:require | 개인용 범위에 맞는 최소 인증(로컬 계정 또는 토큰) 결정 필요 → 5절 D1 |
| ~~B2~~ | ✅ 2026-11-17 완료. 기능 플래그 `brightness`, `videoSeek`, `audioSeek`, `videoPlaybackSpeed`, `audioPlaybackSpeed`와 서버 반출의 `rotation`이 적용되지 않던 문제 | editing.mjs:exportWithRuntime, App.jsx | 아래 완료 기록 |
| ~~B3~~ | ✅ 2026-11-17 완료. 스탬프 목록·이미지 조회, 편집본 합성, 화면 표시·업로드 버튼이 `features.stamps`를 확인하지 않던 문제 | editing/controller/routes.mjs, editing.mjs, App.jsx | 아래 완료 기록 |
| ~~B4~~ | ✅ 2026-11-17 삭제 완료. 원본·메타·편집 문서 삭제와 원본 용량 반환, 파일 목록 삭제 버튼. **전체 미디어 목록 화면은 B10으로 분리** | media/routes.mjs, MediaService.remove, MediaStore.remove, App.jsx:removeFile | 아래 완료 기록 |
| B5 | 실행 중 캐시 축출 없음 — 캐시 합계가 2GiB에 닿으면 재시작 전까지 새 미리보기/재생본이 507 (의도된 설계로 문서화됨) | conversion/service/cache-storage.mjs:publish | 개인용이면 실행 중 LRU 축출 검토(활성 읽기 보호 필요) |
| ~~B6~~ | ✅ 2026-11-17 완료. 여러 파일 업로드 중 하나가 실패하면 이미 올라간 파일이 목록·세션에 반영되지 않던 문제 | App.jsx:openFiles | 아래 완료 기록 |
| ~~B7~~ | ✅ 2026-11-17 완료. 세션이 생성 시점만 저장돼 새로고침하면 첫 파일 1페이지로 돌아가던 문제 | ViewerSessions.update, App.jsx:saveSession | 아래 완료 기록 |
| ~~B8~~ | ✅ 2026-11-17 완료. 세션의 `initialTimeSeconds`가 음성에 적용되지 않던 문제 | App.jsx `mediaProps` | 아래 완료 기록 |
| ~~B9~~ | ✅ 2026-11-17 완료. 오류 코드 12개 한국어 안내 추가(이후 B4·B7 작업에서 `MEDIA_BUSY`, `SESSION_NOT_FOUND`, `MEDIA_NOT_FOUND` 추가) | App.jsx:messages | 새 오류 코드는 계속 함께 추가 |
| B10 | 저장된 미디어 전체 목록이 없음 — 세션에 들어 있지 않은 예전 원본은 화면에서 다시 찾거나 삭제할 수 없음 | media/routes.mjs, App.jsx | 소유자별 목록 API(페이지 나눔) + 목록 화면. 화면 구성 결정 필요 |
| B11 | 삭제한 미디어의 미리보기·재생 캐시가 서버 재시작 시 기간/용량 정리 전까지 남아 캐시 한도(2GiB)에 계속 포함됨(접근은 404) | MediaStore.remove, CacheStorage | B5(실행 중 캐시 축출)와 함께 검토 |

#### 2026-11-17 완료 기록 (B2·B3·B6·B8·B9)

- 서버
  - 편집본 반출에서 꺼진 밝기(≠100)·회전(≠0)을 403 `FEATURE_DISABLED`로 거절한다. 회전 미적용은 작업 중 추가로 발견했다. 자르기는 원래 100/0 고정이라 영향이 없다.
  - 스탬프가 꺼지면 `GET /stamps`, `GET /stamps/:id/content`도 403으로 거절한다.
  - 편집본 합성에서 꺼진 기능의 항목(스탬프/메모)을 뺀다. 저장된 편집 문서에서는 지우지 않으므로 설정을 다시 켜면 그대로 나타난다.
- 화면
  - 밝기가 꺼지면 슬라이더·초기화를 막는다.
  - 배속이 꺼지면 배속 선택을 숨기고 `controlsList="noplaybackrate"`를 지정하며, 배속이 바뀌면 1×로 되돌린다.
  - 탐색이 꺼지면 0.5초 넘는 위치 이동을 직전 위치로 되돌린다. 끝난 뒤 처음부터 다시 재생하는 것은 허용한다.
  - 꺼진 기능의 주석 항목은 표시하지 않는다. 스탬프 업로드 버튼은 `stamps`도 확인한다(브라우저 확인 중 발견).
  - 일부 업로드가 실패해도 성공한 파일을 목록·세션에 넣고, 파일 목록 위에 "N개 중 M개를 열지 못했습니다"를 표시한다.
  - 시작 시간을 음성에도 적용한다.
- **한계:** 탐색·배속은 화면에서만 제한한다. 재생 자체에 Range 요청이 필요해 서버가 막을 수 없으며, 보안 경계가 아니라 열람 편의 설정이다.
- 검증
  - `server.test.mjs`에 2개를 추가했다(밝기·회전 거절, 스탬프 조회 거절과 합성 제외의 픽셀 비교). 둘 다 이전 코드에서는 실패하고 수정 후에는 통과했다.
  - 전체 142/142(`SIV_NATIVE_TESTS=1`), React 빌드·라이선스 기준선이 통과했다.
  - 임시 저장소로 띄운 서버 2개(기본 / 기능 끔)에서 앱 내장 브라우저로 이미지·MP4·WAV의 컨트롤 상태, 탐색 되돌림, 배속 복귀, 음성 시작 1초, 일부 업로드 실패 안내를 확인했다. 콘솔 오류는 일부러 올린 실패 파일의 415 하나뿐이었다.

#### 2026-11-17 완료 기록 (B7 세션 위치, B4 미디어 삭제)

- B7 서버: `PATCH /api/v1/viewer/sessions/:id` (`ViewerSessions.update`)
  - 선택 파일·시작 페이지·재생 시작 시간만 바꿀 수 있고, 다른 키는 400이다.
  - 생성 API와 같은 검증(본인·만료 전·세션 안 파일·범위)을 쓰고, 생성·정리와 같은 직렬화 안에서 저장한다. 만료 시간은 연장하지 않는다.
- B7 화면
  - 파일/페이지를 열 때, 동영상·음성을 일시 정지할 때(끝까지 재생했으면 0초), 재생 중 페이지를 떠날 때(`pagehide` + `keepalive`) 위치를 저장한다.
  - 저장 실패는 무시해 보기를 막지 않는다. 시간은 초 단위로 내림 저장한다.
- B4 서버: `DELETE /api/v1/media/:id` → `MediaService.remove` → `MediaStore.remove`
  1. 같은 mediaVersion의 미리보기/재생 변환이 진행 중이면 409 `MEDIA_BUSY`로 거절한다.
  2. 원본 `source`를 먼저 지운다. 다른 프로세스가 공유 삭제 없이 열고 있으면(`EBUSY`/`EPERM`/`EACCES`) 아무것도 바꾸지 않고 409를 돌려준다.
  3. 그 뒤 `record.json`(+임시 파일)·폴더, 편집 문서 `editing/<id>-<page>.json`(+임시 파일)을 지우고 `UploadCapacity.remove`로 원본 용량을 반환한다.
  4. 도중에 중단돼도 메타만 남으므로 같은 삭제를 다시 보내면 마저 정리된다.
  - 세션 조회는 삭제된 파일을 건너뛴다. 선택 파일이 없으면 남은 첫 파일·1페이지·0초로 열고, 남은 파일이 없으면 404다.
- B4 화면
  - 파일 목록 각 줄에 삭제 버튼(`×`)과 확인 창을 둔다.
  - 삭제 후에는 남은 파일로 새 세션을 만들고, 보던 파일이면 같은 위치의 다음 파일을 연다. 모두 지우면 빈 화면과 `/viewer`로 돌아간다.
  - 실패는 파일 목록 위 안내로 표시해 현재 화면을 가리지 않는다.
- **한계**
  - 캐시는 즉시 지우지 않는다(B11).
  - 저장된 전체 미디어 목록은 없다(B10).
  - 스탬프는 사용자 자원이라 삭제 대상이 아니다.
  - 편집 문서 저장(`EditingService.change`)과 삭제가 정확히 겹치면 문서 저장 직전 `store.require`가 404가 되어 쓰지 않는다. 다만 그 사이의 아주 짧은 구간은 잠금으로 막지 않았다.
- 검증
  - `server.test.mjs`에 3개를 추가했다(세션 갱신 검증·만료 불변, 삭제 후 404·파일 정리·용량 반환·재스캔 일치·세션 건너뛰기, 진행 중 변환과 실제 PowerShell 파일 잠금에서 409·무변경 후 잠금 해제 시 삭제). 이전 코드에서는 3개 모두 실패했다.
  - 전체 145/145(`SIV_NATIVE_TESTS=1`), React 빌드·라이선스 기준선이 통과했다.
  - 앱 내장 브라우저로 확인한 것
    - GIF 2페이지·음성 일시 정지 위치(2.18→2초)·재생 중 새로고침(1.37→1초) 복원
    - 삭제 취소 시 무변경, 보던 파일 삭제 시 다음 파일과 새 세션, 새로고침 후 목록 유지
    - 실제 파일 잠금 시 안내만 표시되고 화면 유지, 전부 삭제 시 빈 화면
  - 확인 중 발견한 `SESSION_NOT_FOUND`·`MEDIA_NOT_FOUND` 안내 누락을 보완했다.

### P2 — 뷰잉 속도 (프로젝트 최우선 기준)

| # | 항목 | 근거 | 제안 |
| --- | --- | --- | --- |
| C1 | **이미지 하나를 열 때 원본 전체 SHA-256을 최소 6회 재계산**(페이지 2 + 주석 조회 내부 페이지 2 + 미리보기 1 + 썸네일 1). 원본 최대 100MB라 큰 파일일수록 지연이 선형 증가 | media/service/media.mjs:pageWithRuntime/source, store.verify | (inode·size·mtime) 기반 검증 결과 재사용, 또는 캐시 적중 경로에서 검증 횟수 축소 — 무결성 정책과 맞바꿈이라 결정 필요(D3) |
| C2 | 주석 조회(`GET …/annotations`)가 `media.page` 전체 경로를 다시 수행 | editing.mjs:document | 페이지 정보는 이미 받은 값 재사용 또는 가벼운 바인딩 확인 |
| C3 | 모든 응답이 `Cache-Control: no-store` — 내용 주소형 URL(sourceId)인 미리보기·썸네일·재생본도 브라우저 캐시를 못 씀. 파일을 다시 선택할 때마다 재다운로드 | application.mjs 보안 헤더 미들웨어 | sourceId URL에 한해 `private, max-age, immutable` 검토(공용 PC 노출과 맞바꿈, D3) |
| C4 | 미리보기가 1600px PNG 1장 — 확대 시 흐려지고, PNG라 용량이 큼. 고해상도는 타일 미구현(A4) | image-worker.mjs, settings `previewEdge` | WebP/JPEG 미리보기 또는 확대 시 고해상도 2단계 로드 검토 |
| C5 | 여러 파일 업로드가 순차 처리, 썸네일은 파일을 선택해야 생성 | App.jsx:openFiles | 서버 admission(2건) 범위 안에서 병렬화, 썸네일 선생성 |

### P3 — 구조·유지보수

| # | 항목 | 근거 | 제안 |
| --- | --- | --- | --- |
| ~~E1~~ | ✅ 2026-11-17 완료. 화면 전체가 `App.jsx` 단일 컴포넌트였던 문제 → 모듈 4개 + `components/` 8개로 분리(상태·흐름은 App 유지) | frontend/src/viewer | 아래 완료 기록. 상태 관리 분리(커스텀 훅 등)는 필요할 때 별도 검토 |
| E2 | 한 줄에 여러 문장을 넣는 압축 코드 스타일 → 리뷰·diff 추적이 어려움 | backend 전반 | 포매터 도입 여부 결정(D4). 도입 시 기능 변경과 별도 커밋 |
| ~~E3~~ | ✅ 2026-11-17 완료. 루트 테스트 9개를 기능/역할 폴더로 이동(`git mv`, 상대 경로 31곳 수정). 공용 `backend/test/fixtures`는 scripts도 쓰므로 유지 | backend/test | 아래 완료 기록 |
| E4 | 한도 상수 중복: bootstrap `limits`와 각 서비스에 같은 숫자가 따로 있음(주석 200, 메모 500, 스탬프 100/2MiB, 자르기 8MP) | viewer/routes.mjs, editing.mjs, lpac-bindings.mjs | 기능별 상수 모듈로 단일화 |
| ~~E5~~ | ✅ 2026-11-17 완료. 백엔드 미사용 import 6개(`integer`×2, media.mjs의 `fs`·`randomUUID`·`privateRoot`, `constants`) 제거. 화면은 자동 JSX 런타임이라 새 파일에서 `import React`를 쓰지 않음(`main.jsx`만 남음) | 정적 확인 스크립트 | 아래 완료 기록 |
| E6 | 개인 LAN IP `192.168.0.86`이 기본값·예제·화면 placeholder에 하드코딩 | settings.mjs, example yml, App.jsx | 기본값에서 제거, 설정으로만 지정 |
| E7 | 서버가 현재 작업 디렉터리 기준 상대 경로에 의존(`dist`, `config/`, `build/`, `pnpm-lock.yaml`, `.runtime/`) | settings.mjs, application.mjs, image-engine.mjs | 저장소 루트 기준 절대 경로로 해석 |
| E8 | 화면 단위 테스트 없음, 브라우저 시나리오는 외부 Playwright 경로 환경 변수 필요 | scripts/test-node-viewer.mjs | devDependency로 고정하거나 실행 안내를 스크립트화 |
| E9 | 사용하지 않는 API: `GET /media/:id/status`, `POST /media/:id/previews`(페이지 API와 동일 동작) | media/routes.mjs | 유지 이유 없으면 제거 |
| E10 | Java 잔재(로컬, Git 제외): `.gradle/`, `build/classes`, `build/resolvedMainClassName`, `.tools/gradle-*`, `config/imagegate.example.yml` | 디렉터리 확인 | 필요 없으면 정리(업무 자료·`data/`는 대상 아님) |
| E11 | docs 90여 개 중 대부분이 Java 시절·단계 기록 | docs/ | PROJECT_MAP.md 10절 분류 유지, 필요 시 `docs/history/`로 이동 |

#### 2026-11-17 완료 기록 (E1 화면 분리, E3 테스트 위치, E5 import 정리)

- **E1:** 동작을 바꾸지 않는 구조 정리다. App.jsx는 229줄에서 177줄이 됐다.
  - 모듈: `api.js`(createApi: CSRF·오류 코드), `messages.js`(문구 33개, `describe`·`brief`), `format.js`, `launch.js`(진입 주소 해석)
  - `components/`: `Header`, `Icon`, `FilePanel`, `Viewport`(재생 화면은 children), `PlaybackStage`, `ToolsPanel`(`ImageTools`·`PlaybackInfo`), `Lens`, `UrlDialog`(입력값 상태 자체 보유)
  - App은 상태와 업무 흐름(select·업로드·삭제·편집·세션 저장·포인터)을 그대로 갖고, 컴포넌트는 props로 받아 그리기만 한다.
- **E3:** 루트 테스트 9개를 옮겼다.
  - `conversion/service`: cache-storage, image-job, work-directories
  - `conversion/util`: windows-job
  - `playback/service`: playback-native
  - `system/controller`: server
  - `viewer/service`: sessions
  - `media/service`: temporary-files, upload-capacity
- **E5:** 앞 grep 방식이 놓친 media.mjs의 `fs`까지, 이름별 사용 횟수를 세는 스크립트로 확인했다.
- 검증
  - Node 테스트: 네이티브 포함 145/145, 기본 실행 129 통과·16 건너뜀. React 빌드(28 모듈)·라이선스 기준선도 통과했다.
  - 앱 내장 브라우저(1280×860, 기본/기능 끔 서버)로 다음을 다시 확인했다.
    - 헤더 컨트롤 전부
    - 실제 마우스로 메모 추가·드래그·크기 수정·실행 취소/다시 실행, 스탬프 업로드·배치
    - 자르기·편집본 PNG(편집본에만 스탬프·메모 픽셀 합성)
    - 돋보기, URL 창, GIF 페이지, 동영상·음성 재생·배속, 새로고침 위치 복원, 일부 업로드 실패 안내, 삭제 흐름
    - 기능 끔: 밝기·스탬프 비활성, 탐색·배속 복귀
  - 새 결함은 없었다.

---

## 5. 사용자 결정이 필요한 항목

| # | 결정 | 배경 |
| --- | --- | --- |
| D1 | 인증 방식: 개인용 최소 인증(로컬 계정/토큰) vs 기존 계획대로 SSO·연계 권한 | 지금은 `--dev` 실행에서만 사용 가능(B1, A3) |
| D2 | 프로젝트 범위: 기업 운영 전제 항목(LPAC 운영 인수, 조직별 quota, 다중 서버, 감사/백업 도구 A7·A8·A10)을 계속 진행할지, 개인용 기능(B·C 항목) 우선으로 전환할지 | 이전 작업 대부분이 운영 격리·무결성에 집중됨 |
| D3 | 무결성 검사 vs 뷰잉 속도: 요청마다 원본 전체 해시(C1), `no-store` 캐시 정책(C3) 완화 여부 | 최우선 기준 두 개(속도·보안)가 충돌하는 지점 |
| D4 | 코드 포매터 도입 여부(E2) | 기존 압축 스타일 유지 여부 |

---

## 6. 권장 진행 순서

1. ~~미커밋 작업 커밋(1절)~~ ✅ 11-17
2. D1~D3 결정
3. ~~P1 중 작은 것부터: B2·B3·B6·B8·B9~~ ✅ 11-17
4. ~~B4 미디어 삭제 + B7 세션 갱신~~ ✅ 11-17 (목록은 B10, 캐시 정리는 B11로 남음)
5. C1~C3 뷰잉 속도 개선(결정 결과 반영, 측정 전후 비교)
6. ~~E1 화면 컴포넌트 분리(동작 변경 없이), E3·E5 정리~~ ✅ 11-17
7. 기존 잔여 A1·A2 등은 D2 결정에 따라 진행
