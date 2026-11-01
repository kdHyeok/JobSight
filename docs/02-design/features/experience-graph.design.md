# JobSight 경험-지원 관계 구조 최종 설계

작성 2026-11-02 · 상태: **설계, 미구현** (1.3 의 P2 만 적용) · 근거: 코드 분석 5갈래, 외부 조사, 3안 독립 설계와 심사, 반박 40건 처리. 1절은 같은 날 공개 도메인 실측으로 고쳤다.

아래에서 `B/` 는 `backend/src/main/java/com/jobsight/company/`, `M/` 은 `backend/src/main/resources/db/migration/`, `F/` 는 `frontend/src/components/` 를 줄여 쓴 것이다.

- **뼈대**: PostgreSQL 관계형에 관계 종류별 FK 조인 테이블을 둔다. 초안의 안 A를 유지한다.
- **반박을 받아 크게 바뀐 점**
  1. 경험 테이블에는 정체성만 둔다. 서술은 이력서 행에 남겨, 같은 내용을 두 곳에 두는 정본 이중화를 없앤다.
  2. 간선의 status·confidence와 제안 저장을 없앴다. 출처(`source`) 열 하나만 둔다.
  3. pgvector 대신 `real[]` 컬럼에 저장하고 Java에서 cosine을 계산한다. DB 이미지는 바꾸지 않는다.
  4. MCP 신규 도구를 12개에서 4개로 줄였다.
  5. "자소서를 어느 지원용으로 썼나"와 "의미 검색"을 경험 은행보다 앞 단계에 둔다.
  6. MCP 전용 동작은 컨트롤러가 아니라 `McpActions` 에 둔다.
- **수치의 성격**: SQL 횟수와 바이트는 코드를 읽고 낸 추정이다. 실측은 둘이다: e5 임베딩 지연(2026-09, `docs/experience-graph-search.md`)과 공개 도메인 왕복 지연(2026-11-02, 1.0절). 이 문서의 줄 근거는 이번에 직접 열어 본 것만 남겼다.

---

## 0. 결론

**진단** (1.0절 실측)
- 이력서 화면 지연의 대부분은 **네트워크**다. 서버 안 처리는 2ms 미만인데, 한국에서 보낸 요청이 Cloudflare LAX 엣지를 거쳐 서울 터널로 가서 요청 하나가 약 300ms다. 그래서 체감을 줄이는 손잡이는 DB가 아니라 **화면을 열 때 차례로 기다리는 요청 수**와 **진입 경로**다. DB·트랜잭션 개선(1.3·1.4)은 체감 속도가 아니라 정확성과 확장성 때문에 한다.
- AI가 경험을 빠뜨리거나 맞지 않는 자소서를 가져오는 원인 후보는 다섯 가지다.
  1. 쓰기 경로의 조용한 손실: 잠금 없는 행 수정, null이 삭제로 동작
  2. 자소서↔직무↔사건 관계가 없음
  3. MCP 도구가 화면 CRUD를 그대로 감쌈
  4. AI가 새 이력서를 만들 때 처음부터 일부만 고름
  5. 출처 없는 검색 결과

  저장 엔진은 원인 후보가 아니다.
- 그래서 지금의 PostgreSQL 17 하나 안에서 해결한다.

**가장 먼저 할 일**은 구조 변경이 아니라 **직렬 요청 줄이기와 손실 막기(1단계)** 다. 스키마는 바꾸지 않는다. 지연 측정은 끝났고(1.0), 0단계에 남은 측정은 SQL 수, MCP 호출 로그, 검색 골드셋이다.

**검토한 기술별 판단**

| 후보 | 판단 | 이유 | 이 설계에 가져오는 것 |
|---|---|---|---|
| 그래프 DB (Neo4j, Apache AGE) | 쓰지 않는다 | (1) 질의가 늘 계정 하나(노드 수백 개) 안이다. (2) 탐색이 최대 4조인의 고정 깊이라 SQL 조인으로 그대로 표현된다. (3) 운영 비용이 든다: 커스텀 DB 이미지, 확장 종속 백업·복원, 두 번째 질의 언어, Neo4j는 소유자 격리를 저장소 두 곳에서 지켜야 함 | 노드 + 타입 간선 모델링, 결과마다 경로 근거 `why[]` |
| NoSQL (MongoDB 등) | 쓰지 않는다 | 요구가 N:M 관계 중심이라 문서 모델과 반대 방향이다. 문서로 둘 곳(이력서 본문)은 이미 `resumes.content` JSONB다 | 이력서 본문 JSONB를 그대로 둔다 |
| LLM wiki | 정본으로 쓰지 않는다 | 파일 기반이라 계정 1000개의 소유자 격리와 트랜잭션이 없다. 웹 화면과 정본이 둘로 갈린다 | 빠짐없는 목차는 `experience_index` 가 맡는다. "답을 위키에 다시 저장"하는 대신 AI가 쓴 연결은 `AI` 출처로 남기고 사용자가 웹에서 확정한다 |
| pgvector | 지금은 쓰지 않는다 | 계정당 수백 행이고 벡터 인덱스도 쓰지 않을 거라면 확장이 필요 없다. 쓰면 AGE를 기각한 것과 같은 비용(커스텀 이미지, 확장 종속)을 진다 | `real[]` 컬럼 + Java 정확 cosine. 계정당 행이 수만 개를 넘으면 다시 본다 |

그래프 엔진을 다시 볼 조건: 4홉을 넘는 가변 길이 탐색이나, 계정 전체를 가로지르는 그래프 분석이 필요해질 때.

---

## 1. 이력서 조회 지연: 진단과 개선

### 1.0 실측 (2026-11-02)

이 PC와 사용자 브라우저 양쪽에서 `https://job.donyk.link/cdn-cgi/trace` 가 `loc=KR`, `colo=LAX` 였다. 서버의 터널 커넥터는 `icn01`·`icn05`·`icn06`(서울)에 붙어 있다.

| 구간 | 값 | 잰 방법 |
|---|---|---|
| 서버 안 앱 처리 | 0.4~1.8ms | 서버에서 `127.0.0.1:8088` 직결 curl (`login-options`, `index.html`, 미인증 `/api/resumes` 401) |
| LAX 엣지에 캐시된 정적 자산 | 약 157ms | 연결 재사용 curl, `cf-cache-status: HIT` |
| 동적 요청 (LAX → 서울 터널 → 서버) | 약 300~310ms | 연결 재사용 curl. 정적 `index.html` 도 같은 값(동적 취급) |
| ping 왕복 (LAX 엣지) | 평균 148ms | `ping job.donyk.link` |
| 프로토콜 | 브라우저 HTTP/2, br 압축, 해시 자산 `max-age=14400` | 응답 헤더, `/cdn-cgi/trace` |

읽는 법:
- 요청 하나가 약 300ms이고 그중 앱 몫은 1%도 안 된다. **차례로 기다리는 단계 하나 = 약 0.3초**다.
- HTTP/2 라 동시에 보낸 요청은 한 번 기다리는 것과 같다. 요청 개수가 아니라 **직렬 깊이**가 체감 속도다.
- 무거운 인증 요청(`/api/positions` 의 1+N SQL)의 서버 시간은 재지 못했다. 로그인 세션이 필요해서다. 1.2의 `$upstream_response_time` 로그를 넣으면 함께 잡힌다.

진입 경로(N1) 선택지:
- (A) 그대로 두고 직렬 단계를 줄인다. 1단계에서 한다.
- (B) 이 도메인만 Cloudflare 프록시를 끄고 서버로 직접 받는다. 터널을 버리고 공유기 443 개방이 필요하다. 서버 프록시는 이미 `donyk.link` 인증서로 TLS를 처리한다(`docs/nginx-deploy.md:48`). 대가는 집 IP 노출, DDoS 방어·엣지 캐시 상실, 동적 IP 갱신이다.
- (C) 상위 요금제. 커뮤니티 보고로는 Pro+Argo에서도 `colo` 가 바뀌지 않았고, 엔터프라이즈는 확인하지 못했다.
- MCP는 사정이 다르다. 클라우드 AI 클라이언트는 그 회사 서버에서 호출하므로 LAX 진입이 불리하지 않을 수 있다(미확인). 로컬 클라이언트(Claude Code 등)는 브라우저와 같다.

### 1.1 `/resumes?focus=<id>` 를 열 때 일어나는 일 (코드 기준, 1.0 의 단계당 약 0.3초를 곱해 읽는다)

```
[1] index.html → JS/CSS → (main.css:1 의 @import) Google Fonts → woff2      렌더 차단, 직렬
[2] GET /api/auth/me                                                        끝나야 보드가 마운트된다
[3] GET /api/resumes/{id} ∥ GET /api/positions                              F/ResumeBoard.vue:62 Promise.all
[4] GET /api/self-introductions?resumeId= ∥ GET /api/attachments/{id}/meta × A
```

2026-11-02: [4] 의 자소서 요청을 [3] 으로 옮겼다(`ResumeBoard.loadCurrent`, `ResumeBoard.spec.ts`). 첨부가 있는 이력서는 첨부 메타가 아직 [4] 에 남는다(P5).

- 이력서 자체 조회 `GET /api/resumes/{id}` 는 SQL 2회 수준이다: `findByIdAndOwnerId` 1회 + EAGER `positionIds` 1회(`B/resume/Resume.java:42-45`).
- SQL 대부분은 병렬로 나가는 `/api/positions` 에서 나온다. 원인은 EAGER 컬렉션의 1+N이다:
  - `Position` 의 `techStack`·`referenceIds` (`B/position/Position.java:71-79`)
  - `JobPosting.steps` (`B/posting/JobPosting.java:66-70`)
  - `Company` 의 `industries`·`businesses` (`B/company/Company.java:42-51`)
- 같은 머신의 DB에서 쿼리 수십~백여 회는 수십 ms 정도로 추정한다. 체감 지연과 같은 크기인지는 모른다.

원인이 아니라고 보는 것:
- **트랜잭션 경계**: OSIV off(`backend/src/main/resources/application.yml:12`)다.
- **저장 모델**: 이력서 1건을 JSONB 한 행으로 읽는 것은 이미 가장 싼 구조다.

### 1.2 0단계: 처방 전에 잴 것

| 측정 | 방법 | 이것으로 가르는 것 |
|---|---|---|
| 백엔드 시간과 전체 시간 | **가벼운 요청은 완료(1.0).** 인증 요청의 서버 시간은 `frontend/nginx.conf` `log_format` 에 `$request_time $upstream_response_time` 를 넣어 배포 서버에서 기록한다 | 네트워크가 지배적임을 확인. 남은 것은 무거운 요청의 서버 몫 |
| 요청당 SQL 수 | 로컬에서 `hibernate.generate_statistics=true` 를 잠깐 켠다 | P1 효과의 기준선 |
| 브라우저 waterfall | 공개 도메인에서 DevTools HAR 저장 | P2(직렬 단계), 폰트 렌더 차단 |
| MCP 호출 로그 | `McpController` 에 도구 이름, 대상 id, 소요 ms를 INFO 한 줄로 남긴다. 본문은 남기지 않는다 | "프로젝트 누락"의 원인이 무엇인지: 병렬 행 추가, 통째 교체, 생성 시 선택 누락 |
| 검색 골드셋 | 사용자가 실제 지원 건으로 질의 10~20개와 정답(쓸 만한 자소서·경험)을 손으로 만든다. 지금의 `self_intro_list` 로 기준선을 잰다 | 단계별 개선 여부 (4.5) |

### 1.3 개선 항목 (1.0 실측으로 우선순위를 다시 정했다)

| # | 원인 (근거) | 처방 | 예상 효과(추정) | 단계 |
|---|---|---|---|---|
| N1 | 한국 요청이 Cloudflare LAX 엣지로 들어간다(1.0) | 1.0의 (A)·(B)·(C) 중 선택 | (B)면 요청당 약 300ms → 국내 왕복 | 사용자 결정 |
| P1 | 배치 페치 설정이 없다(`application.yml:10-18`). 그래서 위 EAGER 컬렉션들에서 1+N이 난다 | `spring.jpa.properties.hibernate.default_batch_fetch_size: 100` | 직무 목록 SQL → 10회 안팎 | 1 |
| P2 | 자소서 요청이 편집기가 그려진 뒤에야 나갔다(`F/ResumeQuestions.vue` onMounted) | **적용함(2026-11-02)**: 이력서·직무와 같은 `Promise.all` 로 옮기고 `questions` prop 으로 내렸다. 실패하면 `null` 을 넘겨 문항 칸이 직접 다시 부른다. `Promise.all` 을 풀어 직무를 배경으로 돌리는 것은 하지 않는다 — HTTP/2 병렬이라 이득이 서버 처리 시간 차이(ms)뿐이다 | 직렬 단계 하나, 약 0.3초 | 완료 |
| P3 | 자소서: 소유자 전체를 읽고 자바에서 거른다(`B/selfintro/SelfIntroductionService.java:41-45`). LAZY `resumeIds` 가 행마다 SQL을 낸다(`B/selfintro/SelfIntroduction.java:27-31`). 인덱스 `idx_self_introduction_resumes_resume`(`M/V20__self_introduction_resumes.sql:10-11`)를 쓰지 않는다 | `join s.resumeIds r where s.ownerId=:o and r=:rid` 조인 쿼리 | SQL 1+S → 2~3 | 2 |
| P4 | 이력서 목록이 content JSONB까지 읽는다(`B/resume/ResumeService.java:52-56`) | JPQL 생성자 projection | JSONB 전송 0 | 2 |
| P5 | 첨부 메타를 편집기가 그려진 뒤 필드마다 GET한다(`F/AttachmentField.vue:23-33`). 첨부가 있는 이력서에서는 **남은 직렬 단계**다 | 이력서 상세 응답에 `attachments:{id→meta}` 동봉. 새 경로가 아니라 응답 필드라 경로 표는 그대로다 | 직렬 단계 하나(약 0.3초), HTTP A건 → 0 | **1** |
| P6 | 편집기가 쓰는 직무 값은 이름·기업·공고·`updatedAt` 정도(`F/ResumeEditor.vue:88-95`)인데 `PositionResponse` 전체를 받는다 | `GET /api/positions?view=summary`. 쿼리 파라미터라 경로 표는 그대로다. `position_list` MCP 스키마에 `view` 가 노출되는지 확인하고, 노출된다면 설명을 단다 | 응답 크기 감소 | 2 |
| P7 | 소유 확인 때문에 엔티티 전체를 로드한다(`ResumeService.java:204-213`) | id 목록 projection 또는 count | 저장 SQL 감소 | 2 |
| P8 | 컬렉션을 새 Set으로 갈아 끼워 저장마다 전체 DELETE와 INSERT가 나간다(`Resume.java:69-70`, `Position.java:116,121`, `Company.java:108-109`) | `clear()+addAll()` 로 바꾼다(`SelfIntroduction.java:54-56` 방식) | 바뀐 부분만 갱신 | 2 |
| P9 | 저장소 `frontend/nginx.conf` 에는 gzip과 캐시 헤더가 없다. 다만 공개 도메인은 Cloudflare가 br 압축과 엣지 캐시(`cf-cache-status: HIT`, `max-age=14400`)를 이미 한다(1.0). 폰트는 CSS `@import` 로 직렬 로딩한다(`frontend/src/styles/main.css:1`) | 해시 자산에 `immutable`·1년 캐시, 폰트는 `<link rel=preconnect>` + `<link>`. nginx gzip은 N1-(B)로 갈 때만 의미가 있다 | 재방문 자산 왕복 0, 폰트 체인 단축 | 2 |
| P10 | MCP 요청마다 `findByToken(…, ACCESS_TOKEN)` 를 호출한다(`B/mcp/McpOAuthConfig.java:286`). `access_token_value`(`M/V21__persist_mcp_oauth_tokens.sql:13`)에 인덱스가 없다 | 먼저 행 수와 `EXPLAIN` 을 본다. 만료 행이 쌓였으면 정리 작업을 먼저 검토하고, 그다음 hash 인덱스(1.5) | seq scan 제거 | 2 |
| P11 | 키 입력마다 stringify를 2회 한다(`F/ResumeEditor.vue:53-57`). `rank()` 의 df 계산이 O(N²)이다(`SelfIntroductionService.java:82-91`) | 원본 쪽은 computed로 한 번만 계산하고, df는 루프 밖으로 뺀다 | 타이핑 지연과 CPU 감소 | 2 |

### 1.4 트랜잭션과 동시성 (지연보다 우선)

| # | 문제 (근거) | 처방 | 단계 |
|---|---|---|---|
| T1 | **이력서 lost update.** `mutateRows` 는 잠금 없이 읽고 문서 전체를 저장한다(`ResumeService.java:148-157,195-198`). `@Version` 도 없다. 그래서 병렬 `resume_row_add` 두 건 중 한 행이 사라질 수 있다. 웹 저장이 AI가 추가한 행을 덮어쓸 수도 있다 | `ResumeRepository.findOwnedForUpdate` 와 `setLocalLockTimeout` 를 추가한다. `B/posting/JobPostingRepository.java:25-33` 패턴을 그대로 옮긴다. `update`·`updateBasic`·`mutateRows` 와 뒤 단계의 링커·가져오기·병합이 모두 이 조회를 쓴다 | 1 |
| T2 | 화면이 예전에 읽어 둔 문서로 덮어쓴다 | `resumes.version` 과 `@Version` 을 둔다. 요청에 `expectedVersion` 이 **있을 때만** 서비스가 비교해 다르면 409 `RESUME_STALE` 을 준다. 웹 편집기는 항상 보내고, MCP에서는 선택이다(행 잠금이 서버 안 lost update를 이미 막는다). `ObjectOptimisticLockingFailureException` 은 `GlobalExceptionHandler` 와 `McpController` 양쪽에서 409로 바꾼다. 지금은 `RuntimeException` → `INTERNAL_ERROR` 로 뭉갠다(`B/mcp/McpController.java:77-78`) | 2 |
| T3 | **null이 삭제로 동작한다.** content가 null이면 빈 문서가 된다(`ResumeService.java:216-219`). 빠진 섹션은 `[]` 가 된다(`B/resume/ResumeContent.java:34-40`). positionIds가 null이면 직무 연결이 끊긴다(`ResumeService.java:72-73,204-206`). `syncPositions` 는 null을 "공고 제목 직무 하나"로 바꾸고, 목록에 없는 직무를 지운다(`B/posting/JobPostingService.java:151-156` 이하) | **`resume_*`·`posting_update` 만** null=유지, `[]`=비움으로 바꾼다. 그 밖의 update 도구는 지금처럼 전체 교체다(5.1). `syncPositions` 는 id 없는 항목을 이름으로 기존 직무와 맞춘다 | 이력서 1, 공고 2 |
| T4 | `resume_row_update` 가 행을 통째로 교체한다. 배열만 `carryArrays` 로 옮기고, 빠진 문자열 필드는 지운다(`ResumeService.java:113-146`) | 행 갱신도 병합한다(null=유지, `""`=비움). 그러면 `carryArrays` 는 필요 없다 | 2 |
| T5 | 행 도구가 이력서 전체를 돌려주고(`ResumeService.java:156`), MCP는 그것을 text와 structuredContent에 두 번 싣는다(`McpController.java:65-66`) | 반환을 `{version, section, count, index, 행 머리}` 로 줄인다 | 2 |
| T6 | 행 추가가 섹션 상한 검증을 거치지 않는다(`mutateRows` 는 `normalized()` 만 호출한다) | `mutateRows` 끝에서 문서 전체를 Bean Validation으로 검증한다 | 2 |
| T7 | **첨부 손실.** 교체나 비우기를 하면 이전 첨부를 즉시 DELETE한다(`F/AttachmentField.vue:51-52,65`). 그런데 복제 이력서는 같은 id를 공유한다(같은 파일 51행 주석, `docs/attachments.md:71`). 그래서 복제본에서 교체하면 원본의 파일이 사라지고, 저장하지 않고 나가도 이미 지워져 있다 | 즉시 삭제만 없앤다. 고아 첨부 정리 작업은 **만들지 않는다.** 기존 문서가 고아 첨부를 함정으로 받아들이고 있고, 용량이 실제 문제가 될 때 추가한다. 그때 기준은 "7일 이상, 같은 owner의 어느 이력서도 참조하지 않음"이고, JSONB 내부 질의가 생긴다는 점을 1.5에 반영한다 | 1 |
| T8 | 트랜잭션 안에서 임베딩 서비스를 부르면 DB 연결 획득 3초 제한(`docs/backend.md:18`)에 걸린다 | 쓰기는 `embedding_dirty` 만 표시하고, 계산은 트랜잭션 밖 스위퍼가 한다(4.4) | 4 |
| T9 | 여러 이력서를 잠그는 작업(가져오기, 병합)에서 교착이 생길 수 있다 | id 오름차순으로 잠근다 | 5 |

그대로 두는 것: GET 공고 목록이 쓰기 트랜잭션인 것(자동 보관, `docs/decisions.md:85`). Hikari 기본 풀.

### 1.5 인덱스 (V22)

```sql
-- V22__resume_version_and_indexes.sql
ALTER TABLE resumes ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- 타입을 지정한 조회(ACCESS_TOKEN, REFRESH_TOKEN)만 이 인덱스를 탄다. 타입 없는 revoke·introspect 는
-- 인덱스 없는 컬럼까지 OR 로 묶을 수 있어 seq scan 이 남을 수 있다. 빈도가 낮아 허용한다. 적용 후 EXPLAIN 으로 확인.
CREATE INDEX oauth2_authorization_access_token_idx  ON oauth2_authorization USING hash (access_token_value);
CREATE INDEX oauth2_authorization_refresh_token_idx ON oauth2_authorization USING hash (refresh_token_value);

-- ON DELETE 의 참조 쪽
CREATE INDEX job_postings_target_position_idx
    ON job_postings (target_position_id) WHERE target_position_id IS NOT NULL;
CREATE INDEX reference_items_related_position_idx
    ON reference_items (related_position_id) WHERE related_position_id IS NOT NULL;
CREATE INDEX position_references_reference_idx ON position_references (reference_id);
```

- **`CONCURRENTLY` 를 쓰지 않는 이유**: 테이블이 작아 잠깐의 잠금으로 충분하다. CONCURRENTLY를 쓰려면 마이그레이션 파일을 분리해야 하는데 얻는 것이 없다.
- **만들지 않는 인덱스**:
  - JSONB GIN: JSONB 내부를 질의하는 코드가 없다. 고아 첨부 정리를 나중에 넣으면 다시 판단한다.
  - 벡터 인덱스: 계정당 수백 행이라 필요 없다.
- `docs/experience-graph-search.md` 의 V22 stories 예약은 이 설계로 대체하고 푼다.

### 1.6 기대 효과와 회귀 방지

- 기대 효과(추정): 이력서 화면 1회 SQL 백여 회 → 10회 안팎, API 요청 4+A건 → 3~4건. 실제 체감 효과는 0단계와 같은 방법으로 다시 잰다.
- 회귀 방지: Testcontainers와 `Statistics#getPrepareStatementCount()` 로 요청 1회의 SQL 수 상한을 단언한다.
- 동시성 테스트: 같은 이력서에 `addRow(projects)` 2건을 병렬로 보내면 끝난 뒤 2행이어야 한다.

---

## 2. 경험-지원 관계 데이터 모델

### 2.1 사용자 개념과 저장 위치

| 사용자 개념 | 저장 위치 | 비고 |
|---|---|---|
| 경험 (정체성) | `experiences` (신규): kind, title, 시작 연월, 메모 | **서술은 두지 않는다.** 서술의 정본은 이력서 행이다(6.1) |
| 경험 종류 | `experiences.kind` CHECK | PROJECT→`projects`, ACTIVITY→`activities`, CAREER→`experiences`(제목은 `company`), TRAINING→`trainings`, OTHER는 이력서에 렌더하지 않는다 |
| 경험 서술 (역할, 기간, 기술, 설명, 성과) | 기존 `resumes.content` 의 행 + 행의 `experienceId` | 검색은 "가장 최근에 수정한 연결 이력서의 행"을 쓴다 |
| 사건 | `episodes` (신규, STAR) | 검색, 임베딩, 자소서 연결의 단위 |
| 결과 여러 개 | `episodes.outcomes TEXT[]` | 아무것도 결과를 가리키지 않으므로 별도 테이블을 두지 않는다. 경험 전체의 성과는 이력서 행의 `outcome` 에 있다 |
| 역량·문항 유형 | `competencies` (시스템 고정 코드 18개) | 사건 태그와 문항 유형이 같은 어휘를 쓴다 |
| 기술·도메인 키워드 | `keywords` (계정별) + `experience_keywords` | 정규화는 Java 한 곳에서 한다 |
| 회사 도메인 | 기존 `company_industries` | |
| 직무 요구 | 기존 `position_tech_stack` + `positions.required_skills/preferred_skills` 본문 | `position_requirements` 테이블은 두지 않는다(2.6) |
| "이 답변을 어디에 냈나" | `self_introduction_positions` (신규) | **직접 출처** |
| "이 답변에 어떤 사건을 썼나" | `self_introduction_episodes` (신규) | |
| 문항 유형 | `self_introduction_competencies` (신규) | 저장하지 않은 문항은 질의 때 정규식으로 추정한다 |

### 2.2 관계도

```
[시스템]  competencies (18개, search_mode)

[계정, 모든 행에 owner_id]
self_introductions (기존)
 ├─N:M─ resumes        self_introduction_resumes      (기존, 간접 출처)
 ├─N:M─ positions      self_introduction_positions    written_for (직접 출처)     ← 3단계
 ├─N:M─ competencies   self_introduction_competencies 문항 유형                   ← 3단계
 └─N:M─ episodes       self_introduction_episodes     uses                       ← 5단계

experiences (PROJECT|ACTIVITY|CAREER|TRAINING|OTHER, 정체성만)                     ← 5단계
 ├─1:N─ episodes (STAR, outcomes[]) ──N:M── competencies   episode_competencies
 ├─N:M─ keywords (SKILL|DOMAIN)                             experience_keywords
 └─N:M─ resumes                                             resume_experiences ← JSON 행 experienceId 에서 파생

(기존) resumes ─N:M─ positions ─N:1─ job_postings ─N:1─ companies ─1:N─ company_industries
```

모든 간선에는 `source`(USER|AI) 열 하나만 둔다.
- 제안·거절 상태, confidence, 저장된 자동 제안은 없다.
- 제안은 질의할 때 계산해서 보여 주기만 한다(3절).
- 다형 간선 `links` 는 쓰지 않는다. FK를 걸 수 없어 고아 간선이 남고, `docs/decisions.md:50` 의 FK 원칙과 충돌한다.

### 2.3 카디널리티와 삭제

| 관계 | 카디널리티 | 삭제 시 |
|---|---|---|
| 경험 → 사건 | 1:N (0개 허용) | 경험을 지우면 사건도 CASCADE |
| 경험 ↔ 키워드 | N:M | 양쪽 CASCADE |
| 사건 ↔ 역량 | N:M, 사건당 3개 이하 | CASCADE |
| 이력서 ↔ 경험 | N:M (한 이력서 안에서 한 경험은 한 행) | 이력서를 지우면 연결만 사라진다. 이력서가 쓰는 경험을 지우려 하면 409 `EXPERIENCE_IN_USE` 와 이력서 이름 목록 |
| 자소서 ↔ 직무 / 사건 / 문항 유형 | N:M (각 10 / 10 / 3 이하) | 양쪽 CASCADE. 연결된 사건을 지우려 하면 `detach:true` 없이는 409 `EPISODE_IN_USE` |

### 2.4 소유자 격리

1. 새 테이블은 모두 `owner_id NOT NULL` 을 직접 가진다(`docs/job-postings.md:25` 규칙).
2. 간선은 양쪽 끝을 모두 `(대상_id, owner_id)` 복합 FK로 건다. 참조 대상 기존 테이블(`resumes`, `positions`, `self_introductions`)에 `UNIQUE (id, owner_id)` 를 둔다. 다른 계정의 노드를 잇는 행은 DB가 거부하고, 서비스는 이를 404로 바꾼다.
3. 요청 경로의 리포지토리는 `…AndOwnerId` 형태이거나 쿼리 안에 `owner_id = :o` 가 있어야 한다. 검색 SQL은 조인하는 모든 테이블에 owner 조건을 단다.
4. 연결할 id 묶음은 `count(*) … where id = any(:ids) and owner_id = :o` 한 번으로 확인한다. 개수가 모자라면 404다.
5. `competencies` 는 시스템 어휘라 사용자 데이터 공유 금지(`docs/decisions.md:117`)와 충돌하지 않는다.
6. **시스템 배치 예외 (D13)**: 임베딩 스위퍼만 owner 조건 없이 dirty 행을 읽고 쓴다.
   - 리포지토리는 별도 패키지·이름(`embedding/sweep/SystemEmbeddingSweepRepository`)으로 분리해 요청 경로에서 주입하지 못하게 한다.
   - 대상은 dirty 플래그와 임베딩 컬럼뿐이고, 읽은 텍스트는 embedder로만 보낸다.
7. 관리자 화면에는 경험 관련 숫자를 추가하지 않는다. 필요해지면 `UserDataCountResponse` 에 개수만 추가한다(`docs/decisions.md:171`).

### 2.5 DDL

번호는 병합 시점의 다음 번호로 정한다. 새 파일만 추가한다.

```sql
-- V23__self_intro_relations.sql  (3단계: written_for 와 문항 유형)
CREATE TABLE competencies (
    code          VARCHAR(40) PRIMARY KEY,
    name          VARCHAR(60) NOT NULL,
    question_only BOOLEAN     NOT NULL DEFAULT false,   -- true: 문항 유형으로만 쓴다
    search_mode   VARCHAR(10) NOT NULL CHECK (search_mode IN ('COMPETENCY','FIT','SEMANTIC')),
    sort_order    INT         NOT NULL
);
INSERT INTO competencies VALUES
 ('COLLABORATION','협업·팀워크',false,'COMPETENCY',10),     ('COMMUNICATION','의사소통·설득',false,'COMPETENCY',20),
 ('PROBLEM_SOLVING','문제 해결',false,'COMPETENCY',30),     ('CONFLICT','갈등 조정',false,'COMPETENCY',40),
 ('LEADERSHIP','리더십·주도성',false,'COMPETENCY',50),      ('CHALLENGE','도전·목표 달성',false,'COMPETENCY',60),
 ('FAILURE','실패와 극복',false,'COMPETENCY',70),           ('LEARNING','학습·자기개발',false,'COMPETENCY',80),
 ('TECH_EXPERTISE','기술 전문성',false,'COMPETENCY',90),    ('DATA_ANALYSIS','데이터·수리 분석',false,'COMPETENCY',100),
 ('IMPROVEMENT','개선·창의',false,'COMPETENCY',110),        ('CUSTOMER','고객·사용자 지향',false,'COMPETENCY',120),
 ('ETHICS','원칙·직업윤리',false,'COMPETENCY',130),
 ('MOTIVATION','지원동기',true,'FIT',200),                  ('ASPIRATION','입사 후 포부',true,'FIT',210),
 ('JOB_FIT','직무 이해·적합성',true,'FIT',220),             ('GROWTH','성장과정·가치관',true,'SEMANTIC',230),
 ('PERSONALITY','성격 장단점',true,'SEMANTIC',240);
-- Java enum Competency 와 같아야 한다. 기동 시 대조하고 다르면 기동 실패.

ALTER TABLE positions          ADD CONSTRAINT positions_id_owner_key          UNIQUE (id, owner_id);
ALTER TABLE self_introductions ADD CONSTRAINT self_introductions_id_owner_key UNIQUE (id, owner_id);

CREATE TABLE self_introduction_positions (         -- written_for
    owner_id             UUID       NOT NULL,
    self_introduction_id UUID       NOT NULL,
    position_id          UUID       NOT NULL,
    source               VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, position_id),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (position_id, owner_id)          REFERENCES positions (id, owner_id)          ON DELETE CASCADE
);
CREATE INDEX self_introduction_positions_position_idx ON self_introduction_positions (position_id);

CREATE TABLE self_introduction_competencies (      -- 문항 유형
    owner_id             UUID        NOT NULL,
    self_introduction_id UUID        NOT NULL,
    competency_code      VARCHAR(40) NOT NULL REFERENCES competencies (code),
    source               VARCHAR(4)  NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, competency_code),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE
);
```

```sql
-- V24__self_intro_embeddings.sql  (4단계: 자소서 의미 검색. DB 확장 없음)
ALTER TABLE self_introductions ADD COLUMN embedding_dirty BOOLEAN NOT NULL DEFAULT true;  -- 기존 행 전부 적재 대상
CREATE INDEX self_introductions_embedding_dirty_idx ON self_introductions (updated_at) WHERE embedding_dirty;

CREATE TABLE self_introduction_chunks (
    owner_id             UUID        NOT NULL,
    self_introduction_id UUID        NOT NULL,
    part                 VARCHAR(8)  NOT NULL CHECK (part IN ('QUESTION','ANSWER')),
    chunk_no             INT         NOT NULL,
    text_hash            VARCHAR(64) NOT NULL,      -- 바뀐 조각만 다시 계산 (docs/decisions.md:203 의 해시 비교)
    embedding            REAL[]      NOT NULL,      -- 384, 정규화된 벡터
    PRIMARY KEY (self_introduction_id, part, chunk_no),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX self_introduction_chunks_owner_idx ON self_introduction_chunks (owner_id);
```

```sql
-- V25__experiences.sql  (5단계: 경험 은행)
CREATE TABLE experiences (
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    kind            VARCHAR(10)  NOT NULL CHECK (kind IN ('PROJECT','ACTIVITY','CAREER','TRAINING','OTHER')),
    title           VARCHAR(200) NOT NULL,          -- 경력은 회사명
    title_key       VARCHAR(200) NOT NULL,          -- Java KeyNormalizer 가 채운다(생성 컬럼 아님)
    start_ym        VARCHAR(200),                   -- 정체성 구분용. 사용자가 쓴 문자열 그대로
    memo            VARCHAR(2000),                  -- 연결 행이 없을 때(OTHER 등)만 검색 텍스트로 쓴다
    version         BIGINT       NOT NULL DEFAULT 0,
    embedding_dirty BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (id, owner_id)
);
CREATE INDEX experiences_owner_key_idx ON experiences (owner_id, kind, title_key);
CREATE INDEX experiences_embedding_dirty_idx ON experiences (updated_at) WHERE embedding_dirty;

CREATE TABLE experience_chunks (                    -- 연결 행 텍스트를 480토큰 이하로 나눈 조각, max-sim
    owner_id      UUID        NOT NULL,
    experience_id UUID        NOT NULL,
    chunk_no      INT         NOT NULL,
    text_hash     VARCHAR(64) NOT NULL,
    embedding     REAL[]      NOT NULL,
    PRIMARY KEY (experience_id, chunk_no),
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX experience_chunks_owner_idx ON experience_chunks (owner_id);

CREATE TABLE episodes (
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL,
    experience_id   UUID         NOT NULL,
    title           VARCHAR(200) NOT NULL,
    situation TEXT, task TEXT, action TEXT, result TEXT,
    outcomes        TEXT[]       NOT NULL DEFAULT '{}',
    -- STAR 4필드 + outcomes 합계 900자 상한은 서비스에서 검사한다(1200자가 512토큰에서 잘린 실측, docs/experience-graph-search.md:150).
    sort_order      INT          NOT NULL DEFAULT 0,
    version         BIGINT       NOT NULL DEFAULT 0,
    text_hash       VARCHAR(64),
    embedding       REAL[],
    embedding_dirty BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (id, owner_id),
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX episodes_experience_idx ON episodes (experience_id, sort_order);
CREATE INDEX episodes_owner_idx      ON episodes (owner_id);
CREATE INDEX episodes_embedding_dirty_idx ON episodes (updated_at) WHERE embedding_dirty;

CREATE TABLE keywords (
    id       UUID PRIMARY KEY,
    owner_id UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    kind     VARCHAR(6)   NOT NULL CHECK (kind IN ('SKILL','DOMAIN')),
    name     VARCHAR(100) NOT NULL,
    norm_key VARCHAR(100) NOT NULL,                 -- Java KeyNormalizer + 별칭 시드 사전 적용 결과
    UNIQUE (owner_id, kind, norm_key),
    UNIQUE (id, owner_id)
);

CREATE TABLE experience_keywords (
    owner_id      UUID       NOT NULL,
    experience_id UUID       NOT NULL,
    keyword_id    UUID       NOT NULL,
    source        VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (experience_id, keyword_id),
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (keyword_id, owner_id)    REFERENCES keywords (id, owner_id)    ON DELETE CASCADE
);
CREATE INDEX experience_keywords_keyword_idx ON experience_keywords (keyword_id);

CREATE TABLE episode_competencies (
    owner_id        UUID        NOT NULL,
    episode_id      UUID        NOT NULL,
    competency_code VARCHAR(40) NOT NULL REFERENCES competencies (code),
    source          VARCHAR(4)  NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (episode_id, competency_code),
    FOREIGN KEY (episode_id, owner_id) REFERENCES episodes (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX episode_competencies_owner_code_idx ON episode_competencies (owner_id, competency_code);

ALTER TABLE resumes ADD CONSTRAINT resumes_id_owner_key UNIQUE (id, owner_id);
CREATE TABLE resume_experiences (                   -- JSON 행 experienceId 에서 파생. 손으로 쓰지 않는다
    owner_id      UUID NOT NULL,
    resume_id     UUID NOT NULL,
    experience_id UUID NOT NULL,
    PRIMARY KEY (resume_id, experience_id),
    FOREIGN KEY (resume_id, owner_id)     REFERENCES resumes (id, owner_id)     ON DELETE CASCADE,
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX resume_experiences_experience_idx ON resume_experiences (experience_id);

CREATE TABLE self_introduction_episodes (           -- uses
    owner_id             UUID       NOT NULL,
    self_introduction_id UUID       NOT NULL,
    episode_id           UUID       NOT NULL,
    source               VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, episode_id),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (episode_id, owner_id)           REFERENCES episodes (id, owner_id)           ON DELETE CASCADE
);
CREATE INDEX self_introduction_episodes_episode_idx ON self_introduction_episodes (episode_id);
```

엔티티 규칙과 함정:
- 새 엔티티에는 EAGER 컬렉션을 두지 않는다. `experiences`·`episodes` 에는 `@Version` 을 둔다.
- `ExperienceKind`, `Competency`, `LinkSource` 는 Java enum으로 둔다. MCP 입력 스키마에 enum으로 나가므로 AI가 없는 값을 지어낼 수 없다.
- 입력 DTO는 평평한 record와 enum으로 만든다(`docs/mcp-plugin.md:137` 의 스키마 생성기 제약).
- `ddl-auto: validate`(`application.yml:14`)에서 `REAL[]` 를 `float[]` 로 매핑하려면 `@JdbcTypeCode(SqlTypes.ARRAY)` 가 필요할 것으로 본다(미검증). 안 되면 `bytea` 로 바꾸고 Java에서 변환한다. 5단계 첫 커밋에서 확인한다.
- `title_key`·`norm_key` 는 DB 함수나 생성 컬럼 대신 Java `KeyNormalizer` 한 곳에서 계산해 일반 컬럼에 저장한다. 별칭 시드 사전(`k8s→kubernetes`, `js→javascript` 등)도 이 클래스에 둔다.

### 2.6 지금 만들지 않는 것

| 항목 | 이유 | 추가 시점 |
|---|---|---|
| `position_requirements`(필수/우대 태그) | `position_tech_stack` 과 요구·우대 본문(`Position.java:57,60`)으로 v1 신호를 만든다 | 골드셋에서 REQUIREMENT 부족이 확인될 때 |
| `outcomes` 테이블 | 결과를 가리키는 관계가 없다 | 결과 단위 연결이 필요해질 때 |
| 간선 status·confidence, 저장된 자동 제안, REJECTED | 1인 규모에 검토 UI까지 붙어 과하다. 제안은 질의 때 계산한다 | 저장된 제안이 실제로 필요해질 때 마이그레이션으로 열 추가 |
| `applications` 테이블 | "어디에 냈나"는 `resume_positions`·`self_introduction_positions` 로 표현된다 | 제출 버전 기록이 필요해질 때(9.2) |
| 사건 단위 기술 태그 | 사건은 경험의 기술 태그를 물려받는다 | 필요가 보일 때 |
| 고아 첨부 정리 작업 | 1.4 T7 | 용량이 문제가 될 때 |
| `job_postings.target_position_id` 정리 | 쓰지도 지우지도 않는다(D9) | — |

---

## 3. 관계를 누가 어떻게 만드는가

| 관계 | 사용자 (웹) | AI (MCP) | 질의 때 계산하는 후보 (저장 안 함) |
|---|---|---|---|
| 이력서 행 → 경험 | "경험에서 불러오기", "경험으로 등록", "다른 경험에 연결" | `resume_add_experiences`, 행 도구의 `experienceId` | 저장할 때 링커가 연결한다. (kind, title_key)가 정확히 1건 일치하고 시작 연월 숫자가 충돌하지 않을 때만 연결한다(6.2). 파생 관계라 출처가 없다 |
| 경험 → 기술 | 자동완성 칩 | `experience_save(skills)` | 웹 "경험 가져오기"를 하면 이력서 `techStack` 원문을 `, / · \|` 기준으로 나눈다(USER) |
| 경험 → 도메인 | 칩(선택) | `experience_save(domains)` | **파생**: 경험이 실린 이력서 → 직무 → 회사 업종, 경력 행의 회사가 기업 탭에 있으면 그 회사 업종. trust "추정" |
| 사건 → 역량 | 칩 | `experience_save(episodes[].competencies)` | 5단계 이후: 사건 임베딩과 역량 설명 문장의 최근접 상위 2개를 화면에 "추천"으로만 보여 준다 |
| 자소서 → 직무 (written_for) | 편집기에서 직무 선택. 출처가 없는 답변에는 "어느 지원용?" 후보 칩을 띄운다 | `self_intro_create/update(positionIds)` | 연결 이력서들의 직무 **전부**를 후보로 보여 준다. 개수 제한은 없고, 사용자가 클릭 한 번으로 고른다 |
| 자소서 → 사건 (uses) | "쓴 사건" 선택 | `self_intro_create/update(episodeIds)` | 5단계: 답변 조각과 사건의 cosine 상위 후보 |
| 자소서 → 문항 유형 | 칩 | `questionTypes` | 정규식 상위 2개를 신뢰도와 함께 계산한다(4.1) |

규칙:
1. **서버는 외부 LLM을 부르지 않는다.** 추출은 사용자가 쓰는 MCP 클라이언트가 한다.
2. **출처는 요청 주체가 정한다.** 웹 세션 요청은 `USER`, MCP 토큰 요청(`B/mcp/McpPrincipal.java` 주체)은 항상 `AI` 다. `confirm` 같은 인자는 두지 않는다. 서버가 검증할 수 없기 때문이다.
3. **확정은 웹에서만 한다.**
   - 웹은 AI 간선을 "AI 기록" 칩으로 보여 주고, ✓를 누르면 `USER` 로 바꾸고 ✗를 누르면 삭제한다. 경험 목록에는 "AI 기록 n건" 배지를 단다.
   - 제안은 저장하지 않으므로 거절 기억이 필요 없다.
4. **검색은 출처에 따라 신호를 다르게 쓴다.** 타사 자료가 섞이는 것을 막는 핵심 규칙이다.
   - **하드 필터**(회사·직무 필터, `kinds`, `competencies`)는 `USER` 간선만 쓴다.
   - **PAST_USE**는 `USER` 를 1배, `AI` 를 0.5배로 반영하고 `why` 에 "AI 기록"으로 표시한다. AI가 잘못 남긴 연결이 사람이 확정한 것과 같은 무게로 증폭되지 않게 하려는 것이다.
   - 태그 신호(REQUIREMENT, DOMAIN, COMPETENCY)는 `AI` 와 "추정"을 0.5배로 반영한다.
5. **`trust` 문자열**은 `"확정"`(USER), `"AI 기록"`, `"추정"`(파생·계산) 셋 중 하나다. 웹과 MCP가 같은 값을 쓴다.

---

## 4. 검색 설계

### 4.1 질의 모드

| 입력 | 쓰는 신호 |
|---|---|
| `positionId` / `postingId` (FIT) | REQUIREMENT, DOMAIN, PAST_USE, DENSE·LEXICAL(직무 요구·우대 본문을 질의로) |
| `question` | 먼저 문항 유형을 정한다. 순서는 입력값 → 저장된 USER 태그 → 정규식 → (4단계 이후) 가장 가까운 과거 문항의 USER 유형이다. **상위 2개를 다중 라벨**로 두고 `query.questionTypes[{code, confidence, from}]` 에 싣는다. 두 라벨의 search_mode가 다르면 두 모드의 신호를 합친다 |
| ↳ COMPETENCY 유형 | COMPETENCY, PAST_QUESTION, DENSE, LEXICAL |
| ↳ FIT 유형 (지원동기, 포부, 직무 이해) | 직무가 주어지면 FIT 신호 전부. **PAST_QUESTION은 같은 회사·같은 업종의 written_for로 제한**한다. 직무가 없으면 DENSE와 `warnings:["NO_POSITION"]` 을 준다 |
| ↳ SEMANTIC 유형 | DENSE, PAST_QUESTION |
| `q` (자유 문장) | LEXICAL, DENSE |

등록되지 않은 회사의 자소서를 쓰는 흐름에 대한 처리:
- SKILL에 "지원 맞춤 검색 전에 `posting_create` 로 공고·직무를 먼저 등록한다"를 필수 단계로 둔다.
- 서버는 직무 없이 FIT 문항이 오면 위 warning을 준다.
- 임시 공고 본문 입력(`postingText`)은 이 방식이 부족할 때 추가한다.

### 4.2 후보와 신호

**후보 단위**
- **사건**이 기본 후보다.
- **사건이 없는 경험**은 경험 자체를 후보로 넣는다(`type: EXPERIENCE`).
- **은행에 없는 이력서 경험 행**도 후보로 넣는다(`type: RESUME_ROW`, 근거로 `{resumeId, resumeName, section, index}`). 그래서 가져오기 전에도 검색이 비지 않는다. RESUME_ROW에는 LEXICAL과 REQUIREMENT(행 `techStack` 원문)만 적용한다.

| 신호 | 계산 | 출처 반영 |
|---|---|---|
| REQUIREMENT | 직무 `position_tech_stack` 키와 내 SKILL 키워드를 맞춘다. 일치는 정확 일치, 별칭 일치, ASCII 토큰 포함 관계("AWS EC2"⊇"AWS")다. 포함 관계 일치는 0.5배로 둔다. 채널 안 순서는 Σ 희소도 가중 `log(N/(1+df))` 다(N = 계정 경험 수, df = 그 기술을 가진 경험 수). Java·Spring처럼 흔한 기술이 동률을 만드는 것을 막기 위해서다 | AI·추정 0.5배 |
| DOMAIN | 대상 회사 `company_industries` 정규화 값 = 경험의 DOMAIN 키워드(USER/AI), 또는 3절의 파생 도메인(추정) | AI·추정 0.5배 |
| PAST_USE | 같은 직무(3) > 같은 회사(2) > 같은 업종(1)에 written_for로 낸 답변이 uses로 쓴 사건 | USER 1배, AI 0.5배 |
| COMPETENCY | 문항 유형 코드가 사건에 붙어 있음 | AI 0.5배 |
| PAST_QUESTION | 문항↔과거 문항 벡터 상위 30 → 리랭커 K=10 → 그 문항들이 uses로 쓴 사건. FIT 유형이면 같은 회사·업종으로 제한 | USER·AI 모두. AI는 0.5배 |
| DENSE | 질의 임베딩과 사건 벡터, 경험 조각 벡터(max-sim)의 cosine. owner로 좁힌 뒤 Java에서 정확히 정렬 | — |
| LEXICAL | 기존 `rank()`(df를 루프 밖으로 뺀 판)를 사건 텍스트, 경험 텍스트(연결 행 ∪ 메모), RESUME_ROW 텍스트에 적용. 직무 요구·우대 본문은 요구 태그 유무와 관계없이 **항상** 질의에 넣는다 | — |

**`uncovered[]`**: 받쳐 줄 후보가 없는 직무 기술 키의 목록이다. 결핍을 단정하지 않도록 두 가지 장치를 둔다.
- 일치하지 않은 이유를 구분한다: `{key, reason: NO_MATCH | TAG_MISMATCH_POSSIBLE}`. 후보 텍스트에 그 키 문자열이 보이면 `TAG_MISMATCH_POSSIBLE` 이다.
- 미등록 행이 있거나(`counts.unlinkedRows > 0`), 사건 없는 경험의 비율이 절반을 넘으면 `coverageIncomplete:true` 를 함께 준다. SKILL 규칙: 이때 AI는 "경험이 없다"고 단정하지 않고, 가져오기나 사건 정리를 권한다.

**`alreadyUsedFor[]`**: 후보 사건이 written_for로 이미 쓰인 회사·직무다.
- 지금 지원하는 회사와 같으면 `sameCompany:true` 다.
- FIT 유형 답변을 다른 회사용으로 쓴 경우에는 `otherCompanySpecific:true` 를 주고, SKILL에서 그 답변 본문의 재사용을 금지한다.

### 4.3 융합, 필터, 다양성, 트랜잭션

1. **융합은 RRF(k=60)** 로 한다. 척도가 다른 점수는 더하지 않는다. 신호별 0.5배는 그 신호 목록 안의 순위에만 반영한다. 신호가 드문드문한 구조에서 RRF가 "몇 개 목록에 나왔나"로 기우는지는 4.5 평가로 확인하고, 필요하면 신호 가중을 조정한다.
2. 계정당 후보가 수백 개라 신호마다 후보를 자르지 않는다.
3. 하드 필터는 USER 간선에만 적용한다.
4. **다양성**: 상위 `limit` 안에서 한 경험의 사건은 2개까지만 둔다.
5. **트랜잭션**: 오케스트레이터 `ExperienceSearch` 에는 `@Transactional` 이 없다.
   1. 질의 벡터를 받는다. 트랜잭션 밖이고 타임아웃은 2초, 실패하면 `degraded:["dense"]` 로 표시하고 계속한다.
   2. readOnly 메서드 하나로 SQL 4~6회와 벡터 로드를 한다.
   3. 리랭커도 트랜잭션 밖에서 부른다.

SQL 스케치. 직무 소유는 먼저 `findByIdAndOwnerId` 로 확인한다.

```sql
-- REQUIREMENT 후보 (키 매칭과 희소도는 Java 에서, KeyNormalizer 로 정규화한 요구 키 목록 :keys 를 넘긴다)
SELECT k.norm_key, ek.experience_id, ek.source
  FROM keywords k JOIN experience_keywords ek ON ek.keyword_id = k.id AND ek.owner_id = :o
 WHERE k.owner_id = :o AND k.kind = 'SKILL';

-- PAST_USE
SELECT sie.episode_id, sie.source AS use_src, sip.source AS for_src,
       CASE WHEN p2.id = :pid THEN 3 WHEN jp.company_id = :companyId THEN 2 ELSE 1 END AS w,
       c.name || ' · ' || p2.name AS used_for
  FROM self_introduction_episodes  sie
  JOIN self_introduction_positions sip ON sip.self_introduction_id = sie.self_introduction_id AND sip.owner_id = :o
  JOIN positions    p2 ON p2.id = sip.position_id AND p2.owner_id = :o
  JOIN job_postings jp ON jp.id = p2.posting_id   AND jp.owner_id = :o
  JOIN companies    c  ON c.id  = jp.company_id   AND c.owner_id  = :o
 WHERE sie.owner_id = :o
   AND (p2.id = :pid OR jp.company_id = :companyId
        OR EXISTS (SELECT 1 FROM company_industries ci WHERE ci.company_id = c.id AND ci.industry = ANY (:industries)));
-- 가중 = w × (use_src='USER' and for_src='USER' ? 1 : 0.5). 사건별 최대값으로 순위.

-- DENSE: 벡터를 읽어 Java 에서 정확 cosine
SELECT id, embedding FROM episodes WHERE owner_id = :o AND embedding IS NOT NULL;
SELECT experience_id, embedding FROM experience_chunks WHERE owner_id = :o;
```

모든 그래프 확장은 고정 깊이 조인(최대 4조인)이다. 재귀 CTE는 쓰지 않는다.

### 4.4 임베딩과 리랭커

**embedder**: 4번째 컨테이너다(Python, sentence-transformers).
- 엔드포인트: `POST /embed {mode: query|passage, texts[]}` → `{vectors, truncated[]}`, `POST /rerank {query, docs[]}`, `GET /health`.
- 내부 네트워크에만 붙이고 nginx 경로는 없다. 모델 파일은 볼륨으로 마운트한다(`docs/experience-graph-search.md:157`).
- **기본은 CPU다.** `compose.yaml` 하나로 로컬과 서버를 모두 빌드하고, `up` 하는 순간 공개 도메인에 반영되기 때문이다(`docs/nginx-deploy.md:30,53`).
- GPU 예약은 `compose.mcp.yaml` 과 같은 방식의 선택 오버라이드 `compose.gpu.yaml` 에 둔다. GPU를 쓸 때는 Pascal(sm_61)을 지원하는 torch 정확 버전(cu126 계열)을 고정한다. 앞으로 나올 빌드의 Pascal 지원은 불확실하다.
- 실측 CPU 지연은 질의 19ms, 문서 142ms다. 적재는 백그라운드라 CPU로 시작하고, GPU는 4단계 측정 뒤에 판단한다.
- 리랭커 CPU 지연은 재지 않았다. 4단계에서 재고, 목표를 넘으면 그때 GPU 오버라이드를 켠다.

**리랭커**: `dragonkue/bge-reranker-v2-m3-ko` 를 문항↔문항 단계에만 쓴다(K=10).
- 쓰이는 곳: `self_intro_list(q)` 와 PAST_QUESTION.
- 사건 재정렬은 평가에서 이득이 확인될 때만 켠다.

**접두 규칙** (`docs/experience-graph-search.md:158` 을 이 표로 바꾼다)

| 비교 | 질의 쪽 | 문서 쪽 |
|---|---|---|
| 직무·문항·자유 문장 → 사건·경험 조각 | `query: ` | `passage: ` |
| 문항 ↔ 문항 | `query: ` | `query: ` |
| 문항·자유 문장 → 답변 조각 | `query: ` | `passage: ` |

**길이 처리** (1200자가 512토큰에서 조용히 잘린 실측, `docs/experience-graph-search.md:150`):
- **사건**: STAR 4필드 + 성과 합계 900자 상한을 서비스에서 검사한다. 900자는 실측 비례로 낸 추정이고, 5단계에서 토크나이저로 다시 잰다. 그래도 `truncated` 가 오면 저장 응답에 경고를 싣는다.
- **경험**: 연결 행 텍스트(요약, 역할, 기술, 설명, 성과)를 필드·문단 경계로 480토큰 이하 조각으로 나눈다. 점수는 조각들의 max-sim이다. 사건이 생겨도 경험 조각은 유지한다. 사건이 없는 협업 사례가 뒤쪽 성과 문단에만 있을 수 있기 때문이다.
- **자소서**: 질문 1조각, 답변은 480토큰 조각들.

**계산 시점 (dirty + 해시 + 스위퍼, D14)**
- 쓰기 경로는 대상에 `embedding_dirty = true` 만 표시한다.
  - 이력서를 저장하면 링커가 그 이력서에 연결된 경험을 dirty로 만든다. 그래서 정본이 낡지 않는다(6.1).
  - 자소서와 사건은 텍스트를 바꾸면 dirty가 된다.
- 스위퍼는 `@Scheduled(fixedDelay = 3s)` 로 돈다.
  1. 테이블마다 `WHERE embedding_dirty ORDER BY updated_at LIMIT 32` 로 읽는다.
  2. 텍스트를 다시 만들고 조각별 `text_hash` 를 비교한다. 바뀐 조각만 `/embed` 한 번으로 계산한다.
  3. `… WHERE id = ? AND updated_at = ?` 조건으로 쓰고 dirty를 푼다. 그사이 텍스트가 또 바뀌었으면 다음 회차에 다시 계산한다.
- 스케줄러를 두지 않는다는 기존 결정의 이유(`docs/decisions.md:85-86`, 꺼져 있던 기간을 건너뜀)는 이 경우에 해당하지 않는다. dirty 플래그가 남아 있어 재기동 후 따라잡는다.
- embedder가 죽어 있어도 저장은 성공하고, 검색은 `degraded` 표시와 함께 동작한다.

### 4.5 평가

- **기본 골드셋**: 0단계에서 사용자가 실제 지원 건으로 만든 질의 10~20개와 정답(1.2). 코드를 바꾸기 전에 지금의 `self_intro_list` 로 기준선을 잰다.
- **보조 골드셋 (silver)**: 자소서 답변 본문에 프로젝트명이나 경력 회사명이 문자열로 나오는 경우를 약한 정답으로 쓴다. 사람은 표본만 검토한다.
- **누적 골드셋**: USER written_for와 USER uses를 가진 답변. 이때 그 답변 자신의 링크는 PAST_USE와 PAST_QUESTION에서 뺀다(leave-one-out). 같은 사용자 라벨이 신호와 정답 양쪽에 들어가는 자기 강화 편향이 남으므로 보조 지표로만 쓴다.
- **지표**: Recall@5, nDCG@10, **타사 혼입률**(상위 5개 중 다른 회사용으로만 쓰인 항목의 비율), **문항 유형 분류 정확도**(정규식 상위 1·상위 2 적중률).
- **방법**: 단계마다 같은 셋으로 다시 재고, 신호를 하나씩 켜고 끄며 기록한다. LEXICAL(BM25)을 남길지는 실데이터 측정 결과로 정한다(D8). 도구는 `poc/self-intro-search/search_poc.py` 를 확장한다.

---

## 5. MCP 도구 재설계

### 5.1 원칙

- **MCP 도구는 REST 컨트롤러를 감싼다.** `McpTools` 는 컨트롤러를 주입받아(`B/mcp/McpTools.java:37-40`) `tool.method.invoke` 로 호출한다(`:160`). 등록 예: `resume_update` → `ResumeController.update`(`:75`), `self_intro_list` → `SelfIntroductionController.findAll`(`:83`).
  - 그래서 응답 모양이나 가드를 바꾸면 웹에도 그대로 적용된다. 예: 웹은 목록 항목의 `answer` 를 그대로 PUT한다(`F/ResumeQuestions.vue:46`, `F/SelfIntroductionBoard.vue:136`). 목록 응답을 미리보기로 줄이면 웹에서 답변이 잘려 저장된다.
  - **MCP 전용 동작은 `B/mcp/McpActions.java` 의 별도 메서드로 만들고, 등록도 그쪽으로 옮긴다.** 기존 `resume_row_*` 가 이미 이 방식이다(`McpTools.java:80-82`). REST 응답은 바꾸지 않는다.
- **읽는 순서는 목차 → 검색 → 상세의 3단이다.** 목록에는 기본 `limit` 이 있다. 잘라야 하면 `complete:false` 와 `nextCursor` 를 붙이고, 조용히 자르지 않는다.
- **질의는 서버가 만든다.** 결과마다 `why[]`, `trust`, id, 사람이 읽을 이름을 붙인다.
- **쓰기 응답은 개요다.**
- **수정 규칙은 도구마다 다르다.**
  - `resume_*` 와 `posting_update` 는 null=유지, `[]`=비움이다.
  - 그 밖의 update 도구는 지금처럼 전체 교체다.
  - 그래서 `McpTools.java:123` 이 모든 `update` 에 붙이는 "전체 교체" 문구를 도구별 설명으로 바꾼다. SKILL.md 공통 규칙(`plugins/jobsight/skills/jobsight/SKILL.md:23-25`, "수정은 전체 교체", "지우려면 null")도 도구별 규칙으로 나눈다.
- **오류에는 필드 경로를 넣는다.** `McpSchema.validateShape`(`B/mcp/McpSchema.java:43`)에 경로 인자를 하나 넘긴다.
- text와 structuredContent에 이중으로 싣는 것은 호환을 위해 유지하고, 실리는 내용 자체를 줄인다.

### 5.2 새 도구 (4개, 54 → 58)

| 도구 | 입력 | 출력 | 쓰기 |
|---|---|---|---|
| `experience_index` | `kinds?[]`, `ids?[]`(10개 이하. 주면 상세 모드), `cursor?` | **맨 앞**: `counts`(종류별 경험 수, 사건 수, AI 기록 수, 미등록 행 수, 출처 미상 자소서 수)와 `complete`. 미등록 행이 있으면 `importHint`(웹 가져오기 화면 안내). 경험 한 줄: `{id, kind, title, period, skills[], domains[], episodes[{id, title, competencies}], inResumes[{id,name}], usedInSelfIntros}`. 그다음 **`unlinkedRows[]`**: 은행에 없는 행을 (kind, title_key)로 묶은 것. 정렬은 PROJECT → CAREER → ACTIVITY → TRAINING → OTHER, 한 쪽에 60개. 상세 모드는 최신 연결 행 텍스트, 사건 STAR, 키워드(trust), 이 사건을 쓴 자소서 `{id, question, company · position, trust}` | × |
| `experience_search` | `positionId?`, `postingId?`, `question?`, `q?`, `competencies?[]`, `kinds?[]`, `limit?=8 (≤20)` | `query`(직무, 회사, 업종, 요구 키, 문항 유형과 신뢰도, 이 직무용 직접 출처 자소서 `{id, question}`), `results[]`(5.4), `uncovered[]`, `coverageIncomplete`, `warnings[]`, `degraded[]` | × |
| `experience_save` | `{id?, expectedVersion?, kind, title, startYm, memo, skills?[], domains?[], episodes?[{id?, title, situation, task, action, result, outcomes[], competencies[], sortOrder}], removeEpisodeIds?[]}`. null=유지. 키워드는 이름으로 받아 서버가 정규화한다 | `{id, version, episodes[{id, version}], normalizedSkills[], warnings[]}` (truncated 경고 포함) | ○ (출처 AI) |
| `resume_add_experiences` | `id`, `experienceIds[]`, `expectedVersion?` | 경험마다 **가장 최근에 수정한 연결 행을 복사**해 종류에 맞는 섹션 끝에 붙인다. 연결 행이 없으면 제목과 시작 연월만 넣고 `emptyFields:true` 로 표시한다. 전부 들어가거나 전부 실패한다. 이미 있는 경험은 건너뛴다. 반환은 outline 모양 | ○ |

웹 전용으로 두는 것 (사람이 검토해야 하는 작업):
- 경험 가져오기와 병합
- 경험·사건 삭제
- AI 기록 확정과 삭제
- 키워드 별칭 편집

### 5.3 바뀌는 기존 도구 (모두 `McpActions` 래퍼)

| 도구 | 변경 |
|---|---|
| `resume_get` | `view?: full\|outline` 을 둔다. outline은 `version`, 섹션별 `count`, 행 머리 `{index, experienceId, title, period}`, `missing[]`, `unlinkedRows[]` 다. **full이어도 응답 머리에 `missing` 요약을 붙인다** |
| `missing[]` 정의 | 다음 두 가지를 합친 것이다. 계정의 은행 경험 중 이 이력서에 없는 것, 그리고 **다른 이력서에만 있는 미연결 행 묶음**(가져오기 계획과 같은 그룹 키). `possibleMatch` 가 있으면 `maybeUnlinked` 로 표시한다 |
| `resume_create` | 응답에 `missing` 요약을 붙인다. AI가 생성 때 일부만 고른 경우도 응답 기본값으로 드러난다 |
| `resume_update` | content·positionIds·빠진 섹션이 null이면 유지한다(1단계). `expectedVersion` 은 선택이다(2단계). **`LINKED_ROWS_MISSING` 가드**(5단계): 저장 전 문서의 `experienceId` 중 새 문서에서 사라진 것은 `removedExperienceIds` 에 모두 적혀 있어야 하고, 아니면 409다. 래퍼에 있으므로 웹에는 걸리지 않는다. 설명은 "섹션 일부 편집은 `resume_row_*`, 경험 행 추가는 `resume_add_experiences`"로 바꾼다 |
| `resume_row_*` | `FOR UPDATE` 로 직렬화한다(1단계). `expectedVersion?` 이 다르면 409다. update는 병합한다(`experienceId` 보존). 반환은 개요다(2단계) |
| `self_intro_list` | 필터 `positionId?`·`postingId?`·`companyId?`·`questionType?`·`unlinked?` 와 `limit?=10` 을 둔다. 반환은 `answerPreview`(300자), `score`, `matchedTerms`, 출처 `{direct:[{position, company, trust}], indirect:[{resume, position, company}]}`, `questionTypes`, `episodes[{id,title}]`, `alreadyUsedFor[]` 다. **직접 출처가 0건이면** 빈 배열 대신 `{fallback:"indirect", results:[…각 항목 "출처 미상"]}` 을 준다. `q` 의 100자 제한을 스키마에 드러내고, 넘으면 400을 준다. 원문은 `self_intro_get` 으로 읽는다. 웹 REST 목록은 그대로 둔다 |
| `self_intro_create` / `_update` | `positionIds?`, `episodeIds?`, `questionTypes?` 를 추가한다. 이 필드는 REST에도 생기지만 선택이고 null=유지라 웹에 영향이 없다. 규칙: 생성 시 "이력서 또는 직무 1개 이상", 수정 시 0개 허용. 지금은 생성과 수정이 같은 DTO의 `@Size(min = 1)` 을 공유하므로(`B/selfintro/dto/SelfIntroductionRequest.java:12`) 생성용과 수정용 요청을 분리한다. 빈 목록에서 `ids.get(0)` 로 `IndexOutOfBoundsException` 을 던지는 `requireOwnedResumes`(`SelfIntroductionService.java:118-123`)도 함께 고친다 |
| `posting_update` | `positions`·`steps` 가 null이면 유지한다. id 없는 직무는 이름으로 기존 직무와 맞춘다(2단계) |
| 리소스 설명 | 스킬 설명(`SKILL.md:3`)과 `resources/list` 설명(`McpController.java:54-55`)에 자기소개와 경험을 넣는다 |

### 5.4 응답 예시 (`experience_search`)

```json
{
  "query": {"position": "B사 · 서버 개발", "industries": ["핀테크"],
            "requirements": [{"key": "kafka"}, {"key": "kubernetes"}],
            "questionTypes": [], "directSelfIntros": []},
  "coverageIncomplete": false, "warnings": [], "degraded": [],
  "uncovered": [{"key": "kubernetes", "reason": "NO_MATCH"}],
  "results": [
    {"rank": 1, "type": "EPISODE", "episodeId": "…", "experienceId": "…",
     "experience": "결제 정산 배치 (PROJECT, 2025.03~2025.06)",
     "title": "정산 배치 지연 원인 추적", "resultPreview": "배치 40분 → 6분",
     "competencies": [{"code": "PROBLEM_SOLVING", "trust": "확정"}],
     "why": [
       {"signal": "REQUIREMENT", "matched": ["Kafka"], "trust": "확정"},
       {"signal": "DOMAIN", "matched": ["핀테크"], "trust": "추정"},
       {"signal": "PAST_USE", "detail": "A사 · 백엔드 인턴 '문제 해결' 문항에 사용", "trust": "AI 기록"},
       {"signal": "DENSE", "rank": 2}],
     "alreadyUsedFor": [{"company": "A사", "position": "백엔드 인턴", "sameCompany": false, "otherCompanySpecific": false}]},
    {"rank": 2, "type": "RESUME_ROW", "resume": "백엔드 v3", "section": "projects", "index": 2,
     "title": "사내 메시지 큐 이전", "why": [{"signal": "LEXICAL", "matched": ["kafka"]}]}
  ]
}
```

### 5.5 두 증상을 구조적으로 막는 방법

**(a) 이력서를 쓸 때 프로젝트를 빠뜨린다**

| 원인 후보 | 막는 장치 |
|---|---|
| 병렬 행 추가의 lost update, 웹 저장이 AI 결과를 덮어씀 | 행 잠금(1단계), `version` 409(2단계) |
| 통째 교체에서 섹션·필드를 빠뜨리면 삭제됨 | null=유지(1단계), 행 병합(2단계), `LINKED_ROWS_MISSING`(5단계) |
| **AI가 새 이력서를 만들 때 처음부터 일부만 고름** | `resume_create`·`resume_get` 응답 머리의 `missing` 요약. 다른 이력서에만 있는 미연결 행 묶음까지 포함한다. 대조가 AI의 선택이 아니라 응답 기본값이 된다 |
| 응답이 커서 뒤쪽 `projects` 가 잘림 | 쓰기 응답은 개요다. 목차는 PROJECT를 맨 앞에 두고 `counts`·`complete` 도 맨 앞에 둔다 |
| AI가 행을 다시 타이핑하다 필드를 틀림 | `resume_add_experiences`: id만 넘기고 서버가 행을 복사한다 |

**(b) 직무·회사에 맞지 않는 자소서 경험을 가져온다**

| 원인 후보 | 막는 장치 |
|---|---|
| 자소서에 "어느 지원용"이라는 관계가 없음 | `self_introduction_positions`(3단계). 직접 출처가 없을 때는 간접 출처를 "출처 미상"으로 표시해 돌려준다. AI가 필터를 버리고 다시 부르지 않게 하려는 것이다 |
| 부분 문자열 BM25, 결과 상한 없음, 출처 없음 | 의미 검색(4단계), `limit`, `why[]`, `trust`, `alreadyUsedFor[]` |
| 문항이 비슷하다는 이유로 다른 회사용 지원동기를 가져옴 | FIT 유형의 PAST_QUESTION은 같은 회사·업종으로 제한한다. 다른 회사용이면 `otherCompanySpecific` 표시와 SKILL의 재사용 금지 |
| 맞는 경험이 없어도 비슷한 것을 억지로 고름 | `uncovered[]`. 단 커버리지가 불완전하면 `coverageIncomplete` 를 주어 결핍 단정을 막는다 |
| AI가 잘못 남긴 연결이 이후 검색을 오염 | 하드 필터는 USER만 쓰고, PAST_USE에서 AI는 0.5배다. 웹 확정은 사람만 한다 |
| SKILL을 읽지 않는 클라이언트가 예전 경로로 감 | 예전 도구(`self_intro_list`, `resume_get`, `resume_create`) 응답에 출처, `alreadyUsedFor`, `missing` 을 직접 싣는다 |

### 5.6 SKILL.md 절차 (`plugins/jobsight/skills/jobsight/SKILL.md`)

1. **이력서 작성·수정**
   1. `experience_index` 를 호출하고 `complete:true` 를 확인한다.
   2. `resume_get(view=outline)` 을 호출한다.
   3. `missing[]` 과 `unlinkedRows[]` 를 사용자에게 번호 목록으로 확인받는다.
   4. 은행에 있는 경험이면 `resume_add_experiences` 를 쓰고, 없으면 `experience_save` 뒤에 추가한다.
   5. 응답의 `count` 가 계획과 같은지 대조한다.

   규칙: 행 도구는 병렬로 부르지 않는다. 409를 받으면 다시 읽고 재시도한다.
2. **지원 맞춤 경험 찾기**
   1. 직무가 등록돼 있지 않으면 `posting_create` 로 먼저 등록한다.
   2. `experience_search` 를 호출한다.
   3. 후보를 `why` 와 함께 보여 준다. `why` 에 DENSE만 있으면 "의미상 비슷할 뿐"이라고 밝힌다. `uncovered` 를 알리되, `coverageIncomplete` 면 결핍을 단정하지 않는다.
   4. 사용자가 고른다.
   5. 작성한다.
   6. `self_intro_create(positionIds, episodeIds, questionTypes)` 로 저장한다. 이 연결은 "AI 기록"이고 웹에서 확정해야 한다고 사용자에게 알린다.
3. **경험 정리**
   1. `self_intro_list({unlinked:true})` 를 호출한다.
   2. 답변마다 사건 초안(STAR)과 역량을 제안한다.
   3. 사용자가 대화에서 승인한 것만 `experience_save` 로 저장한다.
4. 수정 규칙은 5.1의 도구별 규칙으로 바꿔 쓴다.

---

## 6. 정본 관계와 기존 데이터 이전

### 6.1 정본 규칙

| 정보 | 정본 |
|---|---|
| 경험의 존재와 정체 | `experiences.id` (행에는 `experienceId` 앵커) |
| 경험 서술 (역할, 기간, 기술, 설명, 성과) | **이력서 행.** 검색과 임베딩은 "가장 최근에 수정한 연결 이력서의 행" ∪ 사건 ∪ 메모를 본다 |
| 사건, 사건 결과, 역량·키워드 태그 | 새 테이블 |
| 이력서 ↔ 경험 관계 | `resume_experiences` (JSON에서 파생, FK) |
| 답변을 어디에 냈고 무엇을 썼나 | `self_introduction_*` |

- 서술을 한 곳에만 두므로 drift 배지, "정본에 반영", 가져오기 필드 대응표가 필요 없다. 사용자가 이력서 행을 고치면 다음 스위퍼 회차에 검색에 반영된다.
- 이력서 버전마다 문구를 다르게 쓰는 사용 방식은 그대로 지원한다. 검색은 최신 버전을 본다.
- **같은 키를 세 곳에 함께 추가해야 한다.** `ResumeContent` 의 `Activity`·`Experience`·`Project`·`Training`, `ResumeRowRequest`, `frontend/src/types/resume.ts` 다. Jackson 3는 모르는 키를 조용히 버리므로, 한 곳이라도 빠지면 저장은 성공하고 값만 사라진다. PUT→GET 왕복 테스트로 막는다.
- `docs/decisions.md:50` 의 "본문 JSON에 ID를 섞지 않는다"에 대한 예외다(D2).

### 6.2 저장할 때 자동 연결: 모든 저장 경로가 거치는 함수 하나

`ResumeExperienceLinker.link(owner, resume, content)` 는 create, update, copy, `mutateRows`, `updateBasic`, 가져오기, 병합에서 **같은 트랜잭션 안에서** 호출된다. 선행 조건은 T1 잠금과 T2 version이다.

1. 4개 섹션에서 행을 모은다.
2. `experienceId` 가 있는 행은 세 가지를 검사한다.
   - 그 경험이 내 것인지 count로 확인한다. 없으면 404다.
   - 종류와 섹션이 맞는지 확인한다. 다르면 400 `EXPERIENCE_SECTION_MISMATCH` 다.
   - 한 이력서 안에서 같은 id가 두 번 나오면 400이다.
3. `experienceId` 가 없는 행은 `(kind, title_key)` 로 찾는다. **정확히 1건이 맞고 시작 연월 숫자가 충돌하지 않을 때만** 연결한다. 그 밖에는 미연결로 둔다. 새 경험은 만들지 않는다.
4. 연결한 행에 `experienceId` 를 써 넣고, `resume_experiences` 에는 차이만 반영한다.
5. 이 이력서의 연결 경험을 `embedding_dirty` 로 표시한다.
6. **웹 저장에서 이전에 있던 `experienceId` 가 사라지면** 응답에 `unlinkedWarnings[]` 를 싣는다. 웹에서 조용히 끊기는 것을 막기 위해서다. 웹은 이를 토스트로 보여 준다.

예상 비용은 SQL 3~4회이고 외부 호출은 없다.

### 6.3 기존 데이터 가져오기 (웹 기능, 마이그레이션 SQL 아님)

같은 경험인지는 사람이 확인해야 한다. 또 마이그레이션은 데이터를 지우면 안 된다(`docs/decisions.md:98-99`).

1. **계획 (dry-run)**
   - 계정의 미연결 경험 행을 `(kind, title_key, 시작 연월 숫자)` 로 묶는다.
   - 묶음마다 구성 행 `[{resumeId, resumeName, section, index, period}]` 을 싣는다.
   - 제목 키가 서로 포함 관계이거나, 기간 숫자는 같은데 제목이 다르면 `mergeCandidates` 로 표시한다. 자동으로 합치지 않는다.
2. **적용**
   - 묶음마다 `CREATE | SKIP | LINK_TO(experienceId)` 를 받는다.
   - 계정의 이력서를 id 오름차순으로 `FOR UPDATE` 잠근다.
   - CREATE는 정체성(kind, title, start_ym)과 SKILL 키워드(`techStack` 분해, USER)만 만든다. 구성 행 전부에 `experienceId` 를 쓰고, 행 텍스트는 바꾸지 않는다.
   - 여러 번 돌려도 결과가 같다.
3. **병합**: 한 트랜잭션에서 처리한다.
   1. 사건의 `experience_id` 를 target으로 바꾼다. 사건을 참조하는 FK는 `(id, owner_id)` 뿐이라 막히지 않는다.
   2. 키워드 연결을 `ON CONFLICT DO NOTHING` 으로 옮긴다.
   3. 관련 이력서를 id 순으로 잠그고 JSON의 `experienceId` 를 고쳐 쓴다.
   4. source를 지운다.
4. **사건 나누기와 자소서 연결**은 5.6 "경험 정리" 절차(AI 제안 → 사용자 승인)로 한다.

**운영 데이터 검증 원칙**:
- 운영 DB 사본에서는 내용 없이 집계만 비교하는 SQL만 돌린다. 계정별로 `경험 섹션 행 수 = 묶음 구성 행 수 + 미연결 행 수` 가 맞는지 본다.
- 사람이 계획을 직접 보는 검토는 본인 계정이나 합성 데이터에서만 한다.
- 근거: `docs/decisions.md:171-179`(관리자는 집계 숫자만, 업무 데이터 내용은 열지 않는다).

### 6.4 삭제와 복제

- **이력서 복제**: `experienceId` 를 그대로 복사한다.
- **이력서 삭제**: `resume_experiences` 만 지워진다. 경험과 자소서는 남는다.
- **경험 삭제**: 이력서가 쓰고 있으면 409와 이력서 이름 목록을 돌려준다. 쓰이지 않으면 CASCADE로 지우고, 응답에 끊긴 자소서 수를 싣는다.
- **직무 삭제**: `self_introduction_positions` 가 CASCADE로 지워진다. 공고 수정이 직무를 지웠다 다시 만드는 경로는 2단계에서 먼저 막는다(T3).

---

## 7. 단계별 도입 계획

AGENTS.md 순서에 따라, 착수 전에 이 설계를 `docs/experience-graph-search.md` 자리에 한 화면 형식(목적 / 진입점 / 불변 조건 / 실행·검증 / 함정)으로 다시 쓴다. 문서 지도의 행 설명도 갱신한다.

단계마다 공통으로 지킬 검증:
- 백엔드: 컨테이너 안에서 `./gradlew compileJava compileTestJava` 를 돌린다. 출력은 파일로 받고 `BUILD SUCCESSFUL` 과 exit 0을 둘 다 확인한다.
- 프런트: `npm run type-check && npm test` 의 exit code를 확인한다.
- 마지막: `bash scripts/smoke.sh` PASS.
- `SecurityConfig`·`nginx.conf`·`routes.ts`·`ApiPaths` 를 건드린 단계는 Google 로그인 1회 실제 왕복.
- 마이그레이션은 `now at version vN` 확인.
- curl 검증은 상태코드를 먼저 보고, "로그인 → GET 한 번 → 변경 요청" 순서로 한다.
- **`compose up` 은 곧 공개 배포다**(`docs/nginx-deploy.md:53`). 마이그레이션이 있는 단계는 먼저 `docs/nginx-deploy.md:140` 의 `pg_dump` 백업을 받는다.

| 단계 | 내용 | 검증 | 되돌리기 |
|---|---|---|---|
| **0. 측정** | 1.2의 표 전부. 바꾸는 코드는 nginx `log_format`, MCP 호출 로그 한 줄뿐이다. 수작업 골드셋을 만든다 | 기준선 수치(SQL 수, waterfall, `$upstream_response_time`, 골드셋 Recall@5·혼입률) 기록. nginx를 바꿨으므로 로그인 왕복 | 커밋 revert |
| **1. 손실 막기 (스키마 변경 없음)** | ① batch fetch ② 직렬 요청 줄이기: 자소서 동시 요청(적용함), 첨부 메타 동봉(P5) ③ `findOwnedForUpdate` 를 `update`·`updateBasic`·`mutateRows` 에 적용 ④ `resume_update` null=유지 ⑤ `AttachmentField.vue:52,65` 즉시 삭제 제거 ⑥ `resume_update` 설명, SKILL 도구별 수정 규칙, 행 도구 순차 호출 규칙 | 병렬 `addRow` 2건 → 2행. `projects` 를 뺀 `resume_update` 뒤에도 행이 남는지. 복제본에서 첨부를 교체한 뒤 원본 첨부 GET 200. SQL 수와 waterfall을 기준선과 비교 | 항목별 커밋 revert. 데이터 영향 없음 |
| **2. 버전·인덱스·조회 정리 (V22)** | V22, `expectedVersion`(선택)과 409, P3~P11, `posting_update` null=유지와 이름 매칭, 행 병합·개요 반환·검증(T4~T6), 오류 필드 경로, `resume_get(view=outline)` 의 counts와 행 머리 | `now at version v22`. 두 탭에서 저장하면 두 번째가 409. 토큰 조회 `EXPLAIN` Index Scan. `Content-Encoding: gzip`. 이름만 보낸 `posting_update` 에서 직무 id와 `resume_positions` 보존. SQL 상한 테스트. 로그인 왕복 | 코드 revert. 컬럼과 인덱스는 남아도 무해 |
| **3. written_for + 자소서 목록 (V23)** | V23, `self_intro_list` MCP 래퍼(필터, 미리보기, limit, 출처, fallback, `alreadyUsedFor`), 자소서 생성·수정의 `positionIds`·`questionTypes`, 요청 DTO 분리, 웹 "어느 지원용?" 후보 칩과 AI 기록 확정 UI, 정규식 문항 유형 | 골드셋 재측정, 특히 타사 혼입률. 다른 계정 직무 id로 연결 → 404. 웹 자소서 목록 응답이 바뀌지 않았는지(답변 전문). `q` 101자 → 400. **이 결과로 5단계 착수 여부와 범위를 다시 정한다** | 낮음. 테이블은 무시해도 된다 |
| **4. embedder + 자소서 의미 검색 (V24)** | `embedder` 컨테이너(기본 CPU), `compose.gpu.yaml`, V24, 스위퍼(D13·D14), `self_intro_list(q)` 에 DENSE + 리랭커 K=10, PAST_QUESTION. 컨테이너 3 → 4. `.env.example`·`compose.yaml`·`docs/local-dev.md` 동기화 | 골드셋 재측정. CPU 리랭커 지연 실측. embedder를 멈춘 상태에서 저장 성공과 `degraded` 표시, 재기동 후 dirty 0으로 수렴. 해시가 같은 조각은 재계산하지 않는지 | 낮음. 컨테이너를 내리면 3단계 검색으로 돌아간다. DB 이미지는 그대로다 |
| **5. 경험 은행 + 경험 검색 (V25)** | V25, `experienceId` 세 곳 동시 추가, 링커, 웹 가져오기·병합·경험 화면·행 배지, `ApiPaths`·`routes.ts`·`nginx.conf` 표 동시 갱신, MCP 4개 도구, `missing[]`·`LINKED_ROWS_MISSING`, `experience_search`(REQUIREMENT·DOMAIN·PAST_USE·COMPETENCY·LEXICAL·DENSE, RESUME_ROW 후보), 별칭 시드 사전 | `experienceId` PUT→GET 왕복. 링커 멱등. 제목 키가 2건 맞으면 미연결로 남는지. 복합 FK가 다른 소유자 연결을 거부. **사건이 있는 경험의 병합**. 계정 삭제 cascade 통과. `REAL[]` 매핑 validate 통과. 운영 사본은 집계 비교 SQL만. 두 계정이 같은 태그를 써도 교차 결과 0건. 골드셋 재측정과 신호별 기여 기록. 로그인 왕복 | 중간. MCP 등록과 화면을 끄면 멈춘다. 이력서 행 텍스트는 원본 그대로다 |
| **6. 정리 기능** | `experience_index` 점검 플래그(사건 없는 경험, 역량 없는 사건, 어디에도 쓰이지 않은 사건, AI 기록 수, 미등록 행, 출처 미상 자소서, truncated), 별칭 편집 UI, 사용률 로그를 근거로 쓰이지 않는 CRUD 도구 정리 검토 | 계정 단위로 플래그 수 추적 | 낮음 |

### 함께 갱신할 문서

| 문서 | 갱신 내용 |
|---|---|
| `docs/experience-graph-search.md` | 이 설계로 다시 쓴다. stories/links/pgvector 구상과 V22 예약은 폐기한다 |
| `docs/resumes.md` | 불변 조건 3(행 도구), 4(입력 null=유지, 저장 모양은 그대로 `[]` 정규화), `experienceId`, `version` |
| `docs/self-introductions.md` | 불변 조건 2(`:22`, "이력서 또는 직무 1개 이상"), 연결 대상 확장 |
| `docs/attachments.md` | 검증 3번(`:60`)과 함정(`:71`): 교체해도 이전 파일은 남는다(고아 허용) |
| `docs/mcp-plugin.md` | 도구 수. 문서(`:128`)는 53개인데 코드의 `register` 는 54개라 이미 어긋나 있다. 도구별 수정 규칙, `McpActions` 원칙 |
| `docs/backend.md` | batch fetch, 잠금 패턴, `REAL[]` 매핑, `SystemEmbeddingSweepRepository` 규칙 |
| `docs/local-dev.md`, `docs/nginx-deploy.md` | embedder, `compose.gpu.yaml`, 마이그레이션 전 백업, `log_format` |
| `docs/decisions.md` | 8절 D1~D14 |
| `AGENTS.md` | 컨테이너 수와 문서 지도 |
| `plugins/jobsight/skills/jobsight/SKILL.md` | 5.6 |

---

## 8. 되돌리는 기존 결정 (`docs/decisions.md` 에 기록할 것)

| # | 기존 결정 (근거) | 바꾸는 내용 | 이유 |
|---|---|---|---|
| D1 | 이력서는 JSONB 하나, 섹션 테이블 없음 (`docs/decisions.md:145-146`) | 대부분 유지한다. 경험 정체성과 사건만 테이블로 둔다. 서술은 JSONB 행에 남긴다 | 원 결정의 되돌릴 조건("섹션별 질의가 필요해지면", `:146`)이 경험 4개 섹션에 대해 충족됐다 |
| D2 | 본문 JSON에 ID를 섞지 않는다 (`:50`) | 경험 행에 `experienceId` 앵커를 허용한다. 관계의 정본은 FK 테이블 `resume_experiences` 다 | id 없는 행에 정체를 줄 다른 방법이 없다 |
| D3 | 다형 `links` + `stories` + `tags text[]` 구상 (`docs/experience-graph-search.md`) | 폐기한다. 관계 종류별 FK 조인 테이블과 복합 FK를 쓴다 | 다형 간선에는 FK를 걸 수 없어 고아 간선이 남는다 |
| D4 | 자소서는 이력서와만 N:M, `resumeIds` 1개 이상 (`docs/self-introductions.md:22`, `SelfIntroductionRequest.java:12`) | 직무·사건·문항 유형으로 연결을 넓힌다. 생성 시 "이력서 또는 직무 1개 이상", 수정 시 0개 허용. DTO를 분리한다 | "어느 회사용 답변인가"를 판별할 수 없는 구조가 증상 (b)의 원인 후보다 |
| D5 | `resume_update` 통째 교체 (`docs/resumes.md:44`), 섹션 null → `[]` (`docs/resumes.md:45`), SKILL "수정은 전체 교체" (`SKILL.md:23-25`) | `resume_*`·`posting_update` 만 null=유지, `[]`=비움. 저장되는 모양은 그대로 정규화한다. 그 밖의 update 도구는 전체 교체를 유지한다 | AI의 섹션 누락이 조용한 삭제로 이어진다 |
| D6 | 임베딩은 CPU·JVM ONNX (`docs/decisions.md` "임베딩은 GPU 가 아니라 CPU 로" 절) | Python embedder 컨테이너로 바꾼다. 기본은 CPU이고, GPU는 선택 오버라이드다 | 리랭커(크로스 인코더)가 생겼고, 같은 서비스가 GPU로 옮겨 갈 길이 필요하다. 검색 경로는 CPU로도 충분하다는 원 판단은 유지한다 |
| D7 | 태그 선택지는 계정 기존 값에서 모은다 (`docs/job-postings.md:140`) | 역량·문항 유형은 시스템 고정 코드 18개로 둔다 | 문항과 사건이 같은 어휘를 써야 매칭된다 |
| D8 | 메모리 BM25 (`:50`) | LEXICAL 신호로 유지하고 df 계산만 고친다. 존치는 실데이터 측정으로 정한다 | 합성 PoC는 제거 근거로 부족하다 |
| D9 | `job_postings.target_position_id` | 쓰지도 지우지도 않는다 | 데이터 삭제 금지 원칙 |
| D10 | 첨부 교체 시 즉시 삭제 (`docs/attachments.md:71`, `AttachmentField.vue:51`) | 즉시 삭제를 없앤다. 정리 작업은 두지 않는다 | 복제 이력서가 id를 공유해 원본의 파일이 사라진다 |
| D11 | 임베딩 저장은 pgvector `vector(384)` (`docs/experience-graph-search.md:149`) | `REAL[]` + Java 정확 cosine. DB 이미지는 `postgres:17-alpine` 그대로다 | 계정당 수백 행이고 인덱스도 쓰지 않는다. 확장 종속(이미지 빌드, 백업 복원, 콜레이션 위험)을 피한다 |
| D12 | e5 접두 "질의 `query:`, 문서 `passage:`" (`:158`) | 문항↔문항은 양쪽 `query:` (4.4 표) | 확정된 맥락에 맞춘다 |
| D13 | `owner_id` 없이 id만 보는 조회 메서드 금지 (`docs/experience-graph-search.md:161`) | 시스템 배치 예외: 임베딩 스위퍼만 허용한다. 별도 패키지·리포지토리이고, 대상은 dirty·임베딩 컬럼뿐, 내용은 embedder 외로 내보내지 않는다 | 계정과 무관한 백그라운드 적재가 필요하다 |
| D14 | 스케줄러 없음 (`docs/decisions.md:85-86`) | 임베딩 dirty 스위퍼에 한해 `@Scheduled` 를 쓴다. 해시 비교(`:203`)는 유지한다 | 원 결정의 반대 이유(꺼진 기간을 건너뜀)는 dirty 플래그가 남는 구조에서는 해당하지 않는다 |

---

## 9. 위험과 열린 질문

### 9.1 위험

| 위험 | 영향 | 대응 |
|---|---|---|
| 지연 원인이 DB가 아님 — **실측으로 확인됨(1.0)** | P1·P3의 체감 효과가 작다 | P1·P3은 정확성·확장성 근거로만 한다. 체감은 직렬 단계(P2·P5)와 진입 경로(N1)로 줄인다 |
| 제목 키 자동 연결이 다른 경험을 같은 것으로 묶음 | 잘못된 연결 | 정확히 1건 일치하고 연월이 충돌하지 않을 때만 연결한다. "다른 경험에 연결"과 병합으로 고친다 |
| 콜드 스타트 (사건과 연결이 적음) | 그래프 신호가 약하다 | 의미 검색을 앞 단계(4단계)에 둔다. RESUME_ROW 후보, `coverageIncomplete`, 간접 출처 fallback |
| AI 기록을 사용자가 확정하지 않음 | PAST_USE가 계속 0.5배이고 하드 필터에서 빠진다 | 경험 목록 배지로 확정을 유도한다. 0.5배여도 신호는 남는다 |
| 고정 역량 코드가 일부 문항을 덮지 못함 | SEMANTIC으로만 검색된다 | 코드 추가는 INSERT 한 줄과 enum 한 줄이다 |
| 키워드 표기 흔들림 | REQUIREMENT를 놓친다 | 별칭 시드 사전, 토큰 포함 일치, `TAG_MISMATCH_POSSIBLE`, 요구 본문을 늘 LEXICAL·DENSE 질의에 넣음 |
| 사건 900자 상한이 사용자에게 짧음 | 입력 불편 | 5단계에서 토크나이저로 재측정한 뒤 조정한다. 넘치면 조각 임베딩으로 바꾼다 |
| `REAL[]` 매핑이 validate와 맞지 않음 | 5단계 기동 실패 | 첫 커밋에서 확인하고, 안 되면 `bytea` |
| 4번째 컨테이너 운영 | 운영 부담 | 기본 CPU. 멈춰도 저장과 검색은 계속 동작한다 |
| 384차원이 모델에 묶임 | 모델 교체 시 전체 재적재 | 조각 테이블을 비우고 dirty를 세워 스위퍼로 재적재한다 |
| 검색 품질 수치가 없음 | 신호 하나하나의 기여를 모른다 | 0단계 수작업 골드셋부터 단계마다 다시 잰다 |

### 9.2 열린 질문 (사용자 결정 필요)

1. ~~AI가 관계를 확정하게 할지~~ → **닫음**: 확정은 웹에서만 한다. MCP 쓰기는 "AI 기록"이다.
2. 이력서 버전마다 경험 문구를 다르게 쓰는지. 이제 구조가 이 답에 의존하지 않는다. 검색이 최신 행을 보는 것이 맞는지만 확인하면 된다.
3. 어느 이력서 버전을 실제로 제출했는지 기록이 필요한지. 필요하면 `applications` 를 추가한다.
4. 사건 단위 기술 태그가 필요한지.
5. 서버 로그나 MCP 대화 기록을 볼 수 있는지. "프로젝트 누락"의 원인을 가른다.
6. 수작업 골드셋 10~20개를 언제 만들 수 있는지(0단계 선행 조건).
7. GPU 오버라이드를 켤 시점. 4단계 CPU 리랭커 실측 뒤에 정한다.

### 9.3 검증하지 못한 것

- SQL 횟수와 응답 크기는 코드에서 낸 추정이다. 지연은 인증 없는 가벼운 요청으로만 쟀다(1.0).
- 직접 확인한 코드 사실: `ResumeBoard.vue:62` 의 Promise.all, `application.yml` 에 배치 페치 없음, `mutateRows` 의 잠금 없는 조회, `replacePositions` 의 새 Set 대입, `AttachmentField` 의 즉시 삭제, `frontend/nginx.conf` 에 gzip 없음, `postgres:17-alpine`, 최신 마이그레이션 V21, MCP의 컨트롤러 리플렉션 호출, `RuntimeException` → `INTERNAL_ERROR`.
- 동시 `resume_row_add` 의 lost update, `resume_update` 의 섹션 소거, 생성 시 선택 누락 중 무엇이 "프로젝트 누락"의 실제 원인인지. 재현하지 않았다.
- Spring Authorization Server JDBC의 타입 없는 토큰 조회 쿼리 모양, 만료 행 자동 정리 여부.
- `REAL[]` ↔ `float[]` Hibernate 7 매핑, 리랭커 CPU 지연, `experience_search` p95, 실데이터 검색 품질, 사건 900자 상한의 토큰 수.
- 각 MCP 클라이언트(Claude, ChatGPT, Codex)의 도구 출력 상한과 잘림 방식.
- 계정 삭제 cascade가 새 복합 FK들과 함께 통과하는지. 5단계 테스트에 넣는다.
- 실제 계정의 이력서당 직무 수 분포. written_for 후보 칩의 유용성과 관련이 있다.

---

## 10. 반박 처리 내역

| # | 영역·심각도 | 반박 요지 | 처리 | 이유와 반영 위치 |
|---|---|---|---|---|
| I1 | invariants·major | MCP 도구가 컨트롤러를 리플렉션으로 호출하므로 응답과 가드를 바꾸면 웹도 바뀐다 | 채택 | 코드로 확인(`McpTools.java:37-40,75,83,160`, `ResumeQuestions.vue:46`). MCP 전용 동작은 `McpActions` 에 둔다. 5.1, 5.3 |
| I2 | invariants·major | "SKILL.md:25와 일치"가 거짓이고 도구별 규칙이 혼재한다 | 채택 | `SKILL.md:23-25`, `McpTools.java:123`, `docs/resumes.md:45` 확인. 도구별 규칙으로 나누고 D5에 추가. 5.1, 8 |
| I3 | invariants·major | outcomes FK에 ON UPDATE가 없어 병합이 실패한다 | 채택(다른 방식) | `outcomes` 테이블을 없애고 `episodes.outcomes TEXT[]` 로 바꿔 FK 자체를 없앴다. 병합 테스트는 5단계에 넣었다. 2.5, 6.3 |
| I4 | invariants·major | 필드당 2000자는 512토큰 실측과 모순된다 | 채택 | STAR+성과 합계 900자, truncated 경고, 경험은 조각 + max-sim. 4.4 |
| I5 | invariants·major | 서버 전용 compose가 없고 up은 곧 배포다 | 채택 | `docs/nginx-deploy.md:30,53` 확인. 기본 CPU, `compose.gpu.yaml`, 마이그레이션 전 `pg_dump`. DB 이미지 교체는 D11로 없어졌다. 4.4, 7 |
| I6 | invariants·major | 운영 사본을 사람이 검토하면 관리자 권한 범위를 넘는다 | 채택 | 운영 사본은 집계 SQL만, 사람 검토는 본인·합성 데이터만. 6.3 |
| I7 | invariants·major | 스위퍼와 정리 작업이 owner 없는 첫 경로이고, 스케줄러·해시 결정과 GIN 배제 근거가 서로 맞지 않는다 | 채택 | D13·D14 추가, 리포지토리 분리, 해시 유지. 고아 첨부 정리는 만들지 않아 JSONB 질의 모순이 없어졌다. 2.4, 4.4, 1.4 T7 |
| I8 | invariants·minor | OAuth BitmapOr 주석이 틀렸다 | 채택 | 주석을 고치고 인덱스를 access·refresh 두 개로 줄였다. 1.5 |
| I9 | invariants·minor | CONCURRENTLY 근거가 부정확하다 | 채택 | 근거를 바꿨다. 1.5 |
| I10 | invariants·minor | D4 줄 근거 오류, DTO 공유, `ids.get(0)` | 채택 | `:22`, `SelfIntroductionRequest.java:12`, `SelfIntroductionService.java:118-123` 확인. 5.3, D4 |
| I11 | invariants·minor | `attachments.md`·`admin.md` 가 갱신 목록에 없다 | 일부 채택 | `attachments.md` 추가. 관리자 경험 개수는 만들지 않기로 해 `admin.md` 는 불필요하다. 2.4-7, 7 |
| A1 | ai-effect·blocker | 은행이 빈 상태에서 `uncovered` 가 결핍을 단정한다 | 채택 | RESUME_ROW 후보, `coverageIncomplete`, `TAG_MISMATCH_POSSIBLE`, `importHint`. 4.2, 5.2 |
| A2 | ai-effect·blocker | AI의 `confirm:true` 가 확정 전용 방어선을 통과한다 | 채택 | `confirm` 을 없앴다. MCP 쓰기는 항상 `AI` 출처이고, 확정은 웹에서만 한다. 하드 필터는 USER만, PAST_USE에서 AI는 0.5배. 3절 |
| A3 | ai-effect·major | written_for 자동 제안이 "정확히 1개" 조건이라 대부분 비고, 필터가 빈 결과를 낸다 | 채택(저장 없는 방식) | 후보 직무 전부를 질의 때 계산해 칩으로 보여 준다. `self_intro_list` fallback, 출처 미상 수를 counts에. 3, 5.3 |
| A4 | ai-effect·major | 4단계 검색에 태깅 없이 동작하는 신호가 없다 | 채택 | 의미 검색을 4단계로 앞당기고, 경험 은행보다 먼저 둔다. LEXICAL·DENSE 대상에 경험 텍스트, 미연결 행, 답변을 넣었다. 7 |
| A5 | ai-effect·major | REQUIREMENT가 정확 일치뿐이라 변별력이 없고 표기가 흔들리며 본문을 무시한다 | 채택 | 희소도 가중, 별칭 시드(5단계), 토큰 포함 일치, 요구 본문을 늘 질의에. 4.2 |
| A6 | ai-effect·major | DOMAIN을 자동으로 채우는 길이 없다 | 일부 채택 | 이력서→직무→회사 업종 파생을 "추정"으로 채택했다. 임베딩 유사도 매칭은 근거가 없어 보류. 3, 4.2 |
| A7 | ai-effect·major | 직무를 등록하지 않으면 FIT 신호가 없다 | 일부 채택 | 더 싼 쪽을 골랐다: SKILL의 공고 등록 필수 단계와 `NO_POSITION` warning. `postingText` 는 부족이 확인되면 추가. 4.1 |
| A8 | ai-effect·major | FIT 유형의 PAST_QUESTION이 타사 답변을 가져온다 | 채택 | 같은 회사·업종으로 제한하고 `otherCompanySpecific` 표시. 4.1, 4.2 |
| A9 | ai-effect·major | 생성 시 선택 누락은 막히지 않는다 | 채택 | `missing[]` 을 다른 이력서의 미연결 행까지 넓히고, `resume_create`·`resume_get` 응답에 요약을 붙였다. 0단계 측정 항목에 추가. 5.3, 5.5 |
| A10 | ai-effect·major | 경험 전체 텍스트가 512토큰을 넘는다 | 채택 | `experience_chunks` + max-sim. 4.4 |
| A11 | ai-effect·major | 검색이 낡은 정본만 본다 | 채택 | 서술 정본을 이력서 행으로 일원화하고, 저장 때 연결 경험을 dirty로 만든다. 6.1 |
| A12 | ai-effect·minor | outcomes FK (I3과 같음) | 채택 | I3 참조 |
| A13 | ai-effect·minor | 예전 도구 경로로 가면 같은 증상이 난다 | 채택 | 예전 도구 응답에 출처, `alreadyUsedFor`, `missing` 을 싣는다. 사용률 로그로 도구 정리 검토(6단계). 5.3, 5.5 |
| A14 | ai-effect·minor | 골드셋이 저절로 생기지 않는다 | 채택 | 수작업 골드셋을 기본으로, silver label을 보조로. 4.5 |
| A15 | ai-effect·minor | 정규식 단일 유형 오분류가 모드를 바꾼다 | 채택 | 상위 2개 다중 라벨과 신뢰도, 분류 정확도 지표. 4.1, 4.5 |
| P-1 | perf-ops·major | 측정 없이 원인을 단정했다 | 채택 | 0단계 신설(`log_format`, waterfall), 결론을 가설로 낮췄다. 0, 1.2 |
| P-2 | perf-ops·major | written_for가 경험 은행 뒤에 묶였다 | 채택 | 3단계로 단독 배치하고, 재측정 뒤 5단계 범위를 정한다. 7 |
| P-3 | perf-ops·major | 정본 이중화의 유지비가 크다 | 채택(일부 변형) | 경험에는 정체성만 둔다. 정체성 구분용 `start_ym` 과 행이 없는 경험용 `memo` 만 더했다. 2.1, 6.1 |
| P-4 | perf-ops·major | 도구 12개 증가는 과하다 | 채택 | 4개로 줄였다. outline은 `resume_get` 의 `view`, 가져오기·병합·삭제는 웹, 연결은 save 필드. 5.2 |
| P-5 | perf-ops·major | 간선 신뢰 열과 검토 UI가 과하다 | 일부 채택 | status·confidence, 저장된 제안, REJECTED를 없앴다. A2(AI 확정 오염)를 막으려고 `source` 한 열과 웹 ✓ 버튼만 남겼다. 2.5, 3 |
| P-6 | perf-ops·major | pgvector 소스 빌드는 AGE 기각 이유와 같은 비용이다 | 채택 | `REAL[]` + Java cosine, DB 이미지 유지. D11 |
| P-7 | perf-ops·major | 신호 7개와 GPU 컨테이너가 근거 없이 많다 | 일부 채택 | embedder는 기본 CPU, GPU는 실측 뒤 오버라이드로 켠다. 다만 "DENSE는 나중" 제안은 A4와 PoC(dense 0.8 vs BM25 0.2)를 근거로 기각하고 앞당겼다. 신호별 기여는 단계마다 골드셋으로 판정한다. 4.3, 7 |
| P-8 | perf-ops·major | 평가를 지금은 잴 수 없다 | 채택 | 코드 변경 전 수작업 골드셋, leave-one-out은 보조로. 4.5 |
| P-9 | perf-ops·minor | 웹 경로에서 연결이 조용히 끊긴다 | 채택(자동 연결은 유지) | T2를 링커의 선행 조건으로 명시하고, 웹 응답에 `unlinkedWarnings[]`, PUT→GET 왕복 테스트를 넣었다. 제목 키 자동 연결은 미연결 행을 줄이는 이득이 있어 정확히 1건 조건으로 유지한다. 6.2 |
| P-10 | perf-ops·minor | 고아 첨부 정리는 당장 필요 없다 | 채택 | 즉시 삭제만 없애고 정리 작업은 두지 않는다. 1.4 T7 |
| P-11 | perf-ops·minor | OAuth 만료 행 정리가 더 근본적이다 | 채택 | 행 수와 `EXPLAIN` 을 먼저 보고 정리 작업을 검토한다. 라이브러리 동작은 미검증. 1.3 P10 |
| P-12 | perf-ops·minor | `expectedVersion` 필수는 호출을 깨뜨린다 | 채택 | 선택으로 두고, 보냈을 때만 비교한다. 웹은 항상 보낸다. T2 |
| P-13 | perf-ops·minor | `kw_norm` IMMUTABLE과 생성 컬럼 문제 | 채택 | Java `KeyNormalizer` 와 일반 컬럼. 매핑 함정은 문서화. 2.5 |
| P-14 | perf-ops·minor | 검증 안 된 외부 인용 | 채택 | PG19 SQL/PGQ와 JobMatchAI 인용을 삭제했다. 그래프 DB 기각 근거는 계정 단위, 고정 깊이, 운영 비용 셋으로 충분하다. 0, 4.3 |