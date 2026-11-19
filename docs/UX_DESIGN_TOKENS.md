# 디자인 토큰 체계안 (리뉴얼 2단계)

2026-11-19. [UX_HEURISTIC_REVIEW.md](UX_HEURISTIC_REVIEW.md)의 U7(하드코딩 색), U16(작은 글자), A3(불투명도로 표현한 상태)을 해결하기 위한 **제안**입니다.

- 실제 `viewer.css` 반영은 4단계에서 합니다.
- 3단계 시안은 이 토큰으로 만듭니다.
- 값은 시안 비교 결과에 따라 조정할 수 있습니다. 조정할 때는 8절 대비 검사를 다시 통과해야 합니다.

## 1. 현재 상태(인벤토리)

`frontend/src/viewer/viewer.css`(10.8KB)에서 스크립트로 뽑은 값입니다. JSX 안에는 색 값이 없습니다.

| 종류 | 서로 다른 값 | 문제 |
| --- | --- | --- |
| 색 토큰 | 9종 × 다크·라이트 | 이름이 역할이 아닌 위치 기준(`--raised`, `--input`)이고, 위험·성공·포커스 토큰이 없음 |
| 토큰 밖 색 | 19종 | 위험 `#ffab9f`·`#ffabab` 두 가지, 로고·번호 배지가 라이트 테마에서도 다크 색, 그림자 5종 |
| 글자 크기 | 18종(8~64px) | 8~11px가 22곳. 본문 기준 13px와 아이콘 크기가 섞여 있음 |
| 간격(padding·margin·gap) | 25종(1~80px) | 3·5·7·9·11·13·21px 같은 4px 체계 밖 값이 다수 |
| 모서리 | 12종(2~20px) | 비슷한 요소끼리도 6·7·8·9·10px로 제각각 |
| 그림자 | 5종 | 테마 구분이 없어 라이트 테마에서 지나치게 진함 |
| 글자 굵기 | 400·550·650·750 | 한글 기본 글꼴(맑은 고딕)은 400·700만 있어 550·650은 실제로 구분되지 않음 |
| 불투명도 | .35(비활성)·.55(삭제 버튼) | 상태를 불투명도로 표현해 대비가 측정값보다 낮아짐 |
| 글꼴 | `Inter` 우선 | Inter를 실제로 불러오지 않아 화면은 Segoe UI·맑은 고딕으로 그려짐 |

## 2. 원칙

1. **역할 이름:** `--color-text-muted`처럼 무엇에 쓰는지로 이름 짓습니다. 화면 코드에는 원시 색 값을 쓰지 않습니다.
2. **두 테마 동일 구조:** 모든 색 토큰은 다크·라이트 값을 함께 정의합니다. 이미지 위에 그리는 주석 색만 테마와 무관합니다.
3. **최소 글자 12px:** 화면 UI 글자는 12px 이상입니다. 아이콘 크기는 글자 크기와 별도 토큰으로 둡니다.
4. **4px 간격 체계:** 간격은 4의 배수(예외는 2px 하나)를 씁니다.
5. **상태는 색으로:** 비활성·흐림을 불투명도로 표현하지 않고, 대비가 계산된 전용 색을 씁니다.
6. **시스템 글꼴 유지:** 뷰잉 속도를 위해 웹폰트를 추가로 내려받지 않습니다(9절 결정 항목).
7. **대비 기준:** 글자는 4.5:1 이상, 포커스·경계 같은 비텍스트는 3:1 이상입니다.

## 3. 색 토큰

| 토큰 | 다크 | 라이트 | 용도 | 기존 |
| --- | --- | --- | --- | --- |
| `--color-bg` | `#0b1017` | `#edf1f7` | 앱·뷰어 바탕 | `--bg` |
| `--color-surface` | `#171e28` | `#ffffff` | 헤더·패널 | `--panel` |
| `--color-surface-raised` | `#202a38` | `#f0f3f8` | 버튼·카드 | `--raised` |
| `--color-surface-sunken` | `#111925` | `#f7f9fc` | 입력란·썸네일 | `--input` |
| `--color-selected` | `#283f64` | `#e2ebff` | 선택된 행·활성 도구 바탕 | `--selected` |
| `--color-border` | `#2b3749` | `#d6deea` | 일반 경계선 | `--line` |
| `--color-border-strong` | `#7d9bcc` | `#5f7bab` | 선택 항목 테두리 | `#6585b4` |
| `--color-text` | `#eaf0fc` | `#18253b` | 본문 | `--text` |
| `--color-text-muted` | `#a7b8d0` | `#4b6080` | 보조 문구 | `--muted`(`#9aadc8`/`#59708e`, 대비 개선) |
| `--color-text-disabled` | `#64748b` | `#76839a` | 비활성 글자·아이콘 | `opacity:.35` 대체 |
| `--color-accent` | `#abc4ff` | `#2f58b0` | 강조 글자·링크·선택 표시 | `--accent` |
| `--color-accent-solid` | `#c9d8ff` | `#2f58b0` | 기본(Primary) 버튼 바탕 | `#d3e1ff` |
| `--color-on-accent` | `#13274a` | `#ffffff` | 기본 버튼 글자 | `#14284a` |
| `--color-danger` | `#ffab9f` | `#b42318` | 오류·삭제 | `#ffab9f`, `#ffabab` 통합 |
| `--color-success` | `#7fd6a4` | `#18794e` | 완료 안내(토스트 등) | 신규 |
| `--color-focus` | `#abc4ff` | `#2f58b0` | 포커스 테두리 | `--accent` 재사용 |
| `--color-brand-bg` | `#263f62` | `#e2ebff` | 로고 블록 | `#263f62`(라이트에서도 다크 색이던 문제) |
| `--color-brand-fg` | `#b8d0ff` | `#2f58b0` | 로고 기호 | `#b8d0ff` |
| `--color-scrim` | `rgb(11 16 23 / .75)` | `rgb(255 255 255 / .85)` | 썸네일 위 번호 배지 등 | `#111a`(라이트에서 어두운 사각형) |
| `--color-backdrop` | `rgb(3 7 13 / .8)` | `rgb(24 37 59 / .4)` | 대화상자 뒤 배경 | `#03070dcc` |

**이미지 위 주석 색(테마 무관)**
| 토큰 | 값 | 용도 | 기존 |
| --- | --- | --- | --- |
| `--color-note-bg` | `rgb(255 241 168 / .92)` | 메모 바탕 | `#fff1a8eb` |
| `--color-note-text` | `#172234` | 메모 글자 | `#172234` |
| `--color-annotation-selected` | `#74a8ff` | 선택된 주석 테두리 | `#74a8ff` |
| `--color-crop-border` | `#b2d2ff` | 자르기 영역 점선 | `#b2d2ff` |
| `--color-crop-fill` | `rgb(141 184 255 / .15)` | 자르기 영역 바탕 | `#8db8ff25` |

## 4. 글자

| 토큰 | 값 | 용도 | 기존 대체 |
| --- | --- | --- | --- |
| `--font-family` | `"Segoe UI","Malgun Gothic","Apple SD Gothic Neo","Noto Sans KR",system-ui,sans-serif` | 전체 | `Inter,…`(Inter 제거) |
| `--font-size-xs` | 12px | 배지·파일 정보·힌트·캡션 | 8·9·10·11·12px |
| `--font-size-sm` | 13px | 기본 본문·버튼·입력 | 13px |
| `--font-size-md` | 14px | 파일명·패널 제목·로고 | 15px 일부 |
| `--font-size-lg` | 16px | 메모 글자·음성 카드 제목 | 16px |
| `--font-size-xl` | 20px | 안내 화면 제목·대화상자 제목 | 18·20px |
| `--font-weight-regular` | 400 | 본문 | 400 |
| `--font-weight-semibold` | 600 | 제목·버튼·선택 항목 | 550·650 |
| `--font-weight-bold` | 700 | 로고 | 750 |
| `--line-height-tight` | 1.3 | 제목·한 줄 | — |
| `--line-height-normal` | 1.5 | 안내 문구 | 1.35·1.5·1.8 혼재 |
| `--letter-spacing-caps` | .06em | 영문 대문자 라벨 | 1px·2px |

**아이콘 크기(글자 크기와 분리)**
| 토큰 | 값 | 용도 | 기존 |
| --- | --- | --- | --- |
| `--size-icon-sm` | 16px | 목록·버튼 안 | 16px |
| `--size-icon-md` | 20px | 헤더 | 18·19·20px |
| `--size-icon-lg` | 24px | 도구 버튼·페이지 이동 | 23·24px |
| `--size-icon-xl` | 32px | 썸네일 대체 기호 | 25·29px |
| `--size-icon-hero` | 48px | 빈 화면·음성 카드 | 46·64px |

## 5. 간격·모서리·그림자·크기

**간격(4px 체계)**
| 토큰 | 값 | 기존 대체 예 |
| --- | --- | --- |
| `--space-0-5` | 2px | 1·2px |
| `--space-1` | 4px | 3·4·5px |
| `--space-2` | 8px | 6·7·8px |
| `--space-3` | 12px | 9·10·11·12·13px |
| `--space-4` | 16px | 14·15·16·18px |
| `--space-5` | 20px | 20·21·22px |
| `--space-6` | 24px | 24·25px |
| `--space-8` | 32px | 30px |
| `--space-10` | 40px | 45px |

화면 배치 값(헤더 높이 60px, 재생 영역 위 여백 65px, 빈 목록 80px)은 간격이 아니라 아래 크기 토큰으로 둡니다.

**모서리**
| 토큰 | 값 | 용도 | 기존 |
| --- | --- | --- | --- |
| `--radius-sm` | 4px | 배지·작은 표시 | 2·3·4px |
| `--radius-md` | 6px | 버튼·입력·썸네일 | 6·7·8px |
| `--radius-lg` | 10px | 파일 행·페이지 이동·동영상 | 9·10px |
| `--radius-xl` | 16px | 대화상자·카드·안내 화면 | 14·18·20px |
| `--radius-full` | 999px | 돋보기·알약 모양 | 50% |

**그림자(테마별)**
| 토큰 | 다크 | 라이트 | 용도 |
| --- | --- | --- | --- |
| `--shadow-sm` | `0 2px 6px rgb(0 0 0 / .25)` | `0 1px 3px rgb(24 37 59 / .12)` | 메모 |
| `--shadow-md` | `0 12px 30px rgb(0 0 0 / .35)` | `0 8px 24px rgb(24 37 59 / .12)` | 모바일 패널·돋보기 |
| `--shadow-lg` | `0 20px 60px rgb(0 0 0 / .4)` | `0 16px 48px rgb(24 37 59 / .16)` | 동영상·음성 카드·대화상자 |

**크기·층위**
| 토큰 | 값 | 용도 |
| --- | --- | --- |
| `--size-header` | 60px | 헤더 높이 |
| `--size-panel-left` / `--size-panel-right` | 244px / 272px | 패널 폭(현재 유지, 3단계에서 재검토) |
| `--size-control` | 32px | 기본 버튼·입력 높이 |
| `--size-control-sm` | 28px | 작은 버튼 |
| `--size-target-min` | 24px | 최소 클릭 대상(WCAG 2.5.8) |
| `--size-target-touch` | 40px | `@media (pointer:coarse)`의 최소 대상 |
| `--z-stage-ui` · `--z-message` · `--z-header` · `--z-panel` · `--z-lens` | 2 · 3 · 4 · 5 · 10 | 현재 값 이름 붙이기 |
| `--duration-fast` | 120ms | 상태 전환. `prefers-reduced-motion`이면 0 |

## 6. CSS 초안

4단계에서 `viewer.css` 맨 앞을 이 블록으로 바꿉니다. 옮기는 동안에는 기존 이름(`--bg` 등)을 새 토큰을 가리키는 별칭으로 남겨 한 번에 깨지지 않게 합니다.

```css
:root{
  --font-family:"Segoe UI","Malgun Gothic","Apple SD Gothic Neo","Noto Sans KR",system-ui,sans-serif;
  --font-size-xs:12px;--font-size-sm:13px;--font-size-md:14px;--font-size-lg:16px;--font-size-xl:20px;
  --font-weight-regular:400;--font-weight-semibold:600;--font-weight-bold:700;
  --line-height-tight:1.3;--line-height-normal:1.5;--letter-spacing-caps:.06em;
  --size-icon-sm:16px;--size-icon-md:20px;--size-icon-lg:24px;--size-icon-xl:32px;--size-icon-hero:48px;
  --space-0-5:2px;--space-1:4px;--space-2:8px;--space-3:12px;--space-4:16px;--space-5:20px;--space-6:24px;--space-8:32px;--space-10:40px;
  --radius-sm:4px;--radius-md:6px;--radius-lg:10px;--radius-xl:16px;--radius-full:999px;
  --size-header:60px;--size-panel-left:244px;--size-panel-right:272px;--size-control:32px;--size-control-sm:28px;--size-target-min:24px;--size-target-touch:40px;
  --z-stage-ui:2;--z-message:3;--z-header:4;--z-panel:5;--z-lens:10;--duration-fast:120ms;
  --color-note-bg:rgb(255 241 168 / .92);--color-note-text:#172234;--color-annotation-selected:#74a8ff;
  --color-crop-border:#b2d2ff;--color-crop-fill:rgb(141 184 255 / .15);
  /* dark (default) */
  color-scheme:dark;
  --color-bg:#0b1017;--color-surface:#171e28;--color-surface-raised:#202a38;--color-surface-sunken:#111925;--color-selected:#283f64;
  --color-border:#2b3749;--color-border-strong:#7d9bcc;--color-text:#eaf0fc;--color-text-muted:#a7b8d0;--color-text-disabled:#64748b;
  --color-accent:#abc4ff;--color-accent-solid:#c9d8ff;--color-on-accent:#13274a;--color-danger:#ffab9f;--color-success:#7fd6a4;--color-focus:#abc4ff;
  --color-brand-bg:#263f62;--color-brand-fg:#b8d0ff;--color-scrim:rgb(11 16 23 / .75);--color-backdrop:rgb(3 7 13 / .8);
  --shadow-sm:0 2px 6px rgb(0 0 0 / .25);--shadow-md:0 12px 30px rgb(0 0 0 / .35);--shadow-lg:0 20px 60px rgb(0 0 0 / .4);
}
:root[data-theme=light]{
  color-scheme:light;
  --color-bg:#edf1f7;--color-surface:#ffffff;--color-surface-raised:#f0f3f8;--color-surface-sunken:#f7f9fc;--color-selected:#e2ebff;
  --color-border:#d6deea;--color-border-strong:#5f7bab;--color-text:#18253b;--color-text-muted:#4b6080;--color-text-disabled:#76839a;
  --color-accent:#2f58b0;--color-accent-solid:#2f58b0;--color-on-accent:#ffffff;--color-danger:#b42318;--color-success:#18794e;--color-focus:#2f58b0;
  --color-brand-bg:#e2ebff;--color-brand-fg:#2f58b0;--color-scrim:rgb(255 255 255 / .85);--color-backdrop:rgb(24 37 59 / .4);
  --shadow-sm:0 1px 3px rgb(24 37 59 / .12);--shadow-md:0 8px 24px rgb(24 37 59 / .12);--shadow-lg:0 16px 48px rgb(24 37 59 / .16);
}
@media (prefers-reduced-motion:reduce){:root{--duration-fast:0ms}}
```

## 7. 사용 규칙(4단계 반영 기준)

- **버튼 4단계**
  - 기본: `accent-solid` + `on-accent`
  - 보조: `surface-raised` + `border`
  - 텍스트: 바탕 없음, `accent`
  - 위험: `danger` 글자. 위험 동작의 확인 버튼만 `danger` 바탕에 흰 글자
- **비활성:** 글자·아이콘은 `--color-text-disabled`이고 커서는 기본값입니다. 불투명도는 쓰지 않습니다.
- **선택:** 바탕은 `--color-selected`, 테두리는 `--color-border-strong`, 강조 글자는 `--color-accent`입니다.
- **포커스:** `outline:2px solid var(--color-focus); outline-offset:2px`
- **오류·안내 구분**(U13): 전체 화면 오류는 파일을 표시할 수 없을 때만 씁니다. 그 밖의 안내는 `danger`·`success` 색의 비차단 알림으로 보여 줍니다.
- **영문 대문자 라벨:** `--font-size-xs` + `--letter-spacing-caps`입니다. 장식 문구 자체를 줄이는 것이 우선입니다(U4).

## 8. 대비 검사 결과

스크립트로 66쌍을 계산했고 모두 통과했습니다. 표에는 토큰별로 여러 배경 중 가장 낮은 값을 적었습니다.

| 전경 토큰 | 다크 최저 | 라이트 최저 | 기준 | 비교한 배경 |
| --- | --- | --- | --- | --- |
| text | 9.25 | 12.85 | 4.5 | bg·surface·raised·sunken·selected |
| text-muted | **5.24** | **5.35** | 4.5 | 같음. 기존 4.63·4.25에서 개선(A3) |
| accent | 6.08 | 5.59 | 4.5 | bg·surface·raised·selected |
| danger | 5.83 | 5.50 | 4.5 | 같음 |
| success | 8.32 | 4.77 | 4.5 | bg·surface·raised |
| on-accent / accent-solid | 10.41 | 6.68 | 4.5 | 기본 버튼 |
| brand-fg / brand-bg | 6.86 | 5.59 | 4.5 | 로고 |
| note-text / note-bg | 13.99 | 13.99 | 4.5 | 메모 |
| focus | 6.08 | 5.59 | 3 | bg·surface·raised·selected |
| border-strong | 3.74 | 3.58 | 3 | surface·selected |
| accent-solid / surface | 11.77 | 6.68 | 3 | 기본 버튼 경계 |
| text-disabled | 3.04 | 3.44 | 3(권장) | surface·raised. WCAG 예외지만 읽을 수 있게 유지 |

- 처음 안에서는 5쌍이 미달이었습니다(선택 테두리 3쌍, 라이트 비활성 2쌍). `border-strong`(`#7d9bcc`/`#5f7bab`)과 라이트 `text-disabled`(`#76839a`)를 조정해 통과시켰습니다.
- 반투명 토큰(`scrim`, `backdrop`, `note-bg`)은 아래에 깔리는 내용에 따라 대비가 달라집니다. 그래서 4단계 브라우저 확인에서 실제 화면으로 측정합니다.

## 9. 영향과 결정이 필요한 점

- **글자 최소 12px:** 기존 8~11px 22곳이 커지므로 도구 패널과 파일 행이 더 길어집니다. 이미 화면 밖으로 밀린 저장 버튼(U15)이 더 내려가므로, **3단계 레이아웃 시안에서 패널 구조를 함께 바꿔야** 합니다. 토큰만 먼저 반영하면 U15가 악화됩니다.
- **글꼴(결정 필요):**
  - (가) 시스템 글꼴 유지: 추가 내려받기 없음, 이 안의 기본값
  - (나) Pretendard 같은 OFL 한글 글꼴을 직접 제공: 화면 일관성은 좋아지지만, 첫 화면에 수백 KB 내려받기와 라이선스 고지가 추가됨
- **기본 글자 크기(결정 필요):** 지금 안은 기존과 같은 13px입니다. 14px로 올리면 읽기는 쉬워지지만 정보 밀도가 줄어듭니다. 3단계 시안에서 13px와 14px를 비교할 수 있습니다.
- **강조색 유지:** 기존 파란 계열 강조색을 유지해 리뉴얼 후에도 익숙함을 남깁니다. 바꾸려면 8절 검사를 다시 돌려야 합니다.

## 10. 재현

- 인벤토리 스크립트: CSS 규칙별로 색·글자 크기·간격·모서리·그림자·층위·굵기·불투명도를 집계합니다.
- 대비 스크립트: 토큰 팔레트와 검사 쌍 목록(전경, 배경들, 기준)으로 WCAG 상대 휘도 대비를 계산하고, 미달이 있으면 종료 코드 1을 냅니다.
- 두 스크립트는 작업 중 임시 폴더에서 실행했습니다. 4단계에서 대비 검사를 반복해야 하면 `scripts/`에 정식으로 추가하는 것을 검토합니다.
