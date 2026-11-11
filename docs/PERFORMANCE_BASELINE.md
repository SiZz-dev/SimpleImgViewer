# 이미지 뷰어 API 성능 기준선 — 2026-10-22

개발 PC에서 JDK 21.0.2 / Windows 11 / 논리 CPU 8개 / 성능 JVM 최대 힙 1GiB로 실행했습니다.
Spring Boot 4.0.1, 미리보기 워커 2개·최대 1600×1600입니다. TIFF는 실제 img2tile, JPEG/PNG는 JDK ImageIO를 사용했습니다.
MockMvc 보안/API/파일/실제 변환을 측정하며 네트워크·TLS·실제 SSO·브라우저 표시 시간은 제외합니다.

| 입력 | 최초 등록→준비→PNG P95 | 캐시 HEAD P95 (1명) | 캐시 HEAD P95 (8명) | Mock PNG GET P95 (8명) |
| --- | ---: | ---: | ---: | ---: |
| jpeg-3200x2400 | 2058.05ms | 15.72ms | 84.80ms | 628.90ms |
| png-2400x1600 | 2993.37ms | 11.39ms | 71.80ms | 560.67ms |
| tiff-2400x1600 | 1827.59ms | 12.65ms | 79.57ms | 519.40ms |

총 21단계 / 447개 워크플로·요청 표본입니다. 최초 준비는 형식별 5회, HEAD/GET은 형식·동시성별 24회입니다.
작은 표본과 합성 난수 이미지의 단일 실행이므로 안정적인 운영 P95/SLA를 판단할 수 없습니다.
최초 준비는 OS 페이지 캐시와 JVM 예열 이후의 애플리케이션 캐시 미적중입니다.

Mock PNG GET은 Spring Test 출력 스트림의 바이트별 write/flush와 메모리 응답 배열 비용을 포함합니다.
이 비용을 분리하기 위해 동일 소스의 HEAD를 함께 기록했습니다. 실제 HTTP 서버/브라우저 전송 시험이 이어져야 합니다.
표본 사용 힙은 최대 약 1019.9MiB였습니다. 합성 입력·MockMvc 응답 배열·수거 전 객체가 포함된 JVM 사용 힙이며 운영 RSS/사용자별 필요 메모리를 산출할 수 없습니다.
GC 후 잔존 객체·네이티브 변환 프로세스·실제 HTTP 동시 부하·장시간 메모리를 추가 측정해야 합니다.

각 형식에서 익명·다른 사용자·다른 조직·권한 회수 접근을 검사했습니다. 요청/PNG 형식·길이·디코딩 검증이 모두 통과했습니다.
지연 한도는 지정하지 않아 이번 실행은 기준선 수집입니다.

재실행 방법과 한계는 [PERFORMANCE.md](PERFORMANCE.md)를 참고하세요.
원시 측정은 개발 산출물 build/reports/performance/viewer-1792630126395.json, 전체 표는 같은 이름의 .md에 있습니다.
실제 미디어·외부 실행 파일·인증 정보는 보고서나 저장소에 넣지 않았습니다.

검증: 전체 build -Pimg2tileIntegration -PffmpegIntegration (Java 207개), 별도 performanceTest -PperformanceImg2tile=true (시나리오 1개) 통과.
UI 변경 없음. 설치 ZIP 미생성.

후속 전용 썸네일 도입 후 동일 실행 내 본문/썸네일 비교 결과는 [THUMBNAIL_PERFORMANCE.md](THUMBNAIL_PERFORMANCE.md)에 별도로 기록했습니다. 이 문서는 도입 전 최초 기준선입니다.
