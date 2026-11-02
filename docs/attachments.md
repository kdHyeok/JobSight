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

## 진입점

| 무엇 | 어디 |
|---|---|
| 스키마 | `V15__company_businesses_and_attachments.sql` |
| 도메인 | `attachment/{Attachment,AttachmentRepository,AttachmentService,AttachmentController}` — `ApiPaths.ATTACHMENTS` |
| 화면 | `AttachmentField.vue` — 첨부·교체·제거·미리보기 한 컴포넌트. 이력서 필드 `kind: 'file'` 이 이걸 그린다. `preloaded` 로 받은 메타가 있으면 메타를 부르지 않는다 |
| 이력서 응답의 메타 | `ResumeService.attachmentMetas` → `AttachmentRepository.findMetas`(id 목록 + 소유자, bytea 안 읽음). 첨부 칸 목록은 `ResumeContent.attachmentIds()` |
| 프런트 API | `api/attachments.ts` · 경로는 `routes.ts` 의 `API.attachments*` |
| 제출 기록의 첨부 | `V28__submissions_and_guides.sql` 의 `submission_attachments` · `SubmissionService.lockAttachments`(규칙 B) · `ApiErrors` 의 `ATTACHMENT_IN_USE` |
| 업로드 한계 | `application.yml` 의 `spring.servlet.multipart` · `frontend/nginx.conf` 의 `client_max_body_size` |

## API

```
POST   /api/attachments        multipart/form-data, part 이름 "file"  → {id, filename, contentType, size}
GET    /api/attachments/{id}          → 바이트 (inline)
GET    /api/attachments/{id}/meta     → {id, filename, contentType, size}
DELETE /api/attachments/{id}          화면은 부르지 않는다(함정 참고). 제출 기록이 가리키면 409 ATTACHMENT_IN_USE

GET    /api/resumes/{id}              → {..., attachments: {id → {id, filename, contentType, size}}}
```

이력서 응답의 `attachments` 는 새 경로가 아니라 응답 필드다. 편집기를 열 때 첨부 칸마다 `/meta` 를 부르던 직렬 단계를 없앤다 — `docs/resumes.md` 함정.
`/meta` 는 그대로 둔다. 응답에 빠진 id(남의 것·지워진 것)와 내보내기(`resume-export.ts`)가 쓴다.

MCP 도구는 없다. 바이너리는 JSON 도구 호출에 실을 것이 아니다. `resume_get` 응답에는 메타가 함께 실린다.

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

자동 검증: `AttachmentField.spec.ts`(교체·제거가 DELETE 를 보내지 않음, 받은 메타가 있으면 메타 GET 없음, 편집기 전체에서 메타 GET 없음) · `ResumeServiceTest`(첨부 칸 7개 수집, `[null]` 행이 있어도 상세·생성이 깨지지 않음) · `ResumeAttachmentsIntegrationTest`(PostgreSQL 에서 남의 첨부 메타가 빠짐) · `SubmissionIntegrationTest.confirmedSnapshotPinsAttachmentsAndAccountDeleteCascades`(제출 기록 첨부 삭제 409·파일 남음, 계정 삭제 cascade, 남의 근거 파일 400).

## 함정

- **업로드와 이력서 저장은 시점이 다르다.** 파일은 고르는 즉시 서버에 올라가고(그래야 미리보기가 된다),
  그 id 는 `저장` 을 눌러야 이력서 문서에 들어간다. 저장 없이 나가면 **주인 없는 첨부**가 남는다.
  교체·제거로 연결이 풀린 이전 파일도 남는다(아래).
  자동 청소는 없다 — 개인 도구에서 몇 개의 고아 파일보다 저장 흐름이 복잡해지는 쪽이 비싸다.
  `ponytail: 고아 첨부는 방치. 용량이 문제가 되면 정리 작업을 붙인다 — 기준은 "7일 이상, 같은 owner 의 어느 이력서도 참조하지 않음", JSONB 내부 질의가 생긴다(설계 1.4 T7·1.5).`
- **교체·제거는 이전 파일을 지우지 않는다.** 교체는 새 업로드이고, 제거는 이 이력서에서 연결만 푼다.
  같은 id 의 바이트를 덮어쓰지도 않는다. 복제한 이력서가 같은 id 를 가리키므로, 지우거나 덮어쓰면 원본 이력서의 첨부가 사라지거나 바뀐다.
  저장하지 않고 나간 경우에도 이력서는 이전 id 를 들고 있으니 지우면 안 된다. 전에는 화면이 이전 파일을 즉시 DELETE 했다 — `docs/decisions.md` 「첨부 교체·제거 때 이전 파일을 지우지 않는다」.
  `DELETE /api/attachments/{id}` 경로는 남아 있지만 화면은 부르지 않는다. `AttachmentField.spec.ts` 가 http 계층에서 DELETE 가 나가지 않는 것을 고정한다.
- **이력서 응답에 실리는 메타는 응답 전용이다.** 저장 요청에 넣지 않는다 — 웹 편집기는 보내지 않고, MCP 는 요청의 모르는 필드를 거부한다(`McpTools` 의 `FAIL_ON_UNKNOWN_PROPERTIES`). 응답에 없는 id 는 `AttachmentField` 가 `/meta` 로 직접 부르므로, 새로 올린 직후나 남의 id 가 적힌 칸도 화면은 전처럼 동작한다.
- **첨부 칸을 모을 때 `null` 행은 건너뛴다.** 섹션 배열 요소에는 `@NotNull` 이 없어 `{"trainings":[null]}` 이 검증을 통과해 그대로 저장되고, 예전에 MCP 로 저장된 문서에도 있을 수 있다.
  `ResumeContent.attachmentIds()` 는 모든 이력서 응답이 지나가므로, 여기서 행을 그대로 만지면 상세·생성·저장·복제·`resume_get` 이 전부 500 이 되고 그 이력서를 열 수 없다. `rows()` 가 `null` 행을 거른다.
- **제출 기록의 첨부 수집은 LINKED 와 접수 근거가 다르다.** 이력서 안 첨부는 지금 있는 것만 남긴다(예전에 지워진 id 가 문서에 남아 있어도 기록을 막지 않는다 — 6.9 규칙 B 의 "이미 저장된 값은 통과").
  접수 근거 파일은 모두 내 것이어야 하고 하나라도 없으면 400 `ATTACHMENT_NOT_FOUND` 다. 6.9 의 삭제 판정을 구현할 때 `UNION ALL SELECT … FROM submission_attachments WHERE attachment_id = :id` 를 더한다(인덱스 `submission_attachments_attachment_idx`).
- `fetch` 에 `FormData` 를 줄 때 `Content-Type` 을 직접 넣으면 boundary 가 빠져 서버가 파트를 못 읽는다.
  `api/http.ts` 가 `FormData` 를 예외로 두는 이유다.
- bytea 는 행에 같이 붙는다. 메타 조회는 별도 프로젝션(`AttachmentRepository.findMeta`)을 써서 바이트를 읽지 않는다.
