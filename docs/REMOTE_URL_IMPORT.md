# URL로 이미지·영상·음성 열기

2026-11-12. 서버가 URL 파일을 수신·검증해 비공개 원본으로 등록한 뒤 기존 변환/재생/뷰어 세션을 사용한다. 브라우저나 변환 프로그램에 원격 URL을 직접 소스로 넘기지 않는다. 외부 라이브러리를 추가하지 않았다.

## 사용 방법

IntelliJ의 SimpleImgViewer 구성으로 서버를 재시작한다. 화면 오른쪽 위 ↗(URL로 파일 열기)에 주소를 입력하거나 업무 시스템에서 /viewer?url=인코딩한파일주소 또는 /viewer#url=인코딩한파일주소를 연다. 전체 파일 주소를 encodeURIComponent/URLSearchParams로 인코딩한다.

사용자가 지정한 파일 주소를 여는 예:

```text
http://192.168.0.86:8080/viewer#url=http%3A%2F%2F192.168.0.86%3A8080%2Fviewer%2Fresources%2FTestFiles%2Fsample.jpg
```

파일 주소 자체를 열면 원본 파일 응답이다. 뷰어 UI는 위처럼 /viewer에 전달한다. 성공하면 주소는 /viewer?sessionId=...로 바뀐다. url과 sessionId/mediaId의 동시 전달이나 중복 url은 거절한다. #url은 최초 HTTP 요청에 파일 주소를 보내지 않는다. ?url도 처리 초기에 제거하지만 이미 전송된 접근 로그를 지우지는 않는다.

개발 TestFiles의 물리 경로는 프로젝트 .runtime/development/url-files/TestFiles다. sample.jpg는 이번에 생성한800×500 합성 JPEG이며 업무 자료가 아니다. 새 PC에서는 JAVA_HOME을 JDK21로 지정하고 ./scripts/create-url-sample.ps1을 실행한다. 기존 파일은 읽거나 덮어쓰지 않는다. 자신의 테스트 파일도 이 폴더에 넣을 수 있다. 하위 폴더/목록/경로 이동은 지원하지 않는다.

개발 관리자와 development-resources-enabled가 모두 필요하며, 비활성 상태에서 정적 JAR 파일로 우회하지 않도록 이 URL 접두사 전체를 컨트롤러가 소유한다. 원본/수신 임시 폴더와 겹치는 설정은 거절한다. 기존 src/main/resources/TestFiles의 로컬 파일은 변경하지 않으며 Git과 Gradle 리소스 처리/JAR에서 제외한다. 업무 자료를 src/main/resources 또는 Git에 넣지 않는다.

## API

GET /api/v1/viewer/bootstrap에서 CSRF 헤더/토큰을 받은 뒤 같은 세션으로 POST /api/v1/media/import-url을 호출한다.

```json
{"url":"http://192.168.0.86:8080/viewer/resources/TestFiles/sample.jpg","fileName":"sample.jpg"}
```

url은 최대8192자, fileName은 선택값(1~255자)이며 생략 시 URL의 마지막 파일명을 사용한다. 응답201은 기존 MediaResponse(mediaId/권한/처리 상태)다. POST /api/v1/viewer/sessions에 {"mediaIds":["발급된 mediaId"]}를 보내 뷰어 세션을 만든다. 기존 initialPage/initialTimeSeconds/theme/leftPanelOpen도 사용할 수 있다. 미리보기·변환·재생 준비 조건은 유지한다.

## 설치 설정과 보안

application.yml과 config/imagegate.example.yml은 기본 비활성이다. application-dev.yml에만 사용자 지정 TestFiles 접두사를 허용했다.

```yaml
imagegate:
  remote-import:
    enabled: true
    allowed-prefixes:
      - http://192.168.0.86:8080/viewer/resources/TestFiles/
    max-file-bytes: 104857600
    timeout: 30s
    connect-timeout: 5s
    max-concurrent: 2
    development-resources-enabled: true
    development-resources-directory: ./.runtime/development/url-files/TestFiles
```

- 현재 HTTP/HTTPS의 고정 IP 주소만 지원한다. DNS 이름/와일드카드/사용자 정보/파일 URL은 거절한다. 명시한 IPv4/IPv6 origin과 디렉터리 접두사만 허용한다. loopback/사설망도 설정한 주소만 사용하며 unspecified/link-local/멀티캐스트는 거절한다. HTTPS는 JDK 인증서 검증을 사용하므로 IP SAN/신뢰 설정이 필요하다. 주소/포트가 바뀌면 허용 설정도 바꾼다.
- 접두사는 /로 끝나야 한다. dot segment, 인코딩 구분자/제어 문자/중첩 인코딩을 거절한다. 리디렉션은 같은 서버라도 따라가지 않는다.200 외 응답과 압축 응답도 거절한다. Content-Type/파일명 대신 기존 헤더 검사로 원본 종류를 결정한다.
- Cookie/Authorization/사용자 헤더를 파일 서버에 전달하지 않고 시스템 프록시를 쓰지 않는다. 로그인 쿠키가 필요한 외부 파일 서버는 현재 수신하지 못한다. 공개 파일이나 서버가 검증하는 만료형 URL을 사용한다. 전체 URL/토큰은 애플리케이션 오류/감사 로그에 기록하지 않는다. 운영자가 별도로 켠 HTTP 디버그/프록시/접근 로그는 별도 점검한다.
- 전체 수신 기한1초~2분, 연결 기한1~10초(전체 이하), 최대100MiB이며 공통 upload.max-file-bytes 이하다. URL 슬롯1~4와 기존 사용자/전체 업로드 슬롯을 다운로드 전에 확보한다. 기존 multipart 전용 폴더에 제한된 임시 파일을 만들고 여유 공간을 검사한다.
- 크기 초과·실패·시간 초과 때 수신 취소/파일 닫기/삭제를 수행한다. 불완전 본문을 원본으로 게시하지 않는다. 수신 폴더는 서비스 계정 전용으로 둔다.
- 임시 파일은 multipart-directory 아래 url-import/UUID에 둔다. 시작 시와 작업 접수 시 회수하며, lease 파일의 배타 잠금을 유지하는 실행 중 작업은 건너뛴다. 소유 기록·정규 경로·고정 파일 목록이 일치하는 작업의 payload.part/lease와 빈 작업 폴더만 삭제한다. 잘못된 기록·알 수 없는 파일·링크는 보존하며 재귀 삭제하지 않는다. 최대128개 작업을 검사하고 용량이 차면 접수를 거절한다. 기록에는 URL이나 토큰을 남기지 않는다. 파일 잠금이 정상 작동하는 로컬 디스크와 서비스 계정 전용 폴더가 전제이며 공유 네트워크 파일 시스템은 검증 대상이 아니다.
- 인증·CSRF·조직/파일·설치 기능 정책은 유지한다. 설치 허용 목록이 원격 원본의 업무 권한을 대신하지 않는다. 파일 서버의 공개/서명 정책과 실제 연계 계약이 필요하며 URL 등록만으로 미지원 코덱이 지원되지는 않는다.

오류: URL_IMPORT_DISABLED(403), URL_NOT_ALLOWED(400), URL_FETCH_FAILED(502), URL_FETCH_TIMEOUT(504). 크기413/접수429/미지원415/저장 공간507은 기존 계약이다. 원격 서버401을 뷰어 사용자401로 전달하지 않는다.

## 검증과 남은 작업

정책27개와 합성 HTTP9개에서 허용 경로·위험 입력·Content-Length/chunked·크기/시간/리디렉션/압축/HTML 거절·CSRF/슬롯·원본 바이트·실제 미리보기/뷰어 세션을 검증했다. 기존 UI에 URL 입력/쿼리/프래그먼트/거절/비활성 검사를 더한44개 브라우저 시나리오도 통과했다.

실제 JAR을 별도 루프백 포트에서 실행해 합성 JPEG의 URL 수신→비공개 원본→미리보기→800×500 브라우저 표시, CSRF와 주소 제거를 확인했다. 브라우저는 원격 URL을 직접 이미지로 요청하지 않았다. 시험 서버는 종료했으며 기존8080 서버는 중단하지 않았다.

최종 기본/img2tile/FFmpeg946개·126 suites가6분27초에 실패·오류·건너뜀0으로 통과했다. 라이선스16규칙과 고정 목록63구성요소·53포함 JAR 대조도 통과했다. 목록 일치는 native 배포 승인이나 최종 운영 인수를 뜻하지 않는다. 정확한 최종 범위는 build/reports/remote-url/verification.json과 full-regression.json을 따른다. 실제 업무 파일을 검증 입력으로 사용하지 않았으며 설치 ZIP은 생성하지 않았다.

임시 작업 저장소5개 시험에서는 정상 정리, 실행 중 작업 보호, 잘못된 소유 기록·알 수 없는 파일 보존, 실제 자식 JVM 강제 종료 후 회수를 확인했다. URL HTTP/정책·뷰어 API·개발 로그인·설정 검증을 포함한 집중73개가 통과했다.

재현 도구: scripts/test-viewer.mjs, scripts/test-remote-url-viewer.mjs. Playwright/Chrome은 개발 검증용이며 실행 JAR에 넣지 않는다. 다음은 libvips 부모 Java 어댑터/설치 옵션·권한·캐시·게시 연결, native 고지/저장소 강제 한도/연계 운영 인수 순서다.
