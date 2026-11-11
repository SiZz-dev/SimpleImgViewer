# 원본 파일 등록 자원 보호

등록 처리의 자원 상한이며 사용자에게 업로드 권한을 부여하지 않습니다. 신뢰 인증 주체 확인 및 기존 형식/설치 정책·파일별 접근 통제를 유지합니다. 동시 처리 제한은 단일 JVM의 MediaService.register 수명(헤더 확인, 원본 복사·해시, 메타데이터 게시)에 적용됩니다. 등록 후 미리보기 큐 접수·변환, GET/HEAD 조회·재생 Range에는 이 슬롯을 사용하지 않습니다.

## 설치 YAML

~~~yaml
imagegate:
  upload:
    tenant-original-max-bytes: 0
    max-file-bytes: 104857600
    max-concurrent: 4
    max-concurrent-per-user: 2
    min-free-bytes: 536870912
    multipart-directory: ./.runtime/multipart
    multipart-overhead-bytes: 65536
    multipart-max-concurrent: 4
    body-read-idle-timeout: 30s
~~~

- max-file-bytes: 1~104857600바이트(100MiB), 실제 원본 스트림에 적용합니다. 파일명/클라이언트 길이 선언을 신뢰하지 않습니다. 초과 시 413 FILE_TOO_LARGE.
- max-concurrent: 1~16. 대기 큐 없이 초과 요청을 429 UPLOAD_BUSY로 거절합니다. 이미 복사 중인 등록을 취소하지 않습니다.
- max-concurrent-per-user: 1~8이며 전체 상한 이하여야 합니다. 신뢰 principal의 tenant/user 조합을 정확한 대소문자로 구분합니다. 사용자 식별 정보는 진행 중인 슬롯에만 유지하며 마지막 슬롯 해제 시 제거합니다.
- min-free-bytes: 0~1TiB, 기본 512MiB. 0이면 여유 공간 검사만 생략합니다. 실제 원본 저장 볼륨의 FileStore usable space를 검사하며 부족 시 507 INSUFFICIENT_STORAGE. 파일 크기/동시 처리 제한은 계속 적용됩니다.

파일 크기·여유 공간 검사는 기본 LocalMediaStore에 적용합니다. 별도의 MediaStore 구현을 연동하면 해당 구현에서 같은 저장 정책을 적용해야 합니다.

원본 복사 시작 전, 첫 쓰기 전, 이후 약 1MiB 간격 및 메타데이터 게시 전에 공간을 검사합니다. 공간 확인 자체가 실패하면 기존 503 STORAGE_UNAVAILABLE로 처리합니다. 경로/실제 여유 바이트/원시 예외는 오류 응답에 노출하지 않습니다. 오류/지원하지 않는 형식/설치 정책 거부/스트림 열기 실패/복사 실패에서도 등록 슬롯을 해제하고, 생성한 미게시 파일을 기존의 제한된 정리 경로로 정리합니다. 기존 원본/주석/스탬프는 삭제하지 않습니다.

## 적용 범위와 운영 한계

원본 등록 동시성은 Servlet multipart 파싱 이후에 적용됩니다. 현재 Servlet parser에 파일/요청 단위 상한을 적용하며 CSRF 앞의 multipart 수신 슬롯도 제한합니다. 다만 TCP 연결 수·누적 임시 디스크 바이트·느린 연결의 유지 시간·프록시 버퍼링 합계를 하드 할당량으로 제한하지 않습니다. 설치자는 프록시/서버 연결·속도·시간 제한과 multipart 전용 볼륨/OS 계정 할당량을 함께 구성해야 합니다.

여유 공간 검사는 동시 원본/변환/외부 프로그램의 쓰기 사이에서 원자적으로 예약하지 않습니다. 검사 간격과 동시 쓰기 때문에 지정한 여유 공간을 항상 보장하거나 실제 disk-full을 완전히 방지하지 못합니다. 여유 공간 검사는 조직별 누적 계수나 원본 자동 삭제 기능과 구분합니다. 조직별 원본 논리 바이트 제한은 선택 옵션 tenant-original-max-bytes로 적용하며 아래 문서를 따릅니다. 다른 JVM/노드와 동시 처리 상한을 공유하지 않습니다. 네트워크 파일시스템/ACL/파일 잠금으로 임시 정리가 실패하면 별도 운영 점검이 필요합니다.

서버 재시작 시 진행 슬롯은 사라집니다. 비정상 종료로 남은 .upload 디렉터리를 자동 삭제하지는 않습니다. 저장소를 여러 프로세스가 동시에 수정하지 않도록 전용 서비스 계정과 독립 저장 경로를 사용하세요. quota 비활성 모드에서 관리자가 원본 저장 경로를 심볼릭 링크/정션으로 구성한 경우 여유 공간은 실제 대상 파일시스템 기준입니다. quota 활성 모드는 canonical 경로를 직접 지정해야 합니다.

## 검증

단위 테스트: 전역/사용자별·대소문자/조직 분리, 12개 동시 시도의 최대 4개 접수, 슬롯 중복 해제 방지, 스트림 열기 전 거절, 예외/형식·정책 거부 후 해제. 저장소 테스트: 쓰기 전 공간 부족, 복사 중 공간 감소, 게시 직전 공간 검사 오류, 미게시 파일 정리와 복구 후 재등록, 기존 원본 조회, 공간 검사만 비활성화.

실제 Spring API 테스트: 429/no-store/인증 우선, 설치 파일 크기 초과 413 및 후속 정상 등록. 설치 YAML의 상한·교차 조건·알 수 없는 키도 검증합니다. 공간 부족 검증은 테스트 probe를 사용하며 운영 디스크를 실제로 가득 채우지 않습니다. 운영 프록시/디스크 장애/다중 노드 부하는 별도 검증 대상입니다.

최종 검증: JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java 249개, 실패 0·오류 0·건너뜀 0개. UI 변경이 없어 브라우저 시나리오는 이번 단계에서 재실행하지 않았습니다. 설치 ZIP은 생성하지 않았습니다.

## Servlet 수신 상한과 임시 경로

media/config.UploadIngressConfiguration이 MultipartConfigElement를 제공합니다. 공통 파일 상한은 max(imagegate.upload.max-file-bytes, imagegate.editing.max-stamp-upload-bytes), 전체 요청 상한은 그 값 + imagegate.upload.multipart-overhead-bytes입니다. 정상 파일 하나와 MIME 경계/헤더 등 부가 데이터를 받기 위한 여유이며 여러 파트로 파일 상한을 우회해 요청 전체를 무한히 늘릴 수 없습니다. file-size-threshold는0으로 고정해 수신 파일 본문을 전부 JVM 힙에 쌓지 않고 전용 임시 디스크를 사용합니다. 파서 내부 버퍼까지0이라는 뜻은 아닙니다.

원본과 스탬프는 하나의 Servlet parser를 공유합니다. 원본 상한을128바이트로 줄여도 스탬프 상한이5MiB이면 공통 parser는5MiB를 받습니다. 각 기능의 더 작은 개별 상한은 기존 원본/스탬프 스트림 검사에서 계속 적용됩니다. 양쪽을 작게 설정하면 공통 상한도 함께 줄어듭니다. 형식 식별/디코딩/권한/설치 정책·동시 복사 슬롯을 대체하지 않습니다.

- multipart-directory: 현재 작업 디렉터리 기준 상대 경로 또는 절대 경로. 기본 ./.runtime/multipart이며 storage.root 기준이 아닙니다. 서비스 계정 전용으로 보호하고 공개 웹 경로에 매핑하지 않습니다. 디렉터리가 없으면 기동 시 생성합니다. 기존 상위 경로의 링크/정션은 거절하며 원본·변환·주석·스탬프 경로와 양방향 중첩(실제 canonical 별칭 포함)은 기동 실패입니다. OS ACL/디스크 암호화/볼륨 할당량은 운영자가 적용합니다.
- multipart-overhead-bytes: 1024~1048576, 기본65536바이트. 전체 직렬화된 multipart 요청(파일·추가 파트·경계·헤더)을 위한 여유입니다. 지나치게 작게 지정하면 허용 크기의 파일도 파일명/필드/경계 길이에 따라413이 될 수 있습니다.

이제 spring.servlet.multipart.max-file-size/max-request-size/file-size-threshold/location/enabled/resolve-lazily 직접 설정은 충돌 방지를 위해 기동 시 거절합니다. 기존 설치 파일·환경 변수에 이 키가 있다면 제거하고 imagegate.upload 및 editing 설정으로 이관하세요. 무제한 multipart나 임의 파서 비활성 설정으로 우회하지 않습니다. 설정 변경 후 서버 재시작이 필요합니다. 이 정책은 현재 내장 Servlet 배포에 적용되며 별도 컨테이너/프록시는 해당 계층 설정도 검증해야 합니다.

정상 요청 종료와 크기 초과 오류의 임시 파일은 Servlet 컨테이너가 정리합니다. 비정상 프로세스 종료/OS 파일 잠금/디스크 장애 시 잔재까지 자동 삭제하는 기능은 아닙니다. 기존 오프라인 캐시 정리 도구의 범위에도 multipart 경로는 포함되지 않습니다. min-free-bytes는 원본 저장 볼륨에만 적용되며 수신 임시 볼륨의 여유 공간 예약/하드 할당량을 보장하지 않습니다.

수신 상한은 Content-Length 선언 유무에 관계없이 실제 parser에 적용합니다. 큰 요청에 대한 응답 후 연결 재사용/남은 본문 소비는 Tomcat·클라이언트·프록시 설정에 따라 달라질 수 있습니다. 운영 프록시의 본문 제한은 애플리케이션 공통 전체 요청 상한과 맞추고 실제 환경에서413/연결 동작을 확인하세요.

실제 수신 회귀: 별도 루프백 Tomcat에1024바이트 공통 파일 상한/1024바이트 여유를 적용하고 JDK HttpClient로 정상 업로드, 파일 초과, 길이 미지정 chunked 전체 요청 초과, 오류 후 정상 요청·임시 폴더 비움, 실제 CSRF403/익명 bootstrap401을 확인합니다. 테스트 인증은 src/test의 무작위 토큰→ViewerPrincipal fixture이며 운영 JAR에는 포함하지 않습니다. 컨트롤러 선택 전 파싱 실패에도413 FILE_TOO_LARGE/no-store가 보장되도록 좁은 전역 예외 핸들러를 둡니다. 설정 테스트는 두 기능의 상한 우선순위·고정 threshold0·네 저장소/상위 경로 중첩·정션·경쟁하는 Spring 설정·상한 검증을 포함합니다. 실제 SSO/프록시/대규모 병렬 수신/장애 중 임시 디스크 정리 검증은 별도입니다.

인증/CSRF·프록시가 먼저 거절하는 경우에는 해당 계층의401/403/기타 응답이 우선합니다. 전역 multipart 예외 핸들러는 DispatcherServlet에서 발생한 크기 초과에 적용하며 보안 필터/컨테이너 밖의 모든 오류 응답을 통일하는 필터가 아닙니다. 클라이언트는 bootstrap에서 받은 CSRF 토큰을 파일 본문 필드 대신 지정된 헤더로 보내는 기존 계약을 따릅니다.

여러 인스턴스는 서로 독립된 multipart-directory를 지정하고 각 경로의 임시 용량을 모니터링합니다. 같은 경로를 공유하거나 다른 계정/프로그램이 변경하는 운영은 디렉터리 검사만으로 격리되지 않습니다.

2026-10-22 검증: JDK 21 전체 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java292개(신규 설정5개/실제 HTTP4개 포함), 실패/오류/건너뜀0. 실제 img2tile·FFmpeg와 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 검사 통과. 새 HTTP 및 기존 성능 테스트의 인증 fixture 실행 JAR 포함 항목0개 확인. 브라우저 UI/HTTP 성능 벤치마크와 실제 업무 SSO·프록시 수신은 이번에 재검증하지 않았습니다. 실제 업무 자료 처리나 설치 ZIP 생성은 수행하지 않았습니다.

## Multipart 파싱 전 수신 슬롯

imagegate.upload.multipart-max-concurrent는 기본4, 1~16이며 단일 JVM 전체의 multipart 수신 슬롯 수입니다. media/config.MultipartIngressFilter를 SecurityFilterChain의 CSRF 앞에 한 번만 등록하고 Servlet 자동 중복 등록은 끕니다. CSRF가 파라미터를 확인하면서 multipart 파싱을 유발할 수 있으므로 컨트롤러/원본 서비스보다 먼저 접수합니다. 필터 자체는 본문/파트/요청 파라미터를 읽지 않습니다.

원본과 스탬프를 포함해 content-type이 multipart/로 시작하는 요청은 경로·메서드·인증 여부와 관계없이 같은 접수 상한을 공유합니다. 알 수 없는 경로/비인증 요청이 선행 파싱을 일으킬 가능성도 제한하기 위함입니다. 대기 큐 없이 한도를 초과하면429 UPLOAD_INGRESS_BUSY/no-store 고정 Problem JSON으로 거절하며 body를 읽거나 컨트롤러를 실행하지 않습니다. 인증·CSRF보다 앞선 자원 거절이므로 포화 상태에는 비인증/CSRF 누락 요청도429가 먼저 나올 수 있습니다. 슬롯이 생겼다는 이유로 로그인/파일 권한을 부여하지 않으며 이후의 인증·CSRF·기능별 권한 검사는 그대로 적용됩니다.

본문이 없는 일반 이미지/썸네일 GET, 재생 Range 등 multipart가 아닌 요청에는 이 슬롯을 사용하지 않습니다. 원본 등록의 max-concurrent/max-concurrent-per-user 슬롯은 파싱 후 복사·해시·게시를 보호하는 별도 정책이며 두 슬롯을 합치거나 서로 대체하지 않습니다. 수신 슬롯은 사용자별/조직별 제한 또는 여러 JVM의 공유 한도가 아닙니다.

동기 요청은 이후 보안 필터·Servlet 파싱·업무 처리·DispatcherServlet 정리 경로가 반환할 때까지 슬롯을 유지하고 finally에서 해제합니다. 예외/CSRF 거절에도 해제하며 반환된 lease는 중복 close되어도 한 번만 풀립니다. 비동기가 시작된 경우 완료 listener가 슬롯을 해제하고 재시작 시 listener를 재등록합니다. timeout/error 통지만으로 작업 완료를 가정해 조기 해제하지 않습니다. 현재 미디어 API는 동기 Servlet 경로입니다.

이것은 HTTP 요청 수명에 대한 입장 제한입니다. 컨테이너의 마지막 request recycle/소켓 수신 버퍼/프록시 버퍼와 비정상 종료 잔재까지 계수하지 않으므로 임시 볼륨의 물리적 총량/여유 공간을 보장하지 않습니다. 본문 유휴 제한은 아래 옵션으로 적용하지만 소량을 계속 보내는 송신자가 슬롯을 오래 점유할 수 있어 프록시 전체 전송 시간/최소 전송률·연결 정책·OS 임시 볼륨 할당량은 여전히 필요합니다. 기동 시 초기화되는 메모리 상태이며 비밀정보·사용자 식별자·본문을 저장하지 않습니다.

신규 검증은 semaphore 동시 경합/중복 해제, 본문/파라미터 미접근 거절, 일반 조회 우회, IOException/ServletException 뒤 해제, 비동기 timeout 후 완료 해제를 포함합니다. 실제 루프백 HTTP에서는 Content-Length가 있는 multipart 본문 일부만 전송해 수신 중임을 확인하고 추가 요청429·일반 bootstrap 조회200·원 요청 완료201·후속 정상 업로드를 검사합니다. 중간 연결 종료 후 슬롯/임시 파트 정리와 재업로드도 검증합니다. 수신 확인 marker는 src/test의 fixture에만 있으며 운영 JAR에는 포함하지 않습니다.

동시 수신 보호 최종 검증(2026-10-22): JDK 21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java300개, 실패/오류/건너뜀0. 신규 수신 슬롯2개/필터4개/실제 HTTP2개를 포함하고 기존 수신 설정·HTTP 검증도 재실행했습니다. 실제 img2tile·FFmpeg와 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 검사 통과. 실행 JAR의 테스트 인증/수신 marker 포함 항목0개 확인. 실제 업무 자료와 설치 ZIP은 처리/생성하지 않았습니다. 다음 단계는 느린 본문 수신 시간 제한과 시간 초과 후 슬롯·임시 파일 복구 검증입니다.

## HTTP/1.1 본문 읽기 유휴 시간 제한

imagegate.upload.body-read-idle-timeout는 기본30s, 1s~5m의 정수 밀리초 단위 Duration입니다(예: 1500ms). 0/음수/무제한/밀리초보다 작은 정밀도의 값은 기동 시 거절합니다. media/config.UploadReadTimeoutConfiguration이 고정 Tomcat11.0.15 HTTP/1.1 protocol의 disableUploadTimeout=false와 connectionUploadTimeout을 설정합니다. 별도 스케줄러/본문 복사/새 라이브러리를 추가하지 않습니다.

이는 **다음 데이터를 읽기 위해 기다리는 시간의 상한**입니다. 전체 파일 전송을30초 안에 끝내야 한다는 뜻이 아니며, 읽기 유휴 간격이 상한보다 짧은 정상 전송은 전체 시간이 길어도 계속될 수 있습니다. 소량 전송을 반복해 슬롯을 점유하는 공격까지 막는 절대 마감/최소 속도 정책이 아닙니다. 해당 정책은 다음 프록시 운영 단계에서 별도로 구성·검증합니다.

내장 서버의 HTTP/1.1 커넥터 본문 읽기 정책이므로 multipart뿐 아니라 같은 커넥터의 다른 요청 본문(JSON 등) 읽기에도 적용됩니다. 파일 상한·동시 수신 슬롯·인증/CSRF/권한은 그대로 유지합니다. 헤더/첫 요청 대기의 server.tomcat.connection-timeout, keep-alive 유휴 시간, 응답 다운로드/미디어 Range의 쓰기 시간 제한은 이 옵션으로 변경하지 않습니다.

현재 배포의 지원 대상은 내장 Tomcat HTTP/1.1 백엔드입니다. HTTP/2의 스트림 타임아웃은 다른 정책이므로 server.http2.enabled=true는 보장이 불가능한 설정을 자동 무시하지 않고 기동 실패로 처리합니다. 프록시 앞단의 클라이언트 HTTP/2와 ImageGate까지의 HTTP/1.1 연결은 구분합니다. 외부 Servlet 컨테이너/다른 connector·개별 커스터마이저를 추가하면 적용 상태를 별도 검증해야 합니다.

인증/CSRF 이후 DispatcherServlet multipart 파싱에서 SocketTimeoutException이 원인으로 확인되면408 UPLOAD_READ_TIMEOUT/no-store 고정 Problem JSON을 반환합니다. 원시 소켓 오류·파일명·설치 경로는 응답하지 않습니다. 크기 초과는 기존413 FILE_TOO_LARGE, 슬롯 포화는429 UPLOAD_INGRESS_BUSY입니다. 관련 없는 multipart 실패는 기존 resolver 경로에 넘기며 오류 메시지 문자열로 timeout을 추측하지 않습니다.

컨테이너/TLS/프록시/클라이언트가 더 일찍 연결을 중단하면 HTTP408 대신 연결 종료/클라이언트 IO 오류로 보일 수 있습니다. 보안 필터에서 파싱/거절되거나 애플리케이션 이전 단계에서 발생한 오류도 이 MVC 응답 계약과 구분합니다. 클라이언트는 연결 오류를 성공으로 취급하거나 응답을 못 받은 등록 POST를 무조건 재전송하지 않습니다.

시간 초과 후 파싱이 종료되면 multipart 수신 lease를 finally에서 해제하며 부분 임시 파일은 파서/컨테이너가 정리합니다. 이것은 프로세스 강제 종료·OS 파일 잠금·디스크 장애의 잔재 삭제나 물리적 임시 볼륨 할당량을 보장하는 기능이 아닙니다. 실제 프록시의 전체 본문 시간/최소 전송률·요청 크기·연결 수와 일관되게 설정하고 운영 환경에서 종료/복구 동작을 확인해야 합니다.

Tomcat은 read timeout을 발견할 때 내부적으로 sendError(408)을 표시해 MVC가 예외를 처리해도 뒤에 ERROR 디스패치가 남을 수 있습니다. 이를 일반 /error 허용으로 우회하지 않습니다. ERROR 전용 UploadTimeoutErrorFilter를 보안 체인 앞에 등록하되, 수신 필터가 남긴 multipart 접수 표식 + 컨테이너 ERROR 상태408 + 원인 SocketTimeoutException을 모두 확인한 경우에만 고정408 JSON을 직접 반환하고 연결 종료를 알립니다. 원인 탐색은16단계로 제한하며 메시지 문자열을 신뢰하지 않습니다. 일반 REQUEST/다른 오류/표식 없는 오류는 기존 체인으로 보내고 업무 컨트롤러를 공개하지 않습니다. 표식은 Servlet 내부 요청 속성이며 클라이언트 헤더/본문으로 받지 않습니다. 인증 전 보안 필터에서 파싱이 유휴 만료된 multipart에도 이 고정 전송 오류가 먼저 보일 수 있으며 권한을 부여하는 응답이 아닙니다.

유휴 제한 최종 검증(2026-10-22): JDK 21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java310개, 실패/오류/건너뜀0. 신규10개는 설정/HTTP2 정책2개·MVC 응답3개·ERROR 경계2개·실제 HTTP3개입니다. 실제 테스트 커넥터의2s 설정과 부분 임시 파일 생성을 확인한 뒤 고정 길이/chunked 중단의408·연결 종료·파일/수신 슬롯 해제·재업로드를 검증했습니다. 진행 중인 전송이 총2s를 초과해도201로 완료되는 경우도 통과했습니다. 실제 img2tile·FFmpeg와 라이선스16개 규칙/63개 구성요소·53개 포함 JAR 기준선 통과. 실행 JAR의 HTTP/성능 테스트 fixture 항목0개 확인. UI/HTTP 성능 벤치마크·실제 SSO/프록시·업무 자료는 이번에 재검증하지 않았으며 설치 ZIP을 생성하지 않았습니다. 다음은 프록시 전체 본문 시간/최소 전송률·크기/연결 제한 예제와 배포 전 검증 절차입니다.

## 조직별 원본 누적 용량

imagegate.upload.tenant-original-max-bytes는 기본0(비활성), 양수이면 조직별 원본 논리 바이트를 제한합니다. 실제 버퍼 예약/게시·실패 해제·재시작 재계수·중단 staging 잔재와 writer 잠금 조건은 [ORIGINAL_QUOTA.md](ORIGINAL_QUOTA.md)를 따릅니다. 초과는507 TENANT_STORAGE_QUOTA_EXCEEDED이며 기존507 INSUFFICIENT_STORAGE(볼륨 여유 부족)와 구분합니다. 원본/썸네일/재생 조회에 계수 잠금을 추가하지 않습니다.

최종 검증(2026-10-22): JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java330개(이번 단계 신규16개), 실패/오류/건너뜀0. 실제 img2tile·FFmpeg와 라이선스16개 규칙·63개 구성요소/53개 포함 JAR 기준선 검사 통과. 뷰어 안내 문구 JS 구문 검사/JAR 반영과 테스트 fixture 실행 JAR 미포함 확인. 브라우저 시나리오·운영 부하·실제 강제 종료/다중 JVM 인수는 재실행하지 않았고 실제 원본/잔재 삭제 및 설치 ZIP 생성은 수행하지 않았습니다.
