# 내부 변환 모듈 재현 자료와 잔여 인수

2026-10-29 후속 사용자 확인: **img2tile 수정은 담당 팀에서 진행할 예정입니다.** ImageGate의 현재 회색 JP2 제한과 원시 J2K 실패 처리는 유지합니다. 담당 팀의 수정본과 변경 내역을 받으면 별도 설치 기준선을 확인하고 아래 진단/픽셀·전체 회귀 후 허용 범위를 재검토합니다. 이 확인이 자료 자동 발송이나 수정본 승인 완료를 뜻하지는 않습니다.

2026-10-29. 동일한 설치 img2tile로 RGB JP2 대조군·회색 JP2·원시 J2K의 최소 합성 표본을 생성/관찰하는 선택 진단 작업을 추가했습니다. 실제 업무 자료·실행 파일/DLL·비밀 설정을 저장소나 진단 폴더에 넣지 않고, 메시지/메일/외부 저장소로 자료를 전송하지 않았습니다. 설치 ZIP은 생성하지 않습니다.

## 생성과 파일 구성

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/openjdk/jdk-21.0.2'
.\gradlew.bat nativeCompatibilityDiagnostic
```

Windows와 기존 IMG2TILE_EXECUTABLE/FFMPEG_EXECUTABLE 또는 기본 설치 경로가 필요합니다. 출력은 build/reports/native-compatibility/run-UUID 아래에 새로 생성하며 기존 실행을 덮어쓰지 않습니다. latest.json은 마지막 완료 runDirectory와 manifest SHA-256을 가리킵니다. 실제 경로/발급자/원본 정보를 담는 시스템 설정 파일을 읽거나 수정하지 않습니다.

- README.md: 관측 결과와 기대/실제 PNG 링크·재현 명령/인자.
- 각 case 폴더: source.raw, input.jp2 또는 input.j2k, reference.raw, expected.png 및 실제 출력이 성공한 경우 actual.png.
- manifest.json: 사례/픽셀 비교·종료 코드·프로브 값, Java 버전·앱 허용 정책 버전·두 모듈 fingerprint, 공유 파일의 상대 이름/바이트/SHA-256.
- latest.json: manifest 자체 해시와 실행 디렉터리의 상대 이름. 완료한 뒤 원자 교체합니다.

이번 실행은 manifest에서15개 파일을 기록했으며 부모 도구로 전체 크기/해시와 manifest 해시를 다시 대조했습니다. manifest 자체는 자기 해시 목록에서 제외합니다. 실행에 필요한 resource 복사본과 작업 출력은 별도 build/native-diagnostic-work/run-UUID에 있으므로 전달 폴더에 포함되지 않습니다. 파일 해시는 동일성 점검이며 발급자 서명이나 배포 승인 인증이 아닙니다.

Gradle clean은 진단 자료도 지웁니다. 보존/수정 요청에 사용할 때는 정확한 실행 폴더 전체와 manifest를 해시 대조 후 접근 제한 증적 저장소에 별도 보관합니다. latest만 보고 과거 결과를 현재 모듈의 결과로 간주하지 않습니다. 담당 팀에는 필요에 따라 사용자가 수동 전달하며, 이 작업에서 자동 발송하지 않습니다.

## 이번 실측과 수정 요청 범위

모듈 fingerprint는 native-module-v1/c03da1c62f601ad1ac57b575f0a34d740fbd89ab969cd2a9b5c650a416bd3082입니다.128×96/8비트 무손실 입력을 설치 FFmpeg/libopenjpeg로 만들고 원시 바이트 재디코딩을 대조한 뒤 **img2tile**로 실제 변환합니다. FFmpeg는 제품의 대체 변환기로 선택하지 않습니다.

| case | 기대 | 현재 관측 | 담당 팀에서 확인할 사항 |
| --- | --- | --- | --- |
| rgb-jp2-control | 원본 크기와 RGB 픽셀 유지 | exit0, 12,288픽셀 모두 일치 | 대조군을 유지하면서 수정 후 회귀 확인 |
| gray-jp2 | 단일 회색 성분의 위치/표본 유지 | exit0이지만 display 비교9,216픽셀 불일치. (48,12)의 원시 표본 기대17→실제0 | gray 성분/공간 변환 오류 수정. 원인은 현재 관측만으로 특정하지 않음 |
| raw-j2k | 유효 원시 코드스트림 지원 여부 확정 | 참조 재디코딩 일치, native exit1/출력 없음 | 원시 코드스트림을 지원할 것인지 규약 확정 후 수정/재검증 |

회색 비교의 display 값에는 JDK 색공간 해석이 관여하지만 위 단일 채널 원시 표본도 다르므로 getRGB 변환만으로 설명되는 결과가 아닙니다. 예상 PNG는 회색 래스터를 그대로 기록하고 실제 PNG를 재인코딩하지 않고 복사해 증적을 보존합니다.16비트·축소 등 기존 회귀와 해석 범위는 [실제 호환성 검증](JPEG2000_NATIVE_COMPATIBILITY.md)을 함께 참고합니다.

진단은 관측을 목적으로 하므로 gray mismatch나 raw rejection이 남아 있어도 실행 자체는 완료될 수 있습니다. completed=true를 전 형식 지원/인수 통과로 해석하지 않습니다. RGB 대조군이 불일치하면 자료는 남기되 진단 테스트를 실패시킵니다. gray/raw 문제가 수정되어 결과가 바뀌면 그 사실을 새 manifest에 기록합니다.

직접 native 관찰은 src/test의 선택 작업에만 있으며, 일반 build/test에서 native-diagnostic 태그를 제외합니다. 제품의 Jp2InputProfile 제한을 우회하는 HTTP/YAML 기능은 만들지 않았습니다. actual.png는 뷰어 소스로 등록하지 않습니다. HEIC 유효 표본은 이번3개 사례에 포함되지 않았고 여전히 미검증입니다.

## 수정본 검증 절차

1. 기존 모듈 폴더를 시험 중 덮어쓰지 않고 검토된 별도 설치의 EXE/DLL/리소스 집합과 출처를 확인합니다. 실행 파일만 바꿔 같은 승인으로 취급하지 않습니다.
2. 그 설치 경로를 명시해 nativeCompatibilityDiagnostic을 실행하고 입력·reference의 정확성, RGB 대조군, gray 원시 표본/픽셀, raw J2K 종료/출력을 확인합니다.
3. 기존 일반 회귀는 현재 모듈의 gray 오류/raw 거절을 명시적으로 검사하므로 수정된 모듈에서는 기대값 검토가 필요할 수 있습니다. 단지 테스트가 초록색이 되도록 예외를 숨기거나 assertion을 제거하지 않습니다.
4. gray/색상·정밀도/축소·방향 규약을 재검증한 뒤 허용 프로필/캐시 정책·필요한 승인 fingerprint를 의도적으로 갱신합니다. 픽셀이 맞는다는 이유만으로 원본 좌표 편집을 활성화하지 않습니다.
5. 실제 업무 표본·부하·모듈/외부 DLL 고지와 전체 회귀를 다시 확인하고 운영 인수 증적을 남깁니다.

## 남은 인수 항목

| 항목 | 현재 완료한 부분 | 다음에 필요한 입력/증적 |
| --- | --- | --- |
| 내부 모듈 호환성 | RGB JP2·회색 오류/raw 미지원 재현 폴더 | 모듈 수정본 또는 소스 확인·규약과 픽셀 재검증 |
| HEIC/HEIF | 설치 모듈 경로 및 표본 생성 제약 확인 | 출처/사용 조건·기대 이미지가 확인된 합성 표본 또는 승인된 생성 도구 |
| 인증/전달 권한 | 로그인 미정·권한 추후 전달 방향, 서버 SPI·행위/회수 계약 검증 | 실제 로그인/신뢰 전달 채널·발급자/대상·TTL/회수·업무 파일 매핑 확정 및 연동 |
| 실환경 속도·격리 | 합성 HTTP/변환·캐시/장애 회귀 | 실제 SSO/TLS/프록시·업무 표본·동시 부하 목표와 P95/RSS/디스크·계정/ACL/OS 하드 한도 증적 |
| 복구·공급망 | 합성 복구/해시·런타임 라이선스 식별 기준선 | 실제 보호 manifest/복원본·RPO/RTO·고지 전문/외부 DLL/JDK·SBOM/취약점 조치와 승인 |
| 최종 설치 | 개발 JAR build/test | 위 잔여 기능·전체 검증/인수 후 최종 단계에서만 설치 ZIP 생성·격리 설치 검증 |

상세 A01~A09 통과 기준과 기록 양식은 [운영 인수 계획](OPERATIONAL_ACCEPTANCE.md)을 따릅니다. 자동 검증 성공과 외부 입력이 필요한 인수 완료를 구분하고 distributionReady=false를 유지합니다.

## 이번 검증 기록

nativeCompatibilityDiagnostic1개가40초에 완료됐고, RGB MATCH / gray MISMATCH(9,216픽셀) / raw J2K NATIVE_REJECTED(exit1)를 기록했습니다. manifest의15개 파일과 manifest 자체 해시를 별도 도구로 재대조하고 기대/실제 회색 PNG를 시각 확인했습니다. 이번 폴더는 build/reports/native-compatibility/run-40ee7938-7c18-4d09-a061-575c7735c1ea이며 latest.json으로도 확인합니다.

후속 전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration은6분37초에 성공했습니다. 일반 Java841개/118 suites의 실패·오류·건너뜀0과 실제 모듈/native 브라우저·라이선스16개 규칙/63개 구성요소/53개 포함 JAR 대조를 통과했습니다. 진단1개는 일반 빌드 집계에서 제외했으며, 일반 UI40개도 이번 단계에서 별도 재실행하지 않았습니다.

실행 JAR의 테스트/진단 항목0개와 관련3개 문서 링크26개를 확인했습니다. 집계는 build/reports/native-compatibility/verification.json입니다. 운영 코드·실제 설치 YAML·native 모듈·업무 자료·새 의존성·설치 ZIP 변경이나 외부 발송은 없습니다. 다음 수정 검토에는 img2tile 소스 또는 담당 팀의 수정본이 필요합니다.
