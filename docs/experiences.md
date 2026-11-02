# 경험 원본 (경험 은행, 4단계 최소판)

## 목적

경험의 **사실을 한 곳(경험 원본)에 두고**, 이력서 행은 그 경험의 기업별 표현으로 둔다. 어느 PC 의 AI 클라이언트든 같은 경험 id·사실·근거를 읽는다.
이력서 행은 쓴 경험과 그 **개정 번호**를 앵커로 든다. 원본이 바뀌어도 과거 문구는 그대로이고, 사실이 정정되면 "재검토 필요"로 알린다.
설계 정본: [experience-graph.design.md](02-design/features/experience-graph.design.md) 2.5·6.1~6.5·6.8, 7절 4a. 사건(STAR)·병합 계보·키워드 테이블·`ROW` 복사는 4c(보류, 설계 6.10)라 없다(병합 UI 도 없다). 웹 화면은 4b(아래 "웹 화면").

## 진입점

| 무엇 | 어디 |
|---|---|
| 스키마 | `V25__experiences.sql` — `experiences`(facts·dossier JSONB, `skills`/`domains TEXT[]`, `revision`·`reviewed_revision`·`dossier_based_on`, `@Version`), `experience_revisions`(`seq` IDENTITY, `facts_hash`), `resume_experiences`, `self_introduction_experiences` |
| 쓰기 관문 | `resume/ResumeWriter`(이력서 content 를 쓰는 **유일한** 곳) → `ResumeExperienceLinker`(앵커 이어받기·개정 검사·자동 연결) → 표 차이 반영 → `SET CONSTRAINTS ALL IMMEDIATE` |
| 계정 잠금 | `common/OwnerLock.acquire(owner)` — `SET LOCAL lock_timeout '3s'` 바로 다음 문장에서 `pg_advisory_xact_lock(hashtextextended('exp-graph:'||owner,0))` |
| 경험 쓰기 | `experience/ExperienceBankService`(저장·개정 판정·검토 확정·삭제), `ExperienceImportService`(가져오기 계획·적용), `ExperienceRevisions`(개정 이력 JDBC) |
| 읽기 | `ExperienceService`(목차·상세·outline·missing), `ExperienceSearch`(자료원 = 경험 원본 + 미연결 행 묶음) |
| REST | `ApiPaths.EXPERIENCES` = `/api/experiences` — `GET`(목차, `ids` 면 상세)·`GET /{id}`·`POST`·`PUT /{id}`·`POST /{id}/review`·`DELETE /{id}?detach=`·`GET /import-plan`·`POST /import`. 프런트 `routes.ts` 의 `experience*`, nginx 는 `/api/` 접두사 |
| MCP | `experience_save`·`experience_index`(전체판, `ids` 상세)·`experience_search`·`resume_add_experiences`, 행 도구의 `experienceId` 지목, `resume_update` 의 `removedExperienceIds`. 검토 확정·삭제·가져오기는 웹 전용이라 도구가 없다 |
| 웹 화면(4b) | `/experiences` = `ExperienceBoard.vue`(목록·드로어 상세·검토 확정·삭제) + `ExperienceForm.vue`(원본 편집) + `ExperienceImport.vue`(가져오기). 이력서 편집기 `ResumeEditor`·`ResumeSection` 의 경험 조작, 자소서 편집기의 "쓴 경험". API 는 `frontend/src/api/experiences.ts`, 행 변환은 `types/experience.ts` |
| 자소서 작성 근거 | `self_intro_create/update` 의 `experiences[{experienceId, experienceRevision}]` → `SelfIntroduction.experienceLinks` |

## 불변 조건

1. **소유자 격리**: 새 표는 모두 `owner_id` 를 갖고 간선은 `(id, owner_id)` 복합 FK 다. 남의 경험 id 는 404.
2. **이력서 content 는 `ResumeWriter` 로만 쓴다.** `Resume.update` 는 패키지 전용이고, `ResumeWriterGateTest` 가 `ResumeRepository.save*` 를 다른 곳에서 부르면 실패한다. JSON 이 정본이고 `resume_experiences` 는 같은 트랜잭션에서 맞춘다.
3. **개정은 클라이언트가 바꾸지 못한다.** 개정 없이 온 행은 저장된 같은 경험 행의 개정을 잇는다(새 연결만 지금 개정). 보낸 개정이 저장값과 다르면 400 `EXPERIENCE_REVISION_MISMATCH`. 개정을 올리는 길은 웹 PUT 의 `refreshExperienceIds`("원본으로 갱신")와 `resume_add_experiences` 둘뿐이다.
4. **키가 빠진 행**은 저장된 문서에 같은 `(종류, 제목 키, 시작 연월)` 행이 정확히 하나 있고 앵커가 있으면 그 앵커를 잇는다. 그 밖에는 `(종류, 제목 키)` 가 정확히 1건이고 시작 연월이 충돌하지 않을 때만 자동 연결한다. 새 경험은 만들지 않는다.
5. **개정 판정은 서버가 한다**(입력에 `changeKind` 없음): facts 해시가 직전 개정과 같으면 개정 없음(`NONE`). 배열 항목은 안정 id 로 짝짓는다 — 새 id 만 늘면 `ADD`, 기존 id 의 값이 바뀌거나 사라지면 `CORRECT`. 근거·미확인·충돌 칸만 바뀌면 `ADD`. "재검토 필요"는 참조한 개정보다 `seq` 가 큰 `CORRECT` 가 있을 때만이다.
6. **기존 경험에 facts·dossier 를 보내면 `expectedVersion` 필수**(없으면 400 `VERSION_REQUIRED`, 다르면 409 `EXPERIENCE_STALE`). 웹 저장은 출처 USER 다. 웹 생성과 `facts` 를 보낸 웹 수정만 검토 확정을 겸한다 — 메모·skills·domains·소재집만 고친 저장은 'AI 기록'을 확정하지 않는다. MCP 저장은 'AI 기록'(`reviewed:false`)이다.
7. **경험 관계를 바꾸는 쓰기는 `OwnerLock` 아래에서 한다**: 이력서 create·update·copy·delete·basic·행 도구·`resume_add_experiences`, 경험 저장·검토·삭제·가져오기, 자소서 create·update(확정·삭제는 예외 — 근거를 늘리지 않는다). `OwnerLockGateTest` 가 이 네 서비스의 쓰기 트랜잭션을 소스로 검사한다. 같은 계정은 줄을 서고 3초를 넘기면 409 `BUSY`, 다른 계정은 기다리지 않는다. 그다음 이력서(id 오름차순) → 경험 순서로만 행을 잠근다.
8. **쓰이는 경험은 지우지 못한다.** 이력서가 쓰면 409 `EXPERIENCE_IN_USE`(이력서 이름), 자소서 근거는 `detach=true` 없이 409. 확인은 잠금 아래에서 하고, 지연 FK 셋(`resume_experiences_experience_fk`·`_revision_fk`, `self_introduction_experiences_revision_fk`)이 잠금 밖 경로를 막는다. 계정 삭제 연쇄는 지연이라 통과한다.
9. **자소서 작성 근거는 받은 `(경험, 개정)` 쌍 그대로** 저장·조회한다. REST 는 목록으로 맞추고, MCP 는 더하기만 한다(이미 연결된 경험은 건너뜀).

## 웹 화면 (4b)

- **경험 목록**(`/experiences`, 탑바 "경험"): 카드는 제목·종류·기간·이력서 수와 `AI 기록`(지금 개정을 검토하지 않음)만. 카드 → 드로어 상세: 사실, `소재집 갱신 필요`, 쓰는 이력서 행(`재검토 필요`, 더블클릭으로 이력서 열기), 작성 근거로 쓴 자소서, 개정 이력. `검토 확정` 은 보던 `revision` 을 보낸다(그사이 올랐으면 409 → 다시 읽음).
- **원본 편집**: 사실 항목은 받은 객체를 고쳐 `id`·`evidenceIds` 를 지키고, 새 항목은 `id` 없이 보낸다. 사실을 고쳤을 때만 `facts` 를 보낸다(그때만 검토 확정을 겸함). 늘 `expectedVersion` 을 보낸다. 저장이 409 `EXPERIENCE_STALE` 이면 상세를 다시 읽어 보기로 돌리고 "다른 곳에서 바뀌었습니다"를 보인다 — 폼은 열 때의 `version` 을 쥐고 있어 그대로 다시 보내면 매번 409 다(입력은 버려진다). 그 밖의 오류는 폼과 입력을 남긴다.
- **가져오기**: 계획 → 묶음마다 새 경험/기존 경험에 연결/건너뛰기 → 적용. `mergeCandidates` 는 "같은 경험일 수 있음" 안내만 한다. 409 `IMPORT_PLAN_STALE` 이면 계획을 다시 받고 다시 고르게 한다.
- **이력서 편집기**(프로젝트·경력·대내외활동·교육이수 섹션): 섹션 머리의 `경험에서 불러오기`(사실로 만든 중립 행 + `experienceId`), 행 바의 `경험으로 등록`(미연결 행 → 경험 원본 생성 후 앵커), `다른 경험에 연결`(id 교체, 개정 키 제거), `재검토 필요` + `원본으로 갱신`(다음 저장의 `refreshExperienceIds`), 저장 응답의 `unlinkedWarnings` 알림. 경험 원본을 못 불러오면 경험 조작만 숨기고 편집은 그대로 된다.
- **중복 처리 흐름**(설계 6.4): 같은 경험이 둘이면 이력서 편집기에서 행을 남길 경험에 `다른 경험에 연결` → 저장 → 경험 화면에서 쓰지 않게 된 경험을 삭제. 이력서가 아직 쓰면 409 사유(이력서 이름)를 보이고, 자소서 근거만 남았으면 "근거 끊고 삭제"를 물어 `detach=true` 로 지운다.
- **자소서 "쓴 경험"**: 새로 고른 경험은 그 경험의 지금 개정과 짝짓고, 이미 있던 쌍은 개정을 바꾸지 않는다. 웹 저장은 늘 `experiences` 목록을 보낸다([] 는 비움).

## 실행·검증

```bash
docker compose up -d --build backend frontend && docker compose logs backend | grep "now at version"   # v25
bash scripts/smoke.sh
# 백엔드 테스트(컨테이너): ExperienceBankIntegrationTest(계정 삭제 연쇄·복합 FK·링커·개정·삭제 경합·REST 삭제·T6 상한·T9 대기/줄 서기·MCP·가져오기),
#   ExperienceChangeKindTest(개정 판정 표), ResumeWriterGateTest, OwnerLockGateTest(쓰기 트랜잭션의 OwnerLock 소스 검사), ApiErrorsTest
cd frontend && npm run type-check && npm test
# 웹(4b): ExperienceBoard.spec(AI 기록·소재집 갱신 필요·검토 확정, facts 를 고쳤을 때만 보냄·항목 id 유지, 409 → detach 삭제, 가져오기 결정·409 재계획,
#     수정·검토 확정 409 STALE → 다시 읽음, 그 밖의 저장 오류는 폼 유지),
#   ResumeExperience.spec(저장 payload 앵커 유지, 원본으로 갱신 → 재검토 표시 사라짐, 저장 실패 뒤에도 refreshExperienceIds 유지, 다른 경험에 연결 = 개정 키 제거, 불러오기·등록, unlinkedWarnings,
#     경험 섹션 4개 it.each — 종류별 선택지·불러온 행·등록 kind·앵커 유지, 앵커 없는 섹션엔 경험 조작 없음),
#   ExperienceRelinkFlow.spec(가짜 서버 하나로 편집기 "다른 경험에 연결" 저장 → 경험 화면 삭제까지. 가짜는 링커 개정 규칙·사용처 판정을 따른다),
#   SelfIntroduction.spec "쓴 경험"
```

**운영 점검·롤백 런북**(설계 6.2): JSON↔표 대조는 `ResumeWriter.mismatches(owner)` 와 같은 SQL(설계 6.2 의 질의)로 계정별 건수만 본다 — 0 이어야 한다.
어긋났으면 계정마다 `ResumeWriter.rebuild(owner)` 와 같은 SQL(그 계정 `resume_experiences` 를 지우고 JSON 앵커로 다시 넣기, 멱등)을 돈다.
**앵커 확장 배포(`db1f087`)보다 앞선 코드로는 되돌리지 않는다** — 앵커를 모르는 record 가 저장마다 키를 버린다.

## 함정

- **웹에서 연결을 바꿀 때 `experienceRevision` 을 남기지 않는다.** 남기면 링커가 그 번호를 새 경험의 개정으로 읽어 400 `EXPERIENCE_REVISION_MISMATCH` 가 되거나 엉뚱한 개정에 묶인다. `ResumeSection.relink` 가 키를 지운다.
- **경험 수정의 null 은 "유지"다.** 메모·시작 연월을 지우려면 빈 문자열을 보낸다(`ExperienceForm`). 사실은 고쳤을 때만 보낸다 — 늘 보내면 메모만 고친 저장이 AI 기록을 확정해 버린다.
- **삭제 409 는 이력서·자소서 모두 `EXPERIENCE_IN_USE`** 다. 화면은 메시지 문구가 아니라 다시 읽은 상세의 `rows`·`selfIntros` 로 `detach` 확인 여부를 가른다.
- 편집기의 "재검토 필요"는 앵커 경험 상세(10개씩 나눠 읽음)의 개정 이력으로 판정한다. 앵커 경험이 많을수록 편집기 열 때 요청이 는다(경험 10개당 1회, 병렬).

- **FK 위반 쪽은 메시지로 가른다.** PostgreSQL 23503 의 테이블 필드는 삭제·삽입 쪽 모두 *참조하는* 테이블이다(설계 2.3 의 처음 가정과 다름). `ApiErrors` 는 메시지 머리("insert or update" / "update or delete")로 `EXPERIENCE_CHANGED`·`EXPERIENCE_IN_USE` 를 나눈다. DB `lc_messages` 를 영어가 아닌 언어로 바꾸면 이 구분이 삭제 쪽으로 쏠린다.
- **개정 이력은 JPA 엔티티가 아니다.** `seq` 가 `GENERATED ALWAYS` 라 `ExperienceRevisions` 가 JDBC 로 넣고 읽는다. 경험 행을 먼저 flush 해야 한다(개정 → 경험 FK 는 지연이 아님).
- **가져오기 대량 insert 는 `Experience implements Persistable`** 이라 `saveAll` 이 merge(SELECT) 없이 persist 한다(생성자에서 UUID 를 채우고 version 이 primitive 라 그대로면 merge 가 된다).
- **한 이력서에 같은 묶음 키 행이 둘이면** 가져오기 CREATE 가 같은 경험을 두 행에 붙여 400 `EXPERIENCE_ROW_DUPLICATE` 로 전체가 실패한다. 한 행의 제목·기간을 고친 뒤 다시 계획한다.
- **가져오기 결정은 묶음마다 하나**다. 같은 key 가 두 번 오면 400 `DUPLICATE_DECISION`(앞 결정이 만든 경험이 고아가 되던 문제). CREATE 초안은 사실 상한(항목 2000자·배열 50개·기간 200자)에 맞춰 나누거나 자르고, 담지 못한 몫은 `unverified` 에 적는다.
- **사실 상한이 이력서 칸 상한보다 크다**(role 2000 > 500, 항목 2000×50 > 설명 5000). `resume_add_experiences` 는 칸에 맞춰 자르고 `…` 를 붙인다. 행에서 잘린 원문은 경험 원본에 그대로 있다.
- **23505·23503 은 409 로 응답하지만**, 경험 쪽 지연 FK 셋이 아닌 위반은 `ApiErrors` 가 SQLState·제약 이름을 WARN 로 남긴다. 사용자가 "충돌"을 반복해서 받으면 이 로그부터 본다.
- **`resume_update`(MCP)** 는 저장 전에 있던 경험 행이 빠지면 409 `LINKED_ROWS_MISSING` 이다. 일부러 지우면 `removedExperienceIds` 에 적는다. 웹 PUT 은 막지 않고 응답 `unlinkedWarnings` 로 알린다.
- **자소서 MCP 목록·상세 SQL 상한**이 작성 근거 컬렉션 1회만큼 올랐다(7·7·8·8). 근거가 있는 문항이 있으면 경험 이름·개정 이력 2회가 더 붙는다 — docs/backend.md.
- 정기 백업·복원 리허설은 4a 사용 배포의 운영 선행 조건이다(docs/nginx-deploy.md "정기 백업과 복원 리허설"). 아직 수행하지 않았다.
