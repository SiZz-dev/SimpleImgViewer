# 타일 파일 경계·읽기·동기화 저장 세부 측정

2026-10-28. TileTiming의 선택 진단을 세분화하고, 실제 img2tile/합성24MP TIFF의 준비 과정3회를 측정했습니다. 경계 검사·SHA·PNG 검증·CREATE_NEW·force(true)·원자 게시의 순서/정책은 유지합니다. 운영의 진단은 기본 비활성이며 새 설치 YAML/공개 API/외부 의존성은 없습니다.

## 실행과 측정 수명

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat tilePreparationProfileTest -PtileProfileLabel=io-detail
```

tileProfileRounds는1~5(기본3)입니다. label은 보고서 이름이며 구현을 전환하는 옵션이 아닙니다. io-detail은 기존 baseline/optimized 기록과 분리합니다. 일반 build/test는 이 성능 작업을 실행하지 않습니다.

관측자는 NativeTileRenderer.render 호출 직전에만 기록을 시작하고 종료 즉시 중단합니다. 생성자·원본 등록·게시 이후 manifest/전체 픽셀 검증의 파일 읽기를 준비 구간에 섞지 않습니다. 테스트는 후처리 전후의 관측 결과가 불변인지 확인합니다. source/key/경로/파일명/원본 식별자를 관측자에 전달하지 않으며 enum·누적 시간/횟수만 저장합니다.

초기 native 검증의 inventory는3회, 게시 직전 증거 기반 inventory 재검사는2회입니다. 공개 저장소의 force/create-new/write-close는521개 PNG와 manifest1개, 각각522회인지 확인합니다. 기록 횟수는 세 실행에서 동일했습니다. 경계 검사 결과나 저장 동작을 관측값에 따라 바꾸지 않습니다.

## 관측 결과

전체 render는15.87/15.00/14.41초(중앙값15.00초)였습니다. 각 단계는 누적 시간의3회 중앙값이며 아래 부모/자식 구간은 중첩되므로 합산하지 않습니다. 과거 전후 최적화 실험과 진단 범위·실행 환경이 달라 속도 향상 비교로 사용하지 않습니다.

| 세부 구간 | 호출 수 | 누적 중앙값 | 해석 |
| --- | ---: | ---: | --- |
| native 출력 정규 경로 확인 | 6,946 | 2,894.66ms | inventory와 입력 읽기 경계 전체 |
| native 출력 속성 읽기 | 5,822 | 86.50ms | NOFOLLOW 속성·정체성 대조에 사용하는 값 |
| native 파일 열기/읽기/닫기 | 1,566 | 867.87ms | 이 항목은 IO 수명 전체 포함 |
| native PNG 구조·CRC·래스터 확인 | 521 | 308.65ms | 최초 전체 검증 |
| native 바이트 SHA 계산 | 1,567 | 17.31ms | 출력 PNG/DZI 대상. 원본/모듈 SHA와 구분 |
| 정규화용 원본 파일 경계 검사 | 1,042 | 505.83ms | 읽기 전/해시 대조 후 |
| 정규화용 PNG 처리 | 521 | 3,221.44ms | 디코딩·새 RGBA 복사·인코딩·출력 검사 포함 |
| 공개 저장소 regular 경계 검사 | 2,088 | 697.71ms | 내부 canonical 확인 포함 |
| 공개 저장소 canonical 확인 | 2,094 | 646.19ms | regular 및 directory의 자식 구간 |
| 공개 파일 읽기 open | 522 | 715.47ms | 저장 후 generation 전체 대조 |
| 공개 파일 바이트 read | 522 | 10.43ms | open/close/경계 검사는 제외 |
| 공개 파일 읽기 close | 522 | 7.01ms | 읽기 자원 반환 |
| 공개 파일 CREATE_NEW open | 522 | 182.29ms | 생성에 성공한 후 소유권 등록 |
| 공개 파일 바이트 write | 522 | 52.63ms | 쓰기 루프만 측정 |
| 공개 파일 force(true) | 522 | 1,645.46ms | 저장소 동기화 호출 시간 |
| 공개 파일 쓰기 close | 522 | 135.41ms | 쓰기 자원 반환 |

각 실행에서 CREATE_NEW/write/force/close의 합을 분모로 계산한 force 비율의 중앙값은81.7%입니다. 이는 전체 render의81.7%라는 의미가 아닙니다. 장치의 실제 물리 기록/전원 장애 내구성·디스크 IOPS/RSS를 측정한 결과도 아닙니다.

공개 파일의 실제 read/write보다 경계 확인·open·force 비용이 크게 관측됐습니다. native 처리만이 병목이라고 볼 수 없으며, PNG 처리도 별도 분석할 가치가 있습니다. OS 캐시를 비우지 않은 단일 Windows 환경의3회 합성 체커보드 관측이므로 실제 복잡한 업무 TIFF/장시간 동시 부하/브라우저 표시 시간의 보장은 아닙니다. 관측자 자체의 시간 측정/맵 갱신 비용도 남습니다.

## 보안·자원 수명

파일 경계 도우미는 객체별 관측자를 전달하기 위해 private instance 메서드로 구성했습니다. 경로를 캐시하거나 기존 canonical/NOFOLLOW/파일 키·속성/전후 inventory/SHA 검사를 생략하지 않습니다. source 증거와 원본/모듈/현재 권한·버전/commit gate 검사는 유지합니다.

close 관측 래퍼는 진단 비활성일 때 원래 Closeable을 그대로 반환합니다. 활성 상태에서도 실제 close를 한 번 호출하고, 본문 예외·억제된 close 예외의 순서와 원래 IOException/interrupt를 보존합니다. 관측자 RuntimeException은 업무 예외를 대체하지 않습니다. 기존 정상 게시/원본·권한·버전·모듈/기능 변경 거절 테스트에서 새 native 세부 관측자도 실패하도록 하여 검증합니다.

521개 타일/32,000,189픽셀의 해시는3회 모두 동일하고, 이전6회 기준 해시와도 일치했습니다. 원본 SHA 검사와 force 호출 수 검사도 통과했습니다. 원시 결과는 build/reports/tile-profile/io-detail.json, 집계/해석 범위는 io-summary.json에 있습니다.

다음 분석 대상은 PNG 처리 내부의 디코딩·픽셀 복사·인코딩·출력 검증 비용입니다. 입력/출력 픽셀·메타데이터 제거·바이트 예산을 유지하는 비교부터 진행합니다. 실제 업무 인수·SSO/ACL·운영 부하/OS 격리·최종 라이선스/배포 인수는 계속 필요하며 설치 ZIP은 최종 단계에만 생성합니다.

## 최종 검증

`tilePreparationProfileTest -PtileProfileLabel=io-detail`은1분32초에3회/타일 호출 수·전체 픽셀/원본 SHA 검증을 통과했습니다. 전체 `build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration`도5분39초에 성공했습니다. Java734개/108개 suite 실패·오류·건너뜀0, 실제 내부 모듈·FFmpeg·HTTP·Chrome 통합과 라이선스16개 규칙/63개 식별 구성요소·53개 포함 JAR 기준선을 확인했습니다.

close 진단의 본문/억제 예외 순서·standalone IOException/interrupt·비활성 원본 자원 반환을 검사하는 신규2개 테스트를 포함합니다. 최종 JAR의 타일 정규화/게시/재열기 smoke와 테스트/인증 fixture 항목0개도 통과했습니다. 통합 검증 결과는 `build/reports/tile-profile/io-verification.json`입니다. 실제 설치 YAML/업무 자료/모듈 변경과 설치 ZIP 생성은 수행하지 않았습니다.
