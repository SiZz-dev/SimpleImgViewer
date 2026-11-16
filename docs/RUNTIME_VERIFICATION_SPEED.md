# 런타임 전체 무결성 검사의 읽기 비용 개선

현재 후속 상태: [구간별 비용·제한 병렬 읽기 비교](RUNTIME_VERIFICATION_PROFILE.md)를 완료하고 기본 파일 병렬 수를2개로 선택했다. 아래의 호출당 단일 버퍼와 후속 작업 설명은 읽기 단위 개선 단계의 기록이다.

2026-11-16. 캐시 페이지 manifest에서도 관리형 런타임 전체 파일을 검증하는 정책을 유지하면서 `verifyRuntimeTree`의 읽기 단위를 64KiB에서 1MiB로 변경했다. 검증 호출당 한 버퍼를 순차적으로 재사용하므로 파일 수만큼 큰 버퍼를 만들지 않는다. 모든 파일의 실제 바이트 수와 SHA-256, 예상 파일·디렉터리 목록, 경로·링크·크기 검사를 그대로 수행한다. 완료된 검증 결과를 저장하거나 TTL을 적용하지 않는다.

`readChunkBytes`는 util의 내부 비교·검증 옵션이며 YAML이나 HTTP 설정으로 공개하지 않는다. 허용 범위는 정수64KiB~1MiB다. 검증이 겹치는 서로 다른 버전은 각각 최대1MiB의 읽기 버퍼를 사용한다. Node 서버 재시작 후 적용되며 실행 중인 IntelliJ 서버나 실제 설치 YAML은 변경하지 않았다.

## 실제 LPAC 서버 교차 측정

`scripts/test-runtime-verification-speed.mjs`로 기존 승인된 합성 런타임 카탈로그를 사용하고 새 저장소·LPAC 풀을 생성했다. 실제 YAML 기동, PNG 업로드·최초 변환 후 동일 캐시 페이지에 대해64KiB/1MiB 읽기를6쌍 비교했다. 각 쌍의 실행 순서는 번갈아 반전했고, 측정 중 별도 회귀 시험을 병행하지 않았다. 각 요청에서 실제 전체 트리 해시가1번 호출되는 것, 메타데이터 동일성, 캐시 구간 decoder 호출0회, 비공개 PNG 응답을 검증했다.

| 항목(각6회 중앙값) | 64KiB | 1MiB |
| --- | ---: | ---: |
| 캐시 페이지 manifest HTTP 요청·본문 읽기 | 775ms | 656ms |
| 해당 요청의 전체 트리 검증 | 683ms | 551ms |
| PNG 본문 수신·로컬 크기 확인 | 10ms | 9ms |

manifest 시간은 약15%, 트리 검증 시간은 약19% 줄었다. Windows x64 `10.0.26300`, Intel Core i5-8250U, loopback의 소규모 합성 이미지 측정이다. 이전 LPAC 설치 부하 시험과는 표본·실행 조건이 다르므로 직접 전후 비교에 사용하지 않는다. 양쪽 모두 같은 버퍼 재사용 구현을 사용해 읽기 크기의 효과를 비교했으며, 과거의 파일별 버퍼 할당 구현 전체와 동일한 기준선은 아니다. PNG 본문 항목에는 시험 도구의 sharp 크기 검사도 포함되어 있다. 원시 표본은 `build/reports/runtime-verification-speed/verification.json`에 기록한다.

재현 예시(앞선 LPAC 설치 시험의 승인된 합성 fixture가 필요):

```powershell
$env:SIV_SPEED_YAML = '.tools/load/<fixture ID>/<run ID>/lpac.yml'
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-runtime-verification-speed.mjs
```

현재 `build/lpac-image/launcher.json`, `build/lpac-media/launcher.json`의 실행기 경로와 해시를 사용한다. 실제 설치 설정은 읽거나 덮어쓰지 않는다. 서버 시작과 최초 변환 비용은 위 표에 포함하지 않는다. FFmpeg 트리·모든 Windows 장비의 동일 개선율이나 지연 SLA를 보장하지 않는다.

## 보안·회귀 검증

합성2MiB 초과 파일에서64KiB·1MiB 경계 전후 및 마지막 짧은 꼬리의 같은 크기 변조가 모두 거절된다. 작은 두 번째 파일, 파일 크기 증가, 미등록 파일, 하드링크, 누락된 파일과 부분 회수 정책도 검증한다. 기존 RuntimeVersions 테스트로 완료 검증 캐시 부재와 진행 중 참조/회수 경쟁을 계속 확인한다. 경로·해시 검사 생략이나 별도 decoder 실행 경로는 추가하지 않았다.

전체 Node/네이티브118개(실패/건너뜀0), 실제 Chrome13개 시나리오, React production 빌드와 npm90개 라이선스 기준선을 통과했다. 설치 ZIP이나 새 외부 의존성은 추가하지 않았다.

## 남은 작업

캐시 manifest 중앙값은 여전히656ms로, 전체 해시·파일 경로 검사와 폰트 승인 비용이 남아 있다. 다음은 이 비용을 파일 목록/경로 검사·본문 읽기/해시·폰트 검증으로 나누어 계측하고, 제한된 병렬 읽기 등 추가 후보가 전체 해시 정책·메모리 상한·동시 작업에 미치는 영향을 비교하는 것이다. 운영 Windows/서비스 계정 인수는 별도 과제로 유지한다.
