# LPAC 한글 메모와 고정 폰트

현재 상태: [YAML 선택·서버 연결·실제 Chrome 및 혼합 부하 검증](LPAC_INSTALLATION_LOAD.md)을 추가했다. 아래의 서버 선택 미구현 설명은 선행 단계 기록이다. 승인 폰트 프로필의 한글 메모·스탬프 합성 저장을 실제 LPAC 서버에서도 확인했다.

2026-11-16 후속: [실패·중단 작업 회수](LPAC_WORK_RECOVERY.md)를 추가했다. 폰트 포함 snapshot의 정상 정리도 소유 기록과 요청 무효화를 적용한다. 일반 서버 선택은 여전히 동시 성능·Windows 인수 후 연결할 단계다.

2026-11-16. 승인된 Noto Sans CJK KR Regular 2.004와 전용 Fontconfig를 공유 Node/sharp snapshot에 포함해 LPAC 메모를 렌더링한다. 일반 서버 factory/YAML의 LPAC 선택은 아직 제공하지 않는다. Windows 전역 폰트 설치나 업무 원본 ACL 변경은 하지 않았다. 기존 폰트 없는 snapshot의 미리보기/스탬프 호환성은 유지하며 해당 snapshot에서 LPAC 메모는 계속 거절한다.

## 출처·설치·고지

폰트는 notofonts/noto-cjk의 Sans2.004 태그가 가리키는 고정 커밋 `523d033d6cb47f4a80c58a35753646f5c3608a78`의 원본 OTF다. [공식 소스](https://github.com/notofonts/noto-cjk/tree/523d033d6cb47f4a80c58a35753646f5c3608a78)와 [OFL 1.1 원문](https://github.com/notofonts/noto-cjk/blob/523d033d6cb47f4a80c58a35753646f5c3608a78/LICENSE)을 사용했다. GitHub contents API의 Git blob SHA-1과 실제 바이트를 대조한 뒤 SHA-256·크기·출처를 config/oss/note-font.windows-x64.json에 고정했다. 이 SHA-256은 내려받은 고정 Git 객체의 식별값이며 별도 서명 인증을 의미하지 않는다.

| 파일 | 크기 | SHA-256 |
| --- | ---: | --- |
| NotoSansCJKkr-Regular.otf | 16,433,112 B | 6bcb2a0703aa137e874fc2dffa85f6c21ba9a67fa329e81b8c801663af7e992a |
| LICENSE | 4,301 B | 6a73f9541c2de74158c0e7cf6b0a58ef774f5a780bf191f2d7ec9cc53efe2bf2 |

원본 폰트를 수정/서브셋하지 않았으며 내부 name table의 `© 2014-2021 Adobe (http://www.adobe.com/).` 고지를 NOTICE.txt와 함께 보존한다. OFL은 소프트웨어와 함께 배포할 때 원문·저작권 고지 보존 등의 조건을 둔다. 폰트 바이너리는 Git에 넣지 않고 .tools 전용 설치 경로에 저장하며, 공유 snapshot에도 LICENSE/NOTICE를 함께 넣는다. 전체 제품/기존 native 의존성의 최종 배포 인수는 별도이므로 distributionReady=false를 유지한다.

```powershell
& '.tools/runtimes/node-24.22.1/node.exe' scripts/install-note-font.mjs --download
& '.tools/runtimes/node-24.22.1/node.exe' scripts/install-note-font.mjs --verify-only
& '.tools/runtimes/node-24.22.1/node.exe' scripts/stage-sharp-lpac.mjs --fonts
.\scripts\build-shared-node-lpac.ps1
.\scripts\build-lpac-image.ps1
& '.tools/runtimes/node-24.22.1/node.exe' scripts/test-lpac-note-font.mjs
```

설치 도구는 고정 raw Git URL만 사용하고 redirect·크기/해시 불일치를 거절하며 기존 파일을 덮어쓰지 않는다. 폰트 stage는 build/node-sharp-font-lpac/runtime.json에 별도로 기록하므로 기존 build/node-sharp-lpac/runtime.json을 대체하지 않는다. 신규 snapshot은 117개 파일·약 125MiB이며 승인/게시/활성화는 기존 버전 관리 절차를 사용한다. 개발 검증 단계에서 ZIP을 생성하지 않는다.

## 실행 경계와 역할

ManagedRuntimes는 conversion/service.note-font를 통해 profile ID/family와 폰트·LICENSE·NOTICE·fonts.conf 바이트를 고정 정책과 대조하고 noteFont 지원 여부를 반환한다. 오류가 나면 acquire한 참조를 해제한다. LpacImageTransport는 승인 표시가 있을 때만 메모를 허용한다. 프로필이 없는 기존 runtime은 지원 표시를 받지 않고 LPAC_NOTES_NOT_VALIDATED로 거절하며 다른 실행 경로로 우회하지 않는다.

native의 shared-node 목록에는 fonts 아래 고정 5개 파일과 폴더만 추가했다. 임의 폰트 확장자나 하위 디렉터리는 허용하지 않는다. 기존 전체 inventory digest와 파일/폴더 pin에 폰트·설정·고지도 포함한다. 실행 중 읽기만 허용하며 부모 소유자도 pin이 유지되는 동안 쓰기·교체할 수 없다.

전용 이미지 실행기는 FONTCONFIG_FILE/PATH를 snapshot의 고정 경로로 지정하고, SIV_NOTE_FONT의 고정 값으로 워커가 Noto family를 선택하게 한다. API나 메모 내용으로 폰트 경로·family를 전달하지 않는다. [sharp 문서](https://sharp.pixelplumbing.com/install/)의 Fontconfig 경로를 사용하며 config는 해당 폴더 하나만 조회하고 host config include를 하지 않는다. 캐시는 작업별 Windows 프로필의 AC 안으로 보내 기존 16MiB 감시와 프로필 제거 정책을 적용한다. 공유 코드·폰트 폴더에는 캐시나 사용자 메모를 쓰지 않는다.

기존 SVG의 텍스트 escape·최대 500자·줄 수/픽셀 제한과 원본 좌표 합성 순서를 유지한다. 폰트 없는 LPAC으로 새 워커가 메모를 받으면 거절한다. 일반 Job 경로는 기존 폰트 동작을 유지한다. 모든 Unicode/emoji 표시를 보장하지 않으며 폰트가 제공하지 않는 글자는 별도 지원 범위다. 브라우저 오버레이 폰트의 동기화는 서버 선택/사용자 화면 인수에서 확인할 항목이며 이번 픽셀 비교는 양쪽 실행에 같은 승인 폰트를 적용한 조건이다.

## 캐시 교체 중 저장 공간 검사 수정

실제 합성 편집 시험에서 Fontconfig 임시 캐시의 원자적 교체와 파일명 기반 ADS 검사가 경합했다. Scan은 이제 열린 동일 파일 핸들로 GetFileInformationByHandleEx(FileStreamInfo)를 호출해 ADS를 검사한다. [Microsoft API 문서](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-getfileinformationbyhandleex)의 스트림 구조를 정렬·길이·개수 상한 안에서 해석한다.

열거 뒤 임시 항목이 사라졌거나 열린 파일의 이름이 바뀐 경우에는 전체 output/profile inventory를 최대 3번 다시 검사한다. 파일을 건너뛰어 승인하지 않는다. 접근 거절·reparse·하드링크·ADS·용량 초과는 즉시 실패하고, 계속 변하는 목록도 재시도 한도를 넘으면 실패한다. 저장 공간 제한과 출력 파일 목록은 유지한다.

## 검증

- 실제 메모 9개: 한글/CJK, escape·줄바꿈, 빈 메모, 누락 글리프 박스와 구별, 캐시 분리, 실제 EditingService의 메모+업로드 스탬프+밝기+crop+회전, 폰트/설정 변조 거절, 자식 쓰기 차단·실행 중 pin·종료 후 버전 회수. 같은 승인 폰트로 실행한 일반 Job과 디코딩 픽셀이 일치했다.
- 저장 공간 회귀 5개를 3회 실행했다. 일반 파일 허용과 ADS·하드링크·초과 크기 거절, 캐시 교체 중 총 600회 스캔을 확인했다. 재현은 build-storage-watch.ps1과 scripts/test-storage-watch.mjs다.
- 전체 Node 99개(실패/건너뜀 0), 기존 이미지 LPAC 11개, 스트림 16개, Chrome 13개 시나리오와 React 빌드·기존 npm 90개 라이선스 기준선이 통과했다. 폰트 고지는 별도 고정 정책/원문으로 검증한다.

보고서: build/reports/lpac-note-font/verification.json, build/reports/storage-watch/verification.json. 합성 렌더링 예시는 같은 폴더의 korean-cjk.png와 composed.png다. 다음은 실패 작업의 안전한 재시작 회수, 동시 부하/복사 비용·Windows 환경 인수와 서버 선택 설정이다.
