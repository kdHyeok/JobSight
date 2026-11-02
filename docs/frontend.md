# frontend — Vue 3 / TypeScript / Vite

## 진입점

| 무엇 | 어디 |
|---|---|
| 모든 경로 상수 | `src/routes.ts` — `ROUTES`(SPA), `API`(백엔드), `GOOGLE_LOGIN_URL`. 다른 곳에 경로 리터럴을 쓰지 않는다 |
| 셸·라우팅·세션 | `src/App.vue` — `pathname` 으로 `/`·`/positions`·`/companies`·`/resumes`·`/introductions`·`/experiences`·`/admin`·`/plugin` 분기. 라우터 라이브러리 없음 |
| HTTP + CSRF·세션 갱신 | `src/api/http.ts` — `XSRF-TOKEN` 헤더 첨부, 401이면 한 번만 refresh 후 재시도 |
| 화면 | `PostingBoard.vue`(홈 `/`, 공고 D-day·절차 체인) · `PositionBoard.vue`(`/positions`, 관심 직무/진행 중/보관함 탭·검색·참고 정보 스트립) · `CompanyWorkspace.vue`(`/companies`) · `ResumeBoard.vue`(`/resumes`, 카드 목록 + `?focus=` 편집기) · `SelfIntroductionBoard.vue`(`/introductions`, 쓴 경험 선택) · `ExperienceBoard.vue`(`/experiences`, 경험 원본 목록·드로어 상세·`ExperienceForm` 편집·`ExperienceImport` 가져오기 — docs/experiences.md) · `AdminRequestWidget.vue`(로그인 화면 공통 요청 창) · `LoginView.vue` · `AdminView.vue` |
| 이력서 편집 | `ResumeEditor.vue`(전체 페이지, 문서 통째 저장) · `ResumeSection.vue`(섹션 하나 — 행 카드 + ↑↓× + 행 추가. 경험 섹션은 행 바에 경험 연결·등록·갱신, 머리에 "경험에서 불러오기"). 섹션·필드 정의는 `types/resume.ts` 의 `SECTIONS` — `docs/resumes.md` |
| 상세·수정 | `Drawer.vue` 하나가 보기/수정/추가를 모두 맡는다. 동작 버튼은 헤더 아래 `#actions`. 폼은 껍데기 없이 드로어 안에 들어가고 헤더 버튼이 `form="…-form"` 으로 제출한다. 읽기 영역을 더블클릭해도 같은 수정 폼을 연다 |
| 도메인 계산 | `src/types/posting.ts` 의 D-day·마감 시각 변환. 컴포넌트에 흩뿌리지 않는다 — `docs/job-postings.md` |
| 타입 | `src/types/*.ts` — 백엔드 DTO 와 1:1. `posting.ts`(공고·절차·D-day), `position.ts`(직무·참고 정보), `company.ts`, `company-content.ts`, `resume.ts`(문서 모양 + `SECTIONS`), `experience.ts`(경험 원본 + 섹션↔종류 `SECTION_KINDS`, 행↔경험 변환, `needsReview`) |
| 스타일 | `src/styles/main.css` 한 파일. 토큰과 배치 규칙은 [docs/design-system.md](design-system.md) |

## 원칙

- `/plugin`은 비로그인 사용자도 볼 수 있는 연결 가이드다. 상단 메뉴 버튼의 작은 사이드 패널로 진입한다. 관리자 항목은 ADMIN만 표시한다. canonical URL은 서버 공개 설정에서 가져온다. 화면에는 ChatGPT URL 연결과 Codex GitHub 설치·업데이트에 필요한 동작만 표시하고 CIMD·scope·수동 OAuth 같은 내부 설정은 노출하지 않는다.
  GitHub 설치·복사 명령은 `kdHyeok/JobSight` 저장소를 사용하며, 마켓플레이스·플러그인 식별자는 `jobsight`로 유지한다.

- **base URL 을 모른다.** nginx 뒤 same-origin 이라 상대 경로만 쓴다. 단, `localhost:8088`의 Google 로그인 시작만 쿠키 호스트가 갈라지지 않도록 `127.0.0.1:8088`로 보낸다.
- **로그인은 fetch 가 아니라 네비게이션.** `<a :href="googleLoginUrl()">`. 실패는 `/?authError=CODE` 로 돌아오며 `LoginView` 가 문구로 바꾸고 쿼리를 지운다.
- 첫 세션 확인이 실패하면 로그인 화면을 함께 노출하지 않는다. 연결 실패와 `다시 시도`만 표시해 장애를 비로그인으로 오인하지 않게 한다.
- 로그인 옵션 조회가 실패해도 Google 미설정으로 표시하지 않는다. 연결 실패와 `다시 시도`를 보여 주고, 성공 응답을 받은 뒤에만 로그인 설정 여부를 표시한다.
- 첫 `me`가 비로그인이라도 HttpOnly 리프레시 쿠키가 있으면 한 번 갱신한다. 여러 API가 동시에 401을 받아도 공유 Promise 하나만 토큰을 회전한다. 실패하면 기존 401/로그인 화면 흐름을 그대로 쓴다.
- **상태 보유자는 화면당 하나.** 자식은 props-down / events-up. Pinia 없음.
- **목록은 핵심만, 나머지는 클릭.** 카드에 필드를 더하고 싶으면 드로어 상세에 넣는다 — design-system.md 규칙 3.
- **더블클릭 편집은 기존 수정 진입점을 재사용한다.** 공고·기업·직무 상세, 이력서 카드, 자기소개 카드, 참고 정보 카드는 더블클릭으로 기존 편집 화면을 연다. 링크·버튼·입력과 다른 항목을 여는 행은 그 동작을 우선한다.
- **에러 계약**: 백엔드 `ApiError.fieldErrors` → `ApiClientError.fieldErrors` → 폼 필드 메시지.
- **늦은 응답은 요청 순번으로 버린다.** 겹칠 수 있는 조회는 `let seq` 를 두고 시작할 때 올린 번호가 마지막일 때만 반영한다(성공·실패 모두). 닫기·모드 전환도 번호를 올려 진행 중 응답이 드로어를 다시 열거나 폼을 덮지 않게 한다. 쓰는 곳: `SubmissionRecords`(목록·확인 폼), `ResumeQuestions`, `ExperienceBoard.open`, `ResumeEditor.loadExperiences`, `ResumeBoard`. 같은 GET 을 두 번 보낼 수 있는 펼침(`WritingGuideEditor`)은 진행 중 플래그로 한 번만 보낸다.
- **삭제 확인창은 덧붙일 경고를 받기 전에 확정되지 않는다.** 직무·공고·기업 삭제는 `deleteNotice` 가 `null`(자소서 수를 받는 중)인 동안 `ConfirmDialog` 의 `pending` 으로 삭제 버튼만 막고 취소는 둔다. 수를 못 받으면 0 으로 치지 않고 "확인하지 못했습니다. 있다면 … 사라집니다" 를 붙인다(`utils/writtenFor.ts`).
- **409 뒤 다시 읽기는 열 때의 version 을 바꾸지 않는다.** `ResumeQuestions` 수정은 편집을 시작할 때의 version(`editVersion`)으로 보낸다 — 목록을 다시 읽어도 열린 초안이 새 version 을 얻어 남의 저장을 덮지 않는다. 편집기를 다시 열어야 새 version 이다. 다시 읽기마저 실패하면(`WritingGuideEditor`) 입력을 남기고 이유를 보인다.
- **행 번호로 결과를 다는 비동기 작업 중에는 행 순서를 잠근다.** 이력서 편집기 "경험으로 등록" 이 끝날 때까지 `ResumeSection` 의 `locked` 로 ↑↓×·다른 등록을 막는다. 칸 입력·행 추가(끝에 붙음)는 번호를 바꾸지 않아 막지 않는다.
- **경험 목차는 `complete` 까지 다 받는다**(`api/experiences.ts listExperiences`, 쪽수 상한 없음). 커서가 앞으로 가지 않으면 잘린 목록을 돌려주지 않고 실패한다. 가져오기는 건너뛴 묶음을 보내지 않고, 나머지가 서버 상한(500)을 넘으면 보내지 않고 나눠 가져오라고 알린다. 제출 확인 폼의 처음 선택은 지금 목록에 있는 문서로만 고른다(지운 문서는 스냅샷에만 남는다).
- 기업 상세의 `뉴스·유튜브` 탭은 `CompanyContentAlbum`이 상태를 가진다. `CompanyContentForm`은 탭 안에 인라인으로 열리고 별도 모달·드로어를 만들지 않는다.

## 테스트

`src/tests/*.spec.ts` (Vitest + @vue/test-utils). `api/*` 는 `vi.mock` 으로 대체한다. 새 API 함수를 추가하면 `App.spec.ts` 의 mock 목록에도 추가해야 한다.

```bash
npm run type-check && npm test
```

## 빌드

MCP/OAuth 클라이언트 경로는 `routes.ts`의 `MCP_ENDPOINTS`에 정리한다. SPA는 이 경로로 세션 인증 API 요청을 보내지 않는다.

`npm run build` = `vue-tsc -b && vite build`. Dockerfile 이 이걸 실행하므로 타입 오류는 이미지 빌드 실패로 드러난다.
