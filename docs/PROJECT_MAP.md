# 프로젝트 맵 (SimpleImgViewer / ImageGate)

2026-11-17 기준 소스(`2bd6a6a` + 미커밋 런타임 검증 worker 연결) 분석 결과입니다. 기능을 고치거나 추가할 때 **어떤 파일이 같이 움직이는지, 무엇을 다시 검증해야 하는지** 찾는 용도입니다. 남은 작업·보완 항목은 [REMAINING_WORK.md](REMAINING_WORK.md)에 따로 정리했습니다.

- 현재 실행 기준: Node.js 24.22.1 + Express 5 + React 19 + Vite 8 (Java/Gradle 제거됨)
- docs의 나머지 문서 다수는 Java 시절 또는 단계별 기록입니다. 현재 동작 판단은 **코드 → 이 문서 → NODE_REACT_MIGRATION.md → 각 주제 문서** 순으로 합니다. (문서 분류는 [10. 문서 맵](#10-문서-맵))
- 코드는 한 줄에 여러 문장을 넣은 압축 스타일입니다. 아래 파일:함수 이름으로 검색하면 빠릅니다.

---

## 1. 전체 구조 한눈에

```mermaid
flowchart LR
  subgraph FE[frontend/src]
    App["viewer/App.jsx<br/>상태·흐름 + components/*"]
  end
  subgraph SYS[backend/system]
    main[main.mjs] --> appf[controller/application.mjs<br/>createApplication]
    appf --> settings[config/settings.mjs]
    errors[util/errors.mjs]
  end
  subgraph SEC[backend/security]
    sess["service/session.mjs<br/>security()"]
  end
  subgraph CTRL[각 기능 controller/routes.mjs]
    vr[viewer]
    mr[media]
    pr[playback]
    er[editing]
  end
  subgraph SVC[service]
    VS[ViewerSessions] --> MS
    ES[EditingService] --> MS[MediaService]
    MS --> IE[ImageEngine]
    PS[PlaybackService]
    Store[MediaStore<br/>media/repository]
  end
  subgraph CONV[backend/conversion]
    IE --> WJ[util/windows-job<br/>WindowsJobRunner]
    IE -.lpac.-> LIT[LpacImageTransport]
    PS -.lpac.-> LMT[LpacMediaTransport]
    MR[ManagedRuntimes] --> RV[RuntimeVersions] --> RC[(RuntimeCatalog<br/>SQLite)]
    WD[WorkDirectories]
    CS[CacheStorage]
  end
  subgraph NATIVE[native/process-job · 로컬 빌드 EXE]
    JL[Job 실행기 main.cpp]
    LI[lpac-image.cpp] & LM[lpac-media.cpp]
  end
  App -- fetch /api/v1 --> CTRL
  appf --> sess --> CTRL
  WJ --> JL --> W[conversion/util/image-worker.mjs<br/>sharp/libvips]
  PS --> WJ --> JL --> FF[ffmpeg.exe / ffprobe.exe]
  LIT --> LI --> W
  LMT --> LM --> FF
  Store --> WD
  Store --> CS
  VS --> Store
  ES --> Store
  MS --> Store
  PS --> Store
```

| 계층 | 위치 | 역할 |
| --- | --- | --- |
| 진입 | `backend/main.mjs`, `scripts/dev.mjs`, `scripts/start.mjs` | 서버 시작, `--dev`만 자동 로그인 활성화 |
| 조립 | `backend/system/controller/application.mjs` | 설정→저장소→런타임→전송→서비스 생성, 미들웨어·라우트 등록, 종료 순서 |
| 설정 | `backend/system/config/settings.mjs` | 기본값 + YAML + override 병합, 범위 검증, 개발용 FFmpeg/Job 실행기 manifest 자동 연결 |
| 인증 | `backend/security/service/session.mjs` | 개발 모드 전용 쿠키 세션 + CSRF + Origin 검사. **운영 인증은 없음(항상 401)** |
| 기능 | `viewer` `media` `editing` `playback` `conversion` | 아래 2~5절 |
| 화면 | `frontend/src/viewer/` | `App.jsx`가 상태·업무 흐름을 갖고, `components/`가 props로 그림. `api.js`·`messages.js`·`format.js`·`launch.js`는 보조 모듈 (11절) |
| 네이티브 | `native/process-job`, `native/imagegate-converter` | Windows Job/LPAC 실행기(C++), 로컬 빌드 후 해시로 고정 |

---

## 2. 기동·종료 흐름 (`createApplication`)

순서가 곧 의존성입니다. 앞 단계가 실패하면 뒤 단계는 만들어지지 않고, catch에서 역순 정리합니다.

1. `settings({development,overrides})`
   - `SIMPLEIMGVIEWER_CONFIG` 또는 `config/simpleimgviewer.yml` → 기본값과 섹션별 병합(`runtimeVersions.verification`은 필드 단위 병합)
   - 개발 모드: `build/native-modules/imagegate.native.yml`에서 playback 경로/해시, `build/process-job/launcher.json`에서 Job 실행기 경로/해시를 자동 주입(소스 해시가 `native/process-job/main.cpp`와 다르면 `PROCESS_JOB_BUILD_STALE`)
2. `storage.root`가 `dist` 안이면 거절 → `new MediaStore(config).init()`
   - `privateRoot`: `originals/ derived/ multipart/ stamps/ editing/ sessions/`
   - `TemporaryFiles.init()` – `multipart/transfers/owner.sqlite` 배타 잠금(=같은 저장소에 Node 서버 1개), 오래된 `.part` 회수
   - `UploadCapacity.init()` – originals 전체 스캔으로 원본 사용량 집계(이상 항목 있으면 기동 거절)
   - `WorkDirectories(image)`, `WorkDirectories(playback)` 회수 → `CacheStorage.init()` (기동 시에만 기간/용량 초과 캐시 삭제)
3. `runtimeVersions.enabled`면 `ManagedRuntimes.init()` → `RuntimeVersions.init()` → `RuntimeCatalog.open()` (+ `verification.mode: worker`면 `RuntimeVerifierPool`)
4. `createLpacTransports()` – `isolation.mode: lpac`일 때만 이미지/미디어 전송 생성, `--capabilities` 프로필 확인
5. `ImageEngine`, `PlaybackService.init()`(FFmpeg 해시·DLL baseline·LGPL/코덱 검사) → `MediaService`, `EditingService`, `ViewerSessions` → `sessions.cleanupExpired()`
6. 미들웨어 → 라우트 → 정적 `dist` → 404 → 오류 핸들러, `http.createServer`
7. 종료 `close()` (한 번만 실행되도록 공유 Promise): HTTP 닫기 + `images.close()` + `playback.close()` → `runtimes.close()` → `store.close()`

> 경로 대부분이 **현재 작업 디렉터리 기준 상대 경로**(`dist`, `config/…`, `build/…`, `.runtime/…`, `pnpm-lock.yaml`)입니다. 서버는 반드시 저장소 루트에서 실행해야 합니다.

---

## 3. HTTP 요청 파이프라인

```
요청 → 보안 헤더(nosniff, X-Frame-Options, CSP, Cache-Control:no-store …)
     → Host 헤더 형식 + server.allowedHosts 검사 (403 HOST_NOT_ALLOWED)
     → /actuator/health (인증 없음)
     → /api/*  : security(config)  ─ 운영: 401 / 개발: siv-session 쿠키, 비GET은 X-CSRF-TOKEN + Origin 검사
               express.json(64kb) ─ JSON 본문은 객체만 허용
     → 기능 라우트 (wrap → 비동기 오류를 next로)
     → /viewer/resources/TestFiles/* (개발 전용 URL 수신 샘플)
     → /viewer 정적(dist) → 404 JSON → 오류 핸들러(ApiError.code만 노출)
```

- 오류 규약: 서비스는 `requireThat(조건, status, CODE)`로 `ApiError`를 던지고, 응답은 `{code}`만 반환합니다. 화면은 `frontend/src/viewer/messages.js`(`describe`·`brief`)로 한국어 안내를 매핑합니다. **새 오류 코드를 추가하면 `messages`도 함께 확인**하세요.
- 업로드 계열(`POST /media`, `/media/import-url`, `/stamps`)은 `application.mjs`의 `admission`으로 동시 2건 제한(429 `UPLOAD_BUSY`).

---

## 4. API 라우트 맵

| 메서드·경로 | controller | 서비스 호출 체인 | 기능 플래그 | 화면 호출 위치(App.jsx 함수) |
| --- | --- | --- | --- | --- |
| GET `/api/v1/viewer/bootstrap` | viewer/routes | config만 읽음, CSRF 토큰 반환 | 응답에 features·limits 포함 | 초기 `useEffect` |
| POST `/api/v1/viewer/sessions` | viewer/routes | `ViewerSessions.create` → `store.require`×N → `scan` → `atomicJson` | – | `sessionFor` |
| GET `/api/v1/viewer/sessions/:id` | viewer/routes | `ViewerSessions.view` → `store.require`(404 파일은 건너뜀) → `media.public` | – | `?sessionId=` 진입 |
| PATCH `/api/v1/viewer/sessions/:id` | viewer/routes | `ViewerSessions.update` → 검증 → `atomicJson` (만료 불변) | – | `saveSession`(선택·일시 정지·pagehide) |
| POST `/api/v1/media` | media/routes | `admission` → `receiveUpload` → `store.register` → `store.releaseTemp` | `features[mediaType]` | `openFiles` |
| POST `/api/v1/media/import-url` | media/routes | `admission` → `importRemote` → `store.register` | `remote.enabled` | `openUrl`, `?url=` 진입 |
| GET `/api/v1/media/:id` | media/routes | `store.require` → `media.public` | `features[mediaType]` | `?mediaId=` 진입 |
| DELETE `/api/v1/media/:id` | media/routes | `MediaService.remove` → `MediaStore.remove` (5.8) | `features[mediaType]` | `removeFile` |
| GET `/api/v1/media/:id/pages/:page` | media/routes | `MediaService.page` (5.2) | image | `select` |
| GET `/api/v1/media/:id/status`, POST `…/previews` | media/routes | `MediaService.page`와 동일(`?page=`) | image | 사용 안 함 |
| GET `/api/v1/media/:id/sources/:sourceId/{content,thumbnail}` | media/routes | `MediaService.source` → PNG `sendFile` | image | `<img src>`·썸네일·돋보기 |
| GET/POST `/api/v1/media/:id/playback` | playback/routes | `PlaybackService.prepare` (5.6) | `playback.enabled`, video/audio | `select`(POST) |
| GET/HEAD `/api/v1/media/:id/playback/sources/:sourceId/content` | playback/routes | `PlaybackService.source` → 단일 Range 스트리밍 | `playback.enabled` | `<video>/<audio src>` |
| GET `/api/v1/stamps` | editing/routes | `stampsEnabled` → `EditingService.listStamps` | stamps | 부트스트랩·업로드 후 |
| POST `/api/v1/stamps` | editing/routes | `admission` → `receiveUpload(2MiB, 원본 아님)` → `createStamp` | stamps+stampUpload | `uploadStamp` |
| GET `/api/v1/stamps/:id/content` | editing/routes | `stampsEnabled` → `EditingService.stamp` → PNG | stamps | 스탬프 `<img>` |
| GET `/api/v1/media/:id/pages/:page/annotations` | editing/routes | `EditingService.document` → `media.page` | – | `select` |
| POST `…/annotations` · PUT/DELETE `…/annotations/:itemId` · POST `…/annotations/history/{undo,redo}` | editing/routes | `EditingService.change` (5.4) | annotations / stamps | `saveItem`, `historyChange` |
| POST `…/pages/:page/crop` · `…/export` | editing/routes | `EditingService.export` (5.5) | cropAndSave / exportEdited (+brightness·rotation·annotations·stamps) | `download(false/true)` |
| GET `/viewer/resources/TestFiles/*` | application | 개발 모드 + `.runtime/development/url-files/TestFiles` | development | URL 수신 시험용 |
| GET `/actuator/health` | application | `{status:'UP'}` | – | – |

---

## 5. 기능별 호출 체인

### 5.1 파일 업로드·URL 수신

```
App.openFiles ─ 파일마다 순차 POST /api/v1/media (FormData) ─ 이후 sessionFor(all) → select(added[0])
  media/routes → admission(동시 2)
    receiveUpload(req, store)                       media/service/upload.mjs
      store.temp()  → TemporaryFiles.allocate() + UploadCapacity.reserve(max 크기 선예약, 디스크 여유/원본 한도)
      busboy(files:1, fields:0) → .part 파일 기록 (fileSize 초과 413)
    store.register(temp, fileName, actor)          media/repository/store.mjs
      regular() → capacity.check → 앞 512B로 detect() → features 검사 → digest(SHA-256)
      originals/<uuid>/source 로 rename → capacity.commit → record.json(atomicJson)
    finally store.releaseTemp() → 예약 반환
  → media.public(record)  (permissions는 현재 하드코딩 true)

importRemote(body, store, actor)                    media/service/remote.mjs
  remoteUrl(): IP 리터럴 host만, 사용자정보/해시/모호한 경로/인코딩 차단, allowedPrefixes 접두 일치
  fetch(redirect:manual, identity) → 크기 제한 Transform → temp → store.register
```

- 형식 판별: `media/util/files.mjs:detect` (PNG/JPEG/GIF/BMP/TIFF/WebP, WAV/MP3, MP4(ftyp, HEIF 브랜드는 unsupported), MPEG-PS). **새 입력 형식은 detect + image-worker 허용 format + playback 포맷 매핑 + 화면 표시를 함께 수정**해야 합니다.

### 5.2 이미지 페이지 정보·미리보기 (핵심 뷰잉 경로)

```
App.select(record, page) → GET /media/:id/pages/:page → 이어서 GET …/annotations
MediaService.page → ImageEngine.withRuntime(runtime => pageWithRuntime)
  runtime = ManagedRuntimes.acquire('node') (관리형일 때만) – 전체 트리 SHA 검증 포함
  pageWithRuntime:
    store.require(id, actor)  – record.json, tenant/owner(개발 모드는 owner 우회), features
    store.verify(record)      – 원본 전체 SHA-256 재계산 ①
    target = derived/<mediaId>-<sourceId>
      sourceId = sha256(sha256 + mediaVersion + ':sharp-node-v2:' + identity + ':' + page + ':' + previewEdge)
      identity = 관리형 runtime.identity | sha256(image-worker.mjs + work-lease.mjs + bmp.mjs + pnpm-lock.yaml)
    running Map으로 같은 (version,page,identity) 동시 요청 합치기
    prepare():
      캐시 적중: facts.json 바인딩 검사 + preview.png 확인 + cache.touch → 반환
      캐시 미스: store.work.create() (derived/image-work/<uuid> + lease.sqlite)
        → ImageEngine.run({operation:'preview', input, page, edge, output, thumbnail})
            동시 처리 processing.concurrency 초과 시 429 PREVIEW_QUEUE_FULL
            [windows-job] job.json 기록 → WindowsJobRunner.command() → spawn(Job 실행기 -- node image-worker.mjs job.json)
            [lpac]        LpacImageTransport.run() → lpac-bindings → SIVLPAC1 요청 → native lpac-image
            image-worker: retired 표식 확인 → sharp(rotate→resize(edge)→png) + 256px 썸네일 → stdout JSON facts
        → facts.json 기록 → work.publish(target, cache, beforePublish: require+verify+runtime.verify)
          → CacheStorage.publish(용량 초과 507) → rename → work.release
    다시 store.require + store.verify ② → 응답(좌표계, previewToOriginal 행렬, source/thumbnail URL)

GET /sources/:sourceId/content|thumbnail → MediaService.source
  store.require → store.verify ③ → facts 재바인딩(sourceId 재계산) → cache.touch → sendFile
```

- 한 이미지를 처음 열 때 원본 SHA-256 전체 계산이 `page`(2회) + `annotations`(내부 `page` 2회) + `content`(1회) + `thumbnail`(1회) = **최소 6회** 일어납니다. 큰 원본의 뷰잉 지연 원인 후보입니다(REMAINING_WORK 참고).
- 캐시 키(sourceId) 공식, `CacheStorage`의 디렉터리 이름 정규식, `MediaService.source`의 재계산은 **한 세트**입니다. 하나를 바꾸면 셋 다 바꿔야 하며 기존 캐시는 무효(고아)가 됩니다.

### 5.3 좌표계 (화면 ↔ 서버)

- 서버 응답: `coordinateWidth/Height`(EXIF 회전 반영한 원본 픽셀), `displayWidth/Height`(미리보기), `coordinateSpaceId = original:<mediaVersion>:<page>:upright-v1`.
- 화면 `App.point()`가 화면 좌표 → 회전(angle)·확대(zoom)·이동(pan) 역변환 → 원본 좌표로 바꿉니다. 메모/스탬프/자르기 영역은 모두 **원본 좌표**로 저장·전송됩니다.
- 회전·밝기는 화면에서는 CSS(`transform`, `filter`)만, 서버 반출 시에만 `image-worker`가 실제 적용합니다.

### 5.4 메모·스탬프 편집

```
App.pointerDown(tool=note|stamp) → saveItem → POST …/annotations
App 드래그 종료/크기·내용 수정 → PUT …/annotations/:itemId ;  삭제 → DELETE ;  undo/redo → POST …/history/:dir
EditingService.change(id, page, actor, body, action, itemId)
  (id:page) 단위 Promise 잠금
  document() → media.page()(5.2 전체 경로) + editing/<mediaId>-<page>.json (없으면 빈 문서)
  body.mediaVersion/coordinateSpaceId/expectedRevision 불일치 → 409 EDIT_CONFLICT
  item 검증: note|stamp, 원본 영역 안, w*h ≤ 4,194,304, note ≤ 500자, 페이지당 200개, 스탬프 소유 확인
  undo 스택 20개, redo 초기화 → revision++ → atomicJson
```

- 스탬프 업로드: `createStamp` → PNG/JPEG/WebP만 → `ImageEngine.run(preview, edge 1024, maxPixels 4MP)`로 재인코딩 → `stamps/<uuid>.png` + `.json`, 사용자당 100개.
- 한도 숫자(200, 500, 100, 2MiB, 8MP)는 **서버 서비스 코드와 `viewer/routes.mjs`의 bootstrap `limits`에 중복** 정의되어 있습니다.

### 5.5 자르기·편집본 저장

```
App.download(edited) → POST …/crop | …/export  {mediaVersion, coordinateSpaceId, expectedRevision, x,y,width,height, brightness, rotation, includeAnnotations}
EditingService.export → ImageEngine.withRuntime → exportWithRuntime
  features(cropAndSave | exportEdited) → document() → 좌표/크기(≤8MP) 검증 → edited면 revision 검사
  밝기≠100이면 features.brightness, 회전≠0이면 features.rotation 필요(아니면 403; crop은 항상 100/0)
  items: 꺼진 기능(annotations/stamps) 항목 제외 → 스탬프는 파일 경로 결합, 합계 ≤16MP → ImageEngine.run({operation:'export', crop, brightness, rotation, items})
    image-worker: rotate(EXIF) → modulate(brightness) → composite(메모 SVG/스탬프) → extract(crop) → rotate(rotation)
    메모 SVG: 고정 템플릿 + escape, LPAC에서는 승인 폰트(Noto Sans CJK KR) 필수
  → 원본/버전/runtime 재검증 → PNG 바이트 응답(attachment)
```

### 5.6 동영상·음성 재생

```
App.select(video|audio) → POST /media/:id/playback → <video|audio src=info.url>
PlaybackService.prepare → withRuntime(ManagedRuntimes.acquire('ffmpeg') 선택) → prepareWithRuntime
  (mediaVersion:identity) 동시 요청 합치기, 동시 2건(429 PLAYBACK_QUEUE_FULL)
  convert():
    store.verify + FFmpeg 변경 검사(verify) + approveManaged
    sourceId = sha256(sha256 + mediaVersion + identity + ':node-playback-v2') → derived/playback-<sourceId>
    캐시 적중이면 facts 반환
    probe-input(ffprobe, 포맷 화이트리스트) → 길이 ≤2h, 스트림 ≤32, 해상도 ≤ 8,294,400px, fps ≤60, 채널 ≤8
    playbackWork.create → runProfile(video: VP8+Opus WebM ≤1080p / audio: MP3 192k)
    결과 probe-output 재분석 → facts.json → playbackWork.publish(cache)
  runProfile(): ffmpegProfile()로 고정 인자 생성 → [windows-job] run() spawn Job 실행기 / [lpac] LpacMediaTransport
GET …/playback/sources/:sourceId/content → PlaybackService.source → 크기·바인딩 검사 → 206 Range 스트리밍
```

- `playback/util/ffmpeg-profile.mjs`는 **PlaybackService, conversion/util/lpac-bindings(mediaLpacBinding), native lpac-media 요청 인코딩**이 공유합니다.
- 화면 `App.jsx`의 `mediaProps`가 `<video>`·`<audio>` 공통 처리를 담당하고, `components/PlaybackStage.jsx`가 이를 요소에 적용합니다. 세션 시작 시간 적용(`onLoadedMetadata`), 탐색 제한(`lastTime` 기준 0.5초 초과 이동 되돌림), 배속 제한(`controlsList`, `onRateChange`)을 여기서 처리합니다. 서버 Range 전달은 이 설정과 무관합니다.

### 5.7 뷰어 세션·진입 URL

- `/viewer?sessionId=` → 세션 복원(목록·테마·좌측 패널·선택 파일·시작 페이지/시간), `/viewer?mediaId=` → 단일 파일, `/viewer?url=` 또는 `#url=` → URL 수신 후 주소창 정리.
- 세션 파일 `sessions/<uuid>.json`, TTL 기본 30분, 기동·생성 시 만료분 삭제, 최대 1000개.
- 위치 저장: `App.saveSession` → `PATCH /viewer/sessions/:id` → `ViewerSessions.update`(선택 파일·페이지·시간만, `exclusive` 직렬화, 만료 불변).
  - 저장 시점: `select` 성공 직후, `mediaProps.onPause`(끝까지 재생했으면 0), `pagehide`(재생 중이면 `keepalive` 요청).
  - 세션 id는 `sessionRef`, 현재 파일은 `currentRef`가 들고 있습니다.
- 파일을 추가하거나 삭제하면 `sessionFor()`가 새 세션을 만들고 주소를 바꿉니다(기존 세션은 갱신하지 않음).

### 5.8 미디어 삭제

```
App.removeFile(file) → setConfirmRequest → ConfirmDialog(취소에 첫 포커스) → 확인 → App.deleteFile(file) → DELETE /api/v1/media/:id
MediaService.remove(id, actor)
  store.require → running(이미지)·playback.running 키가 mediaVersion으로 시작하면 409 MEDIA_BUSY
  MediaStore.remove(record)
    originals/<id>/source unlink (EBUSY/EPERM/EACCES → 409 MEDIA_BUSY, 무변경) → UploadCapacity.remove(size)
    record.json(+record.json.<uuid>.tmp) → rmdir
    editing/<id>-<page>.json(+임시) 삭제
  derived 캐시는 건드리지 않음(접근 404, 시작 시 기간/용량 정리)
화면: 목록 제거 → 성공 토스트 → sessionFor(남은 파일) → 보던 파일이면 다음 파일 select, 없으면 빈 화면
실패: 파일 목록 위 notice 표시(현재 화면 유지)
```

---

## 6. 저장소 레이아웃

```
storage.root (기본 .runtime/node/media)
├─ originals/<mediaId>/source, record.json        원본(자동 삭제 없음, DELETE API로만 삭제) + 메타(tenant/owner/sha256/mediaVersion)
├─ derived/
│  ├─ <mediaId>-<sourceId>/facts.json, preview.png, thumbnail.png   이미지 캐시
│  ├─ playback-<sourceId>/facts.json, playback                     재생 캐시
│  ├─ image-work/<uuid>/lease.sqlite, retired, job.json …          이미지 작업(회수 대상)
│  └─ playback-work/<uuid>/…                                      FFmpeg 작업
├─ multipart/transfers/owner.sqlite, <uuid>.part   업로드 임시 + 저장소 소유 잠금
├─ stamps/<stampId>.png, .json
├─ editing/<mediaId>-<page>.json                   메모/스탬프 문서 + undo/redo
└─ sessions/<sessionId>.json
runtimeVersions.root (기본 .runtime/node/runtimes)  owner.sqlite, catalog.sqlite, versions/<uuid>/{payload,runtime.use}
isolation.root      (기본 .runtime/node/lpac)      LPAC 작업 풀 image/, media/
```

---

## 7. 설정 → 코드 영향 맵

| YAML 섹션 | 읽는 곳 | 바꿀 때 함께 볼 것 |
| --- | --- | --- |
| `server` (host/port/allowedHosts) | application(Host 검사), main | `scripts/test-proxy-config*`, `config/proxy/*` |
| `storage` | MediaStore, UploadCapacity, settings(dist·runtime·lpac 루트 겹침 검사) | 6절 레이아웃 |
| `upload` | receiveUpload, store.register/verify, UploadCapacity, bootstrap limits | 화면 오류 메시지 |
| `processing` | ImageEngine(동시성/timeout/maxPixels), WindowsJobRunner(Job 한도), MediaService(previewEdge → sourceId) | previewEdge 변경 시 캐시 전체 무효 |
| `features` | store(mediaType), editing(주석·스탬프·반출의 밝기/회전), editing/routes(스탬프 조회), viewer bootstrap, lpac-factory, App.jsx(버튼 활성화, 주석 표시 필터, `mediaProps`의 탐색·배속 제한) | zoom·magnifier·seek·playbackSpeed는 화면 전용 제한(서버 강제 아님) |
| `remote` | importRemote, bootstrap `remoteImport` | `scripts/create-url-sample*` |
| `playback` | PlaybackService, WindowsJobRunner(playback) | `scripts/install-native-modules.ps1` |
| `sessions` / `temporary` / `cache` | ViewerSessions / TemporaryFiles·WorkDirectories / CacheStorage | 각 회수·정리 테스트 |
| `runtimeVersions` (+`verification`) | ManagedRuntimes, RuntimeVersions, RuntimeVerifierPool | RUNTIME_VERIFIER_CONFIGURATION.md |
| `isolation` | lpac-factory, LpacTransport, LpacWorkRecovery | LPAC_INSTALLATION_LOAD.md |

예제 스키마는 `config/simpleimgviewer.example.yml`, 기본값·검증은 `settings.mjs` 한 곳입니다. **키를 추가하면 둘 다 수정**합니다(알 수 없는 키는 400 `INVALID_CONFIGURATION`으로 기동 거절).

---

## 8. 변경 영향도 — "이걸 바꾸면 같이 확인할 것"

| 바꾸는 대상 | 함께 영향받는 곳 | 최소 재검증 |
| --- | --- | --- |
| `system/util/errors.mjs` (`requireThat`, `uuidPattern`, `integer`) | 백엔드 전체 | 전체 Node 테스트 |
| `media/util/files.mjs` `regular/digest/atomicJson/readJson/privateRoot` | store, media, editing, playback, sessions, cache, work, image-worker, note-font, windows-job | 전체 Node 테스트(+네이티브) |
| `media/util/files.mjs` `detect` | 업로드·URL·스탬프 판별, TestFiles 응답 | server.test, 화면 업로드 시나리오 |
| `conversion/util/image-worker.mjs` | ImageEngine identity(→ 모든 이미지 캐시 무효), LPAC 이미지 실행, **관리형 런타임 스냅샷 내부 경로 `backend/conversion/util/image-worker.mjs`** | image-job, lpac-bindings, managed-runtimes 테스트, `SIV_NATIVE_TESTS=1`, 관리형 사용 시 런타임 재게시 |
| `conversion/util/work-lease.mjs`, `bmp.mjs` | 위와 동일(identity 포함), WorkDirectories, LPAC 전송 | work-directories, image-job |
| `playback/util/ffmpeg-profile.mjs` | PlaybackService, lpac-bindings `mediaLpacBinding`, native lpac-media | windows-job, lpac-media-transport, lpac-bindings, `scripts/test-lpac-media-transport.mjs` |
| `*-lpac-*-request.mjs` (SIVLPAC1 바이너리 형식) | `native/process-job/lpac-request.h`, lpac-image.cpp, lpac-media.cpp | 네이티브 재빌드 + LPAC 전송 테스트 |
| sourceId 공식 / 캐시 디렉터리 이름 | MediaService(page/source), PlaybackService(convert/source), CacheStorage 정규식 | cache-storage, server.test |
| 오류 코드 추가·변경 | `viewer/messages.js` | 화면 오류·안내 표시 |
| 편집 문서 이름 `editing/<mediaId>-<page>.json` | `EditingService.docPath`, `MediaStore.remove`(삭제용 정규식), `atomicJson` 임시 이름 | server.test 삭제 테스트 |
| 원본 폴더 구성(`source`, `record.json`, 임시 파일) | `MediaStore.register/remove`, `UploadCapacity.init`(낯선 파일이 있으면 기동 거절) | upload-capacity, server.test 삭제 테스트 |
| 세션 필드 | `ViewerSessions.create/update/view`, App.jsx 복원·`saveSession` | sessions.test, server.test 세션 테스트 |
| 한도 값(주석 200, 메모 500, 스탬프 100/2MiB, 자르기 8MP) | editing.mjs, editing/routes, viewer/routes bootstrap, lpac-bindings, `components/ToolsPanel.jsx`(maxLength) | server.test |
| `settings.mjs` 키 | example.yml, 해당 서비스, `backend/test/system/config/*` | settings 테스트 + 기동 확인 |
| `native/process-job/main.cpp` | 개발 모드 manifest 해시(`PROCESS_JOB_BUILD_STALE`) | `scripts/build-process-job.ps1 -TestTools` 후 전체 테스트 |
| API 응답 필드 | App.jsx 해당 함수, `scripts/test-node-viewer.mjs`(브라우저 시나리오) | 빌드 + 브라우저 시나리오 |
| `frontend/src/viewer/App.jsx`(상태·흐름) | 이를 props로 받는 `components/*` 전체 | `node scripts/build-web.mjs` + 브라우저 시나리오 |
| `frontend/src/viewer/components/*` | 해당 화면 영역만(props는 App에서 전달) | 빌드 + 해당 영역 브라우저 확인 |
| `App.jsx` 단축키(`keyHandler`) | `ShortcutHelp.shortcuts`, `CanvasToolbar` title·`aria-keyshortcuts`, 주석 aria-label 안내 문구 | 도움말 표와 실제 키 동작 대조, 입력란에서 무시되는지 |
| `App.jsx` 저장 경로(`saveItem`·`historyChange`·`queueEdit`·`docRef`) | 드래그·방향키 이동·크기 수정·실행 취소가 모두 같은 revision 사용 | 연속 방향키 이동 후 revision 1회 증가·409 없음, 메모 더블클릭 시 저장 요청 0건, 연달아 끌기, 실행 취소/다시 실행 |
| `viewer.css` 토큰(`--color-*`, `--font-size-*`, `--space-*`) | 모든 화면 요소, 다크·라이트 양쪽 | 다크·라이트에서 12px 미만 글자 0, 대비 4.5 미만 0, 24px 미만 대상 0(UX_DESIGN_TOKENS.md 측정 방법) |
| `viewer.css` 760px 미디어 쿼리 / `narrowQuery` | MobileBar, 패널 시트, 헤더 축약, 토스트 위치, 세션 패널 복원 | 375×812에서 넘침 없음, 시트 열고 닫기, 저장 버튼·토스트 보임 |
| 새 화면 동작의 실패 처리 | `run`(전체 화면 오류) vs `act`(토스트) 선택 | 실패시켜서 이미지가 가려지지 않는지(편집 동작은 `act`) |
| `annotationDown`·`pointerDown` 배치·`wheelZoom` | 메모·스탬프 놓기, 끌기, 두 번 눌러 편집, 휠 확대(회전·패닝 상태 포함) | 실제 마우스로 놓기 위치(클릭점 = 가운데), 두 번 누름 → 입력란 포커스·저장 0건, 회전 후 휠 확대 시 커서 아래 지점 유지 |
| `download` 파일 이름·SaveBar 문구 | `scripts/test-node-viewer.mjs`(버튼 이름 `편집본 저장`), 사용성 진행안 T6·T7 | 원본/편집본 저장 파일 이름과 알림 문구 |
| `frontend/src/viewer/api.js` | 모든 서버 호출(CSRF 헤더·오류 코드 변환) | 빌드 + 브라우저 전체 시나리오 |

---

## 9. 테스트·스크립트 맵

### 9.1 Node 테스트 (`backend/test`, `pnpm test` = `node --test "backend/test/**/*.test.mjs"`)

| 파일 | 주 대상 | 비고 |
| --- | --- | --- |
| system/controller/server.test.mjs (23) | application 전체 HTTP: 업로드·URL·페이지·주석·반출·세션(생성·갱신)·삭제·보안 헤더·CSRF·기능 설정 | 통합 회귀의 중심 |
| system/config/* | lpac-settings, runtime-verifier-settings | |
| media/service/* | temporary-files, upload-capacity | |
| viewer/service/sessions.test.mjs | ViewerSessions 만료 정리·생성 한도 | |
| conversion/service/* | cache-storage, image-job(ImageEngine + Job 실행기, 네이티브 필요), work-directories, lpac-image-transport, lpac-work-recovery, managed-runtimes, managed-verifier, note-font, runtime-verifier-pool, runtime-versions | |
| conversion/util/* | windows-job, lpac-bindings, runtime-inventory | |
| playback/service/* | playback-native(실제 FFmpeg, `SIV_NATIVE_TESTS=1`), lpac-media-transport | |
| fixtures/, conversion/fixtures/ | 자식 프로세스 진입점 등 공용 fixture | `scripts/test-lpac-*`, `test-runtime-*`도 사용하므로 위치 유지 |

테스트는 대상 기능·역할 폴더(`backend/test/<기능>/<역할>/`)에 둡니다. 옮길 때는 상대 import(`../../../<기능>/…`)와 fixture 경로(`../../fixtures/…`)를 함께 고칩니다.

- 2026-11-17 실행 결과: `SIV_NATIVE_TESTS=1`에서 **145/145 통과**(약 27초). 환경 변수가 없으면 네이티브 16개를 건너뜁니다. 2026-11-19 UX 리뉴얼 후 다시 145/145 통과(약 26초).
- 화면(React) 단위 테스트는 없습니다. 화면 검증은 `scripts/test-node-viewer.mjs`(Playwright + Chrome, `IMAGEGATE_NODE_MODULES`/`IMAGEGATE_BROWSER` 환경 변수 필요, Playwright는 의존성에 없음)뿐입니다.

### 9.2 scripts 분류

| 분류 | 파일 |
| --- | --- |
| 실행·빌드 | dev.mjs, start.mjs/.ps1/.sh, build-web.mjs, audit-node-licenses.mjs |
| 설치 | install-node-runtime.ps1, install-native-modules.ps1, install-note-font.mjs, create-url-sample.* |
| 네이티브 빌드 | build-process-job.ps1, build-lpac-image/media.ps1, build-*-lpac*.ps1, build-libvips-*.ps1, build-storage-watch.ps1, prepare-libvips-headers.ps1 |
| 스테이징 | stage-sharp-lpac.mjs, stage-ffmpeg-lpac.mjs |
| 수동 검증·측정 | usability-test-server.mjs(사용성 테스트용 임시 저장소 서버·표본 생성, [UX_USABILITY_TEST.md](UX_USABILITY_TEST.md)), test-node-viewer.mjs(브라우저), test-lpac-*, test-*-lpac.mjs, test-runtime-*, test-managed-runtime-services.mjs, test-proxy-config*, test-storage-watch.mjs, test-native-*.ps1 |

---

## 10. 문서 맵

| 구분 | 문서 |
| --- | --- |
| **현재 기준(먼저 읽기)** | 이 문서, [REMAINING_WORK.md](REMAINING_WORK.md), [ARCHITECTURE.md](ARCHITECTURE.md), [NODE_REACT_MIGRATION.md](NODE_REACT_MIGRATION.md), README.md, AGENTS.md |
| 화면 UX 리뉴얼 | [UX_HEURISTIC_REVIEW.md](UX_HEURISTIC_REVIEW.md) — 휴리스틱 점검 결과, 항목 ID(U·A·M), 리뉴얼 단계별 배정 · [UX_DESIGN_TOKENS.md](UX_DESIGN_TOKENS.md) — 색·글자·간격·모서리·그림자 토큰 체계안과 대비 검사 · [UX_LAYOUT_OPTIONS.md](UX_LAYOUT_OPTIONS.md) — 레이아웃 시안 A·B·C 비교(시안: `docs/prototypes/viewer-layouts.html`) · [UX_USABILITY_TEST.md](UX_USABILITY_TEST.md) — 사용성 과업 테스트 진행안·기록지·AI 파일럿 결과 |
| 현재 Node 구현 상세 | LPAC_*.md, MANAGED_RUNTIME_EXECUTION.md, RUNTIME_VERSION_LIFECYCLE.md, RUNTIME_VERIFIER_*.md, RUNTIME_VERIFICATION_*.md, RUNTIME_RESPONSIVENESS.md, NODE_LPAC_INTEGRATION.md, FFMPEG_LPAC_INTEGRATION.md, LEGACY_SOURCE_REMOVAL.md, INSTALLATION.md |
| Java 시절 기록(현재 실행 규칙 아님) | DEVELOPMENT_PLAN.md, API_CONTRACT.md, TILE_*.md, IMG2TILE_*.md, LIBVIPS_*.md, PREVIEW_*.md, ORIGINAL_*.md, PUBLICATION_*.md, LINKED_*.md, BACKUP_*.md, AUDIT_LOGGING.md 등 — 요구사항·설계 의도 참고용 |

---

## 11. 화면(frontend) 구조

```
frontend/src/main.jsx → viewer/App.jsx
viewer/
├─ App.jsx        상태(useState/useRef)와 업무 흐름: bootstrap, select, openFiles/openUrl, removeFile→deleteFile,
│                 saveItem/historyChange/uploadStamp/download, saveSession, 포인터(pan/crop/주석 드래그/핀치),
│                 단축키(keyHandler·keyUpHandler), 끌어다 놓기(dropHandlers), 알림(notify/act), mediaProps
├─ api.js         createApi(csrfRef) → api(path,{method,json,body,binary,signal,keepalive}); 실패 시 Error(code)
├─ messages.js    오류 코드 → 한국어 문구, describe(전체 화면 오류) / brief(파일 목록 위 안내)
├─ format.js      clamp, bytes
├─ launch.js      ?url= / #url= / ?sessionId= / ?mediaId= 해석(모듈 로드 시 1회, url 진입은 주소창 정리)
├─ viewer.css     디자인 토큰(:root 다크 기본, [data-theme=light]) → 컴포넌트 규칙 → 760px 이하 시트 배치
└─ components/    props만 받아 그리는 화면 조각
   ├─ Header.jsx         패널 전환(좁은 화면에선 숨김), 이미지일 때만 확대·맞춤·회전·돋보기, 도움말, 테마, 파일/URL 열기(file-input)
   ├─ Icon.jsx           SVG 아이콘 33종(name → path, stroke=currentColor, aria-hidden)
   ├─ IconButton.jsx     아이콘 전용 버튼(aria-label·title 필수, active → aria-pressed)
   ├─ FilePanel.jsx      파일 목록(아이콘 썸네일), notice, 삭제 버튼(→ App.removeFile → ConfirmDialog)
   ├─ Viewport.jsx       이미지 surface·주석(포커스 가능, role=button)·자르기 영역, 안내/빈 화면, 페이지 이동,
   │                     toolbar 슬롯(= CanvasToolbar), children(= PlaybackStage)
   ├─ CanvasToolbar.jsx  이미지 위 떠 있는 도구 막대: 이동·메모·스탬프·자르기(V/N/S/C), 실행 취소·다시 실행
   ├─ PlaybackStage.jsx  <video>/<audio>에 mediaProps 적용
   ├─ ToolsPanel.jsx     패널 틀(스크롤 영역 + footer) + ImageProperties(메모 내용·선택 항목·내 스탬프·밝기·파일 정보)
   │                     / SaveBar(자르기 정보·자르기 저장·편집본 저장, 패널 하단 고정) / PlaybackInfo / EmptyTools
   ├─ MobileBar.jsx      760px 이하 아래 막대: 파일 목록 · 속성(재생 정보) 시트 열기
   ├─ Toast.jsx          알림 한 개(오류는 role=alert, 그 외 status), 선택 동작 버튼, 5초(오류 8초) 후 닫힘
   ├─ ConfirmDialog.jsx  <dialog showModal> 확인 창, 처음 포커스는 취소, Esc = 취소
   ├─ ShortcutHelp.jsx   단축키 표(shortcuts 배열) + 마우스·터치·끌어다 놓기 안내
   ├─ DropOverlay.jsx    파일을 끌고 들어오면 보이는 놓기 영역
   ├─ Lens.jsx           돋보기
   └─ UrlDialog.jsx      URL 입력 창(입력값 상태 자체 보유, 닫히면 초기화)
```

- 서버 호출과 오류 처리는 App에서 감싼 콜백을 컴포넌트에 넘깁니다. 컴포넌트에서 `fetch`를 직접 하지 않습니다. 실패 경로는 두 가지입니다.
  - `run(()=>…)`: 파일 표시에 필요한 작업(선택·업로드·URL 열기). 실패하면 `setError` → 전체 화면 오류와 "다시 시도".
  - `act(()=>…)`: 편집·저장·삭제처럼 파일은 그대로 보이는 작업. 실패하면 `notify('error', …)` 토스트만 띄우고 화면을 가리지 않습니다(U13). 성공 안내도 `notify('success', …)`.
- 단축키는 `window` keydown/keyup → `keyHandler`/`keyUpHandler` ref(매 렌더 최신 상태를 봄)입니다. 입력란·열린 대화상자에서는 무시합니다. 키를 바꾸면 `ShortcutHelp.shortcuts`와 CanvasToolbar의 `aria-keyshortcuts`·title도 같이 고칩니다.
  - 메모·스탬프가 선택되어 있으면 방향키는 페이지 대신 항목 이동(`nudgeSelected`, 회전·배율 반영)이고, 키를 뗀 뒤 400ms 디바운스로 한 번만 저장합니다.
  - 편집 저장(`saveItem`)과 실행 취소·다시 실행(`historyChange`)은 `queueEdit`로 한 줄로 보냅니다. 각 요청은 앞 요청의 응답을 `applyDocument`(docRef와 상태를 함께 갱신)로 반영한 뒤 `docRef.current.revision`을 읽으므로, 빠른 연속 편집에도 409 EDIT_CONFLICT가 나지 않습니다.
  - 주석을 누르기만 하고 움직이지 않으면 선택만 하고 저장하지 않습니다(`d.changed`가 null).
- 끌어다 놓기는 `.app` 루트의 `dropHandlers`(dragDepth 카운터로 자식 요소 진입·이탈 깜빡임 방지) → `openFiles(파일 배열)`입니다.
- 터치: `touches` Map에 포인터를 모아 두 손가락이면 `drag.kind='pinch'`, 이동 도구에서 두 번 탭하면 `fit()`. `pointerUp`은 자르기·주석 드래그의 마지막 위치를 반영한 뒤 끝냅니다.
- 좁은 화면(`(max-width:760px)`)에서는 시작 시 두 패널을 닫고, 세션에 저장된 패널 열림 상태를 복원하지 않습니다. 파일을 고르면 파일 목록 시트가 닫힙니다.
- 스탬프 없이 스탬프 도구를 고르면 `askForStamp()`가 "스탬프 고르기" 동작이 있는 안내 토스트를 띄웁니다(U10). 동작 버튼이 있는 토스트는 10초, 오류는 8초, 나머지는 5초 뒤 닫힙니다.
- 사용성 파일럿 반영(UX_USABILITY_TEST.md 7.4)
  - `wheelZoom`: 커서 아래 지점을 고정한 채 확대·축소합니다. 헤더 버튼·`+`/`−` 키는 화면 가운데 기준(`manualZoom`)입니다. `viewRef`는 다시 그리기 전에 연달아 오는 휠 이벤트가 최신 zoom/pan을 쓰게 합니다.
  - `pointerDown`에서 메모·스탬프는 클릭한 점이 가운데가 되도록 놓습니다.
  - `annotationDown`: 같은 메모를 400ms 안에 두 번 누르면 `editNote`가 실행됩니다(속성 패널을 열고 `noteInput`에 포커스). 포인터 캡처 때문에 브라우저 dblclick은 화면 영역으로 가므로 `onDoubleClick`을 쓰지 않습니다. 포커스된 메모에서 Enter도 같은 동작입니다.
  - `removeItem`: Delete 키와 패널 삭제 버튼 모두 이 함수를 거칩니다. "…를 지웠습니다 · 실행 취소" 토스트를 띄우고, 실행 취소는 `historyChange('undo')`입니다.
  - `download`: 파일 이름은 `<원본 이름>-원본|편집본[-부분].png`입니다. "원본 잘라 저장"(crop API, 주석 없음)과 "편집본 저장"(export API, 주석 합성)은 같은 자르기 영역을 씁니다.
- JSX는 자동 런타임으로 변환되므로 새 컴포넌트에 `import React`가 필요 없습니다.
- 이미지 위 포인터 처리는 `setPointerCapture`(실패해도 무시하는 `capturePointer`)를 쓰므로 브라우저 확인 시 스크립트 이벤트가 아니라 실제 마우스 입력이 필요합니다.
- 앱 내장 브라우저 패널이 숨겨져 있으면 `requestAnimationFrame`이 돌지 않아 첫 맞춤이 100%로 남고, 창에 포커스가 없으면 스크립트 `focus()`가 focus 이벤트를 내지 않습니다. 확인할 때 `0` 키로 맞추고, 포커스 동작은 실제 키 입력으로 봅니다.
- 내려받기는 Blob → `URL.createObjectURL` → `<a download>`입니다. CSP `connect-src 'self'` 때문에 `blob:` 주소를 `fetch`로 다시 읽을 수 없습니다.

## 12. 작업할 때 쓰는 절차

1. 이 문서 4~5절에서 대상 기능의 호출 체인을 찾고, 8절에서 영향 범위를 확인합니다.
2. 수정 후 최소 검증:
   ```powershell
   $node = '.tools/runtimes/node-24.22.1/node.exe'
   $env:SIV_NATIVE_TESTS = '1'; & $node --test "backend/test/**/*.test.mjs"
   & $node scripts/build-web.mjs
   ```
3. 화면·API 변경이면 브라우저 시나리오(`scripts/test-node-viewer.mjs`) 또는 `node scripts/dev.mjs` 실행 후 실제 브라우저로 확인합니다.
4. 호출 체인이나 파일 구성이 바뀌면 이 문서의 해당 절을, 작업이 끝나거나 새로 생기면 REMAINING_WORK.md를 갱신합니다.
