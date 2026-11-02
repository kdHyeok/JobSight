# 경험 원본 (경험 은행, 4단계 최소판)

## 목적

경험의 **사실을 한 곳(경험 원본)에 두고**, 이력서 행은 그 경험의 기업별 표현으로 둔다. 어느 PC 의 AI 클라이언트든 같은 경험 id·사실·근거를 읽는다.
이력서 행은 쓴 경험과 그 **개정 번호**를 앵커로 든다. 원본이 바뀌어도 과거 문구는 그대로이고, 사실이 정정되면 "재검토 필요"로 알린다.
설계 정본: [experience-graph.design.md](02-design/features/experience-graph.design.md) 2.5·6.1~6.5·6.8, 7절 4a. 사건(STAR)·병합 계보·키워드 테이블·`ROW` 복사는 4c(보류, 설계 6.10)라 없다. 웹 화면은 4b 다.

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
| 자소서 작성 근거 | `self_intro_create/update` 의 `experiences[{experienceId, experienceRevision}]` → `SelfIntroduction.experienceLinks` |

## 불변 조건

1. **소유자 격리**: 새 표는 모두 `owner_id` 를 갖고 간선은 `(id, owner_id)` 복합 FK 다. 남의 경험 id 는 404.
2. **이력서 content 는 `ResumeWriter` 로만 쓴다.** `Resume.update` 는 패키지 전용이고, `ResumeWriterGateTest` 가 `ResumeRepository.save*` 를 다른 곳에서 부르면 실패한다. JSON 이 정본이고 `resume_experiences` 는 같은 트랜잭션에서 맞춘다.
3. **개정은 클라이언트가 바꾸지 못한다.** 개정 없이 온 행은 저장된 같은 경험 행의 개정을 잇는다(새 연결만 지금 개정). 보낸 개정이 저장값과 다르면 400 `EXPERIENCE_REVISION_MISMATCH`. 개정을 올리는 길은 웹 PUT 의 `refreshExperienceIds`("원본으로 갱신")와 `resume_add_experiences` 둘뿐이다.
4. **키가 빠진 행**은 저장된 문서에 같은 `(종류, 제목 키, 시작 연월)` 행이 정확히 하나 있고 앵커가 있으면 그 앵커를 잇는다. 그 밖에는 `(종류, 제목 키)` 가 정확히 1건이고 시작 연월이 충돌하지 않을 때만 자동 연결한다. 새 경험은 만들지 않는다.
5. **개정 판정은 서버가 한다**(입력에 `changeKind` 없음): facts 해시가 직전 개정과 같으면 개정 없음(`NONE`). 배열 항목은 안정 id 로 짝짓는다 — 새 id 만 늘면 `ADD`, 기존 id 의 값이 바뀌거나 사라지면 `CORRECT`. 근거·미확인·충돌 칸만 바뀌면 `ADD`. "재검토 필요"는 참조한 개정보다 `seq` 가 큰 `CORRECT` 가 있을 때만이다.
6. **기존 경험에 facts·dossier 를 보내면 `expectedVersion` 필수**(없으면 400 `VERSION_REQUIRED`, 다르면 409 `EXPERIENCE_STALE`). 웹 저장은 출처 USER 이고 검토 확정을 겸한다. MCP 저장은 'AI 기록'(`reviewed:false`)이다.
7. **경험 관계를 바꾸는 쓰기는 `OwnerLock` 아래에서 한다**: 이력서 create·update·copy·delete·basic·행 도구·`resume_add_experiences`, 경험 저장·검토·삭제·가져오기, 자소서 create·update. 같은 계정은 줄을 서고 3초를 넘기면 409 `BUSY`, 다른 계정은 기다리지 않는다. 그다음 이력서(id 오름차순) → 경험 순서로만 행을 잠근다.
8. **쓰이는 경험은 지우지 못한다.** 이력서가 쓰면 409 `EXPERIENCE_IN_USE`(이력서 이름), 자소서 근거는 `detach=true` 없이 409. 확인은 잠금 아래에서 하고, 지연 FK 셋(`resume_experiences_experience_fk`·`_revision_fk`, `self_introduction_experiences_revision_fk`)이 잠금 밖 경로를 막는다. 계정 삭제 연쇄는 지연이라 통과한다.
9. **자소서 작성 근거는 받은 `(경험, 개정)` 쌍 그대로** 저장·조회한다. REST 는 목록으로 맞추고, MCP 는 더하기만 한다(이미 연결된 경험은 건너뜀).

## 실행·검증

```bash
docker compose up -d --build backend frontend && docker compose logs backend | grep "now at version"   # v25
bash scripts/smoke.sh
# 백엔드 테스트(컨테이너): ExperienceBankIntegrationTest(계정 삭제 연쇄·링커·개정·삭제·T9 대기·MCP·가져오기), ResumeWriterGateTest, ApiErrorsTest
```

**운영 점검·롤백 런북**(설계 6.2): JSON↔표 대조는 `ResumeWriter.mismatches(owner)` 와 같은 SQL(설계 6.2 의 질의)로 계정별 건수만 본다 — 0 이어야 한다.
어긋났으면 계정마다 `ResumeWriter.rebuild(owner)` 와 같은 SQL(그 계정 `resume_experiences` 를 지우고 JSON 앵커로 다시 넣기, 멱등)을 돈다.
**앵커 확장 배포(`db1f087`)보다 앞선 코드로는 되돌리지 않는다** — 앵커를 모르는 record 가 저장마다 키를 버린다.

## 함정

- **FK 위반 쪽은 메시지로 가른다.** PostgreSQL 23503 의 테이블 필드는 삭제·삽입 쪽 모두 *참조하는* 테이블이다(설계 2.3 의 처음 가정과 다름). `ApiErrors` 는 메시지 머리("insert or update" / "update or delete")로 `EXPERIENCE_CHANGED`·`EXPERIENCE_IN_USE` 를 나눈다. DB `lc_messages` 를 영어가 아닌 언어로 바꾸면 이 구분이 삭제 쪽으로 쏠린다.
- **개정 이력은 JPA 엔티티가 아니다.** `seq` 가 `GENERATED ALWAYS` 라 `ExperienceRevisions` 가 JDBC 로 넣고 읽는다. 경험 행을 먼저 flush 해야 한다(개정 → 경험 FK 는 지연이 아님).
- **가져오기 대량 insert 는 `Experience implements Persistable`** 이라 `saveAll` 이 merge(SELECT) 없이 persist 한다(생성자에서 UUID 를 채우고 version 이 primitive 라 그대로면 merge 가 된다).
- **한 이력서에 같은 묶음 키 행이 둘이면** 가져오기 CREATE 가 같은 경험을 두 행에 붙여 400 `EXPERIENCE_ROW_DUPLICATE` 로 전체가 실패한다. 한 행의 제목·기간을 고친 뒤 다시 계획한다.
- **`resume_update`(MCP)** 는 저장 전에 있던 경험 행이 빠지면 409 `LINKED_ROWS_MISSING` 이다. 일부러 지우면 `removedExperienceIds` 에 적는다. 웹 PUT 은 막지 않고 응답 `unlinkedWarnings` 로 알린다.
- **자소서 MCP 목록·상세 SQL 상한**이 작성 근거 컬렉션 1회만큼 올랐다(7·7·8·8). 근거가 있는 문항이 있으면 경험 이름·개정 이력 2회가 더 붙는다 — docs/backend.md.
- 정기 백업·복원 리허설은 4a 사용 배포의 운영 선행 조건이다(docs/nginx-deploy.md "정기 백업과 복원 리허설"). 아직 수행하지 않았다.
