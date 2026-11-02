# 이력서

## 목적

이력서를 **여러 버전**으로 두고(이름 + id 로 구분), 버전마다 섹션 행을 자유롭게 추가·삭제·순서 변경한다.
섹션 구성은 사용자가 쓰던 이력서 양식을 그대로 옮겼다 — 기본정보 · 학력 · 교육이수 · 대내외활동 · 경력 · 수상 · 자격증 · SW 역량 · 프로젝트.
각 이력서는 여러 모집 직무를 연결할 수 있고 자기소개 질문은 별도 데이터로 관리한다 — [self-introductions.md](self-introductions.md).

## 도메인

```
app_users ──1:N──▶ resumes
                     id · owner_id · name(이력서 명) · content JSONB · version · created_at · updated_at
                     └─N:M─ positions via resume_positions

content = {
  basic:        { name, phone, birthDate, email, address, portfolioUrl, portfolioFileId, githubUrl }
  educations:   [ { startYm, endYm, school, schoolType, major, gpa, totalCredits,
                    admissionExam, overallRank, diplomaId, transcriptId,
                    collegeTerms: [ { grade, courses, credits, gpa } ],   // 대학교
                    schoolTerms:  [ { grade, term, subject, units, achievement, rank, students } ] } ]  // 고등학교
  trainings:    [ { name, institution, startYm, endYm, description, fileId } ]
  activities:   [ { name, organizer, startYm, endYm, description, fileId } ]
  experiences:  [ { company, startYm, endYm, description } ]
  awards:       [ { name, issuer, awardedYm, fileId } ]
  certificates: [ { name, issuer, acquiredYm, licenseNo, fileId } ]
  skills:       [ { name, level, description } ]
  projects:     [ { name, headcount, startYm, endYm, summary, techStack, role, outcome, description, url } ]
}
```

**섹션마다 테이블을 만들지 않았다.** 이력서는 통째로 읽고 통째로 저장하는 문서이고, 자격증만 SQL 로 뽑을 일이 없다. 9개 테이블은 엔티티·DTO·리포지토리 40개를 뜻한다. 대신 `ResumeContent` Java record 가 모양과 검증(길이 제한)을 들고, 프런트 `types/resume.ts` 의 `SECTIONS` 가 같은 키로 화면을 그린다 — `docs/decisions.md`.

`diplomaId` · `transcriptId` · `fileId` 는 **첨부의 id 문자열**이다. 바이트는 `attachments` 테이블에 있다 — `docs/attachments.md`.
학력의 `schoolType`(`대학교`/`고등학교`/`기타`)이 화면에서 어떤 학업 이수 표를 보여 줄지 정한다.
두 표를 한 record 에 함께 둔 이유는 섹션을 쪼개면 학력 순서·정렬이 두 벌이 되기 때문이다.

연월(`startYm` 등)은 **문자열**이다. 사용자는 `2024.03`, `202403`, `현재 교육 중` 처럼 쓴다. 날짜로 파싱하면 그 표현을 잃는다.

## 불변 조건

1. **소유자 격리**: `findByIdAndOwnerId` 만. 타인 이력서는 404.
2. **이름은 유일하지 않다.** 버전은 id 로 구분한다. 같은 이름의 v1·v2 가 있어도 된다 — 사용자 요구.
3. **REST 저장은 문서 통째 PUT.** REST 에 행 단위 API 는 없다. 화면은 행 추가·삭제·이동을 로컬에서 하고 `저장` 을 눌러야 DB 에 간다(저장 안 한 변경은 `변경됨` 표시 + 이탈 경고). **MCP 만** 행 도구를 갖는다 — 서버가 조회→수정→저장을 한 트랜잭션으로 묶는 편의 계층이지, 두 번째 저장 경로가 아니다.
4. **저장 모양은 항상 같다.** 생성 때 `content` 가 없으면 `ResumeContent.empty()` 로 만들고, 저장하는 JSON 의 섹션은 언제나 배열이다. 읽는 쪽이 `null` 검사를 안 하게. 단 **수정 요청의 `null` 은 "유지"** 다(9번).
5. **복제는 새 id.** `POST /resumes/{id}/copy` 는 content 를 그대로 든 새 행을 만든다. 원본은 건드리지 않는다.
6. **연결 직무는 관계로 저장한다.** `positionIds`는 같은 소유자의 직무만 허용하고 이력서 복제에도 복사한다. 편집 화면은 검색창과 선택 칩만 보여 주고, 검색창 클릭·포커스 시 미선택 직무를 드롭다운으로 펼친다. 검색어가 없으면 최근 수정순 전체 목록, 검색 시 이름·기업·공고를 좁힌다. 목록은 최대 320px(작은 화면은 화면 높이의 45%) 안에서 휠·스크롤바로 스크롤하며 페이지 본문을 밀어내지 않는다.
7. **내보내기는 저장된 문서 기준이다.** Word는 브라우저가 `.doc`을 만들고 PDF는 인쇄 대화상자의 PDF 저장을 사용한다. 선택한 첨부는 원본 바이트를 별도 ZIP으로 묶는다.
   첨부 체크 목록에는 저장된 문서에 있지만 응답 `attachments` 에 메타가 없는 id(지워졌거나 남의 것)를 싣지 않는다 — 고르면 ZIP 만들기가 실패한다.
8. **예전에 읽은 문서로 덮어쓰지 않는다** (`version`). 이력서는 저장할 때마다 `version` 이 오른다. 문서 전체 저장(`PUT`, `resume_update`)과 행 번호로 고치는 `resume_row_update`·`resume_row_delete` 는 `expectedVersion` 이 **필수**이고, 행 추가·기본정보 수정은 선택이다. 없으면 400 `VERSION_REQUIRED`, 지금 버전과 다르면 409 `RESUME_STALE`. 오류에는 지금 버전을 싣지 않는다 — 클라이언트가 다시 읽고 대상을 확인하게 하려는 것이다. 잠금만으로는 부족하다: AI가 `projects[0]` 을 읽은 뒤 웹에서 0번을 지우면, 잠금이 정상이어도 AI의 0번 수정이 엉뚱한 행에 들어간다.
   `RESUME_STALE` 과 `STALE_WRITE` 는 감지 지점이 달라 둘 다 둔다. `RESUME_STALE` 은 서비스가 저장 전에 요청의 `expectedVersion` 을 잠근 행과 비교해 낸다. `STALE_WRITE` 는 저장 flush 때 JPA `@Version` 이 어긋나면 공통 변환(`common/ApiErrors`)이 낸다 — 잠금 덕에 보통은 오지 않는다. 둘 다 409 이고 다시 읽은 뒤 고친다.
9. **수정 요청의 `null` 은 유지, `[]` 는 비움이다.** `PUT`·`resume_update` 의 `content`·`positionIds`·`content` 안의 빠진 섹션과 `basic`, `resume_row_update` 의 빠진 행 필드, `resume_basic_update` 의 빠진 기본정보 필드가 모두 그렇다. 행 갱신과 기본정보 수정은 기존 값 위에 보낸 필드만 덮는다(`""` 는 그 필드를 비운다). AI가 섹션이나 필드를 빠뜨려 보낸 저장이 조용한 삭제가 되던 것을 막는다. 기본정보는 `ResumeServiceTest.updateBasicKeepsFieldsTheRequestLeavesOut`·`updateBasicEmptyStringClearsOnlyThatField` 가 본다.
10. **쓰기는 이력서 행을 잠근다.** `update`·`updateBasic`·행 도구는 `ResumeRepository.findOwnedForUpdate`(`PESSIMISTIC_WRITE`, `lock_timeout 3s`)로 읽는다. 동시에 들어온 행 추가 두 건이 서로를 덮어 한 행이 사라지던 것을 막는다. 3초 안에 잠금을 못 얻으면 409 `RESUME_BUSY`. 잠금 조회 밖(저장 flush·커밋·FK 검사)에서 잠금 대기 초과(55P03)나 교착(40P01)이 나면 공통 변환이 409 `BUSY` 로 바꾼다 — docs/backend.md.
11. **경험 행의 앵커를 버리지 않는다.** 프로젝트·경력·대내외활동·교육이수 행은 `experienceId`·`experienceRevision`(이 행이 쓴 경험과 그 개정, docs/experiences.md)을 든다. 화면에 칸이 없지만 `fillRow` 가 그대로 통과시켜 저장 payload 로 돌아간다. 행 도구 요청(`ResumeRowRequest`)은 `experienceId` 만 받는다 — 개정은 서버가 정한다. 키 하나라도 빠지면 Jackson 이 조용히 버려 연결이 끊기므로, `ResumeServiceTest.experienceAnchorsSurviveSaveAndRowEdit` 와 `resume.spec.ts` "경험 앵커 통과" 가 왕복을 고정한다.
12. **이력서 content 는 `ResumeWriter` 한 곳으로만 쓴다**(4단계). 모든 쓰기는 계정 잠금(`OwnerLock`) → 행 잠금 → version 확인 뒤 관문에서 문서 전체 검증(섹션 상한 포함,
    400 `INVALID_CONTENT`) → 링커(앵커 이어받기·개정 검사·자동 연결) → 저장과 `resume_experiences` 반영을 한다. 웹 PUT 의 `refreshExperienceIds` 는 그 경험 행을
    지금 개정으로 올리고("원본으로 갱신"), 저장으로 끊긴 앵커는 응답 `unlinkedWarnings` 로 알린다. MCP `resume_update` 는 연결 행이 빠지면 409 `LINKED_ROWS_MISSING`
    (`removedExperienceIds` 로 허용), 행 도구는 `experienceId` 로도 행을 지목한다 — docs/experiences.md.
    관문은 저장 flush 뒤 **첨부 규칙 B** 도 건다: 저장 전 문서에 없던 첨부 id 를 `for key share` 로 잡고, 내 파일이 아니거나 정규형 UUID 가 아니면 400 `ATTACHMENT_NOT_FOUND`.
    이미 저장된 값은 통과한다. 복제는 원본을 잠그고 읽어 규칙 B 없이 안전하다 — docs/attachments.md 불변 조건 7·8.
13. **저장·삭제가 성공한 뒤 빠진 첨부를 묻는다**(설계 6.9). `ResumeBoard` 가 저장 전후 응답의 `attachments` 맵 키를 비교해 빠진 파일을 매번 묻고(답을 기억하지 않는다), 이력서를 지웠으면 그 이력서의 첨부 전부를 묻는다.
    다른 곳(복제본·경험·제출 기록)에서 쓰면 서버 409 를 받아 "남겨 두었습니다"로 알린다. 저장이 실패하면 묻지 않는다. 목록 화면의 [첨부 파일] 서랍은 docs/attachments.md.

## 진입점

| 무엇 | 어디 |
|---|---|
| 스키마 | `V14__resumes.sql` |
| 도메인 | `resume/Resume`(엔티티, `content` 는 `@JdbcTypeCode(SqlTypes.JSON)` String, `positionIds`는 관계) · `ResumeContent`(record + 검증) · `ResumeService` · `ResumeController` — `ApiPaths.RESUMES` |
| JSON 변환 | `ResumeService.write()/read()` — Boot 4 의 Jackson 3 `tools.jackson.databind.ObjectMapper` |
| 화면 | `ResumeBoard.vue`(`/resumes` 목록 카드, `?focus=<id>` 면 편집기) · `ResumeEditor.vue`(전체 페이지 편집기) · `ResumeSection.vue`(섹션 하나 = 행 카드 + ↑↓× + 행 추가) |
| 섹션 정의 | `frontend/src/types/resume.ts` 의 `SECTIONS` — 라벨·필드·입력 종류. 섹션을 더하려면 **여기와 `ResumeContent`, `ResumeSection` enum 세 곳** |
| MCP 도구 | `McpTools` 의 `resume_*` 10개. 행 도구는 `McpActions` → `ResumeService.addRow/updateRow/deleteRow/updateBasic`. 입력 `ResumeRowRequest`(합집합 record) + `ResumeSection`(enum). 행 도구는 이력서 전체가 아니라 개요 `ResumeRowResult{id, version, section, count, index, row}` 를 돌려준다 — `docs/mcp-plugin.md`. `resume_get`·`resume_create` 는 `McpActions` 래퍼라 응답 맨 앞에 `missing`(다른 이력서에만 있는 경험 묶음)이 붙고, `resume_get(view=outline)` 은 개요다 — `docs/experience-graph-search.md` |

## API 요약 (상세는 Swagger)

```
GET    /api/resumes            → [ {id, name, createdAt, updatedAt, positionIds} ]   목록엔 content 없음. positionIds 는 자소서 "어느 지원용?" 후보용
POST   /api/resumes            {name, content?, positionIds?}    content 없으면 빈 문서
GET    /api/resumes/{id}       → {id, name, content, positionIds, version, attachments, createdAt, updatedAt}
                                 attachments = {첨부 id → {id, filename, contentType, size}}  응답 전용, 내 첨부만
PUT    /api/resumes/{id}       {name, content?, positionIds?, expectedVersion}   null=유지. 버전이 다르면 409 RESUME_STALE
POST   /api/resumes/{id}/copy  {name}                            새 버전
DELETE /api/resumes/{id}

MCP (REST 에는 없음)
resume_list · resume_get · resume_create · resume_copy · resume_delete
resume_update       {id, request:{name, content?, positionIds?, expectedVersion}}   PUT 과 같음
resume_basic_update {id, request:BasicInfo, expectedVersion?}          보낸 필드만 덮음(null=유지, ""=비움)
resume_row_add      {id, section, request, expectedVersion?}           section ∈ educations…projects, request 는 그 섹션 필드만
resume_row_update   {id, section, index, request, expectedVersion}     index 0부터, 보낸 필드만 덮음(null=유지, ""=비움)
resume_row_delete   {id, section, index, expectedVersion}
```

## 실행·검증

### 지원 직무 검색 드롭다운

- 검색창 아래에 겹쳐 열리는 단일 열 목록이다. 항목 개수를 잘라 버리지 않고 전체 검색 결과를 내부 스크롤로 탐색한다.
- 직무를 선택하면 검색어를 비우고 선택 칩·개수를 갱신한다. 목록은 열린 상태로 유지해 여러 직무를 연속 연결한다.
- 바깥 클릭·포커스 이동·Tab·Esc로 닫는다. Esc 뒤 검색창을 다시 클릭하거나 입력하면 다시 열린다.
- 키보드는 ↑/↓로 항목 이동, Enter로 연결한다. 한글 조합 중 Enter는 연결 동작으로 처리하지 않는다.
- 직무 없음·검색 결과 없음·이미 모두 선택함을 구분한다. 연결·해제는 초안만 바꾸고 기존 `저장`에서 함께 저장한다.

```bash
docker compose up -d --build && docker compose logs backend | grep "now at version"   # v14
bash scripts/smoke.sh                       # /api/resumes 미인증 401 포함
cd frontend && npm run type-check && npm test
```

로그인 뒤 브라우저에서:
1. 이력서 탭 → `이력서 추가` → 편집기가 열리고 이름이 `새 이력서` 인가
2. 학력 `행 추가` 3개 → ↑↓ 로 순서 바꾸고 × 로 하나 지운 뒤 `저장` → 새로고침해도 그대로인가
3. 이름을 바꾸고 저장 안 한 채 `← 목록` → 이탈 확인이 뜨는가
4. `복제` → 같은 내용의 새 카드가 생기고 원본은 그대로인가
5. 목록 카드에 이름·최근 수정만 보이는가
6. 편집기에서 여러 모집 직무를 선택해 저장하고 다시 열어도 연결이 유지되는가
7. 이력서 목록 카드를 더블클릭하면 해당 이력서 편집기가 열리는가
8. 직무 목록은 처음에 접혀 있고, 검색창 클릭·포커스 시 최근 수정순 드롭다운이 뜨며 목록 안에서 휠로 끝까지 내릴 수 있는가
9. 기본정보 포트폴리오·대내외활동·수상내역에 파일을 연결할 수 있는가
10. Word 다운로드와 PDF 인쇄가 열리고 선택한 첨부만 ZIP으로 내려오는가
11. 검색·연속 선택·칩 제거·바깥 클릭·Tab·Esc 닫기와 키보드 ↑/↓·Enter 연결이 동작하는가

### 직무 드롭다운 검증 결과 (2026-10-30)

- 컨테이너에서 프런트 타입 검사·전체 테스트 86개·빌드 종료코드 0. 초기 접힘·검색·연속 선택·해제·저장 payload·키보드·한글 조합·빈 결과·이력서 전환을 검증했다.
- 로컬 Chrome에서 가짜 API 데이터 40개로 검증했다. 실제 휠 스크롤과 마지막 항목 선택, 목록 끝에서 페이지로 스크롤이 이어지지 않음, 드롭다운 개폐 시 본문 위치 유지, 390px 모바일 배치를 확인했다.
- 공개 서비스 스모크 59개 PASS(종료코드 0). 이 시점의 로컬 브라우저 검증은 운영 계정·데이터를 사용하지 않았다. 이후 공개 서버 배포·검증 결과는 [배포 기록](nginx-deploy.md#2026-10-31-드롭다운-배포-검증)을 따른다.

## 함정

- **`content` 컬럼은 `jsonb`, 엔티티 필드는 `String`.** Hibernate 7 의 JSON 매핑이 Jackson 3 를 직접 다루는지 확실하지 않아 문자열로 받고 서비스가 변환한다. `ddl-auto: validate` 는 `@JdbcTypeCode(SqlTypes.JSON)` 으로 통과한다 — 통합 테스트가 기동 시 확인한다.
- **섹션 필드 키는 두 곳이 같아야 한다** — `ResumeContent` 의 record 컴포넌트 이름과 `types/resume.ts` `SECTIONS[].fields[].key`. Jackson 3 는 모르는 키를 조용히 버리므로 오타가 나면 저장은 되는데 값이 사라진다. `resume.spec.ts` 가 `emptyContent()` 의 키를 `SECTIONS` 와 맞춰 본다.
- 편집기는 **드로어가 아니라 전체 페이지**다(design-system 규칙 7). 520px 에 9개 표를 넣을 수 없다.
- 행 카드의 `:key` 는 index 다. 값은 `:value` + `@input` 로 묶여 있어 ↑↓ 이동 뒤에도 입력값이 따라간다. `v-model` 로 바꾸면 index 키와 충돌해 값이 남는다.
- 목록 응답에는 `content` 가 없다(요약). 카드는 이름·수정일만 그린다.
- **MCP 행 도구는 다른 섹션 필드를 거부한다**(`UNKNOWN_ROW_FIELD`). 합집합 record 로 받기 때문에 스키마상으로는 `school` 을 `certificates` 에 넣을 수 있어 보이지만, 서버가 섹션 record 로 엄격 변환한다. 조용히 버리면 값이 사라진 채 "성공" 이 되기 때문이다.
- 섹션을 추가하면 `ResumeContent`(record) · `ResumeSection`(enum) · `types/resume.ts`(SECTIONS) · `ResumeRowRequest`(새 필드가 있으면) 네 곳을 함께 고친다.
- **행 값이 전부 문자열은 아니다.** `kind: 'terms'` 필드만 배열이다. 행에서 값을 꺼낼 때는 `text(row, key)` / `terms(row, key)` 를 쓴다 — 캐스팅을 `types/resume.ts` 한 곳에 모아 둔 이유다.
- **MCP 행 도구는 중첩 표를 실을 수 없다.** `ResumeRowRequest` 는 평평한 문자열 합집합이다. `resume_row_update` 는 기존 행 위에 보낸 필드만 덮으므로(불변 조건 9), 요청이 표현하지 못한 배열 필드(성적표)는 그대로 남는다. 이 병합이 없으면 학력 행 하나를 고칠 때마다 성적표가 조용히 사라진다.
- `when` 이 붙은 필드는 같은 행의 다른 값에 따라 **숨는다**. 숨어도 값은 문서에 남는다 — 학교 구분을 바꿨다가 되돌리면 그대로 돌아온다.
- **저장 중인 이력서를 다시 읽을 때는 저장 완료를 기다린다.** `api/resumes.ts` 가 같은 id의 쓰기 순서와 저장 뒤 조회 순서를 보장한다. 다른 id의 조회는 기다리지 않는다. 저장이 실패해도 대기는 풀리고 서버에 남은 문서를 다시 읽을 수 있다. 이 순서는 화면 컴포넌트를 벗어났다가 다시 만들어도 유지된다. 편집기는 조회가 끝날 때까지 숨겨 오래된 문서를 다시 저장하지 못하게 한다. `ResumeSaveRace.spec.ts` 가 A 저장 중 B로 이동 후 A 복귀, 재저장 때 첫 수정 보존, 컴포넌트 재생성, 저장 실패와 같은 id의 연속 저장을 검증한다.
- **편집기를 열 때 연결 문항은 이력서와 같은 단계에서 부른다.** `ResumeBoard.loadCurrent` 가 이력서·직무·연결 문항을 `Promise.all` 로 함께 보내고 `ResumeEditor` → `ResumeQuestions` 로 `questions` 를 내린다. 문항 칸이 그려진 뒤에 부르면 왕복 하나를 더 기다리는데, 공개 도메인은 한국 요청이 Cloudflare LAX 엣지를 거쳐 서울 터널로 가서 왕복 하나가 약 0.3초다(2026-11 측정, 서버 안 처리는 2ms 미만). 문항 요청이 실패하면 `null` 을 넘기고 문항 칸이 직접 다시 불러 오류를 보여 준다 — 문항 실패가 이력서 열기를 막지 않게. 추가·연결·수정 뒤 다시 부르는 것은 계속 `ResumeQuestions` 가 한다. `ResumeBoard.spec.ts` 가 "문항 요청이 이력서 응답을 기다리지 않는다" 를 고정한다.
- **첨부 메타는 이력서 응답에 함께 온다.** 이력서를 돌려주는 모든 응답(상세·생성·저장·복제·`resume_get`)에 `attachments`(첨부 id → 파일명·형식·크기)가 실린다. `ResumeEditor` → `ResumeSection` → `AttachmentField` 로 `preloaded` 를 내리고, 받은 메타가 있는 칸은 `GET /api/attachments/{id}/meta` 를 부르지 않는다. 전에는 편집기가 그려진 뒤 첨부 칸마다 메타를 불러 첨부가 있는 이력서에 직렬 단계가 하나 남아 있었다(설계 1.3 P5). 남의 것·지워진 id·id 가 아닌 값은 응답에서 빠지고, 그 칸만 전처럼 직접 불러 `파일을 찾을 수 없습니다` 를 보인다. 첨부 칸은 `ResumeContent.attachmentIds()` 가 모으며 `SECTIONS` 의 `kind: 'file'` 필드와 같아야 한다 — 첨부 칸을 더하면 둘 다 고친다. 단위 테스트(`ResumeServiceTest`)가 7칸을, 통합 테스트(`ResumeAttachmentsIntegrationTest`)가 남의 첨부가 빠지는 것을, `AttachmentField.spec.ts` 가 편집기에서 메타 요청이 나가지 않는 것을 본다.
- **늦게 온 응답은 버린다.** A 를 연 뒤 B 를 열면 A 응답이 B 보다 늦게 올 수 있다. `ResumeBoard.loadCurrent` 와 `ResumeQuestions.load` 는 요청 순번(`loadSeq`)을 두고 마지막 요청의 결과만 반영한다. 저장도 같다: `ResumeBoard.save` 는 시작할 때의 순번을 기억하고, 저장 중에 다른 이력서로 옮겼으면 응답을 편집기에 넣지 않는다. 문항 칸은 이력서 id 가 바뀌면 목록·수정 상태·오류를 바로 비우고, 부모가 문항을 못 받았으면(`null`) 직접 다시 부른다. 순서를 뒤집는 테스트가 `ResumeBoard.spec.ts`·`ResumeQuestions.spec.ts` 에 있다.
