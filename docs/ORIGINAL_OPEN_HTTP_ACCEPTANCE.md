# 원본 열기 정책 HTTP 회귀 검증

2026-10-27. [운영 설치 정책](ORIGINAL_OPEN_POLICY.md)의 허가 대기 초과·실패·취소·회복을 실제 루프백 HTTP로 검증합니다. 운영 기본 비활성 설정은 유지하며 실제 설치 YAML을 수정하지 않습니다.

~~~powershell
$env:JAVA_HOME='C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat --no-daemon --console=plain test --tests '*OriginalOpenPolicyHttpTests'
.\gradlew.bat --no-daemon --console=plain build -Pimg2tileIntegration -PffmpegIntegration
~~~

## 실제 경로와 테스트 경계

media/controller.OriginalOpenPolicyHttpTests는 실제 내장 Tomcat의127.0.0.1 임의 포트와 JDK HttpClient를 사용합니다. redirect/proxy를 사용하지 않으며 클라이언트 연결/요청과 테스트 전체의 기한을 둡니다. 기본 application.yml을 읽되 테스트 설정으로 실제 설치 import를 차단하고, 별도 합성 임시 저장소/수신 경로·PNG만 사용합니다. 외부 변환기/재생·감사 로그·주기 캐시 정리는 이 HTTP fixture에서 비활성입니다. 실제 외부 모듈 회귀는 전체 build에서 별도로 실행합니다.

운영 CSRF 필터·실제 bootstrap 토큰/세션 cookie·파일 권한 정책·등록·원본 저장·PreviewQueue·PreviewCache·ImagePreviewRenderer·원본 SHA·PNG/썸네일·HTTP 상태/콘텐츠 경로를 유지합니다. 설치 정책은 enabled=true/max-concurrent=1/wait-timeout=2s, 워커2개/큐4개입니다.

conversion/config.PreviewConfiguration은 기존 PreviewRenderer SPI를 빈으로 연결하고 큐에 주입합니다. 일반 renderer 빈의 destroyMethod를 비워 큐가 종료를 소유합니다. 기본 구현/캐시 엔진 식별/설정/공개 API는 유지합니다. 테스트는 @Primary 렌더러만 교체하며 운영 큐나 보안 체인을 모조 구현으로 바꾸지 않습니다.

합성 bearer 식별은 테스트 소스의 필터가 기존 SecurityContextHolderFilter 직후에만 제공합니다. 실제 SSO 구현이 아닙니다. 같은 조직 다른 소유자와 다른 조직의 동명 사용자를 구분하며 합성 권한 철회를 제공합니다. 인증 우회/제어 헤더/테스트 credentials를 운영 코드에 추가하지 않습니다.

conversion/service.ControlledOriginalOpenFixture도 src/test 전용입니다. 렌더러의 기존 패키지 한정 opener에서 다음 native open을 latch로 보류하거나 중단/실패시키며 정상 반환에는 실제 Files.newInputStream을 사용합니다. 허가 획득/대기/회수는 운영 OriginalOpenAdmission이 수행합니다. 생성된 스트림/원본 바이트/SHA를 대체하지 않습니다. latch는20초 상한·finally 해제를 갖고 HTTP 요청에서 제어할 수 없습니다.

## 확인한 시나리오

| 시나리오 | 확인 내용 |
| --- | --- |
| 대기 초과와 명시적 재시도 | 첫 open이 허가를 보유할 때 두 번째 작업이 failed/ORIGINAL_OPEN_BUSY. status/pages는 source/thumbnail을 제공하지 않고 정확한 캐시 키를 안다고 가정한 GET/HEAD도404. retry=false와 읽기 조회로 재접수되지 않고 첫 작업이 끝난 뒤에도 실패 유지. 권한·CSRF를 갖춘 retry=true 뒤 PNG/썸네일 정상 제공. |
| 워커 중단과 후속 회복 | 보류 중인 opener의 워커를 인터럽트해 첫 작업은 INTERRUPTED, 대기하던 두 번째 작업은 ready. 첫 실패 작업 HEAD 거절 후 명시적 재시도로 회복. |
| 보안과 처리 중 권한 철회 | anonymous401, 같은 조직 다른 소유자/다른 조직404, CSRF 없는 POST403. status/pages/content/HEAD/retry 경로가 입구에 도달하지 않음을 open 호출 수로 확인. 처리 도중 권한을 철회하면 늦게 끝난 작업도 게시하지 않고 권한 복구 후 failed/PREVIEW_FAILED 상태에서 명시적 재시도 필요. |
| native open 실패와 회복 | private 경로/진단을 담은 IOException에도 공개 상태는 PREVIEW_FAILED와 null 소스만 반환. 다음 신규 작업과 실패 작업의 명시적 재시도가 정상 완료. |

상태·페이지·거절 및 성공 GET/HEAD 응답의 no-store/nosniff를 확인합니다. 성공 PNG/썸네일은 실제 디코딩으로32×16을 확인하고 HEAD의 빈 본문/정확한 Content-Length를 GET과 대조합니다. 각 정상 작업 원본을 다시 전체 SHA로 확인합니다. 실패 응답은 저장 경로/내부 native 문구를 포함하지 않습니다. 테스트 후 대기 작업을 해제하고 모두 종료 상태가 될 때까지 확인한 뒤 work/임시 산출물과 multipart 수신 잔재가 없는지 검사합니다.

## 범위의 한계

이 검증의 취소는 테스트가 실제 큐 워커를 인터럽트하는 방식입니다. 새 HTTP 작업 취소 API나 브라우저 연결 중단 시 백그라운드 작업 취소를 구현했다고 주장하지 않습니다. 실제 Windows native open이 인터럽트에 응답하지 않는 경우의 강제 종료/OS 격리도 제공하지 않습니다. 고정2초는 테스트의 허가 대기 설정이며 native 호출 기한이 아닙니다.

합성 루프백 HTTP 기능 회귀로 실제 업무 SSO/공유 ACL·TLS/프록시·실자료/운영 부하·브라우저 표시 P95를 대체하지 않습니다. 새 지표/공개 actuator 경로/자동 재시도/권한 완화는 추가하지 않습니다. 설치 ZIP은 최종 배포 단계까지 생성하지 않습니다.

## 검증 결과 (2026-10-27)

집중 HTTP4개는49초에 통과했습니다. 최종 JDK21 build -Pimg2tileIntegration -PffmpegIntegration은4분42초에 성공했으며 Java552개/87개 suite의 실패·오류·건너뜀은0입니다. 실제 img2tile·FFmpeg 통합과 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 통과했습니다.

실행 JAR 해시 IO smoke가 통과했고 ControlledOriginalOpenFixture를 포함한 테스트/fixture 클래스 항목은0개입니다. 일반 테스트 결과는 build/test-results/test 및 build/reports/tests/test에 있습니다. 기술적 라이선스 식별 검사와 최종 배포 준비를 구분하여 distributionReady=false를 유지합니다. 실제 설치 설정/모듈/업무 자료는 변경하지 않았고 설치 ZIP은 생성하지 않았습니다.

후속 운영 지표 수집 구현과 활성/비활성·관리 접근 검증은 [PREVIEW_METRICS.md](PREVIEW_METRICS.md)를 참고하세요. HTTP fixture는 수집 활성 상태에서도 기존4개 시나리오를 통과하고, 고정32개 meter·캐시 조회 관측 불변·관리 접근 거절 시나리오를 추가해 현재5개입니다.
