# 기업 · 채용공고 · 모집 직무 · 참고 정보

## 목적

채용공고를 마감 임박 순으로 관리하고, 공고 안의 **모집 직무**를 따로 들여다보며, 직무마다 **참고 정보**를 붙여 둔다.
내 참여 상태와 회사의 채용 절차는 서로 다른 축이라 따로 기록한다.

## 도메인

```
app_users
 └─1:N─ companies                    unique(owner_id, lower(replace(name,' ','')))
         └─1:N─ job_postings         company_id NOT NULL
                 │  title(모집 부문) · posting_url · employment_type · deadline_at
                 │  status(내 상태) · qualifications · memo · target_position_id? · archived_at
                 ├─1:N─ recruitment_steps   seq · name · result · scheduled_at · memo
                 └─1:N─ positions (모집 직무)  ≥ 1 보장
                         │  name · team · role · responsibilities · impact · growth
                         │  experience · required_skills · preferred_skills · headcount · work_location · memo
                         ├─ position_tech_stack   (ElementCollection)
                         └─N:M─ reference_items  via position_references
                                  kind · title · url · memo · related_position_id?
```

소유자(`owner_id`)는 `companies` `job_postings` `positions` `reference_items` 네 테이블 모두에 있다. 조인 없이 소유자 격리 쿼리를 쓰기 위해서다.

### 두 축 — 내 상태 vs 회사 절차

| 축 | 어디 | 값 |
|---|---|---|
| **내 참여 상태** `status` | `job_postings` | `INTERESTED` `DRAFTING` `SUBMITTED` `WRITTEN_TEST_PREP` `INTERVIEW_PREP` `ACCEPTED` `DOCUMENT_REJECTED` `WRITTEN_TEST_REJECTED` `INTERVIEW_REJECTED` (`CLOSED`는 기존 데이터 호환 전용) |
| **회사 절차 완료 여부** | `recruitment_steps.result` | `UPCOMING` `PASSED` |

이전 `stage` 의 `CODING_TEST` `INTERVIEW` `AWAITING_RESULT` 는 절차 단계다. V10 이 `SUBMITTED` + 그 이름의 step 하나로 옮겼다.
"현재 단계" 는 첫 `UPCOMING` step. 화면은 `[서류] → [인적성] → [면접]` 체인으로 그리고 노드를 누르면 예정↔완료로 바꾼다.

### 필드 위치

| 필드 | 어디 | 이유 |
|---|---|---|
| 모집 부문(제목), 링크, 고용형태, 마감, 지원자격 | 공고 | 공고 하나에 하나 |
| 담당업무, 요구/우대 역량, 기술 스택, 인원, 근무지, 팀, 역할, 영향력, 성장, 경험 | 직무 | 공채는 직무마다 다르다 |
| 절차 | 공고 | 결정 2 — 직무별 차이는 단계 이름에 적는다("코딩테스트(개발직군)") |
| 복지 | 기업 | 공고·직무와 무관한 기업 공통 정보 |
| 개인 메모 | 기업·공고·직무 | 대상마다 별도 기록. 원문 Markdown으로 저장하고 상세에서만 렌더링 |

## 불변 조건

1. **소유자 격리**: 네 테이블 모두 `…AndOwnerId` 조회만. 타인 데이터는 404.
2. **공고는 직무가 1개 이상**: 생성 때 직무가 비었거나 수정 때 `positions:[]` 면 `title` 과 같은 이름의 직무 하나만 남긴다(그 이름의 기존 직무가 있으면 그 행을 쓴다). 마지막 직무를 삭제하면 소속 공고도 함께 삭제한다.
3. **기업은 직접 입력하면 자동 생성**: `companyId` 없이 `companyName` 만 오면 공백 제거·소문자 기준으로 기존 기업을 찾고, 없으면 이름만 채운 기업을 만든다. `company_name_snapshot` 은 없다.
4. **D-day 정렬**: 마감 가까운 순, 상시(NULL)는 맨 뒤. 서버가 정렬한다.
5. **자동 보관**: 마감 지남 + `status ∈ {INTERESTED, DRAFTING}` 인 공고는 미지원으로, 세 탈락 상태와 기존 `CLOSED`는 즉시 보관한다. 탈락이 아닌 상태로 옮기면 보관함에서 자동으로 꺼낸다.
6. **참고 정보는 계정 안에서 공유**(결정 1): 한 참고 정보가 여러 직무에 붙는다. 다른 사용자와는 공유하지 않는다.
7. **절차는 공고 단위**(결정 2). `seq` 는 `@OrderColumn` 이 관리하는 0-based 정수. 분기 없음.
8. **메모는 원문 저장**: 기업·공고·직무 memo는 5,000자 이하 Markdown 문자열이다. 서버는 HTML로 변환하지 않는다.
9. **공고 수정의 `positions`·`steps` 는 null=유지, 배열=그 목록으로 맞춤, `[]`=비움**(REST PUT 과 `posting_update` 공통). 나머지 공고 필드는 전체 교체다.
   직무는 id 로 먼저, id 가 없으면 이름(`Company.nameKey`: 공백 제거·소문자)으로 남은 기존 직무와 맞춘다. **같은 직무를 지웠다 다시 만들지 않는다** — 직무 행이 바뀌면 `resume_positions`(와 2단계의 자소서 직무 연결)가 `ON DELETE CASCADE` 로 끊긴다. 어느 항목과도 맞지 않은 기존 직무만 삭제한다. — `JobPostingService.syncPositions`, 설계 1.4 T3
10. **공고 수정과 상태 변경은 공고 행을 잠근다.** `JobPostingService.update`·`changeStatus` 는 `findOwnedForUpdate`(`PESSIMISTIC_WRITE`, `lock_timeout 3s`)로 읽는다. 상태 변경이 잠금 없이 읽으면, SUBMITTED 를 떠나는 수정이 커밋 전일 때 옛 SUBMITTED 를 보고 DRAFTING → SUBMITTED 전이를 놓쳐 LINKED 를 새로 남기지 않는다(계정 잠금은 SUBMITTED 로 가는 쓰기만 잡는다, `JobPostingUpdateIntegrationTest.changeStatusWaitsForAnUpdateLeavingSubmitted`). 수정은 읽은 뒤 직무를 맞춘다. 잠금이 없으면 병렬 수정 두 건이 같은 기존 직무 목록을 읽고 같은 새 직무를 두 행으로 만든다. 직무 id 에 걸린 연결(이력서, 2단계 자소서 written_for)이 중복·삭제 경쟁으로 오염된다. 3초 안에 잠금을 못 얻으면 409 `BUSY`(docs/backend.md).

11. **제출 기록은 상태 전이 한 곳에서 남긴다**(설계 6.6). 쓰기의 결과가 `SUBMITTED` 면 `JobPostingService.withSubmission` 이 `SubmissionService.recordLinked` 를 부른다 — 웹 드래그(`changeStatus`)·공고 수정·공고 생성(MCP `posting_set_status`·`posting_update`·`posting_create` 포함) 셋 모두다. 응답의 `submission{id, level}` 로 돌려준다. `SUBMITTED` 로 가는 쓰기는 트랜잭션 맨 앞에서 `OwnerLock`(T9)을 잡고, 스냅샷은 직무 동기화를 flush 한 뒤 JDBC 로 읽는다.
12. **LINKED 는 제출본이 아니다.** 상태 변경 때 남는 기록은 "기록 당시 연결 자료"(그 공고 직무에 연결된 이력서 `resume_positions` 와 그 직무용 자소서 written_for)다. 실제로 낸 것은 웹 공고 상세의 "실제로 낸 것 확인"으로 사용자가 고른다(`USER_CONFIRMED`, 접수 메일 발췌·번호·근거 파일을 붙이면 `RECEIPT_VERIFIED`). 기록은 고치지 않고 쌓으며, 같은 공고·수준에서 스냅샷 내용의 SHA-256(`content_hash`)이 같으면 새로 쓰지 않고 기존 기록을 돌려준다.

## 진입점

| 무엇 | 어디 |
|---|---|
| 스키마 | `V9__…job_postings.sql` → `V10__positions_steps_references.sql` → `V12__application_status_and_revenue_unit.sql` → **`V13__company_benefits_and_step_results.sql`** |
| 공고 | `posting/JobPosting` `JobPostingService` `JobPostingController` — `ApiPaths.POSTINGS` |
| 절차 | `posting/RecruitmentStep` (공고에 임베드, cascade) · `PATCH /postings/{id}/steps/{seq}` |
| 직무 | `position/Position` `PositionService` `PositionController` — `ApiPaths.POSITIONS` |
| 참고 정보 | `reference/ReferenceItem` `ReferenceService` `ReferenceController` — `ApiPaths.REFERENCES` · 연결은 `PUT /positions/{id}/references` |
| 제출 기록 | `V28__submissions_and_guides.sql` · `submission/SubmissionService` `SubmissionController` — `ApiPaths.POSTING_SUBMISSIONS` · 화면 `SubmissionRecords.vue`(공고 상세, 관심·작성 중에는 숨김) |
| 기업 자동 생성 | `JobPostingService.resolveCompany()` + `CompanyRepository.findByOwnerIdAndNameKey()` |
| 화면 | `PostingBoard`(홈 `/`, 관심 공고/진행 중/보관함 칸반) · `PositionBoard`(`/positions`) · `CompanyWorkspace`(`/companies`) |
| Markdown 메모 | `MarkdownTextarea.vue`(Tab·들여쓰기) · `MarkdownMemo.vue`(안전한 렌더링) · `utils/markdown.ts`(지원 문법 파서) |
| D-day·절차 표기 | `frontend/src/types/posting.ts` — `daysUntil` `ddayLabel` `ddayTone` `formatDeadline` `nextStepResult` |
| 화면 간 이동 | `routes.ts` 의 `FOCUS_QUERY`. `App.navigate(route, id)` → `?focus=<id>` → 보드가 `applyFocus()`/`openById()` 로 그 드로어를 연다 |
| 이동 방향 | 정방향(자식, 행 더블클릭): 기업→공고, 공고→직무. 역방향(부모, 링크 버튼): 공고→기업, 직무→공고 |

공고·기업·직무 상세의 일반 읽기 값을 더블클릭하면 기존 수정 폼을 연다. 정방향 이동 행과 링크·버튼·입력은 원래 동작을 유지한다. 참고 정보 카드를 더블클릭하면 직무가 아니라 그 참고 정보의 수정 폼을 연다.

## API 요약 (상세는 Swagger)

```
GET/POST        /api/postings              ?archived=
GET/PUT/DELETE  /api/postings/{id}         PUT 의 positions·steps 는 null=유지, 배열=그 목록으로 맞춤(불변 조건 9)
                                           GET 상세만 linkedSelfIntroCount(소속 직무 전부, 함정 참고)
PATCH           /api/postings/{id}/status  {status}   결과가 SUBMITTED 면 응답에 submission{id, level}(PUT·POST 도 같다)
GET/POST        /api/postings/{id}/submissions  제출 기록 최신순 / 제출본 확인 {level USER_CONFIRMED|RECEIPT_VERIFIED,
                                           resumeIds, selfIntroIds, receiptExcerpt?, receiptNumber?, receiptAttachmentIds?}
PATCH           /api/postings/{id}/steps/{seq}  {result}
PATCH           /api/postings/{id}/archive ?archived=
GET             /api/positions             ?q=  (이름·팀·회사·스택 부분 일치)
GET/PUT/DELETE  /api/positions/{id}         마지막 직무 DELETE 는 소속 공고까지 삭제. GET 상세만 linkedSelfIntroCount
GET             /api/companies/{id}         상세만 openPostings·linkedSelfIntroCount(소속 공고 전부의 직무)
PUT             /api/positions/{id}/references  {referenceIds[]}  집합 교체
GET/POST        /api/references            ?q=
PUT/DELETE      /api/references/{id}
```

## 실행·검증

```bash
docker compose up -d --build && docker compose logs backend | grep "now at version"   # v10
bash scripts/smoke.sh                       # /api/positions /api/references 미인증 401 포함
cd frontend && npm run type-check && npm test
```

자동 검증(불변 조건 9): `JobPostingServiceTest` 의 null 유지·이름 맞춤·`[]` 비움 3건과 id 경로 4건(id 우선 맞춤, id 로 이름 변경·빠진 직무 삭제·지원 직무 비움, 이름 맞춤 시 지원 직무 유지, 같은 id 반복·남의 id), `JobPostingUpdateIntegrationTest`(Testcontainers — 이름만 보낸 수정과 웹 폼처럼 id 를 담은 수정 뒤에도 직무 id 와 `resume_positions` 행이 맞는 이름에 남는다. 불변 조건 10: 같은 새 직무를 담은 병렬 수정 두 건 뒤에도 그 직무 행은 하나다), `McpToolsTest.postingUpdateKeepsOmittedListsAsNull`(MCP 가 빠진 배열을 null 로 넘기고 "전체 교체" 문구를 붙이지 않는다), `PostingForm.spec.ts`(폼은 다 지운 직무·절차를 `[]` 로 보낸다).
제출 기록(불변 조건 11·12): `SubmissionIntegrationTest`(Testcontainers — 같은 상태 변경 두 번·SUBMITTED→DRAFTING→SUBMITTED 에도 기록 하나, 웹 드래그·공고 수정·공고 생성·MCP `posting_set_status` 모두 LINKED, 뒤늦게 SUBMITTED 로 만든 공고는 빈 LINKED, 사용자 확인·접수 근거 첨부와 그 첨부의 삭제 409, 계정 삭제 cascade, 남의 공고·이력서·파일 404·400, alreadyUsedFor 의 제출본 우선), `Submission.spec.ts`(LINKED 는 "미확인"으로 보이고, 확인 폼은 최근 기록 문서를 처음 선택으로 둔다). 변형 확인: `changeStatus` 가 기록을 부르지 않게 하면 3건이 실패한다.
삭제 전 자소서 안내: `SelfIntroductionRelationsIntegrationTest.detailCountsSelfIntrosWhoseDirectSourceADeleteWouldDrop`(Testcontainers — 직무 1·2, 공고 2(두 직무에 연결한 문항은 한 번), 기업 3, 목록 null, MCP `*_get` 같은 값, 남의 직무·공고·기업 id 로 세면 0), `PositionBoard.spec.ts`(목록에서 고른 직무는 상세를 불러 N 개 안내, 0 이면 문구 없음), `CompanyWorkspace.spec.ts`(상세에 실린 수를 다시 부르지 않음).

로그인 뒤 브라우저에서:
1. 공고 추가 → 기업을 직접 입력 → 기업 탭에 생겼는가
2. 직무를 비우고 저장 → 직무 하나가 제목 이름으로 생겼는가
3. 절차 3개 입력 → 상세에서 체인이 보이고 노드를 누르면 결과가 바뀌는가
4. 마감 과거 + `작성중` → 목록 새로고침 → 보관함으로 갔는가
5. 직무 페이지의 관심 직무/진행 중/보관함 탭에서 검색 → 드로어 → 참고 정보 추가(기존 검색 / 새로 만들기) → 수정 → 떼기 → 삭제
6. 기업 상세의 채용정보 행을 더블클릭 → 채용공고 화면에서 그 공고가 열리는가(주소창에 `?focus=`)
7. 공고 상세의 모집 직무 행을 더블클릭 → 모집 직무 화면에서 그 직무가 열리는가
8. 공고 상세의 `고용회사 ↗` → 기업 화면에서 그 기업이 열리는가
9. 직무 상세의 `공고 ↗` → 채용공고 화면에서 그 공고가 열리는가(보관된 공고도)
10. 관심 공고 칸반은 관심·작성 중, 진행 중 칸반은 제출 완료·필기 준비·면접 준비·합격, 보관함은 미지원·세 탈락으로 나뉘는가
11. 탈락 상태로 바꾸면 즉시 보관되고, 다른 상태로 바꾸면 다시 진행 중으로 나오는가
12. 새 공고의 마감과 새 절차 단계 시간이 현재 분으로 채워지는가
13. 기업 업종과 직무 기술 스택을 기존 태그에서 고르거나 새 태그로 추가·수정·삭제할 수 있는가
14. 매출액을 만 원/억 원으로 입력하고 같은 단위로 다시 표시·수정할 수 있는가
15. 카드를 같은 탭의 다른 열로 끌면 그 열의 상태가 되고, 다른 탭으로 끌면 탭 기본 상태로 이동하는가
16. 기업 복지를 저장·수정하면 상세에 그대로 보이는가
17. 채용 절차 노드를 누를 때 예정과 완료만 번갈아 표시되는가
18. 공고 제목 옆 숫자는 없고 관심 공고·진행 중·보관함 탭마다 원형 개수 배지가 보이는가
19. 직무 상세와 수정에서 요구·우대 역량이 성장 방향·취득 경험보다 먼저 나오는가
20. 기업·공고·직무 메모에서 `#`~`######`, `-`, `1.` 목록, 들여쓰기가 표시되고 bare http(s) URL과 `[이름](URL)`이 새 탭 링크가 되는가
21. 세 메모 입력칸에서 Tab은 두 칸 들여쓰기, Shift+Tab은 들여쓰기 제거로 동작하는가
22. 공고에 직무가 여러 개면 한 직무만 삭제되고, 마지막 직무를 삭제하면 소속 공고와 직무가 함께 사라지는가
23. 직무 탭이 소속 공고의 상태와 보관 여부에 따라 관심 직무/진행 중/보관함으로 분류되는가

## 함정

- MCP는 기존 컨트롤러의 DTO 검증·서비스를 사용한다. `position_create`만 공고 소유권 확인 후 직무를 추가하는
  `PositionService.create`를 별도로 사용한다. 다른 직무·공고 배열은 변경하지 않으며 최대 30개를 검사한다.

- **마감 시각은 로컬 입력 → UTC 저장.** `datetime-local` 값에는 시간대가 없다. `toUtcIso()` 로 변환해 보낸다.
- 새 공고와 새 절차 단계의 시각 기본값은 폼을 여는 시점의 현재 분이다. 기존 행의 빈 시각을 임의로 채우지는 않는다.
- **D-day 는 서버가 내려주지 않는다.** 프런트가 사용자 시계로 계산한다.
- **기업 목록 응답의 `openPostings` 는 비어 있다**(N+1 회피). 상세에서만 채워진다.
- **직무·공고·기업을 지우면 그 직무로 쓴 자소서의 직접 출처 연결이 `ON DELETE CASCADE` 로 함께 사라진다**(답변은 남고 "출처 미상"이 된다).
  스키마는 그대로 두고, 상세 응답(GET·`position_get`·`posting_get`·`company_get`)의 응답 전용 `linkedSelfIntroCount` 로 지우기 전에 알린다.
  직무는 그 직무, 공고는 소속 직무 전부, 기업은 소속 공고(마감 지난 것 포함)의 직무 전부를 세고, 한 문항은 한 번만 센다.
  소유자 조건을 넣은 count 한 문장이다(`PositionRepository.countSelfIntrosWrittenFor*`). 목록·수정 응답은 null 이다.
  웹 삭제 확인창(`PositionBoard`·`PostingBoard`·`CompanyWorkspace`)은 들고 있는 대상에 수가 없으면(목록에서 고름) 상세를 한 번 불러
  0 보다 크면 "…로 쓴 자소서 N개의 직접 출처 연결이 함께 사라집니다(답변은 남습니다)" 를 붙인다(`utils/writtenFor.ts`).
  수를 받는 동안은 삭제 버튼만 막고(`ConfirmDialog` 의 `pending`), 받지 못하면 0 으로 치지 않고 "자소서 수를 확인하지 못했습니다" 경고를 붙인다.
  공고 수정 폼에서 직무를 빼는 경우는 알리지 않는다 — docs/self-introductions.md 함정.
- **공고 PUT 의 직무 목록은 "그 목록으로 맞춤"이다.** `id` 가 있는 항목은 그 직무, 없는 항목은 같은 이름의 남은 기존 직무를 쓰고 이름만 갱신한다. 둘 다 없으면 생성, 어느 항목과도 맞지 않은 기존 직무는 삭제된다. id 를 먼저 다 고른 뒤 이름으로 맞추므로 이름 맞춤이 id 로 지목된 직무를 가져가지 않는다. 이름이 같은 기존 직무가 둘이면 그중 하나만 맞춰지고 나머지는 삭제 대상이다. 같은 id 가 두 번 오면 두 번째는 새 직무가 되고, 이 공고의 직무가 아닌 id 는 없는 것으로 보고 이름으로 맞춘다. 삭제되는 직무가 지원 직무(`targetPositionId`)면 비운다 — 직무 상세(팀·역량 등)는 직무 페이지에서 편집하므로 공고 폼은 `id`+`name` 만 다룬다.
- **웹 공고 폼은 직무·절차를 항상 배열로 보낸다**(행을 다 지우면 `[]`). 필드를 빼면 서버가 "유지"로 읽어 지운 행이 조용히 남는다. `PostingForm.spec.ts` 가 이것을 지킨다. 전에는 폼이 지웠다 같은 이름으로 다시 추가한 직무가 새 행이 됐지만, 이제는 기존 행을 다시 쓴다.
- 공고 PUT의 절차 목록은 배열이면 값 기준 통째 교체(null 이면 유지)지만, 영속화할 때는 같은 순번의 엔티티를 갱신하고 끝에서만 추가·삭제한다. 전부 지운 뒤 다시 넣으면 Hibernate flush 순서에 따라 `(posting_id, seq)` 고유키가 충돌한다.
- 절차 `seq` 는 재정렬하면 바뀐다. step 을 밖에서 id 로 참조하지 않는다.
- **이미 SUBMITTED 인 공고에 SUBMITTED 를 다시 보내면 새 기록을 만들지 않고 최신 LINKED 를 돌려준다.** 제출 뒤 고친 이력서가 "기록 당시"로 섞이지 않게 하려는 것이다. 이 기능 전부터 SUBMITTED 였던 공고는 LINKED 가 없으므로 그때 한 번 남는다. SUBMITTED → 다른 상태 → SUBMITTED 는 전이라 다시 찍지만, 내용이 같으면 `content_hash` 로 같은 기록이다.
- **알려진 한계: 확인 제출본을 A → B → A 로 다시 고르면 최신 확인이 B 로 남는다.** `content_hash` 맞춤이 공고·수준의 *모든* 기록과 비교하므로(UNIQUE `(posting_id, content_hash, level)`, 설계 2.5·6.6) 세 번째 확인은 A 의 옛 기록을 돌려주고, `alreadyUsedFor` 는 `recorded_at` 최신인 B 를 쓴다. 고치려면 유일 제약을 바꾸는 마이그레이션(최신 기록과만 맞춤)이 필요해 이번에 손대지 않았다(OCR 리뷰 2026-11-03).
- **`submissions.recorded_at` 은 `clock_timestamp()` 다(`now()` 금지).** `now()` 는 트랜잭션 시작 시각이라 `OwnerLock` 을 기다린 트랜잭션의 기록이 먼저 커밋된 기록보다 이르게 찍혀, 최신 LINKED·최신 확인 제출본(alreadyUsedFor)·목록 최신순이 직렬 순서와 뒤집힌다. 잠금 아래에서 쓴 시각이면 순서가 맞다. `SubmissionIntegrationTest.recordedAtIsWriteTimeNotTransactionStart` 가 지킨다.
- `SubmissionRecords.vue` 는 공고 서랍에서 다른 공고로 바뀌어도 같은 인스턴스다. 목록 요청은 번호로 늦게 온 이전 응답을 버리고, `postingId` 가 바뀌면 "실제로 낸 것 확인" 편집을 닫는다(이전 공고용 선택이 새 공고의 확인 기록으로 저장되지 않게).
- 공고를 지우면 제출 기록도 CASCADE 로 사라진다(`submissions` → `job_postings (id, owner_id)`). 기록은 공고에 딸린 자료다.
- **`?focus=` 는 먼저 이미 불러온 목록에서 찾는다.** 기업·직무는 전체 목록을 받으므로 항상 찾힌다. 공고만 진행 중/보관함으로 나뉘어 있어, 목록에 없으면 `getPosting(id)` 로 한 번 더 읽는다 — 보관된 공고로도 건너뛸 수 있다. 지워졌거나 남의 것이면 조용히 넘어간다.
- `reference_items.related_position_id` 는 직무를 지우면 NULL 이 된다(ON DELETE SET NULL). 카드는 제목으로 남는다.
- **‘떼기’ 와 ‘삭제’ 는 다르다.** 떼기는 `PUT /positions/{id}/references` 로 이 직무의 연결만 끊고, 삭제는 `DELETE /references/{id}` 로 참고 정보 자체를 지워 **붙어 있던 모든 직무에서 사라진다**(`position_references` ON DELETE CASCADE). 삭제만 확인 대화상자를 받는다.
- 참고 정보를 고치면 그 정보가 붙은 다른 직무의 표시도 바뀐다. `PositionBoard` 가 수정·삭제 뒤 목록을 다시 읽는 이유다.
- 업종·기술 스택 선택지는 별도 마스터 테이블이 아니라 현재 계정의 기존 기업·직무 값에서 모은다. 태그 이름 수정은 선택 태그를 입력칸으로 되돌린 뒤 새 이름으로 확정하는 현재 항목 단위 편집이다.
- 매출액은 DB에 원 단위 `annual_revenue`와 입력·표시 단위 `revenue_unit`을 함께 저장한다. 계산 기준은 만 원=`10,000원`, 억 원=`100,000,000원`이다.
- **드롭 규칙**: 관심 공고 탭=`INTERESTED`, 진행 중 탭=`SUBMITTED`. 보관함 탭은 제출 완료→서류 탈락, 필기 준비→필기 탈락, 면접 준비→면접 탈락이고 관심·작성 중은 상태를 유지해 미지원에 둔다. 합격처럼 매핑이 없는 상태도 상태를 유지한 채 `기타 보관`에 둔다. 열에 직접 놓으면 그 열 상태가 우선한다.
- 탭 개수는 진행 목록과 보관 목록을 함께 읽어 계산한다. 관심 공고는 `INTERESTED·DRAFTING`, 진행 중은 나머지 미보관 상태, 보관함은 보관 목록 전체 개수다.
- 직무 탭은 별도 상태를 저장하지 않는다. 소속 공고가 보관됐으면 보관함, 아니면 `INTERESTED·DRAFTING`은 관심 직무, 그 밖의 상태는 진행 중이다.
- 마지막 직무 삭제는 공고 행을 소유자 범위에서 잠근 뒤 개수를 확인한다. 마지막이면 공고 삭제 cascade를 사용해 동시 삭제에서도 직무 없는 공고를 남기지 않는다.
- 같은 공고 삭제가 경합해 3초 안에 잠금을 얻지 못하면 `409 POSITION_DELETE_BUSY`로 재시도를 요청한다. 잠금을 얻은 뒤에도 대상 직무를 다시 조회해 중복 DELETE가 남은 직무를 지우지 못하게 한다.
- Markdown은 사용자 HTML을 해석하지 않는다. 지원 문법만 Vue 노드로 만들고 링크도 `http://`·`https://`만 허용한다. 제목·목록·링크 외 문법은 평문으로 남는다.
