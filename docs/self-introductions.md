# 자기소개 문항

## 목적

이력서 버전별로 자기소개 질문과 답변을 저장하고, 별도 자기소개 화면에서 계정 안의 문항을 검색·수정·삭제한다.
검색 결과는 검색어를 포함한 단어의 빈도와 문서 길이를 반영한 BM25 유사 점수순이며, 화면은 검색어가 나온 부분을 강조한다.
문항 카드는 수정 버튼뿐 아니라 더블클릭으로도 같은 편집기를 연다.

답변마다 **어느 지원용으로 썼나(직접 출처, written_for)** 와 **문항 유형**을 남긴다. AI가 다른 회사용 답변을
가져오는 증상을 막기 위한 2단계다 — [설계](02-design/features/experience-graph.design.md) 2.5·3절·5.3, 7절 2단계.

## 도메인

```
app_users ──1:N──▶ self_introductions ──N:M──▶ resumes          self_introduction_resumes      (간접 출처)
                  id · owner_id · question · answer         ──N:M──▶ positions        self_introduction_positions    (직접 출처, source)
                  · created_at · updated_at                 ──N:M──▶ competencies     self_introduction_competencies (문항 유형, source)
```

하나의 문항은 같은 계정의 이력서 여러 개에 연결할 수 있다. 질문은 이력서 편집기 맨 아래에서 선택적 답변과 함께 추가하거나 과거 문항 검색으로 연결한다. 연결된 기존 문항은 그 자리에서 질문·답변을 수정할 수 있고, 질문 추가와 과거 검색은 기존 문항 목록 아래에 둔다. `/introductions`에서도 전체 CRUD를 한다.

- **직접 출처**: `self_introduction_positions`. 이 답변을 낸(쓸) 모집 직무다. 10개 이하.
- **간접 출처**: 연결 이력서가 지원하는 직무(`resume_positions`). 추정이라 "출처 미상"으로 표시한다.
- **문항 유형**: `competencies`(시스템 고정 코드 18개, V23 시드 = `selfintro/Competency.java`). 3개 이하.
  저장하지 않은 문항은 질의 때 정규식 상위 2개를 신뢰도와 함께 추정한다(`Competency.estimate`).
- **출처(source)**: 간선마다 `USER`(웹 세션 요청, "확정") 또는 `AI`(MCP 토큰 요청, "AI 기록"). 요청 주체가 정하며 인자로 받지 않는다.

## 불변 조건

1. 모든 조회·수정·삭제는 `owner_id`가 포함된 repository 메서드만 사용한다. 타인 데이터는 404다.
   직무·문항 간선은 `(id, owner_id)` 복합 FK라 서비스를 거치지 않은 다른 계정 연결도 DB가 거부한다.
2. **생성은 이력서(`resumeIds`) 또는 지원 직무(`positionIds`)가 1개 이상**이다. **수정은 0개도 허용**한다(직무가 지워져
   연결이 없어진 문항도 고칠 수 있게). 연결할 이력서·직무 묶음은 각각 한 번에 소유자 조건으로 확인하고, 없는 id 는 그 id 로 404다.
   생성·수정 DTO는 분리돼 있다(`SelfIntroductionCreateRequest` · `SelfIntroductionUpdateRequest`).
3. 질문은 1,000자 이하 필수, 답변은 10,000자 이하 선택이다.
4. 검색은 질문과 답변을 Unicode 문자·숫자 단어로 나누고 검색어를 포함한 단어를 서버에서 BM25 유사 점수로 계산한다. 검색어가 없으면 최근 수정순이다.
5. 강조 표시는 텍스트 조각을 Vue 노드로 나누며 `v-html`을 사용하지 않는다.
6. 연결 이력서는 검색창 클릭·포커스 시에만 드롭다운으로 펼친다. 미선택 이력서를 이름으로 검색하고 검색어가 없으면 최근 수정순으로 보여 준다. 목록은 최대 320px(작은 화면은 화면 높이의 45%) 안에서 휠·스크롤바로 탐색하며 폼 본문을 밀어내지 않는다. 화면 아래 남은 공간이 더 작으면 높이를 줄이고, 공간이 거의 없으면 입력을 화면 안으로 이동해 마지막 항목까지 접근할 수 있게 한다.
7. 선택한 이력서는 검색과 무관하게 칩으로 유지하고 칩에서 연결을 해제한다. 연속 선택 시 검색어만 비우고 목록은 열어 둔다. 신규 문항은 첫 이력서를 기본 선택한다. 저장 버튼은 생성이면 이력서·직무 1개 이상, 수정이면 질문만 있으면 열린다.
8. **수정의 `positionIds`·`questionTypes` 는 null 이면 유지한다. 웹은 배열 목록으로 맞추고(✗ 해제), MCP 는 더하기만 한다.**
   MCP 요청은 목록에 없는 기존 연결(확정·AI 기록)을 지우지 못한다(`[]` 도 지우지 않음). AI 기록의 확정과 삭제는 웹 전용이다(설계 5.2, `docs/decisions.md` D4).
   더한 결과가 직무 10개·유형 3개를 넘으면 400 `TOO_MANY_LINKS`. 남는 연결은 출처를 유지하고 새 연결만 요청 주체의 출처를 단다.
   그래서 웹에서 저장해도 AI 기록이 저절로 확정되지 않고, 이 필드를 보내지 않는 이력서 편집기의 문항 저장은 연결을 건드리지 않는다.
9. **확정(AI → USER)은 웹에서만 한다.** `POST /api/self-introductions/{id}/confirm` 은 MCP 도구로 등록하지 않는다. 연결되지 않은 대상은 404 `LINK_NOT_FOUND`.
10. **웹 REST 목록 응답은 답변 전문이다.** 웹은 목록 항목의 `answer` 를 그대로 PUT 하므로 미리보기로 줄이면 답변이 잘려 저장된다.
    MCP 전용 목록 모양(미리보기·limit·출처)은 `McpActions` 래퍼가 따로 만든다. REST 응답에는 편집기용 `positionLinks`·`questionTypes` 만 덧붙였다.
11. `competencies` 표와 enum `Competency` 는 순서·이름·모드가 같아야 한다. 다르면 기동이 멈춘다(`CompetencyCatalogCheck`).

## 편집기의 직무·문항 유형

- `지원 직무`: 고른 직무를 칩으로 보여 준다. 추가는 `직무 추가…` select. 라벨은 `회사 · 공고명 · 직무명` 이다 — 같은 회사의 다른 공고에
  같은 이름 직무가 있어도 고를 수 있게 공고명을 넣고, 공고명이 없거나 직무명과 같으면 뺀다. AI 기록 칩은 warning 톤에 `AI 기록`·✓·✗ 를 단다.
  ✓는 초안이 아니라 바로 확정 요청을 보내고, ✗·×는 초안에서 뺀 뒤 `저장`으로 전송한다.
- **직접 출처가 없으면 "어느 지원용?" 후보 칩**을 띄운다. 후보는 초안에 연결된 이력서들의 직무 **전부**이고 개수를 자르지 않는다.
  이력서 목록 응답의 `positionIds` 로 계산한다(이력서마다 다시 부르지 않는다). 클릭 한 번으로 고르면 후보 줄이 사라진다.
- `문항 유형`: 18개 코드에서 3개까지 고른다. 같은 칩 규칙(AI 기록 ✓·✗)을 따른다.
- 카드에는 직접 출처 직무를 칩으로 보이고, AI 기록은 `· AI 기록` 을 붙인다.

## MCP (`self_intro_*`)

| 도구 | 연결 | 동작 |
|---|---|---|
| `self_intro_list` | `McpActions.listSelfIntros` → `SelfIntroductionAgentService.list` | 필터 `q`(100자, 넘으면 `QUERY_TOO_LONG`)·`resumeId`·`positionId`·`postingId`·`companyId`·`questionType`·`unlinked`, `limit`(기본 10, 1~50로 맞춤). 응답 `{fallback, total, complete, results[]}` |
| `self_intro_get` | `McpActions.getSelfIntro` | 같은 항목 모양에 `answer` 전문 |
| `self_intro_create` / `_update` | `SelfIntroductionController` | 위 DTO 그대로. MCP 로 남긴 연결은 항상 `AI`. `_update` 의 연결은 더하기만(불변 조건 8) |
| `self_intro_delete` | `SelfIntroductionController` | — |

목록 항목: `answerPreview`(300자 + `…`), `answerLength`, `score`·`matchedTerms`(q 가 있을 때), `sourceStatus`(`직접 출처`/`출처 미상`),
`sources{direct[{position, company, trust}], indirect[{resume, position, company}]}`, `questionTypes[{code, name, confidence, from, trust}]`,
`alreadyUsedFor[{company, position, sameCompany, otherCompanySpecific}]`.

- 직무·공고·기업 필터는 그 직무에 written_for 로 쓴 답변을 준다(USER·AI 모두, 항목마다 `trust`).
  **직접 출처가 0건이면** 빈 배열 대신 `fallback:"indirect"` 로, 연결 이력서가 그 직무를 지원하는 답변 중 **직접 출처가 없는 것**만 준다.
- `questionTypes` 는 설계 4.1 순서다: 사용자가 확정한 태그(`from` USER)가 있으면 그것, 없으면 정규식 추정(`from` REGEX, trust `추정`).
  AI 기록 태그(`from` AI)는 **덧붙기만 하고 추정을 지우지 않는다** — 확인 안 된 태그가 지원동기(FIT) 추정을 가려 재사용 금지 표시를 끄면 안 된다.
  `questionType` 필터와 `otherCompanySpecific` 의 FIT 판정은 이렇게 합친 목록으로 한다.
- 읽는 양: 목록·상세는 연결된 직무·이력서의 **이름만** 가벼운 조회(`PositionRepository.findLabels`, `ResumeRepository.findPositionRows`)로 읽는다.
  계정의 직무 상세·이력서 본문(content JSONB)은 읽지 않는다. 공고·기업 필터만 계정 직무 이름 전체(`findAllLabels`)를 한 번 읽는다.
  SQL 수 상한은 [backend.md](backend.md#배치-페치와-sql-수-상한).
- `otherCompanySpecific`: FIT 유형(지원동기·포부·직무 이해) 답변이고, 대상 회사가 없거나 다른 회사다 — 본문 재사용 금지.
  `sameCompany` 는 필터로 회사가 정해졌을 때만 true/false, 아니면 null.

## 진입점

| 무엇 | 어디 |
|---|---|
| 스키마 | `V19__resume_positions_and_self_introductions.sql` · 다중 이력서 연결 `V20__self_introduction_resumes.sql` · 직접 출처·문항 유형 `V23__self_intro_relations.sql` |
| 백엔드 | `selfintro/SelfIntroduction*` · `Competency`(코드·정규식) · `CompetencyCatalogCheck` · `common/LinkSource` · `ApiPaths.SELF_INTRODUCTIONS` |
| MCP 모양 | `selfintro/SelfIntroductionAgentService` · `dto/SelfIntroductionList` · `mcp/McpActions` |
| 출처 판정 | `auth/CurrentUser.viaMcp()` — MCP 토큰이면 AI |
| 화면 | `SelfIntroductionBoard.vue` (`/introductions`) · `ResumeQuestions.vue`(이력서 안에서 문항 추가) |
| 검색·강조 | `SelfIntroductionService.rank()` · `utils/highlight.ts` |

## 실행·검증

```bash
docker compose up -d --build
docker compose logs backend   # now at version v23
bash scripts/smoke.sh
cd frontend && npm run type-check && npm test
```

자동 검증:
- `SelfIntroductionServiceTest`: 생성 "이력서 또는 직무 1개 이상"·수정 0개 허용, 웹 USER·MCP AI 출처, 남의 직무 404, 빈 이력서 목록 수정(예전 `ids.get(0)`), 남는 연결의 출처 유지, null 이면 연결 유지, **MCP 수정은 더하기만·상한 초과 `TOO_MANY_LINKS`**, 확정과 `LINK_NOT_FOUND`.
- `CurrentUserTest`: 목 없이 실제 인증 객체로 `viaMcp()` — `McpPrincipal` 이면 true, 웹 세션(`AppSessionUser`)·익명·없음이면 false.
- `SelfIntroductionControllerTest`(MockMvc): 확정의 404 `LINK_NOT_FOUND`·남의 문항 404·유형 4개 400. CSRF 없는 확정 요청 403 은 `scripts/smoke.sh`.
- `CompetencyTest`: 정규식 상위 2개와 신뢰도, 동점은 표 순서, "기술하시오"의 동사 '기술' 제외.
- `SelfIntroductionRelationsIntegrationTest`(Testcontainers, **`CurrentUser` 목 없음** — 출처는 SecurityContext 의 실제 주체가 정한다):
  다른 계정 직무 id 로 연결 → 404, 같은 행을 SQL 로 넣으면 복합 FK 위반. 남의 문항·직무·공고·기업 id 로 `get`·`list` 필터·`confirm` → 404,
  문항 쪽·유형 표의 owner 불일치 SQL 삽입도 거부. **MCP 토큰으로 `McpTools.call("self_intro_create")` → DB source `AI`**, 웹 세션 → `USER`, 웹 확정 → "확정".
  **MCP `self_intro_update` 가 확정 연결·AI 기록을 지우지 못하고 웹 저장은 지움.** 직접 출처 0건이면 `fallback:"indirect"` 와 "출처 미상", 다른 직무용 답변은 섞지 않음.
  미리보기 301자·정확히 300자는 말줄임 없음, limit 기본 10·상한 50·0 이하 1, **REST 목록은 답변 전문**, `q` 100자 통과·101자 400 `QUERY_TOO_LONG`(MCP `tools/call` 도 같은 코드의 도구 오류).
  다른 회사용 지원동기 `otherCompanySpecific`, 두 회사에 연결한 답변의 연결별 `sameCompany`·FIT 여부. **AI 태그가 정규식 FIT 를 가리지 않고 확정 태그는 앞선다.**
  **MCP 목록·상세가 이력서·직무·공고·기업 엔티티를 읽지 않고 SQL 상한(6·6·7·7) 안.** 직무 삭제 시 연결 CASCADE 와 0개 연결 수정. 표와 enum 일치.
- `McpToolsTest`: `self_intro_list`·`get` 이 래퍼로 가고 REST 목록을 부르지 않음, 스키마의 `maxLength:100`·필터·enum, 생성 요청의 직무·유형 전달과 연결 0개 생성 거부, 확정 도구 없음.
- `SelfIntroduction.spec.ts`: 직무만으로 생성·연결 없는 생성 차단, 수정 0개 허용, "어느 지원용?" 후보(연결 이력서들의 직무 전부), AI 기록 ✓ 확정·✗ 해제, 카드의 AI 기록 표시, **같은 회사·같은 이름 직무를 공고명으로 구분**.

손으로 볼 것: 로그인 뒤 `/introductions` 에서 직무를 골라 저장하고 새로고침해 칩이 남는지, MCP 로 만든 문항의 AI 기록 칩을 ✓로 확정하는지.
그 밖에 이력서에서 여러 직무를 선택해 저장한 다음 새로고침해 유지되는지 확인한다. 기존 문항의 질문·답변을 이력서에서 수정하고 질문 추가·과거 검색이 목록 아래에 있는지 확인한다. 질문을 추가해 자기소개 화면에서
답변 작성·수정·삭제하고, 여러 이력서를 연결할 수 있는지, 문항 카드 더블클릭으로 편집기가 열리는지, 질문 또는 답변의 단어로 검색했을 때 점수순 결과와 강조 표시가 나오는지 확인한다.
연결 이력서 목록의 초기 접힘·이름 검색·연속 선택·칩 해제·키보드·빈 결과·취소 후 재진입을 검사한다.
브라우저에서는 목록 내부 휠 스크롤과 끝에서 페이지로 스크롤이 이어지지 않음, 모바일 너비·최대 높이를 확인한다.

### 연결 이력서 드롭다운 검증 (2026-10-31)

- 컨테이너에서 프런트 타입 검사·전체 테스트 95개·빌드 종료코드 0. 신규·수정 저장 payload와 최소 1개 연결 조건, 검색·선택·해제·닫기·키보드·한글 조합을 검증했다.
- 로컬 Chrome에서 이력서 40개와 가짜 API로 실제 휠·마지막 항목 선택·폼 위치 유지·검색 Enter 자동 저장 방지·신규/수정 저장을 확인했다. 모바일 390px에서는 긴 이름 칩의 말줄임·가로 넘침 없음과 화면 아래 공간에 맞춘 목록 높이를 검증했다.
- 공용 스타일 변경 후 기존 이력서의 지원 직무 드롭다운도 데스크톱·모바일에서 다시 통과했다. 공개 스모크 59개 PASS(종료코드 0).
- 로컬 검증에는 운영 계정·데이터를 사용하지 않았다. 이후 공개 서버 배포·검증 결과는 [배포 기록](nginx-deploy.md#2026-10-31-드롭다운-배포-검증)을 따른다. 실제 운영 계정의 저장은 이번 검증에 포함하지 않는다.

## 함정

- BM25는 계정의 현재 문항 집합 안에서만 IDF를 계산한다. 형태소 분석은 하지 않고 `협업`이 `협업으로`도 찾도록 단어 내 포함 여부를 쓴다. MCP 목록은 필터를 적용한 뒤의 집합으로 IDF를 계산한다.
- 자기소개 문항 생성은 이력서 본문 저장과 별도 요청이다. 성공 응답을 받은 문항만 저장된 것이다.
- 이력서 복제는 연결 직무는 복사하지만 자기소개 문항은 복사하지 않는다. 문항 답변까지 복제하면 서로 다른 지원서가 같은 초안을 갖는 혼동이 생긴다.
- **trust "확정"은 사용자가 연결을 눌러 확인했다는 뜻이지 성과 검증이 아니다.** 웹과 MCP가 같은 문자열(`확정`·`AI 기록`·`추정`)을 쓴다.
- 직무를 지우면(공고 수정에서 빠진 직무 포함) 그 직접 출처가 함께 사라진다(ON DELETE CASCADE). 답변은 남고 "출처 미상"이 된다.
- `ResumeQuestions.vue` 의 문항 저장은 `positionIds`·`questionTypes` 를 보내지 않는다(null=유지). 여기서 빈 배열을 보내면 직접 출처가 지워진다.
- 통합 테스트에서 `CurrentUser` 를 목으로 바꾸고 `viaMcp()` 를 지정하면 출처 판정 자체는 검증되지 않는다. 출처를 보는 테스트는 SecurityContext 에
  `McpPrincipal`·`AppSessionUser` 를 넣어 실제 판정을 탄다.
- 정규식 추정은 키워드 일치 수의 몫이라 질문이 짧으면 신뢰도가 1.0 으로 나와도 틀릴 수 있다. 확정된 유형이 필요하면 웹에서 고른다.
- 같은 id 를 `positionIds` 에 두 번 보내면 하나로 합친다(예전 이력서 중복 id 404 는 없앴다).
