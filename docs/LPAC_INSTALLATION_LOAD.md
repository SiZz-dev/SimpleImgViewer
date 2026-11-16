# LPAC 설치 선택과 혼합 부하 검증

2026-11-16: Node application factory에서 YAML의 `isolation.mode`로 Windows Job과 LPAC을 명시적으로 선택한다. 기본값은 `windows-job`이다. LPAC 선택은 아래 개발 Windows x64 호스트에서 검증했으며, 다른 Windows 버전의 운영 인수와 최종 배포 승인을 의미하지 않는다. 실제 IntelliJ 설정과 실행 중인 서버는 변경하지 않았다.

## 설치 전제와 설정

`config/simpleimgviewer.example.yml`의 `isolation`을 따른다. `lpac`은 `runtimeVersions.enabled: true`와 미리 게시·활성화한 검증된 Node/sharp 런타임이 필요하다. 영상·음성을 켜면 활성 FFmpeg 런타임도 필요하다. 메모가 포함된 편집본 저장을 켜면 승인된 noteFont 프로필을 가진 Node 런타임을 사용해야 한다. 게시 절차는 MANAGED_RUNTIME_EXECUTION.md와 LPAC_NOTE_FONT.md를 따른다. 서버 시작 시 런타임을 자동 다운로드하거나 게시하지 않는다.

```yaml
runtimeVersions:
  enabled: true
  # 기존 승인 카탈로그의 root와 활성 버전은 설치 환경에 맞게 구성
isolation:
  mode: lpac
  root: .runtime/node/lpac
  imageLauncher: 'D:/설치경로/lpac-image.exe'
  imageLauncherSha256: '빌드 manifest의 소문자 SHA-256 64자리'
  mediaLauncher: 'D:/설치경로/lpac-media.exe'
  mediaLauncherSha256: '빌드 manifest의 소문자 SHA-256 64자리'
  maxEntries: 40
  recoveryEnabled: true
  recoveryMinAgeMs: 60000
  recoveryIntervalMs: 60000
  scanLimit: 200
```

위 경로·해시는 설명용 자리표시자다. 이미지 실행기는 `scripts/build-lpac-image.ps1`, 미디어 실행기는 `scripts/build-lpac-media.ps1`로 빌드하고 각각 `build/lpac-image/launcher.json`, `build/lpac-media/launcher.json`의 실제 절대 경로와 해시를 입력한다. 미디어 실행기 설정은 `playback.enabled: true`일 때 필수다. 각 빌드 manifest의 `serverSelectable: true`는 명시적 서버 선택 가능 여부이며 `distributionReady: false`는 유지한다.

LPAC root는 원본·캐시 저장소, 런타임 카탈로그, dist와 겹칠 수 없다. 실행기는 이 쓰기 영역들 밖에 둔다. 이미지와 미디어 풀은 root 아래 별도 디렉터리로 구성한다. 시작 시 EXE 해시와 고정 `--capabilities` 프로토콜·프로필을 확인하고, 실패하면 확보한 풀과 카탈로그 소유권을 해제하고 시작을 거절한다. 다른 모듈이나 직접 프로세스 실행으로 우회하지 않는다. Windows Job 설정은 기본 경로에서 계속 사용한다. 이 옵션은 사용자 권한·인증을 대신하지 않으며 일반 실행의 미인증 API는 계속401이다.

## 동시 검증 병목 수정

관리형 런타임의 전체 파일 해시 확인이 전역 직렬 큐를 점유해 서로 다른 이미지·미디어 작업의 실행이 겹치지 못하는 문제를 발견했다. 메타데이터와 사용 참조 확보만 직렬화하고 실제 전체 검증은 큐 밖에서 수행하도록 변경했다. 동일 불변 버전을 동시에 검증 중일 때만 진행 중인 Promise를 공유한다. 완료 결과나 TTL 캐시는 두지 않으며 다음 순차 요청은 다시 해시를 확인한다. 실행 전·게시 전 검증도 유지한다. 참조가 동시에 해제되어도 검증 중 임시 사용 참조가 회수와 카탈로그 종료를 차단한다. 변조 재검사와 해제/회수 경쟁을 Node 테스트로 검증했다.

## 측정 결과

`scripts/test-lpac-installation-load.mjs`는 실제 YAML을 읽어 동일 승인 런타임 카탈로그를 사용하는 별도 서버·저장소를 구성한다. Windows Job 다음 LPAC 순서로 실행하며 HTTP loopback, 합성 PNG/JPEG/TIFF 1024×768과 MP4/MPEG/MP3/WAV를 사용했다. CPU는 Intel Core i5-8250U, 논리8개, 메모리 약16GiB, Windows x64 `10.0.26300`이다. 측정 중 별도 회귀 시험은 병행하지 않았다.

| 항목 | 관리형 Windows Job | LPAC |
| --- | ---: | ---: |
| 서버 시작(각1회) | 2629ms | 11919ms |
| 최초 페이지 준비+PNG 본문 중앙값(각6회) | 1890ms | 3863ms |
| 최초 페이지 준비+PNG 본문 p95 | 2070ms | 4193ms |
| 캐시 페이지 manifest 중앙값(각18회) | 747ms | 746ms |
| 캐시 PNG 본문 중앙값(각18회) | 33ms | 26ms |
| 이미지2개+영상1개+음성1개 혼합 요청 전체 완료(각1배치) | 5350ms | 13398ms |
| 관찰한 동시 native 실행 수의 최대값 | 2 | 4 |

원시 표본과 결과는 `build/reports/lpac-installation-load/verification.json`에 기록한다. 캐시 응답의 decoder 호출은 양쪽 모두0이며, 과부하 시429 거절과 정상 작업 완료를 확인했다. 양쪽 미리보기의 디코딩 픽셀도 동일하다. 캐시 manifest는 여전히 전체 런타임 무결성 확인 비용이 있고, PNG 본문 시간과 합쳐 해석해야 한다. PNG 본문 시간에는 전송도 포함된다. 동시 native 수는 실행기 계측으로 관찰한 수이며 CPU 코어 활용률이나 처리량 측정치는 아니다.

LPAC은 현재 최초 변환과 혼합 요청에 추가 지연이 있다. 표본 수가 작고 모드 실행 순서를 교차하지 않았으므로 위 수치를 SLA·네트워크 접속 성능·모든 장비의 보장값으로 사용하지 않는다. 일반 비관리형 Windows Job과의 비교도 아니다. 기본 선택은 유지한다.

## 실제 UI·실패 경계 검증

LPAC YAML 서버에서 Chrome으로 비공개 미리보기, 클릭 위치의 한글 메모·업로드 스탬프, 합성 자르기 PNG 저장, 라이트 테마, 영상 재생/seek/Range, 음성 재생/seek/Range의6개 시나리오를 통과했다. 잘못된 실행기 해시와 미디어 프로필은 시작을 거절했고 소유권 재확보를 확인했다. 개발 모드가 아닌 LPAC 서버는 자동 로그인 없이401을 유지했다. 스크린샷과 편집 결과는 같은 보고서 디렉터리에 생성한다.

전체 Node 테스트116개(실패/건너뜀0), 기본 Windows Job 서버의 실제 Chrome 회귀13개, React production 빌드와 npm90개 라이선스 기준선도 통과했다. 빌드 manifest를 서버 선택 가능 상태로 갱신한 뒤 새 실행기로 별도 YAML 서버를 기동하고 실제 이미지·음성 변환과 Range를 재확인했다. 위 성능 표는 그 전에 수행한 동일 native 소스의 측정 결과이며 빌드 manifest 변경 후 성능을 다시 측정한 수치는 아니다.

재현에는 앞선 LPAC 런타임·폰트 스테이징/공유 런타임 준비 실행기와 Chrome/Playwright가 필요하다. `IMAGEGATE_NODE_MODULES`, `IMAGEGATE_BROWSER`를 설치된 시험 도구 경로로 지정한 후 Node24로 위 스크립트를 실행한다. 처음 실행은 합성 카탈로그를 게시한다. `SIV_LOAD_FIXTURE_ID`를 기존 합성 fixture ID로 지정하면 일치하는 승인 버전을 재사용하며, 매회 새로운 저장소에서 최초 변환을 측정한다. 실제 업무 파일이나 설치 설정은 수정하지 않는다. 원본과 네이티브 작업의 디스크 입장 한도가 있으므로 충분한 여유 공간이 필요하다.

다음 단계는 캐시 manifest의 반복 무결성 검사 비용을 보안 경계와 함께 분석하고, 개선안을 회귀 시험으로 검증하는 것이다. 배포 대상 Windows 버전/계정별 접근 차단·중단/회수 인수도 남아 있다. 설치 ZIP은 최종 기능과 전체 검증 완료 후에만 만든다.
