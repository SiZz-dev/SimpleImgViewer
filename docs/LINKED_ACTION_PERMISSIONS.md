# 전달 권한의 스탬프·자르기·반출·재생 검증

후속 처리 중 회수·최종 게시/응답 검사와 수정은 [PUBLICATION_AUTHORIZATION.md](PUBLICATION_AUTHORIZATION.md)를 참고합니다. 아래 요청 시작 전 검사와 구분합니다.

2026-10-29. 기존 MediaAuthorizationPolicy 확장 지점에 테스트 전용 서버 grant를 연결하여 행위별 검사와 회수를 확인했습니다. 실제 로그인·권한 수신·서명/발급자 검증은 아직 미연동입니다. [전달 권한 계약](LINKED_AUTHORIZATION.md)의 사용자·조직·파일·버전·유효기간 바인딩을 그대로 사용하며, 브라우저 헤더나 권한 JSON을 신뢰하는 기능을 추가하지 않습니다.

## 이미지 행위

| 전달 행위 | 확인한 결과 |
| --- | --- |
| VIEW + CROP_AND_SAVE | 실제8×8 crop PNG 성공. EXPORT_EDITED 없는 합성 반출은404. crop 권한만 회수하면 후속 crop은404, 원래 미리보기 열람은 유지 |
| VIEW + EXPORT_EDITED | 전체16×16 합성 PNG 성공. 영역 반출은 CROP_AND_SAVE도 필요하므로404 |
| VIEW + EXPORT_EDITED + CROP_AND_SAVE | 영역8×8 합성 PNG 성공. EXPORT_EDITED 회수 후 전체 반출도404 |
| VIEW + STAMP | 개인 스탬프 업로드·파일 위 배치 가능. ANNOTATE 없는 메모 생성은404 |
| STAMP/CROP_AND_SAVE/EXPORT_EDITED 단독 | 이전에 준비한 페이지가 있어도 VIEW 없이는 업로드/자르기/반출404 |

crop·합성 반출의 성공 응답은 실제 PNG를 다시 디코딩하여 출력 크기를 확인하며 no-store도 검사합니다. crop·export·stamp upload는 CSRF 누락 시403입니다. 설치 정책과 권한의 교집합 검증은 이전 단계의 crop=false 시험에서도 유지됩니다. 미존재와 권한 부족은 기존404 계약으로 처리하고 거절 때문에 원본을 삭제하거나 다른 사용자 권한을 상속하지 않습니다.

원본 해상도/방향·색상·합성 품질의 전체 인수는 각각의 기능 회귀 범위와 구분합니다. 이 단계는 실제 합성 PNG를 생성하지만 픽셀 품질 전체를 새로 인수한 것으로 집계하지 않습니다. 좌표가 미확인인 입력의 편집 차단도 그대로입니다.

## 개인 스탬프와 파일 권한의 구분

업로드한 스탬프의 개인 라이브러리는 uploader의 사용자 범위입니다. 다른 파일 소유자가 개인 contentUrl을 알아도 직접 조회는404입니다. 파일에 배치된 스탬프는 annotation에 기록된 stampOwner를 통해 해당 파일의 VIEW 권한으로 표시합니다. 파일 소유자가 공유 사용자 스탬프를 포함한 문서를 열람할 수 있고, 공유 사용자의 개인 라이브러리 전체를 열람할 수 있는 것은 아닙니다.

STAMP만 회수하고 VIEW를 유지하면 이미 배치된 스탬프는 표시되지만 새 업로드는 거절됩니다. VIEW까지 회수하면 파일의 배치 스탬프 URL도 거절됩니다. 파일 권한 회수가 uploader 자신의 개인 스탬프 자산을 삭제하거나 개인 조회까지 금지하는 의미는 아닙니다. 회수 후 개인 자산1개가 유지되고 실패한 업로드로 항목이 추가되지 않는 것도 확인합니다. 사용자 계정 자체의 로그아웃/정지·개인 자산 폐기 정책은 별도 연동/운영 인수 대상입니다.

## 영상·음성의 준비된 캐시

재생 열람에는 현재 VIEW 권한을 사용합니다. 원본 다운로드 권한이나 이미지 편집 권한을 대신 부여하지 않습니다. WAV와 MP4 후보를 각각 등록하고 준비된 재생 캐시에서 다음을 확인합니다.

- 권한이 있는 공유 사용자의 Range GET은206과 정확한10바이트를 반환하고, Range HEAD는206/Content-Length10/본문0바이트입니다.
- 다른 조직·다른 사용자는 동일 source URL로도404입니다.
- grant 만료 시점부터 기존 URL의 전체 GET·HEAD·범위를 벗어난 Range·상태 조회·명시적 retry 접수 모두404입니다. 권한이 없을 때 Range의416/전체 크기를 먼저 노출하지 않습니다.
- 해당 파일의 준비 작업은1회만 실행되고, 만료 후 요청이 캐시 재생성이나 재접수를 유발하지 않습니다.

재생 엔진은 이 API 계약 시험에서 합성1024바이트와 메타데이터를 반환하는 Mockito fixture입니다. 등록 입력도 후보 헤더를 가진 합성 바이트이므로 유효 WAV/MP4 코덱 인수나 실제 미디어 디코딩 결과로 집계하지 않습니다. controller/service/cache·실제 응답 Range/HEAD와 권한 경로는 그대로 사용합니다. 실제 FFmpeg/브라우저 호환성은 전체 빌드의 기존 native 통합 및 [재생 계약](PLAYBACK.md) 범위에서 별도로 확인합니다.

모든 회수 시험은 요청/전송 시작 전 권한 검사입니다. 전송이 시작된 뒤의 강제 중단이나 이미 받은 바이트의 회수, 원격 grant 갱신 지연·SSO 세션 회수는 입증하지 않습니다.

## 구조와 재현

기존 LinkedPermissionContractTests의 서버 grant·principal/PNG 준비 helper를 abstract LinkedPermissionFixtures로 분리하여 이전 계약10개와 새 LinkedActionPermissionTests의8개가 같은 바인딩·만료 규칙을 사용합니다. 실제 애플리케이션 bean이나 사용자 인증 코드는 바꾸지 않았습니다. fixture는 src/test/media/controller에만 있고 실행 JAR에 포함하지 않습니다.

```powershell
.\gradlew.bat test --tests '*Linked*Permission*Tests' -Pimg2tileIntegration
```

집중18개가44초에 통과했습니다. 실제 공급자 미연동 상태에서의 합성 권한 계약 검증이며 운영 권한 전달 경로가 완성된 것은 아닙니다. 새 의존성·설치 YAML·native 실행 파일/DLL·업무 자료·설치 ZIP 변경은 없습니다.

## 최종 검증

전체 build -Pimg2tileIntegration -PffmpegIntegration -PnativeViewerIntegration이6분18초에 성공했습니다. Java824개/115 suites의 실패·오류·건너뜀은0개이며 신규8개를 포함합니다. 실제 img2tile/FFmpeg/native 브라우저·라이선스16개 규칙/63개 구성요소/53개 포함 JAR 검사도 통과했습니다. 일반 UI40개 시나리오는 앞선 단계 결과이며 이번 서버 권한 시험에서 별도 재실행하지 않았습니다.

실행 JAR의 테스트/grant fixture 항목0개와 관련3개 문서 로컬 링크24개를 확인했습니다. 집계는 build/reports/linked-actions/verification.json입니다. 운영 권한 처리의 결함은 발견하지 않아 runtime 코드를 바꾸지 않았습니다. 실제 로그인·권한 전달·회수 전파 및 진행 중 작업/전송의 권한 재검사 인수는 잔여이고 distributionReady=false입니다.
