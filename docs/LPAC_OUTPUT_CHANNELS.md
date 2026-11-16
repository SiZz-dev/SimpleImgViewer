# LPAC 표준 출력·오류 채널 경계

2026-11-13. 공통 C++ LPAC 실행기의 stdout/stderr 분리를 구현했다. Node/FFmpeg 시험용 CLI와 기존 native 실행기가 같은 수집 경계를 사용한다. Node 웹 서버에 LPAC 운영 어댑터를 활성화한 변경은 아니다.

## 동작

- 익명 파이프 2개를 만들고 각 읽기 핸들의 상속을 해제한다. 명시적 자식 핸들 목록에는 stdout 쓰기·stderr 쓰기·NUL 입력만 포함한다. 자식을 재개한 뒤 부모의 두 쓰기 핸들을 닫는다.
- 각 반복에서 양쪽을 최대 4096바이트씩 읽는다. 한 채널을 무한히 먼저 비우지 않으므로 동시에 쓰는 자식이 다른 파이프 때문에 교착하는 것을 방지한다.
- stdout와 stderr 각각 최대 8192바이트를 보관한다. 8193번째 바이트가 감지되면 채널별 truncated 표식을 남기고 Job을 종료한다. 자식이 이미 exit 0이더라도 최종 exit는 24로 실패 처리한다. 초과 부분은 보관하지 않는다.
- 자식 종료 신호 이후에도 **두 파이프 모두** 다시 확인해 대기 중인 바이트를 수집한다. 깨진 파이프만 정상 EOF로 처리하고 다른 Peek/Read 오류는 실패로 처리한다. 기존 벽시계·메모리·CPU·프로필/출력 감시와 Job 종료 경계도 유지한다.
- `SandboxResult.diagnostics`는 기존 호출자 이름을 보존하되 이제 stdout만 담는다. `errors`는 stderr, `stdout_truncated`/`stderr_truncated`는 개별 초과, `truncated`는 둘의 OR다. 기존 일반 launcher의 공개 JSON에는 비공개 자식 오류 내용을 추가하지 않는다.

FFmpeg 시험 launcher는 자식 stdout를 기존 diagnostics 필드로 전달하고 stderr는 실제 stderr로 전달한다. 부모 시험은 성공 종료와 잘림 없음부터 확인한 뒤 stdout JSON 전체를 파싱한다. 이전의 특정 경고 접두어 제거 코드는 삭제했다. 정상 JSON이 stderr에 있다는 이유로 stdout 실패를 구제하지 않는다. 경고는 실행기/시험의 비공개 기록이며 뷰어 사용자 응답으로 전달하지 않는다.

## 검증

```powershell
.\scripts\build-stream-lpac.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-stream-lpac.mjs
```

컴파일러·소스·헤더·빌드 스크립트 및 EXE 지문을 검증한 실제 LPAC 자식으로 시험한다. `IMAGEGATE_STREAM_LPAC_PROBE`에서만 고정 스트림 프로브 프로그램을 허용한다. 일반 빌드의 프로그램 whitelist를 확대하지 않는다. 결과는 `build/reports/stream-lpac/verification.json`이다.

16개 실행이 통과했다. 종료 직전 두 채널 출력 10회 반복, 빈 출력, 각각 정확히 8192바이트, stdout만 8193바이트, stderr만 8193바이트, 양쪽 동시 2MiB 쓰기, 정상 바이트 출력 후 exit 7을 시험했다. NUL 및 0~255 바이트를 포함한 서로 다른 패턴을 비교해 혼합·유실·텍스트 변환을 검사했다. 경계 초과는 exit 24 및 각 채널 8192바이트 이하로 실패했고, 정상 종료의 전체 바이트와 원래 비정상 exit는 보존됐다. 각 실행 후 남은 lease도 0이었다.

FFmpeg의 로컬 파일 probe에서 관찰된 초기화 경고는 stderr에 남으며 내부 원인은 미확정이다. 이번 변경은 권한을 추가하거나 경고를 제거한 변경이 아니다. 서버 LPAC 운영 연결, 불변 런타임 재사용 성능, Node 부모 종료·작업 잠금/게시/권한 재확인 결합은 별도 후속이다. 설치 ZIP을 만들지 않았다.

FFmpeg 회귀에서 일반 실행끼리 VP8 출력 해시가 달라지는 사례도 확인했다. 최초 일반 대조군 외의 재실행 3개는 LPAC 결과와 같았다. 영상은 전체 50프레임과 엄격한 픽셀 오차 상한으로 비교하고, 음성은 정확한 디코딩 해시를 유지한다. 구체적인 기준과 증거는 [FFmpeg 시험 기록](FFMPEG_LPAC_INTEGRATION.md)에 기술했다. 출력 채널의 바이너리 16개 실행에서는 오차 허용 없이 원래 바이트 전체를 대조한다.

최종 검증은 스트림 16개 실행, FFmpeg 13개 시나리오, 실제 이미지 작업 8개 시나리오와 sharp 5형식/20개 출력 비교, 기본 Node LPAC/생산 guard 및 기존 native 접근 제어가 통과했다. Node/실제 모듈 테스트 65개는 실패·건너뜀 없이 통과했고 Chrome 13개 시나리오, React production 빌드 및 npm 90개 라이선스 기준선도 통과했다. 다음 작업은 읽기 전용 불변 런타임 재사용과 동시 변환 성능 검증이다.
