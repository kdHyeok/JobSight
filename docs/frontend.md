# frontend — Vue 3 / TypeScript / Vite

## 진입점

| 무엇 | 어디 |
|---|---|
| 모든 경로 상수 | `src/routes.ts` — `ROUTES`(SPA), `API`(백엔드), `GOOGLE_LOGIN_URL`. 다른 곳에 경로 리터럴을 쓰지 않는다 |
| 셸·라우팅·세션 | `src/App.vue` — `pathname` 으로 `/`·`/positions`·`/companies`·`/resumes`·`/admin`·`/plugin` 분기. 라우터 라이브러리 없음 |
| HTTP + CSRF·세션 갱신 | `src/api/http.ts` — `XSRF-TOKEN` 헤더 첨부, 401이면 한 번만 refresh 후 재시도 |
| 화면 | `PostingBoard.vue`(홈 `/`, 공고 D-day·절차 체인) · `PositionBoard.vue`(`/positions`, 관심 직무/진행 중/보관함 탭·검색·참고 정보 스트립) · `CompanyWorkspace.vue`(`/companies`) · `ResumeBoard.vue`(`/resumes`, 카드 목록 + `?focus=` 편집기) · `SelfIntroductionBoard.vue`(`/introductions`) · `AdminRequestWidget.vue`(로그인 화면 공통 요청 창) · `LoginView.vue` · `AdminView.vue` |
| 이력서 편집 | `ResumeEditor.vue`(전체 페이지, 문서 통째 저장) · `ResumeSection.vue`(섹션 하나 — 행 카드 + ↑↓× + 행 추가). 섹션·필드 정의는 `types/resume.ts` 의 `SECTIONS` — `docs/resumes.md` |
| 상세·수정 | `Drawer.vue` 하나가 보기/수정/추가를 모두 맡는다. 동작 버튼은 헤더 아래 `#actions`. 폼은 껍데기 없이 드로어 안에 들어가고 헤더 버튼이 `form="…-form"` 으로 제출한다. 읽기 영역을 더블클릭해도 같은 수정 폼을 연다 |
| 도메인 계산 | `src/types/posting.ts` 의 D-day·마감 시각 변환. 컴포넌트에 흩뿌리지 않는다 — `docs/job-postings.md` |
| 타입 | `src/types/*.ts` — 백엔드 DTO 와 1:1. `posting.ts`(공고·절차·D-day), `position.ts`(직무·참고 정보), `company.ts`, `company-content.ts`, `resume.ts`(문서 모양 + `SECTIONS`) |
| 스타일 | `src/styles/main.css` 한 파일. 토큰과 배치 규칙은 [docs/design-system.md](design-system.md) |

## 원칙

- `/plugin`은 비로그인 사용자도 볼 수 있는 연결 가이드다. 상단 메뉴 버튼의 작은 사이드 패널로 진입한다. 관리자 항목은 ADMIN만 표시한다. canonical URL은 서버 공개 설정에서 가져온다. 화면에는 ChatGPT URL 연결과 Codex GitHub 설치·업데이트에 필요한 동작만 표시하고 CIMD·scope·수동 OAuth 같은 내부 설정은 노출하지 않는다.

- **base URL 을 모른다.** nginx 뒤 same-origin 이라 상대 경로만 쓴다. 단, `localhost:8088`의 Google 로그인 시작만 쿠키 호스트가 갈라지지 않도록 `127.0.0.1:8088`로 보낸다.
- **로그인은 fetch 가 아니라 네비게이션.** `<a :href="googleLoginUrl()">`. 실패는 `/?authError=CODE` 로 돌아오며 `LoginView` 가 문구로 바꾸고 쿼리를 지운다.
- 첫 세션 확인이 실패하면 로그인 화면을 함께 노출하지 않는다. 연결 실패와 `다시 시도`만 표시해 장애를 비로그인으로 오인하지 않게 한다.
- 로그인 옵션 조회가 실패해도 Google 미설정으로 표시하지 않는다. 연결 실패와 `다시 시도`를 보여 주고, 성공 응답을 받은 뒤에만 로그인 설정 여부를 표시한다.
- 첫 `me`가 비로그인이라도 HttpOnly 리프레시 쿠키가 있으면 한 번 갱신한다. 여러 API가 동시에 401을 받아도 공유 Promise 하나만 토큰을 회전한다. 실패하면 기존 401/로그인 화면 흐름을 그대로 쓴다.
- **상태 보유자는 화면당 하나.** 자식은 props-down / events-up. Pinia 없음.
- **목록은 핵심만, 나머지는 클릭.** 카드에 필드를 더하고 싶으면 드로어 상세에 넣는다 — design-system.md 규칙 3.
- **더블클릭 편집은 기존 수정 진입점을 재사용한다.** 공고·기업·직무 상세, 이력서 카드, 자기소개 카드, 참고 정보 카드는 더블클릭으로 기존 편집 화면을 연다. 링크·버튼·입력과 다른 항목을 여는 행은 그 동작을 우선한다.
- **에러 계약**: 백엔드 `ApiError.fieldErrors` → `ApiClientError.fieldErrors` → 폼 필드 메시지.
- 기업 상세의 `뉴스·유튜브` 탭은 `CompanyContentAlbum`이 상태를 가진다. `CompanyContentForm`은 탭 안에 인라인으로 열리고 별도 모달·드로어를 만들지 않는다.

## 테스트

`src/tests/*.spec.ts` (Vitest + @vue/test-utils). `api/*` 는 `vi.mock` 으로 대체한다. 새 API 함수를 추가하면 `App.spec.ts` 의 mock 목록에도 추가해야 한다.

```bash
npm run type-check && npm test
```

## 빌드

MCP/OAuth 클라이언트 경로는 `routes.ts`의 `MCP_ENDPOINTS`에 정리한다. SPA는 이 경로로 세션 인증 API 요청을 보내지 않는다.

`npm run build` = `vue-tsc -b && vite build`. Dockerfile 이 이걸 실행하므로 타입 오류는 이미지 빌드 실패로 드러난다.
