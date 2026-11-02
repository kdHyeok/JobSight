# 첨부파일

## 목적

이력서의 증빙 서류(포트폴리오·활동 자료·수상 증빙·자격증 사본·이수증·졸업증·성적증명서)를 **jpg · png · pdf** 로 올리고, 화면에서 바로 보고, 교체한다.

## 도메인

```
app_users ──1:N──▶ attachments
                     id · owner_id · filename · content_type · size_bytes · data(bytea) · created_at
```

**이력서 행은 첨부의 id 문자열만 들고 있다.** 그래서 `Row` 구조가 그대로 유지되고,
이력서는 여전히 JSON 문서 하나다 — `docs/resumes.md`.

## 불변 조건

1. **소유자 격리**: `findByIdAndOwnerId` 만. 타인 첨부는 404. 바이트도 메타도 나가지 않는다.
   이력서 응답에 함께 싣는 메타(`AttachmentRepository.findMetas`)도 쿼리에 소유자 조건이 있다 — 문서에 남의 id 를 적어도 빠진다.
2. **타입은 클라이언트 말이 아니라 파일 앞머리로 정한다.** `Content-Type` 헤더와 확장자는 위조된다.
   `AttachmentService.sniff()` 가 매직 바이트(`\x89PNG`, `\xFF\xD8\xFF`, `%PDF-`)로 판정하고, 셋 중 하나가 아니면 415.
   저장하는 `content_type` 도 이 판정 결과다 — 내려줄 때 그대로 쓰기 때문이다.
3. **10MB 상한.** 서버(`spring.servlet.multipart`)·서비스·nginx(`client_max_body_size 12m`) 세 곳이 함께 막는다.
   셋 다 있어야 한다 — nginx 가 없으면 413 대신 연결이 끊기고, 서비스 검사가 없으면 curl 경로가 뚫린다.
4. **내려줄 때 `X-Content-Type-Options: nosniff` + `Content-Disposition: inline`.** 브라우저가 타입을 다시 추측하지 못하게 한다.
5. **파일명은 표시용이다.** 경로 구분자를 떼고 260자로 자른 뒤 저장하며, 어떤 경로 조립에도 쓰지 않는다.
6. **제출 기록이 가리키는 첨부는 지울 수 없다**(설계 6.6·6.9, V28). 제출 기록 스냅샷의 이력서 안 첨부와 접수 근거 파일은 `submission_attachments` 에 남는다.
   그 첨부 FK(`submission_attachments_attachment_fk`, `(attachment_id, owner_id)` → `attachments (id, owner_id)`)는 `ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED` 라
   단독 삭제는 커밋에서 막히고 공용 변환이 409 `ATTACHMENT_IN_USE` 로 바꾼다. 계정 삭제는 기록과 첨부가 같은 트랜잭션에서 함께 사라져 통과한다.
   기록에 첨부를 적을 때는 그 행을 `FOR KEY SHARE` 로 잡는다(규칙 B) — 동시에 들어온 삭제와 순서가 정해진다.
7. **삭제는 사용자가 하고, 쓰는 파일은 지우지 않는다**(설계 6.9). 자동 정리는 없다. 길은 둘이다: 이력서 저장·삭제가 **성공한 뒤** 빠진 첨부를 매번 묻기, 이력서 목록의 [첨부 파일] 서랍(어디서도 쓰지 않고 올린 지 7일이 지난 파일만).
   `AttachmentService.delete` 는 한 트랜잭션에서 ① 첨부 행을 네이티브 `select … for update` 로 잡고(없으면 404) ② 사용처를 판정해(`USAGE`: 계정 이력서 content·경험 `facts`·경험 지난 개정·`submission_attachments`) ③ 쓰는 곳이 있으면 409 `ATTACHMENT_IN_USE`(쓰는 곳 이름과 지우는 방법을 담은 문구), 없으면 지운다.
   **JPA `PESSIMISTIC_WRITE` 로 바꾸지 않는다** — PostgreSQL 에서 `for no key update` 라 아래 8 의 `for key share` 와 충돌하지 않고, 판정과 새 참조 저장이 엇갈린다.
8. **규칙 B: 첨부 id 를 새로 적는 쓰기는 그 행을 `for key share` 로 잡는다.** 이력서는 `ResumeWriter` 한 곳, 경험 `ATTACHMENT` 근거는 `ExperienceBankService`, 둘 다 `AttachmentService.lockNewReferences` 를 부른다.
   저장 전 문서에 없던 값만 본다. 내 파일이 아니거나 없거나 **정규형(소문자 36자) UUID 가 아니면** 400 `ATTACHMENT_NOT_FOUND`. 이미 저장된 값은 통과한다 — 예전에 지워진 id 를 든 문서도 계속 저장된다.
   두 잠금이 충돌하므로 순서가 정해진다: 쓰기가 먼저면 삭제가 기다렸다가 새 content 를 보고 409, 삭제가 먼저면 쓰기가 행을 못 찾고 400.

## 진입점

| 무엇 | 어디 |
|---|---|
| 스키마 | `V15__company_businesses_and_attachments.sql` |
| 도메인 | `attachment/{Attachment,AttachmentRepository,AttachmentService,AttachmentController}` — `ApiPaths.ATTACHMENTS` |
| 화면 | `AttachmentField.vue` — 첨부·교체·제거·미리보기 한 컴포넌트. 이력서 필드 `kind: 'file'` 이 이걸 그린다. `preloaded` 로 받은 메타가 있으면 메타를 부르지 않는다 |
| 삭제·사용처 판정·규칙 B | `AttachmentService.{delete,list,lockNewReferences}` · `AttachmentRepository.lockForReference` · 호출: `ResumeWriter.write`, `ExperienceBankService.create/update` |
| 지우는 화면 | `ResumeBoard.vue`(저장·삭제 뒤 묻기, `removedAttachments`) · `AttachmentDrawer.vue`(이력서 목록의 [첨부 파일] 서랍) · `ResumeEditor.vue` 내보내기 체크 목록(응답 메타가 없는 id 를 거름) |
| 이력서 응답의 메타 | `ResumeService.attachmentMetas` → `AttachmentRepository.findMetas`(id 목록 + 소유자, bytea 안 읽음). 첨부 칸 목록은 `ResumeContent.attachmentIds()` |
| 프런트 API | `api/attachments.ts` · 경로는 `routes.ts` 의 `API.attachments*` |
| 제출 기록의 첨부 | `V28__submissions_and_guides.sql` 의 `submission_attachments` · `SubmissionService.lockAttachments`(규칙 B) · `ApiErrors` 의 `ATTACHMENT_IN_USE` |
| 업로드 한계 | `application.yml` 의 `spring.servlet.multipart` · `frontend/nginx.conf` 의 `client_max_body_size` |

## API

```
POST   /api/attachments        multipart/form-data, part 이름 "file"  → {id, filename, contentType, size}
GET    /api/attachments        → [{id, filename, contentType, size, createdAt, usedBy[{type, id, name}], deletable}]  서랍 목록
GET    /api/attachments/{id}          → 바이트 (inline)
GET    /api/attachments/{id}/meta     → {id, filename, contentType, size}
DELETE /api/attachments/{id}          204. 쓰는 곳이 있으면 409 ATTACHMENT_IN_USE, 남의 것·없는 것 404

GET    /api/resumes/{id}              → {..., attachments: {id → {id, filename, contentType, size}}}
```

`usedBy.type` 은 `RESUME`(이력서) · `EXPERIENCE`(경험의 지금 근거) · `EXPERIENCE_REVISION`(경험의 지난 개정에만 있음) · `SUBMISSION`(제출 기록, `name` 은 공고 제목).
`deletable` 은 서버가 계산한다(미사용이고 올린 지 7일이 지남). 서버 `DELETE` 는 7일을 보지 않는다 — 저장 뒤 묻기는 방금 올린 파일도 지울 수 있어야 해서다. 목록 GET 은 기존 경로에 메서드만 더했으므로 경로 표는 그대로다.

이력서 응답의 `attachments` 는 새 경로가 아니라 응답 필드다. 편집기를 열 때 첨부 칸마다 `/meta` 를 부르던 직렬 단계를 없앤다 — `docs/resumes.md` 함정.
`/meta` 는 그대로 둔다. 응답에 빠진 id(남의 것·지워진 것)와 내보내기(`resume-export.ts`)가 쓴다.

MCP 도구는 없다. 바이너리는 JSON 도구 호출에 실을 것이 아니다. `resume_get` 응답에는 메타가 함께 실린다. MCP 로는 첨부를 지우지 않는다.
MCP 로 이력서·경험을 저장할 때도 규칙 B 가 걸린다: 새로 적는 첨부 id 가 내 파일이 아니면 400 `ATTACHMENT_NOT_FOUND`.

## 실행·검증

```bash
docker compose up -d --build && docker compose logs backend | grep "now at version"   # v15
bash scripts/smoke.sh                       # /api/attachments 미인증 401 포함
cd frontend && npm run type-check && npm test
```

로그인 뒤 브라우저에서:
1. 이력서 → 자격증 행 → `파일 선택` → png 업로드 → 파일명 칩과 `미리보기` 가 뜨는가
2. `미리보기` → 이미지가 뜨고, pdf 는 뷰어로 열리는가
3. `교체` → 다른 파일로 바뀌는가. 이전 파일은 **남는다**
4. 이력서를 저장하지 않고 나갔다 오면 이전 첨부가 그대로 붙어 있는가 (**업로드는 즉시, 연결은 저장 시점**)
5. `.txt` 를 png 로 이름만 바꿔 올리면 415 로 거부되는가
6. 이력서 내보내기에서 선택한 첨부만 원래 파일명으로 ZIP에 들어가는가
7. 첨부가 있는 이력서를 `복제` → 복제본에서 그 첨부를 `교체` 또는 `제거` 하고 저장 → 원본 이력서를 열면 원래 파일이 그대로 보이고 `미리보기` 가 되는가 (원본 첨부 `GET` 200)
8. 첨부가 있는 이력서를 열 때 DevTools 네트워크에 `/api/attachments/{id}/meta` 요청이 없는가 (메타는 이력서 응답에 함께 온다)
9. 자격증 칸의 첨부를 `제거` 하고 저장 → "빠진 첨부 파일도 지울까요?" 가 뜨는가. 지우면 "파일 1개를 지웠습니다", 취소하면 파일이 서랍에 남는가. 다음 저장에서 또 빠지면 또 묻는가
10. 첨부가 있는 이력서를 `복제` → 원본에서 그 첨부를 빼고 저장 → 묻기에서 지우면 "다른 곳에서 쓰고 있어 남겨 두었습니다" 이고 복제본의 첨부가 그대로인가
11. 이력서 목록 [첨부 파일] → 쓰는 파일에 사용처 칩, 올린 지 7일이 안 된 미사용 파일에 안내 문구만 있고 삭제 버튼이 없는가

자동 검증: `AttachmentField.spec.ts`(교체·제거가 DELETE 를 보내지 않음, 받은 메타가 있으면 메타 GET 없음, 편집기 전체에서 메타 GET 없음) · `ResumeServiceTest`(첨부 칸 7개 수집, `[null]` 행이 있어도 상세·생성이 깨지지 않음) · `ResumeAttachmentsIntegrationTest`(PostgreSQL 에서 남의 첨부 메타가 빠짐) · `SubmissionIntegrationTest.confirmedSnapshotPinsAttachmentsAndAccountDeleteCascades`(제출 기록 첨부 삭제 409·문구·파일 남음, 서랍 사용처 `SUBMISSION`, 계정 삭제 cascade, 남의 근거 파일 400) · `AttachmentDeleteIntegrationTest`(미사용 삭제·남의 것 404, 복제본이 쓰는 파일 409, 규칙 B 의 남의 id·없는 id·대문자·글자 400 과 저장된 지워진 id 통과(content 를 명시해 지워진 id 와 새 id 를 섞어 저장 — 새 내 파일이면 성공, 새 남의 파일이면 400), 경험 근거와 지난 개정 409·경험 생성과 수정의 규칙 B(수정은 저장된 지워진 근거 통과·새 남의 근거 400), 서랍 usedBy·deletable, 삭제의 `lock_timeout` — 다른 트랜잭션이 행을 `for key share` 로 쥐고 있으면 약 3초 뒤 409 `BUSY`, **동시성 여덟 건**: 새 참조 저장 먼저 → 삭제 409 / 삭제 먼저 → 저장 400 / 원본 저장 먼저 → 복제본에 없음·삭제 가능 / 복제 먼저 → 삭제 409 / 경험 근거 저장 먼저 → 삭제 409 / 삭제 먼저 → 경험 저장 400 / 제출 기록(접수 근거) 먼저 → 삭제 409 / 삭제 먼저 → 제출 기록 400·기록 없음. 둘째 트랜잭션이 `pg_stat_activity` 에서 잠금 대기인 것을 확인한 뒤 첫째를 커밋한다) · `AttachmentDelete.spec.ts`(저장·이력서 삭제 뒤 빠진 첨부만 묻기, 매번 묻기, 409 남김 안내, 저장 실패 시 묻지 않음, 서랍 칩·7일 안내·버튼, 서랍 409 문구, 내보내기 체크 목록 거름).

## 함정

- **업로드와 이력서 저장은 시점이 다르다.** 파일은 고르는 즉시 서버에 올라가고(그래야 미리보기가 된다),
  그 id 는 `저장` 을 눌러야 이력서 문서에 들어간다. 저장 없이 나가면 **주인 없는 첨부**가 남는다.
  교체·제거로 연결이 풀린 이전 파일도 남는다(아래).
  자동 청소는 없다 — 개인 도구에서 몇 개의 고아 파일보다 저장 흐름이 복잡해지는 쪽이 비싸다. 사용자가 [첨부 파일] 서랍에서 지운다(불변 조건 7).
  `ponytail: 자동 정리는 없다. 용량이 문제가 되면 서랍의 deletable 과 같은 기준(미사용·7일)으로 정리 작업을 붙인다(설계 1.4 T7·1.5).`
- **교체·제거는 이전 파일을 지우지 않는다.** 교체는 새 업로드이고, 제거는 이 이력서에서 연결만 푼다.
  같은 id 의 바이트를 덮어쓰지도 않는다. 복제한 이력서가 같은 id 를 가리키므로, 지우거나 덮어쓰면 원본 이력서의 첨부가 사라지거나 바뀐다.
  저장하지 않고 나간 경우에도 이력서는 이전 id 를 들고 있으니 지우면 안 된다. 전에는 화면이 이전 파일을 즉시 DELETE 했다 — `docs/decisions.md` 「첨부 교체·제거 때 이전 파일을 지우지 않는다」.
  첨부 칸은 `DELETE` 를 부르지 않는다 — `AttachmentField.spec.ts` 가 http 계층에서 DELETE 가 나가지 않는 것을 고정한다. 지우는 것은 저장이 **성공한 뒤** `ResumeBoard` 가 묻고, 서버 판정이 복제본을 지킨다.
- **저장 뒤 묻기는 응답 `attachments` 맵의 키를 비교한다.** 저장 전 문서의 id 문자열이 아니다. 맵에는 지금 있는 내 파일만 실리므로 이미 지워진 id·남의 id 는 묻지 않는다. 저장 전에 올렸다가 저장 전에 다시 교체한 파일은 두 맵 어디에도 없어 묻지 않는다 — 서랍에서 7일 뒤 지운다.
- **사용처 판정은 텍스트 포함 검사다**(`strpos(content::text, id::text)`). 그래서 첨부 칸에 새로 적는 값은 정규형 UUID 만 받는다(불변 조건 8). 대문자나 축약형(`1-2-3-4-5`, `UUID.fromString` 이 받아 준다)으로 저장된 값이 있으면 그 파일은 판정이 못 찾아 지워질 수 있다 — 아래 「배포 전 점검」. 경험의 `attachmentId` 는 Jackson 이 UUID 로 읽고 정규형으로 쓰므로 해당 없다.
- **경험의 지난 개정이 가리키는 파일은 지울 수 없다.** 개정 이력은 고치지 않고 쌓으므로 근거에서 빼고 저장해도 개정 1 이 계속 가리킨다. 지우려면 그 경험을 지운다(개정이 CASCADE). 409 문구가 이렇게 안내한다.
- **복제는 규칙 B 를 타지 않는다.** `ResumeWriter` 의 before 가 원본 문서라 새 id 가 없다. 원본을 잠그고 읽어(`lockOwned`) 원본이 든 id 는 판정에 계속 보이므로 충분하다. 복제를 규칙 B 에 태우면 원본에 남은 옛 지워진 id 때문에 복제가 400 이 된다(설계 6.9 구현 메모).

- **이력서 응답에 실리는 메타는 응답 전용이다.** 저장 요청에 넣지 않는다 — 웹 편집기는 보내지 않고, MCP 는 요청의 모르는 필드를 거부한다(`McpTools` 의 `FAIL_ON_UNKNOWN_PROPERTIES`). 응답에 없는 id 는 `AttachmentField` 가 `/meta` 로 직접 부르므로, 새로 올린 직후나 남의 id 가 적힌 칸도 화면은 전처럼 동작한다.
- **첨부 칸을 모을 때 `null` 행은 건너뛴다.** 섹션 배열 요소에는 `@NotNull` 이 없어 `{"trainings":[null]}` 이 검증을 통과해 그대로 저장되고, 예전에 MCP 로 저장된 문서에도 있을 수 있다.
  `ResumeContent.attachmentIds()` 는 모든 이력서 응답이 지나가므로, 여기서 행을 그대로 만지면 상세·생성·저장·복제·`resume_get` 이 전부 500 이 되고 그 이력서를 열 수 없다. `rows()` 가 `null` 행을 거른다.
- **제출 기록의 첨부 수집은 LINKED 와 접수 근거가 다르다.** 이력서 안 첨부는 지금 있는 것만 남긴다(예전에 지워진 id 가 문서에 남아 있어도 기록을 막지 않는다 — 6.9 규칙 B 의 "이미 저장된 값은 통과").
  접수 근거 파일은 모두 내 것이어야 하고 하나라도 없으면 400 `ATTACHMENT_NOT_FOUND` 다. 삭제 판정(`AttachmentService.USAGE`)에는 `submission_attachments` 절이 들어 있다(인덱스 `submission_attachments_attachment_idx`). FK 는 그 뒤의 방어선이다.
- `fetch` 에 `FormData` 를 줄 때 `Content-Type` 을 직접 넣으면 boundary 가 빠져 서버가 파트를 못 읽는다.
  `api/http.ts` 가 `FormData` 를 예외로 두는 이유다.
- bytea 는 행에 같이 붙는다. 메타 조회는 별도 프로젝션(`AttachmentRepository.findMeta`)을 써서 바이트를 읽지 않는다.

## 배포 전 점검

규칙 B 를 넣기 전에 저장된 값 중 정규형이 아닌 첨부 칸 값이 있으면, 그 파일은 사용처 판정에 안 잡혀 저장 뒤 묻기·서랍에서 지워질 수 있다. **배포 전에 운영 DB 에서 한 번 돌려 0행인지 본다.** 행이 나오면 그 칸을 소문자 정규형으로 고친 뒤 배포한다(값만 바꾸는 `jsonb_set`, 계정별로).

```sql
-- 이력서 첨부 칸 7개 중 비어 있지 않고 정규형(소문자 36자) UUID 가 아닌 값. 대문자·축약형·손으로 적은 글자가 여기 잡힌다.
select r.owner_id, r.id as resume_id, r.name, f.path, f.value
  from resumes r,
       lateral (select 'basic.portfolioFileId' as path, r.content->'basic'->>'portfolioFileId' as value
                union all
                select s.key || '[' || (e.ord - 1) || '].' || k, e.row->>k
                  from jsonb_each(r.content) s,
                       lateral jsonb_array_elements(case when jsonb_typeof(s.value) = 'array' then s.value else '[]' end)
                               with ordinality e(row, ord),
                       unnest(array['fileId', 'diplomaId', 'transcriptId']) k
                 where s.key in ('educations', 'trainings', 'activities', 'awards', 'certificates')
                   and jsonb_typeof(e.row) = 'object') f
 where coalesce(f.value, '') <> ''
   and f.value !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
```

워크트리 DB(V29)에서 돌려 0행을 확인했고, `resumes` 자리에 합성 행 하나를 넣은 같은 질의가 대문자·`1-2-3-4-5`·`not a uuid` 세 칸만 잡고 정규형과 빈 값은 건너뛰는 것을 확인했다(2026-11-03). **운영 DB 결과는 아직 보지 않았다.**
