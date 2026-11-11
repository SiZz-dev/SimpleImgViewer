# 운영 역방향 프록시 예제와 인수 절차

현재 개발 환경은 **IntelliJ에서 Spring Boot 내장 Tomcat 실행**입니다. 이 상태에서 프록시 설치나 Run 설정 변경은 필요하지 않습니다. config/proxy의 파일은 운영 배포용 선택 예제이며 기본 application.yml에서 자동 import하지 않습니다. 프록시 실행 파일·모듈·인증서·키를 저장소에 포함하지 않고 서비스를 시작/재시작하지 않습니다.

## 예제 선택

| 파일 | 적용 범위 |
| --- | --- |
| config/proxy/nginx.conf.example | NGINX OSS 전용 인스턴스의 전체 설정 예제. TLS 경로/도메인/로그·임시 경로 교체 필요 |
| config/proxy/apache.vhost.conf.example | Apache HTTP Server 2.4의 TLS vhost 조각. 모듈/Listen/MPM을 갖춘 전체 httpd.conf에서 Include |
| config/proxy/imagegate.backend.example.yml | 같은 호스트의 TLS 프록시 → 127.0.0.1:8080 HTTP/1.1 백엔드용 server 블록. 실제 설치 YAML의 server 블록에 병합 |

Apache 코어는 [Apache-2.0](https://httpd.apache.org/docs/2.4/license.html), NGINX OSS 코어는 [2-clause BSD 조건](https://nginx.org/LICENSE)입니다. 유료 기능/외부 추가 모듈은 예제에 사용하지 않습니다. 실제 배포 바이너리·TLS 라이브러리·동적 모듈의 버전/해시·고지문·취약점 검토는 별도입니다. 예제 파일을 제공하는 것만으로 해당 바이너리를 프로젝트 의존성으로 추가하거나 배포 승인한 것은 아닙니다.

Windows용 NGINX는 공식 문서가 성능·확장성 제한을 설명하므로 Windows에서 내장 Tomcat으로 개발한다는 이유만으로 운영 프록시까지 Windows NGINX로 정하지 않습니다. 운영 OS/프록시 선택은 별도로 합니다. [공식 Windows 지원 범위](https://nginx.org/en/docs/windows.html).

## 요청 제한의 차이

| 계층 | 예제 값 | 실제 의미 |
| --- | --- | --- |
| ImageGate 공통 multipart 크기 | 104923136바이트 | max(원본100MiB, 스탬프5MiB)+요청 여유64KiB |
| NGINX client_max_body_size / Apache LimitRequestBody | 104923136 | 프록시 본문 전체 상한. 앱 상한 변경 시 함께 수정 |
| ImageGate body-read-idle-timeout | 30s | 다음 본문 데이터 읽기 대기. 전체 전송 마감 아님 |
| NGINX client_body_timeout | 20s | 읽기 사이 유휴 시간. 전체 전송 마감/최소 전송률 아님 |
| Apache RequestReadTimeout body | 10-120,MinRate=131072 | 처음10초, 데이터 수신량에 따라128KiB당1초씩 허용 시간 연장, 최대120초 |
| NGINX multipart limit_conn | IP별2 / 전체4 | 헤더가 완성되어 처리 중인 multipart 요청의 수. 사용자 권한/전체 TCP 수가 아님 |
| ImageGate multipart-max-concurrent | 4 | 앱의 파싱 전 단일 JVM multipart 슬롯 |

Apache의 수신 시간/전송률은 [mod_reqtimeout](https://httpd.apache.org/docs/2.4/mod/mod_reqtimeout.html), 본문 크기는 [LimitRequestBody](https://httpd.apache.org/docs/2.4/mod/core.html#limitrequestbody)를 따릅니다. 예제 최대120초는 느린 회선에서 정상100MiB 파일도 거절할 수 있으므로 실제 파일 크기·회선·허용 시간을 기준으로 조정합니다. MinRate는 순간 속도 계측값이 아니라 수신량에 따른 시간 예산 연장 규칙입니다. 앱 유휴 제한과 프록시 제한 중 먼저 만료한 계층이 요청을 종료할 수 있습니다.

NGINX 유휴 시간은 [core module](https://nginx.org/en/docs/http/ngx_http_core_module.html#client_body_timeout), 요청 카운터는 [limit_conn](https://nginx.org/en/docs/http/ngx_http_limit_conn_module.html)을 따릅니다. 빈 키는 계수하지 않으므로 MIME이 multipart/로 시작하지 않는 일반 이미지·썸네일·Range 조회를 업로드 카운터에서 제외합니다. 인증 전/알 수 없는 경로의 multipart도 계수합니다. 헤더 미완성 연결은 이 카운터 밖이며 HTTP/2/3에서는 동시 요청마다 별도로 계수됩니다. IP별 값은 NAT 뒤 여러 사용자에게 공유되며 로그인별 할당량이 아닙니다. **NGINX OSS 예제만으로 전체 전송 시간/최소 전송률을 구현했다고 판단하지 않습니다.**

Apache vhost에는 IP별 수신 카운터를 구현하지 않았습니다. 승인되지 않은 추가 모듈을 가져오지 않습니다. 운영 OS에 맞는 MPM 예산을 전용 인스턴스의 전역 설정에서 별도 지정합니다. 예를 들어 Linux/event에서는 ServerLimit4, ThreadsPerChild32, MaxRequestWorkers128을 검토할 수 있고 Windows/winnt에서는 ThreadsPerChild를 운영 동시 처리 예산에 맞춥니다. 이는 동시 작업 스레드/처리량 예산이며 IP별 업로드 수나 모든 유휴 TCP 연결의 정확한 한도가 아닙니다. [MaxRequestWorkers](https://httpd.apache.org/docs/2.4/mod/mpm_common.html#maxrequestworkers)와 설치한 MPM의 공식 문서를 확인하세요. 최종 인수에 계정/네트워크·프록시 연결 예산을 별도 기록합니다.

## 백엔드 신뢰 경계

단일 호스트 예제는 백엔드를127.0.0.1에 바인딩하고 Native RemoteIpValve의 internal-proxies를 **127.0.0.1 하나**로 제한합니다. 실제 설치 파일에 중복 server 키를 만들지 않고 병합합니다. start 스크립트의 prod 프로필은 Secure 세션 쿠키를 유지합니다. 개발 시에는 이 조각을 import하지 않는 기존 IntelliJ Run 구성을 사용합니다.

프록시가 외부 요청의 Forwarded/X-Forwarded-*를 그대로 신뢰하지 않도록 덮어쓰거나 제거하고, 고정된 외부 Host·HTTPS 프로토콜·실제 연결 IP를 전달합니다. NGINX는 $proxy_add_x_forwarded_for로 클라이언트 헤더를 이어붙이지 않습니다. Apache는 기존 값을 제거한 후 mod_proxy가 실제 정보를 추가하도록 합니다. 도메인 placeholder는 인증서와 일치하는 실제 값으로 전부 교체합니다. [NGINX proxy headers](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_set_header), [Apache headers](https://httpd.apache.org/docs/2.4/mod/mod_headers.html), [Apache proxy headers](https://httpd.apache.org/docs/2.4/mod/mod_proxy.html#x-headers).

같은 OS의 임의 프로그램도 loopback에 연결할 수 있으므로 이 구성은 로컬 프로그램을 상호 인증하는 수단이 아닙니다. 전용 서비스 계정·프로세스/포트 접근 제어가 필요합니다. 프록시가 다른 호스트이면 예제의 loopback 설정을 그대로 사용하지 말고 정확한 프록시 주소·방화벽·백엔드 전송 보호와 재검증이 필요합니다. internal-proxies를 .* 또는 전체 사설 대역으로 넓혀 해결하지 않습니다. [Remote IP Valve](https://tomcat.apache.org/tomcat-11.0-doc/config/valve.html#Remote_IP_Valve).

전달 헤더는 HTTPS/원격 주소 해석용이지 인증 정보가 아닙니다. X-Authenticated-User, X-Tenant-Id 등을 ViewerPrincipal로 자동 변환하지 않습니다. 실제 업무 SSO/파일 ACL 연동은 여전히 필요합니다. 직접 접근 가능한 백엔드를 외부에 열고 클라이언트의 전달 헤더를 신뢰하는 구성은 이 예제의 범위 밖입니다.

## 뷰잉·반출 보호

NGINX는 요청/응답 버퍼링·proxy_cache·proxy_store·upstream 재시도를 끄고 HTTP/1.1로 즉시 전달합니다. chunked 본문도 앱 상한을 거치도록 합니다. 프록시에서 먼저 전체 파일을 수신하는 설정을 쓰면 앱 슬롯/유휴 제한이 그 수신 구간을 보호하지 못하므로 프록시 임시 볼륨을 별도 제한해야 합니다. read/send 타임아웃도 전체 업로드 마감은 아닙니다. [proxy module](https://nginx.org/en/docs/http/ngx_http_proxy_module.html).

Apache는 proxy-sendchunked를 사용하고, 강제 Content-Length/HTTP1.0 변환에 따른 불필요한 스풀을 피합니다. 모듈/필터 구성에 따라 스풀링이 남을 수 있어 실제 부하에서 확인합니다. mod_cache가 로드된 경우 해당 vhost의 캐시를 금지하고 앱 오류 상태를 프록시 오류 페이지로 덮어쓰지 않습니다. 추가 balancer/장애 재전송 정책은 제공하지 않습니다. [Apache request body handling](https://httpd.apache.org/docs/2.4/mod/mod_proxy.html#request-bodies).

두 예제는 원본·캐시·주석·스탬프를 정적 root/alias로 노출하지 않고 앱 API로 전달합니다. Cookie/Authorization/CSRF·HEAD·Range를 제거하거나 요청 메서드를 변경하지 않습니다. 응답 압축/콘텐츠 변환을 추가할 경우 원본 크기·Content-Length/206 의미가 달라지지 않는지 검증합니다. 앱 내부의 권한 있는 PNG/썸네일 캐시는 유지합니다.

접속 로그 예제에는 전체 URI/query·Cookie·Authorization·본문을 넣지 않습니다. 프록시 자체 error log는 IP/경로를 포함할 수 있으므로 전용 ACL·회전/보존을 적용합니다. 프록시와 앱의 임시 파일·로그·원본 볼륨은 OS 할당량과 모니터링을 별도로 구성합니다.

## 구문 사전 점검 도구

scripts/test-proxy-config.mjs는 **설치된 신뢰 실행 파일**의 native -t만 실행합니다. 자동 다운로드·서비스 start/reload·방화벽 변경·인증서 생성은 하지 않습니다. Node는 개발 도구이며 앱 JAR의 런타임 의존성으로 추가하지 않습니다.

~~~powershell
# IntelliJ 개발 중에는 필요하지 않습니다. 아래 경로는 운영자가 설치한 실제 경로로 교체.
node scripts/test-proxy-config.mjs --help
node scripts/test-proxy-config.mjs --proxy nginx --executable 'C:\Tools\nginx\nginx.exe' --root 'C:\Tools\nginx' --config 'C:\ImageGateProxy\nginx.conf'
node scripts/test-proxy-config.mjs --proxy apache --executable 'C:\Apache24\bin\httpd.exe' --root 'C:\Apache24' --config 'C:\Apache24\conf\httpd.conf'
~~~

Node 도구 없이 설치된 실행 파일의 native 명령을 직접 사용할 수도 있습니다(예: nginx -t -p 설치폴더 -c 전체설정, httpd -t -d ServerRoot -f 전체설정). Node는 선택 점검 도구용이며 IntelliJ/Spring Boot 실행에는 필요하지 않습니다.

Apache에는 vhost 조각이 아닌 **그 파일을 Include한 전체 httpd.conf**를 지정합니다. NGINX에는 도메인/인증서/로그/임시 경로를 수정한 전체 설정을 지정합니다. 인증서와 키는 저장소 밖에 둡니다. 경로는 절대 경로이고 파일/폴더가 존재해야 하며 정규화 후 canonical 경로를 확인합니다. Windows 심볼릭 링크/정션 또는 Unix symlink 경로는 실제 대상 경로로 지정합니다.

도구는 shell 없이 인자 배열로 실행하고 직접 자식에15초 제한/SIGKILL, 출력 버퍼64KiB 제한을 지정합니다. 원시 stdout/stderr·경로·키 내용을 JSON에 넣지 않습니다. schemaVersion/check/proxy/status/nativeExitCode/runtimeVerified를 출력하며 **runtimeVerified는 항상 false**입니다. proxy 값은 인자 형식 선택일 뿐 실행 파일의 정체/라이선스/버전/해시를 자동 인증하지 않습니다. 승인된 바이너리와 설정만 사용하세요. native 모듈 초기화나 검사 중 로그 파일 접근이 일어날 수 있으며 OS sandbox/전체 프로세스 트리 격리 도구는 아닙니다.

종료0은 native 구문 검사 성공, 2는 인자 오류, 3은 native 실패, 4는 경로/실행 불가, 5는 직접 자식 시간 초과, 6은 출력 상한입니다. 결과가 실패하면 보호된 운영 콘솔에서 native 검사/서버 로그로 상세 원인을 확인합니다. 원시 진단을 공개 자료나 소스 저장소에 붙이지 않습니다. **구문 성공만으로 아래 운영 인수를 통과한 것은 아닙니다.**

## 운영 배포 전 인수

1. 실제 프록시 종류·OS·버전/빌드 옵션·실행 파일 해시·로드 모듈·TLS 라이브러리/고지문·취약점 검토 결과를 기록합니다. nginx -V 또는 httpd -V/-M과 승인된 패키지 자료를 사용합니다. 예제 수치를 업무 회선/최대 파일 크기·사용자 동시성에 맞추고 native -t를 통과합니다.
2. 별도 점검 환경에 실제 TLS 인증서·SSO/파일 ACL을 연동합니다. backend8080 외부 접근 차단, 정상 HTTPS 쿠키/CSRF, 전달 헤더 위조와 다른 사용자/조직의401/403/404를 검증합니다. 프록시가 로그인/파일 권한을 대신하지 않습니다.
3. 파일 상한 직전·직후 및 전체 multipart 상한 직전·직후, Content-Length 유무(chunked)를 전송합니다. 프록시/앱 중 어느 계층의413인지 기록하고 후속 정상 등록 및 두 임시 경로의 정리를 확인합니다. NGINX의 고정 PROXY_* JSON과 앱의 FILE_TOO_LARGE/UPLOAD_READ_TIMEOUT/UPLOAD_INGRESS_BUSY는 구분합니다. Apache 등 프록시 자체의 오류 본문 형식은 앱 Problem JSON과 다를 수 있습니다.
4. 본문을 중간에 멈춰 프록시/앱 유휴 만료를 확인합니다. 유휴보다 짧은 주기로 조금씩 계속 보내는 별도 시험을 수행해 Apache의 최대120초/시간 예산이 적용되는지 확인합니다. NGINX 예제에서 이 시험이 절대 시간에 종료된다고 기대하지 않습니다. 종료 후 backend 수신 슬롯·두 임시 경로·원본 미게시 파일이 회복되는지 확인합니다.
5. IP별/전체 multipart 상한까지 느린 전송을 유지하고 초과 요청의429 또는 프록시 정의 거절·일반 이미지/썸네일/재생 조회 지속을 확인합니다. NAT·HTTP2/3 프런트 사용 시 카운터 의미, MPM/worker 예산·헤더 미완성 연결도 별도로 검증합니다. 처리량을 위해 한도를 무작정 올리지 않습니다.
6. 원본/PNG/썸네일 HEAD/GET, 영상·음성 Range206/416와 seek, 취소/연결 종료, crop/편집 PNG 반출·CSRF·권한 회수·revision 충돌을 확인합니다. 응답 크기/해시/Content-Length/Content-Range와 no-store, 프록시 디스크에 비공개 응답이 남지 않는지를 검사합니다.
7. 콜드/웜·동시1/4/8·여러 파일 작업 집합으로 브라우저/HTTP P95, 앱·프록시 CPU/메모리·활성 요청/임시 디스크를 측정합니다. 기존 루프백 성능 결과를 운영 TLS/프록시/SSO 결과로 대신하지 않습니다. POST 응답 유실/백엔드 단절 시험에서 자동 중복 등록·편집 재전송이 없는지도 확인합니다.
8. 인수한 설정/바이너리 식별자를 보관하고 전환/복구 절차를 승인한 뒤 운영에 반영합니다. 이번 작업에서는 실제 서비스 전환·재시작이나 설치 ZIP 생성을 하지 않습니다.

## 이번 검증 범위

네이티브 프록시 실행 파일은 현재 PATH/확인한 기본 경로에 없으며 사용자는 IntelliJ 내장 실행 환경을 확인했습니다. 따라서 Apache/NGINX의 실제 native -t·TLS/제한 동작 검증은 **미실행**입니다. 프록시 예제는 이 검증을 통과했다고 표시하지 않습니다.

ProxyForwardingHttpTests는 예제 backend YAML 자체를 실제 내장 Tomcat에 import해127.0.0.1에서 전달한 HTTPS가 Secure 세션 쿠키에 반영됨, 신뢰하지 않는127.0.0.2의 위조가 무시됨, RFC Forwarded가 선택한 Native 헤더 정책을 바꾸지 못함, 위조 사용자 헤더만으로 인증되지 않음을 확인합니다. prod의 강제 Secure 설정을 대신하는 시험은 아니며 테스트 전용 인증은 운영에 포함하지 않습니다.

Node 도구 테스트는 인자 제한/누락된 실행 파일의 미실행·상태, shell 없는 정확한 -t 인자/시간·출력 제한/진단 비노출을 검증합니다. mock native runner의 성공을 실제 프록시 검증으로 보고하지 않습니다. 실제 운영 인수·프록시 패키지 검토·SSO는 잔여입니다.

기본 ApplicationSecurityTests도 설치자의 config/imagegate.yml import와 저장 경로를 분리했습니다. IntelliJ에서 실행 중인 앱의 writer 잠금이나 실제 저장소를 테스트가 사용하지 않습니다. Gradle test는 두 설치 YAML 예제를 입력으로 추적해 예제가 변경되면 검사를 다시 수행합니다.

최종 회귀 검증(2026-10-22): JDK21 build -Pimg2tileIntegration -PffmpegIntegration 성공. Java314개(실패/오류/건너뜀0), Node 도구3개, 라이선스 규칙16개 및63개 구성요소/53개 포함 JAR 기준선 검사 통과. 실제 내부 변환 모듈/FFmpeg 검사를 포함했고 테스트 fixture는 실행 JAR에 포함되지 않았습니다. Apache/NGINX 자체 native 검증은 여전히 미실행이며 설치 ZIP은 생성하지 않았습니다.
