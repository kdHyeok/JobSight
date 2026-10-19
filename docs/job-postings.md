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
2. **공고는 직무가 1개 이상**: 직무 없이 저장하면 `title` 과 같은 이름의 직무를 하나 만든다. 마지막 직무를 삭제하면 소속 공고도 함께 삭제한다.
3. **기업은 직접 입력하면 자동 생성**: `companyId` 없이 `companyName` 만 오면 공백 제거·소문자 기준으로 기존 기업을 찾고, 없으면 이름만 채운 기업을 만든다. `company_name_snapshot` 은 없다.
4. **D-day 정렬**: 마감 가까운 순, 상시(NULL)는 맨 뒤. 서버가 정렬한다.
5. **자동 보관**: 마감 지남 + `status ∈ {INTERESTED, DRAFTING}` 인 공고는 미지원으로, 세 탈락 상태와 기존 `CLOSED`는 즉시 보관한다. 탈락이 아닌 상태로 옮기면 보관함에서 자동으로 꺼낸다.
6. **참고 정보는 계정 안에서 공유**(결정 1): 한 참고 정보가 여러 직무에 붙는다. 다른 사용자와는 공유하지 않는다.
7. **절차는 공고 단위**(결정 2). `seq` 는 `@OrderColumn` 이 관리하는 0-based 정수. 분기 없음.
8. **메모는 원문 저장**: 기업·공고·직무 memo는 5,000자 이하 Markdown 문자열이다. 서버는 HTML로 변환하지 않는다.

## 진입점

| 무엇 | 어디 |
|---|---|
| 스키마 | `V9__…job_postings.sql` → `V10__positions_steps_references.sql` → `V12__application_status_and_revenue_unit.sql` → **`V13__company_benefits_and_step_results.sql`** |
| 공고 | `posting/JobPosting` `JobPostingService` `JobPostingController` — `ApiPaths.POSTINGS` |
| 절차 | `posting/RecruitmentStep` (공고에 임베드, cascade) · `PATCH /postings/{id}/steps/{seq}` |
| 직무 | `position/Position` `PositionService` `PositionController` — `ApiPaths.POSITIONS` |
| 참고 정보 | `reference/ReferenceItem` `ReferenceService` `ReferenceController` — `ApiPaths.REFERENCES` · 연결은 `PUT /positions/{id}/references` |
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
GET/PUT/DELETE  /api/postings/{id}         PUT 은 positions[]·steps[] 를 통째로 받는다
PATCH           /api/postings/{id}/status  {status}
PATCH           /api/postings/{id}/steps/{seq}  {result}
PATCH           /api/postings/{id}/archive ?archived=
GET             /api/positions             ?q=  (이름·팀·회사·스택 부분 일치)
GET/PUT/DELETE  /api/positions/{id}         마지막 직무 DELETE 는 소속 공고까지 삭제
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
- **공고 PUT 은 직무 목록을 통째로 받는다.** `id` 가 있는 항목은 갱신, 없는 항목은 생성, 요청에 빠진 기존 직무는 삭제된다 — 직무 상세(팀·역량 등)는 직무 페이지에서 편집하므로 공고 폼은 `id`+`name` 만 다룬다.
- 공고 PUT의 절차 목록은 값 기준 통째 교체지만, 영속화할 때는 같은 순번의 엔티티를 갱신하고 끝에서만 추가·삭제한다. 전부 지운 뒤 다시 넣으면 Hibernate flush 순서에 따라 `(posting_id, seq)` 고유키가 충돌한다.
- 절차 `seq` 는 재정렬하면 바뀐다. step 을 밖에서 id 로 참조하지 않는다.
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
