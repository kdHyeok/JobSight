# JobSight 경험-지원 관계 구조 최종 설계

작성 2026-11-02 · 상태: **설계, 미구현** (1.3 의 P2, 1.4 의 T1·T2·T4·T5 와 이력서 쪽 T3 적용 — 1단계 1a) · 근거: 코드 분석 5갈래, 외부 조사, 3안 독립 설계와 심사, 반박 40건 처리. 1절은 같은 날 공개 도메인 실측으로 고쳤다. 같은 날 외부 설계 리뷰 7건과 보완 4건을 반영했다(10.2절). 이어서 받은 2차 리뷰도 반영했다(10.3절). 3·4차 리뷰로 경험의 정본을 이력서 행에서 경험 원본으로 옮기고 JobSight를 중앙 관리 지점으로 정했다(10.4절). 5차 리뷰로 병합 때 과거 개정을 보존하고, 복사한 행은 원래 개정 번호를 지키게 했다(10.5절).

아래에서 `B/` 는 `backend/src/main/java/com/jobsight/company/`, `M/` 은 `backend/src/main/resources/db/migration/`, `F/` 는 `frontend/src/components/` 를 줄여 쓴 것이다.

- **뼈대**: PostgreSQL 관계형에 관계 종류별 FK 조인 테이블을 둔다. 초안의 안 A를 유지한다.
- **반박을 받아 크게 바뀐 점**
  1. ~~경험 테이블에는 정체성만 둔다. 서술은 이력서 행에 남긴다.~~ → **10.4에서 되돌렸다.** 경험 원본이 정본이고 이력서 행은 기업별 표현이다.
  2. 간선의 status·confidence와 제안 저장을 없앴다. 출처(`source`) 열 하나만 둔다.
  3. 벡터 저장은 `real[]` + Java cosine을 기본으로 두되, 5단계에서 pgvector와 비교해 정한다(D11, 10.3).
  4. MCP 신규 도구를 12개에서 4개로 줄였다(10.4에서 작성 기준 조회를 더해 5개).
  5. "자소서를 어느 지원용으로 썼나"와 "공고를 저장하지 않는 경험 찾기"를 경험 은행보다 앞 단계에 둔다.
  6. MCP 전용 동작은 컨트롤러가 아니라 `McpActions` 에 둔다.
- **리뷰를 받아 바뀐 점** (10.2절)
  1. ~~경험 서술은 연결된 이력서 행 전부를 검색하고, 복사할 대표 행을 따로 지정한다.~~ → 10.4에서 경험 원본으로 대체됐다.
  2. 행 번호로 수정·삭제하거나 문서를 통째로 바꿀 때는 `expectedVersion` 이 필수다. 잠금은 순서만 보장하고, 예전에 읽은 행 번호의 의미는 보장하지 않는다.
  3. `experience_search` 는 저장하지 않은 공고 본문을 받는다. 공고 등록은 사용자가 고른 뒤에 한다.
  4. 직무 요구는 이미 있는 다섯 칸(공고의 지원자격, 직무의 담당업무·요구 역량·우대 역량·기술 환경)을 칸별로 쓴다. 경험과 사건을 모두 후보로 둔다.
  5. 첫 완성 목표는 "공고 하나를 저장하지 않고 평가하고, 내 경험의 원문 출처를 돌려준다"이다(3단계).
- **2차 리뷰를 받아 바뀐 점** (10.3절)
  1. 요구는 내 기술 어휘가 아니라 공고에서 뽑는다. 내게 없는 필수 기술도 `uncovered` 에 나와야 한다.
  2. ~~보존해 둔 서술(`detached_rows`)은 새 연결이 생겨도 지우지 않는다.~~ → 10.4에서 보존본 자체가 필요 없어졌다. 근거에는 인용과 해시를 함께 저장한다.
  3. 경험 관계를 바꾸는 쓰기는 계정 단위로 직렬화한 뒤 이력서 → 경험 순서로 잠근다.
  4. LLM wiki 방식을 경험 소재집(`experiences.dossier`)으로 들인다. 별도 wiki 저장소는 두지 않는다.
  5. pgvector 기각을 거두고 5단계 비교 항목으로 돌린다.
- **3·4차 리뷰: 중앙 관리로 정본을 옮겼다** (10.4절)
  1. JobSight는 개인 취업 컨텍스트를 모으는 중앙 서비스다. 어느 PC의 Codex든 같은 스킬과 계정 연결만으로 같은 사실·근거·작성 기준을 받는다. Codex는 조회·정리·작성을 맡는다.
  2. **경험 원본(사실)이 정본**이다. 한 레코드에 사실과 소재집을 두고, 소재집이 어느 사실 개정에서 만들어졌는지 남긴다. 개정 이력을 보존한다.
  3. 이력서 행은 기업별 표현이다. 쓴 경험 개정 번호와 실제 문구를 보존하고, 원본이 바뀌어도 과거 문서는 그대로다. 대표 행·보존본·"가장 최근" 대체 규칙을 없앴다.
  4. 제출 기록은 확인 수준(기록 당시 연결 / 사용자 확인 / 접수 근거 대조)을 구분한다. 상태 변경만으로 제출본이라 부르지 않는다.
  5. 근거는 텍스트 발췌로 서버에 두고 MCP로 읽는다. 로컬 경로만 있는 근거는 받지 않는다.
  6. 검색 응답에 재현 정보(자료 버전, 검색 설정, 모델, 인덱스 세대)를 싣는다. 작성 기준은 계정당 문서 하나다.
- **수치의 성격**: SQL 횟수와 바이트는 코드를 읽고 낸 추정이다. 실측은 둘이다: e5 임베딩 지연(2026-09, `docs/experience-graph-search.md`)과 공개 도메인 왕복 지연(2026-11-02, 1.0절). 이 문서의 줄 근거는 이번에 직접 열어 본 것만 남겼다.

---

## 0. 결론

**진단** (1.0절 실측)
- 이력서 화면 지연의 대부분은 **네트워크**다. 인증 없는 가벼운 요청의 서버 안 처리는 2ms 미만인데(인증이 필요한 무거운 요청은 재지 못했다, 1.0), 한국에서 보낸 요청이 Cloudflare LAX 엣지를 거쳐 서울 터널로 가서 요청 하나가 약 300ms다. 그래서 체감을 줄이는 손잡이는 DB가 아니라 **화면을 열 때 차례로 기다리는 요청 수**와 **진입 경로**다. DB·트랜잭션 개선(1.3·1.4)은 체감 속도가 아니라 정확성과 확장성 때문에 한다.
- AI가 경험을 빠뜨리거나 맞지 않는 자소서를 가져오는 원인 후보는 다섯 가지다.
  1. 쓰기 경로의 조용한 손실: 잠금 없는 행 수정, null이 삭제로 동작
  2. 자소서↔직무↔사건 관계가 없음
  3. MCP 도구가 화면 CRUD를 그대로 감쌈
  4. AI가 새 이력서를 만들 때 처음부터 일부만 고름
  5. 출처 없는 검색 결과

  저장 엔진은 원인 후보가 아니다.
- 그래서 지금의 PostgreSQL 17 하나 안에서 해결한다.

**가장 먼저 할 일**은 구조 변경이 아니라 **손실과 잘못된 대상 수정 막기(1단계)** 다. 마이그레이션은 `resumes.version` 한 열뿐이다. 지연 측정은 끝났고(1.0), 0단계에 남은 측정은 SQL 수, MCP 호출 로그, 검색 골드셋이다.

**첫 완성 목표**는 "공고 하나를 저장하지 않고 평가하고, 내 경험의 원문 출처를 찾아 돌려준다"이다(3단계). 경험 테이블 없이 기존 이력서 행만 읽어도 만들 수 있다. 그래서 경험 은행과 검색 신호를 다 만들기 전에 하네스에 붙여 효과를 확인할 수 있다.

**검토한 기술별 판단**

| 후보 | 판단 | 이유 | 이 설계에 가져오는 것 |
|---|---|---|---|
| 그래프 DB (Neo4j, Apache AGE) | 쓰지 않는다 | (1) 질의가 늘 계정 하나(노드 수백 개) 안이다. (2) 탐색이 최대 4조인의 고정 깊이라 SQL 조인으로 그대로 표현된다. (3) 운영 비용이 든다: 커스텀 DB 이미지, 확장 종속 백업·복원, 두 번째 질의 언어, Neo4j는 소유자 격리를 저장소 두 곳에서 지켜야 함 | 노드 + 타입 간선 모델링, 결과마다 경로 근거 `why[]` |
| NoSQL (MongoDB 등) | 쓰지 않는다 | 요구가 N:M 관계 중심이라 문서 모델과 반대 방향이다. 문서로 둘 곳(이력서 본문)은 이미 `resumes.content` JSONB다 | 이력서 본문 JSONB를 그대로 둔다 |
| LLM wiki | 저장소로는 쓰지 않는다. **정리 방식은 4단계에 들인다**(10.3 R16) | 파일 기반이라 계정 1000개의 소유자 격리와 트랜잭션이 없고, 웹 화면과 정본이 둘로 갈린다. 그러나 "원문을 읽어 출처와 함께 정리하고 계속 갱신한다"는 방식은 경험 추출에 필요하다 | 경험별 소재집 `experiences.dossier`(JSONB, 원문 인용·해시가 붙은 근거, 미확인 사항과 충돌, 6.5). 추출은 MCP 클라이언트가, 검토와 확정은 사용자가 웹에서 한다. 빠짐없는 목차는 `experience_index` 가 맡는다 |
| pgvector | **5단계에서 `REAL[]` 과 비교해 정한다**(10.3 R17) | 인덱스 없이도 "소유자로 거른 뒤 상위 k개만 반환"을 SQL 안에서 정확히 계산한다. `REAL[]` 은 검색마다 계정 벡터 전부(수백~천 개, 약 1MB)를 JVM으로 가져온다. 대신 공식 `pgvector/pgvector:pg17` 이미지는 Debian 기반이라, 지금의 alpine 이미지에서 바꾸면 콜레이션이 달라져 텍스트 인덱스를 REINDEX해야 할 수 있고 백업·복원이 확장에 묶인다. "인덱스가 필요 없으니 확장도 필요 없다"는 처음 근거는 약했다 | 비교 항목: 계정당 조각 수, 동시 조회 비용, Java 거리 계산과 매핑 유지비, 이미지 교체와 REINDEX 절차 |

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

단계 열은 7절의 단계 번호다. `트랙` 은 단계와 별개로 측정하면서 진행하는 성능 정리다(7절 마지막 행).

| # | 원인 (근거) | 처방 | 예상 효과(추정) | 단계 |
|---|---|---|---|---|
| N1 | 한국 요청이 Cloudflare LAX 엣지로 들어간다(1.0) | 1.0의 (A)·(B)·(C) 중 선택 | (B)면 요청당 약 300ms → 국내 왕복 | 사용자 결정 |
| P1 | 배치 페치 설정이 없다(`application.yml:10-18`). 그래서 위 EAGER 컬렉션들에서 1+N이 난다 | `spring.jpa.properties.hibernate.default_batch_fetch_size: 100` | 직무 목록 SQL → 10회 안팎 | 1 |
| P2 | 자소서 요청이 편집기가 그려진 뒤에야 나갔다(`F/ResumeQuestions.vue` onMounted) | **적용함(2026-11-02)**: 이력서·직무와 같은 `Promise.all` 로 옮기고 `questions` prop 으로 내렸다. 실패하면 `null` 을 넘겨 문항 칸이 직접 다시 부른다. `Promise.all` 을 풀어 직무를 배경으로 돌리는 것은 하지 않는다 — HTTP/2 병렬이라 이득이 서버 처리 시간 차이(ms)뿐이다 | 직렬 단계 하나, 약 0.3초 | 완료 |
| P3 | 자소서: 소유자 전체를 읽고 자바에서 거른다(`B/selfintro/SelfIntroductionService.java:41-45`). LAZY `resumeIds` 가 행마다 SQL을 낸다(`B/selfintro/SelfIntroduction.java:27-31`). 인덱스 `idx_self_introduction_resumes_resume`(`M/V20__self_introduction_resumes.sql:10-11`)를 쓰지 않는다 | `join s.resumeIds r where s.ownerId=:o and r=:rid` 조인 쿼리 | SQL 1+S → 2~3 | 트랙 |
| P4 | 이력서 목록이 content JSONB까지 읽는다(`B/resume/ResumeService.java:52-56`) | JPQL 생성자 projection | JSONB 전송 0 | 트랙 |
| P5 | 첨부 메타를 편집기가 그려진 뒤 필드마다 GET한다(`F/AttachmentField.vue:23-33`). 첨부가 있는 이력서에서는 **남은 직렬 단계**다 | 이력서 상세 응답에 `attachments:{id→meta}` 동봉. 새 경로가 아니라 응답 필드라 경로 표는 그대로다 | 직렬 단계 하나(약 0.3초), HTTP A건 → 0 | **1** |
| P6 | 편집기가 쓰는 직무 값은 이름·기업·공고·`updatedAt` 정도(`F/ResumeEditor.vue:88-95`)인데 `PositionResponse` 전체를 받는다 | `GET /api/positions?view=summary`. 쿼리 파라미터라 경로 표는 그대로다. `position_list` MCP 스키마에 `view` 가 노출되는지 확인하고, 노출된다면 설명을 단다 | 응답 크기 감소 | 트랙 |
| P7 | 소유 확인 때문에 엔티티 전체를 로드한다(`ResumeService.java:204-213`) | id 목록 projection 또는 count | 저장 SQL 감소 | 트랙 |
| P8 | 컬렉션을 새 Set으로 갈아 끼워 저장마다 전체 DELETE와 INSERT가 나간다(`Resume.java:69-70`, `Position.java:116,121`, `Company.java:108-109`) | `clear()+addAll()` 로 바꾼다(`SelfIntroduction.java:54-56` 방식) | 바뀐 부분만 갱신 | 트랙 |
| P9 | 저장소 `frontend/nginx.conf` 에는 gzip과 캐시 헤더가 없다. 다만 공개 도메인은 Cloudflare가 br 압축과 엣지 캐시(`cf-cache-status: HIT`, `max-age=14400`)를 이미 한다(1.0). 폰트는 CSS `@import` 로 직렬 로딩한다(`frontend/src/styles/main.css:1`) | 해시 자산에 `immutable`·1년 캐시, 폰트는 `<link rel=preconnect>` + `<link>`. nginx gzip은 N1-(B)로 갈 때만 의미가 있다 | 재방문 자산 왕복 0, 폰트 체인 단축 | 트랙 |
| P10 | MCP 요청마다 `findByToken(…, ACCESS_TOKEN)` 를 호출한다(`B/mcp/McpOAuthConfig.java:286`). `access_token_value`(`M/V21__persist_mcp_oauth_tokens.sql:13`)에 인덱스가 없다 | 먼저 행 수와 `EXPLAIN` 을 본다. 만료 행이 쌓였으면 정리 작업을 먼저 검토하고, 그다음 hash 인덱스(1.5) | seq scan 제거 | 트랙 |
| P11 | 키 입력마다 stringify를 2회 한다(`F/ResumeEditor.vue:53-57`). `rank()` 의 df 계산이 O(N²)이다(`SelfIntroductionService.java:82-91`) | 원본 쪽은 computed로 한 번만 계산하고, df는 루프 밖으로 뺀다 | 타이핑 지연과 CPU 감소 | 트랙 |

### 1.4 트랜잭션과 동시성 (지연보다 우선)

| # | 문제 (근거) | 처방 | 단계 |
|---|---|---|---|
| T1 | **이력서 lost update.** `mutateRows` 는 잠금 없이 읽고 문서 전체를 저장한다(`ResumeService.java:148-157,195-198`). `@Version` 도 없다. 그래서 병렬 `resume_row_add` 두 건 중 한 행이 사라질 수 있다. 웹 저장이 AI가 추가한 행을 덮어쓸 수도 있다 | `ResumeRepository.findOwnedForUpdate` 와 `setLocalLockTimeout` 를 추가한다. `B/posting/JobPostingRepository.java:25-33` 패턴을 그대로 옮긴다. `update`·`updateBasic`·`mutateRows` 와 뒤 단계의 링커·가져오기·병합이 모두 이 조회를 쓴다 | 1 |
| T2 | 화면이나 AI가 예전에 읽어 둔 문서로 덮어쓴다. **잠금만으로는 행 번호의 의미를 지키지 못한다**(10.2 R2): AI가 `projects[0]` = Planetory를 읽은 뒤 웹에서 0번을 지우면 JOBIS가 0번으로 올라오고, AI의 `index=0` 수정이 JOBIS에 Planetory 내용을 쓴다. 이때도 잠금은 정상적으로 잡힌다 | `resumes.version` 과 `@Version` 을 둔다. **행 번호로 수정·삭제하는 요청과 문서 전체 교체는 `expectedVersion` 필수**, 행 추가는 선택이다. MCP 입력 스키마에도 필수로 표시한다. 없으면 400 `VERSION_REQUIRED`, 다르면 409 `RESUME_STALE` 이다. 두 오류 모두 현재 버전을 싣지 않고 "`resume_get` 으로 다시 읽고 대상을 확인하라"고만 안내한다. 버전을 실어 주면 클라이언트가 확인 없이 그 값으로 다시 보낼 수 있다. 서버가 버전을 대신 채워 통과시키는 호환 처리도 하지 않는다(10.3 R20). 웹 편집기는 항상 보낸다. 모든 쓰기 응답이 새 `version` 을 돌려줘서, 연속 호출이 매번 다시 읽지 않고 이어진다(요청 하나가 약 0.3초, 1.0). `ObjectOptimisticLockingFailureException` 은 `GlobalExceptionHandler` 와 `McpController` 양쪽에서 409로 바꾼다. 지금은 `RuntimeException` → `INTERNAL_ERROR` 로 뭉갠다(`B/mcp/McpController.java:77-78`) | 1 |
| T3 | **null이 삭제로 동작한다.** content가 null이면 빈 문서가 된다(`ResumeService.java:216-219`). 빠진 섹션은 `[]` 가 된다(`B/resume/ResumeContent.java:34-40`). positionIds가 null이면 직무 연결이 끊긴다(`ResumeService.java:72-73,204-206`). `syncPositions` 는 null을 "공고 제목 직무 하나"로 바꾸고, 목록에 없는 직무를 지운다(`B/posting/JobPostingService.java:151-156` 이하) | **`resume_*`·`posting_update` 만** null=유지, `[]`=비움으로 바꾼다. 그 밖의 update 도구는 지금처럼 전체 교체다(5.1). `syncPositions` 는 id 없는 항목을 이름으로 기존 직무와 맞춘다 | 1 |
| T4 | `resume_row_update` 가 행을 통째로 교체한다. 배열만 `carryArrays` 로 옮기고, 빠진 문자열 필드는 지운다(`ResumeService.java:113-146`) | 행 갱신도 병합한다(null=유지, `""`=비움). 그러면 `carryArrays` 는 필요 없다 | 1 |
| T5 | 행 도구가 이력서 전체를 돌려주고(`ResumeService.java:156`), MCP는 그것을 text와 structuredContent에 두 번 싣는다(`McpController.java:65-66`) | 반환을 `{version, section, count, index, 행 머리}` 로 줄인다 | 1 |
| T6 | 행 추가가 섹션 상한 검증을 거치지 않는다(`mutateRows` 는 `normalized()` 만 호출한다) | `mutateRows` 끝에서 문서 전체를 Bean Validation으로 검증한다 | 트랙 |
| T7 | **첨부 손실.** 교체나 비우기를 하면 이전 첨부를 즉시 DELETE한다(`F/AttachmentField.vue:51-52,65`). 그런데 복제 이력서는 같은 id를 공유한다(같은 파일 51행 주석, `docs/attachments.md:71`). 그래서 복제본에서 교체하면 원본의 파일이 사라지고, 저장하지 않고 나가도 이미 지워져 있다 | 즉시 삭제만 없앤다. 고아 첨부 정리 작업은 **만들지 않는다.** 기존 문서가 고아 첨부를 함정으로 받아들이고 있고, 용량이 실제 문제가 될 때 추가한다. 그때 기준은 "7일 이상, 같은 owner의 어느 이력서도 참조하지 않음"이고, JSONB 내부 질의가 생긴다는 점을 1.5에 반영한다 | 1 |
| T8 | 트랜잭션 안에서 임베딩 서비스를 부르면 DB 연결 획득 3초 제한(`docs/backend.md:18`)에 걸린다 | 쓰기는 `embedding_dirty` 만 표시하고, 계산은 트랜잭션 밖 스위퍼가 한다(4.4) | 5 |
| T9 | 이력서와 경험을 함께 잠그는 쓰기(저장 링커, 가져오기, 병합)에서 순서가 엇갈리면 교착이 생긴다. 예: 일반 저장은 이력서 → 경험, 병합은 경험 → 이력서(10.3 R14) | **경험 관계를 바꾸는 쓰기는 계정 단위로 직렬화한다.** 트랜잭션 첫 문장에서 `pg_advisory_xact_lock(hashtextextended(owner_id::text, 0))` 을 얻고, 그다음 이력서(id 오름차순) → 경험(id 오름차순) 순서로만 잠근다. `app_users` 행을 잠그지 않고 권고 잠금을 쓰는 것은 로그인 쓰기와 엉키지 않게 하려는 것이다. 해시 충돌은 두 계정을 잠깐 함께 줄 세울 뿐이다. 외부 호출(embedder, LLM)은 이 트랜잭션에 넣지 않는다. 1인 쓰기 규모라 계정 단위 직렬화의 대기는 허용한다 | 4 |

그대로 두는 것: GET 공고 목록이 쓰기 트랜잭션인 것(자동 보관, `docs/decisions.md:85`). Hikari 기본 풀.

### 1.5 버전 열과 인덱스

마이그레이션 번호는 병합 순서대로 매긴다(2.5). 버전 열은 1단계, 인덱스는 성능 트랙이다.

```sql
-- V{n}__resume_version.sql  (1단계)
ALTER TABLE resumes ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

```sql
-- V{n}__indexes.sql  (성능 트랙)
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
- 행 번호 테스트: AI가 읽은 `version` 으로 `projects[0]` 수정을 보내기 전에 웹에서 0번을 지우면 409여야 하고, 다른 행은 바뀌지 않아야 한다.

---

## 2. 경험-지원 관계 데이터 모델

### 2.1 사용자 개념과 저장 위치

| 사용자 개념 | 저장 위치 | 비고 |
|---|---|---|
| 경험 원본 (정본) | `experiences.facts` (JSONB, 4단계): 기간, 개인 역할, 행동, 결과, 측정 범위, 근거 | **사실의 기준**이다(6.1, 10.4). 사실이 바뀌면 개정 번호가 오르고 `experience_revisions` 에 남는다 |
| 경험 소재집 | 같은 레코드의 `experiences.dossier` (JSONB) | 사실에서 만든 **작성 재료**: 상세 서술, 문항별 활용 포인트, 표현 예시, 주의사항. 어느 사실 개정에서 만들었는지(`dossier_based_on`) 남긴다(6.5) |
| 경험 종류 | `experiences.kind` CHECK | PROJECT→`projects`, ACTIVITY→`activities`, CAREER→`experiences`(제목은 `company`), TRAINING→`trainings`, OTHER는 이력서에 렌더하지 않는다 |
| 기업별 경험 문구 | 기존 `resumes.content` 의 행 + 행의 `experienceId`·`experienceRevision` | **표현**이다. 쓴 문구를 그대로 보존하고, 원본이 바뀌어도 다시 그리지 않는다(6.1) |
| 사건 | `episodes` (신규, STAR) | 검색, 임베딩, 자소서 연결의 단위. STAR(900자 상한)는 **검색용 요약**이다. 긴 원문과 상세 소재는 소재집에 둔다(6.5) |
| 결과 여러 개 | `episodes.outcomes TEXT[]` | 아무것도 결과를 가리키지 않으므로 별도 테이블을 두지 않는다. 경험 전체의 성과는 이력서 행의 `outcome` 에 있다 |
| 역량·문항 유형 | `competencies` (시스템 고정 코드 18개) | 사건 태그와 문항 유형이 같은 어휘를 쓴다 |
| 기술·도메인 키워드 | `keywords` (계정별) + `experience_keywords` | 정규화는 Java 한 곳에서 한다 |
| 회사 도메인 | 기존 `company_industries` | |
| 직무 요구 | 이미 있는 다섯 칸: 공고 `job_postings.qualifications`(지원자격), 직무 `responsibilities`(담당업무), `required_skills`(요구 역량), `preferred_skills`(우대 역량), `position_tech_stack`(기술 환경). 저장하지 않은 공고도 같은 다섯 칸으로 받는다(4.1) | 칸을 섞지 않는다. 기술 환경 목록을 필수 자격으로 계산하지 않는다. `positions.experience` 는 "취득 경험"(입사하면 얻는 것, `PositionDetail.vue:103`)이라 요구로 쓰지 않는다. `position_requirements` 테이블은 두지 않는다(2.6) |
| "이 답변을 어디에 냈나" | `self_introduction_positions` (신규) | **직접 출처** |
| "이 답변에 어떤 사건을 썼나" | `self_introduction_episodes` (신규) | 쓴 사건과 그때의 경험 개정 번호 |
| 문항 유형 | `self_introduction_competencies` (신규) | 저장하지 않은 문항은 질의 때 정규식으로 추정한다 |
| 제출 기록 | `submissions` (신규, 제출·작성 기준 트랙) | 공고별로 무엇을 냈는지. 확인 수준을 함께 남긴다(6.6) |
| 개인 작성 기준 | `writing_guides` (계정당 하나) + 경험별 `dossier.cautions` | 선호 문체, 피할 표현, 성과 설명 범위(6.7) |

### 2.2 관계도

```
[시스템]  competencies (18개, search_mode)

[계정, 모든 행에 owner_id]
self_introductions (기존)
 ├─N:M─ resumes        self_introduction_resumes      (기존, 간접 출처)
 ├─N:M─ positions      self_introduction_positions    written_for (직접 출처)     ← 2단계
 ├─N:M─ competencies   self_introduction_competencies 문항 유형                   ← 2단계
 └─N:M─ episodes       self_introduction_episodes     uses                       ← 4단계

experiences (PROJECT|ACTIVITY|CAREER|TRAINING|OTHER, 사실·소재집 + experience_revisions)                     ← 4단계
 ├─1:N─ episodes (STAR, outcomes[]) ──N:M── competencies   episode_competencies
 ├─N:M─ keywords (SKILL|DOMAIN)                             experience_keywords
 └─N:M─ resumes                                             resume_experiences ← JSON 행 experienceId·experienceRevision 에서 파생

(기존) resumes ─N:M─ positions ─N:1─ job_postings ─N:1─ companies ─1:N─ company_industries
submissions (공고별 제출 기록, 확인 수준) · writing_guides (계정당 작성 기준 하나)        ← 제출·작성 기준 트랙
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
| 이력서 ↔ 경험 | N:M (한 이력서 안에서 한 경험은 한 행). 행은 쓴 경험 개정 번호를 함께 든다 | 이력서를 지우면 연결만 사라진다. 경험 원본은 남는다. 이력서가 쓰는 경험은 **DB가 삭제를 막는다**: 경험 쪽 FK가 `NO ACTION DEFERRABLE INITIALLY DEFERRED` 다. 서비스는 먼저 409 `EXPERIENCE_IN_USE` 와 이력서 이름 목록을 준다 |
| 경험 → 개정 이력 | 1:N | 경험을 지우면 CASCADE. 개정 이력은 고치지 않고 쌓기만 한다. 병합된 경험은 지우지 않으므로(6.3) 그 개정 이력도 남는다 |
| 공고 → 제출 기록 | 1:N | 공고를 지우면 CASCADE. 기록이 가리키는 첨부는 정리 대상에서 뺀다 |
| 자소서 ↔ 직무 / 사건 / 문항 유형 | N:M (각 10 / 10 / 3 이하) | 자소서 쪽과 직무 쪽은 CASCADE. 사건 쪽 FK는 위와 같은 이유로 지연 `NO ACTION` 이다. 연결된 사건(그 사건을 가진 경험 포함)을 지우려 하면 `detach:true` 없이는 409 `EPISODE_IN_USE`, `detach:true` 면 연결 행을 먼저 지운다 |

**지연 FK로 두는 이유** (2026-11-02 확인, 워크트리 PostgreSQL 17, 롤백되는 임시 테이블)

계정 → 경험·이력서 양쪽이 CASCADE이고 연결 → 이력서도 CASCADE인 구조에서, 연결 → 경험 FK만 바꿔 가며 지웠다.

| 연결 → 경험 FK | 계정 삭제 | 연결된 경험 단독 삭제 |
|---|---|---|
| `NO ACTION` | **실패** (`violates foreign key constraint`) | 실패 |
| `RESTRICT` | **실패** | 재지 않음 |
| `NO ACTION DEFERRABLE INITIALLY DEFERRED` | 통과 | 커밋 때 실패 |

- 그래서 `resume_experiences_experience_fk` 와 `self_introduction_episodes_episode_fk` 를 지연 FK로 둔다.
- 위반이 커밋 때 나므로, 경험·사건 단독 삭제 서비스는 먼저 영향받는 지연 제약을 모두 `SET CONSTRAINTS … IMMEDIATE` 로 당기고 삭제 뒤 flush한다. 경험 삭제는 사건을 연쇄로 지우므로 `resume_experiences_experience_fk` 와 `self_introduction_episodes_episode_fk` 둘 다다(10.3 R15). 그러면 위반이 그 문장에서 나서 409로 바꾸기 쉽다.
- 경험이 지워진 뒤에 커밋하는 링커 저장도 커밋에서 실패한다. 커밋 예외(`DataIntegrityViolationException`)는 409로 바꾼다.
- **오류 코드는 REST와 MCP가 같아야 한다.** `McpController` 는 지금 일반 실행 예외를 `INTERNAL_ERROR` 로 뭉갠다(`B/mcp/McpController.java:77-78`). 업무 예외(`EXPERIENCE_IN_USE`, `EPISODE_IN_USE`, `MERGE_CONFLICT`, `RESUME_STALE`, `VERSION_REQUIRED`)와 커밋 시점 FK 위반을 **변환 함수 하나**로 REST 409와 MCP 도구 오류 코드에 함께 쓴다. `GlobalExceptionHandler` 만 고치면 MCP 경로가 남는다. 경험·사건 삭제의 409, 계정 삭제 성공, MCP 도구 오류 코드를 실제 JPA 트랜잭션 경로(Testcontainers)로 통합 테스트한다(4단계).

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

번호는 병합 순서대로 매긴다(`V{n}`). 새 파일만 추가한다. 문항 의미 검색 파일은 경험 은행과 무관해 앞당겨질 수 있다(7절).

```sql
-- V{n}__self_intro_relations.sql  (2단계: written_for 와 문항 유형)
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
-- V{n}__self_intro_embeddings.sql  (5단계: 자소서 의미 검색. DB 확장 없음)
ALTER TABLE self_introductions ADD COLUMN embedding_dirty BOOLEAN NOT NULL DEFAULT true;  -- 기존 행 전부 적재 대상
ALTER TABLE self_introductions ADD COLUMN source_revision BIGINT NOT NULL DEFAULT 0;   -- 원문이 바뀔 때마다 +1 (4.4)
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
-- V{n}__experiences.sql  (4단계: 경험 은행. 임베딩 열은 5단계부터 채운다)
CREATE TABLE experiences (
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    kind            VARCHAR(10)  NOT NULL CHECK (kind IN ('PROJECT','ACTIVITY','CAREER','TRAINING','OTHER')),
    title           VARCHAR(200) NOT NULL,          -- 경력은 회사명
    title_key       VARCHAR(200) NOT NULL,          -- Java KeyNormalizer 가 채운다(생성 컬럼 아님)
    start_ym        VARCHAR(200),                   -- 정체성 구분용. 사용자가 쓴 문자열 그대로
    memo            VARCHAR(2000),                  -- 자유 메모. 검색 텍스트에 더한다
    facts           JSONB        NOT NULL DEFAULT '{}', -- 경험 원본: 사실의 기준(6.5). 기간·개인 역할·행동·결과·측정 범위·근거
    dossier         JSONB,                          -- 소재집: 사실에서 만든 작성 재료(6.5). 상세 서술·문항별 활용·표현 예시·주의
    revision        INT          NOT NULL DEFAULT 1,  -- 사실 개정 번호. facts 가 바뀔 때만 +1 하고 experience_revisions 에 남긴다
    dossier_based_on INT,                           -- 소재집을 만든 사실 개정 번호. revision 과 다르면 '소재집 갱신 필요'
    reviewed_revision INT,                          -- 사용자가 웹에서 검토를 마친 개정 번호. revision 보다 낮거나 없으면 'AI 기록'
    merged_into     UUID,                           -- 병합된 source 면 target id. 지우지 않고 남겨 과거 (경험 id, 개정) 참조를 보존한다(6.3)
    version         BIGINT       NOT NULL DEFAULT 0,  -- 동시 수정 검사(@Version)
    embedding_dirty BOOLEAN      NOT NULL DEFAULT true,
    source_revision BIGINT       NOT NULL DEFAULT 0,  -- 검색 텍스트(사실·소재집·메모·키워드)가 바뀔 때마다 +1 (4.4)
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    UNIQUE (id, owner_id),
    FOREIGN KEY (merged_into, owner_id) REFERENCES experiences (id, owner_id) DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX experiences_owner_key_idx ON experiences (owner_id, kind, title_key);
CREATE INDEX experiences_embedding_dirty_idx ON experiences (updated_at) WHERE embedding_dirty;

CREATE TABLE experience_revisions (                 -- 개정 이력. 당시의 사실·소재집·사건을 함께 보존한다. 고치지 않고 쌓기만 한다
    owner_id      UUID        NOT NULL,
    experience_id UUID        NOT NULL,
    revision      INT         NOT NULL,
    change_kind   VARCHAR(8)  NOT NULL CHECK (change_kind IN ('INIT','ADD','CORRECT','MERGE')),
    facts         JSONB       NOT NULL,
    dossier       JSONB,
    episodes      JSONB       NOT NULL DEFAULT '[]',
    source        VARCHAR(4)  NOT NULL CHECK (source IN ('USER','AI')),
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (experience_id, revision),
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE
);

CREATE TABLE experience_chunks (                    -- 경험 원본(사실·소재집·메모) 텍스트를 480토큰 이하로 나눈 조각, max-sim
    owner_id      UUID        NOT NULL,
    experience_id UUID        NOT NULL,
    chunk_no      INT         NOT NULL,
    field         VARCHAR(40) NOT NULL,           -- 조각의 출처 필드(facts.results 등). why 표시용
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
    source_revision BIGINT       NOT NULL DEFAULT 0,
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
CREATE TABLE resume_experiences (                   -- JSON 행 experienceId·experienceRevision 에서 파생. 손으로 쓰지 않는다
    owner_id            UUID NOT NULL,
    resume_id           UUID NOT NULL,
    experience_id       UUID NOT NULL,
    based_on_experience_id UUID NOT NULL,           -- 이 기업용 문구를 쓸 때 본 경험. JSON 행의 experienceId 그대로다(병합된 source 일 수 있다)
    based_on_revision      INT  NOT NULL,           -- 그때의 개정 번호. experience_id 는 병합을 따라간 지금의 경험이다
    PRIMARY KEY (resume_id, experience_id),
    FOREIGN KEY (resume_id, owner_id)     REFERENCES resumes (id, owner_id)     ON DELETE CASCADE,
    -- CASCADE 가 아니다: 쓰이는 경험의 삭제를 DB 가 막는다. 지연이어야 계정 삭제 연쇄가 통과한다(2.3)
    CONSTRAINT resume_experiences_experience_fk
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX resume_experiences_experience_idx ON resume_experiences (experience_id);

CREATE TABLE self_introduction_episodes (           -- uses
    owner_id             UUID       NOT NULL,
    self_introduction_id UUID       NOT NULL,
    episode_id           UUID       NOT NULL,
    experience_id        UUID       NOT NULL,   -- 답변을 쓸 때 사건이 속했던 경험. 병합으로 사건이 옮겨가도 바꾸지 않는다
    experience_revision  INT        NOT NULL,   -- 그 경험의 개정 번호. (experience_id, experience_revision) 쌍으로 개정 이력을 찾는다
    source               VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, episode_id),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (experience_id, owner_id)        REFERENCES experiences (id, owner_id)        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT self_introduction_episodes_episode_fk   -- 2.3 과 같은 이유로 지연 NO ACTION
    FOREIGN KEY (episode_id, owner_id)           REFERENCES episodes (id, owner_id)           ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX self_introduction_episodes_episode_idx ON self_introduction_episodes (episode_id);
```

```sql
-- V{n}__submissions_and_guides.sql  (제출·작성 기준 트랙, 2단계 이후)
ALTER TABLE job_postings ADD CONSTRAINT job_postings_id_owner_key UNIQUE (id, owner_id);
CREATE TABLE submissions (                          -- 공고별 제출 기록(6.6). 고치지 않고 쌓는다
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL,
    posting_id      UUID         NOT NULL,
    level           VARCHAR(16)  NOT NULL CHECK (level IN ('LINKED','USER_CONFIRMED','RECEIPT_VERIFIED')),
    content_hash    VARCHAR(64)  NOT NULL,          -- 스냅샷 내용의 해시. 같은 요청을 다시 보내면 같은 기록을 돌려준다
    resumes         JSONB        NOT NULL,          -- [{resumeId, name, version, content}]
    self_intros     JSONB        NOT NULL,          -- [{id, question, answer, updatedAt, experienceRevisions}]
    attachment_ids  UUID[]       NOT NULL DEFAULT '{}', -- 스냅샷이 가리키는 첨부. 정리 작업이 생겨도 지우지 않는다
    receipt         JSONB,                          -- RECEIPT_VERIFIED 의 근거(접수 메일 발췌, 접수 번호, 첨부 id)
    recorded_at     TIMESTAMPTZ  NOT NULL,
    UNIQUE (posting_id, content_hash, level),
    FOREIGN KEY (posting_id, owner_id) REFERENCES job_postings (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX submissions_owner_idx ON submissions (owner_id, posting_id);

CREATE TABLE writing_guides (                       -- 계정당 작성 기준 하나(6.7)
    owner_id   UUID PRIMARY KEY REFERENCES app_users (id) ON DELETE CASCADE,
    body       TEXT        NOT NULL,                -- 선호 문체, 피할 표현, 성과 설명 범위. Markdown
    version    INT         NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE self_introductions ADD COLUMN writing_context JSONB;  -- 작성 당시 재현 정보(6.7)
```

엔티티 규칙과 함정:
- 새 엔티티에는 EAGER 컬렉션을 두지 않는다. `experiences`·`episodes` 에는 `@Version` 을 둔다.
- `ExperienceKind`, `Competency`, `LinkSource` 는 Java enum으로 둔다. MCP 입력 스키마에 enum으로 나가므로 AI가 없는 값을 지어낼 수 없다.
- 입력 DTO는 평평한 record와 enum으로 만든다(`docs/mcp-plugin.md:137` 의 스키마 생성기 제약).
- `ddl-auto: validate`(`application.yml:14`)에서 `REAL[]` 를 `float[]` 로 매핑하려면 `@JdbcTypeCode(SqlTypes.ARRAY)` 가 필요할 것으로 본다(미검증). 안 되면 `bytea` 로 바꾸고 Java에서 변환한다. 처음 `REAL[]` 열을 만드는 단계(4단계)의 첫 커밋에서 확인한다.
- `title_key`·`norm_key` 는 DB 함수나 생성 컬럼 대신 Java `KeyNormalizer` 한 곳에서 계산해 일반 컬럼에 저장한다. 별칭 시드 사전(`k8s→kubernetes`, `js→javascript` 등)도 이 클래스에 둔다.

### 2.6 지금 만들지 않는 것

| 항목 | 이유 | 추가 시점 |
|---|---|---|
| `position_requirements`(필수/우대 태그) | 이미 있는 다섯 칸(2.1)을 칸별로 쓰면 첫 판 신호에 충분하다 | 칸 본문에서 키를 놓치는 사례가 골드셋에서 확인될 때 |
| `outcomes` 테이블 | 결과를 가리키는 관계가 없다 | 결과 단위 연결이 필요해질 때 |
| 간선 status·confidence, 저장된 자동 제안, REJECTED | 1인 규모에 검토 UI까지 붙어 과하다. 제안은 질의 때 계산한다 | 저장된 제안이 실제로 필요해질 때 마이그레이션으로 열 추가 |
| 접수 근거 자동 대조 | 제출 기록은 확인 수준만 남긴다. 접수 메일·파일과 제출 스냅샷을 자동으로 대조하지 않는다(6.6) | 대조 수요가 확인될 때 |
| 작성 검토 이력 테이블 | 초기에는 작성 기준 문서 하나와 경험별 주의사항으로 충분하다 | 검토 결과를 질의해야 할 때 |
| 첨부 바이너리의 MCP 전달, 첨부 형식 확대 | 근거는 텍스트 발췌로 서버에 둔다. 첨부는 메타와 웹 주소만 MCP로 준다(6.5) | 발췌로 부족한 사례가 나올 때 |
| 사건 단위 기술 태그 | 사건은 경험의 기술 태그를 물려받는다. 물려받은 일치는 `why` 에 `via: PARENT_EXPERIENCE` 로 표시한다(4.2) | 필요가 보일 때 |
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
| 자소서 → 사건 (uses) | "쓴 사건" 선택 | `self_intro_create/update(episodes)` | 5단계: 답변 조각과 사건의 cosine 상위 후보 |
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
   - `trust` 는 **이 관계를 누가 확인했나**만 뜻한다(10.2 R8). "확정"은 사용자가 연결이 맞다고 눌렀다는 뜻이지, 실제 구현·개인 기여·운영 성과를 검증했다는 뜻이 아니다. SKILL에도 이 구분을 적는다.
   - 원문 출처는 따로 돌려준다: 이력서 행은 `source{resumeId, resumeName, section, index, resumeVersion, textHash, excerpt}`, 사건은 `{episodeId, version}`.

---

## 4. 검색 설계

### 4.1 질의 모드

| 입력 | 쓰는 신호 |
|---|---|
| `positionId` / `postingId` / 저장하지 않은 `posting` (FIT) | REQUIREMENT, DOMAIN, PAST_USE, DENSE·LEXICAL(다섯 칸 본문을 질의로). 저장하지 않은 공고는 `companyName` 이 등록 기업과 맞을 때만 PAST_USE를 쓴다 |
| `question` | 먼저 문항 유형을 정한다. 순서는 입력값 → 저장된 USER 태그 → 정규식 → (5단계 이후) 가장 가까운 과거 문항의 USER 유형이다. **상위 2개를 다중 라벨**로 두고 `query.questionTypes[{code, confidence, from}]` 에 싣는다. 두 라벨의 search_mode가 다르면 두 모드의 신호를 합친다 |
| ↳ COMPETENCY 유형 | COMPETENCY, PAST_QUESTION, DENSE, LEXICAL |
| ↳ FIT 유형 (지원동기, 포부, 직무 이해) | 직무(등록 id 또는 저장하지 않은 본문)가 주어지면 FIT 신호 전부. **PAST_QUESTION은 같은 회사·같은 업종의 written_for로 제한**한다. 직무가 없으면 DENSE와 `warnings:["NO_POSITION"]` 을 준다 |
| ↳ SEMANTIC 유형 | DENSE, PAST_QUESTION |
| `q` (자유 문장) | LEXICAL, DENSE |

**저장하지 않은 공고** (10.2 R3): 공고 20개를 주고 "지원할 만한 것만 추려 줘"라고 할 때, 고르기 전의 공고까지 등록하게 되는 흐름을 없앤다.
- 입력은 `posting{companyName?, industries?[], positionName?, qualifications?, responsibilities?, requiredSkills?, preferredSkills?, techStack?[]}` 다. 2.1의 다섯 칸과 같은 구분이다. 클라이언트가 공고 원문에서 뽑은 요구는 `requirements[]` 로 함께 넘긴다(4.2). 새 도구나 테이블은 없다.
- 서버는 이 입력을 저장하지 않는다. 사용자가 고른 공고만 그다음에 `posting_create` 로 저장한다.
- `companyName` 은 계정의 기업 이름과 정규화 일치로만 맞춘다. 맞으면 PAST_USE·DOMAIN에 쓰고, 안 맞으면 `industries` 로만 DOMAIN을 계산한다.
- 서버는 직무 없이 FIT 문항이 오면 위 warning을 준다.

### 4.2 후보와 신호

**후보 단위**
- **사건과 경험을 모두 후보로 둔다** (10.2 R4). 사건이 있는 경험도 경험 자체가 후보다(`type: EXPERIENCE`). Planetory에 '전처리 최적화' 사건만 있고 'HDFS 구축'은 경험 설명에만 있으면, HDFS 질의는 경험 후보로 돌아온다.
- **같은 근거는 묶는다.** 한 경험과 그 사건이 함께 오르면 경험 한 줄 아래에 사건을 `episodes[]` 로 묶는다. 순위는 묶음 안 최고 점수다.
- **물려받은 일치는 경로를 밝힌다.** 사건에는 기술 태그가 없어 경험의 태그로 일치한다. 이때 `why` 에 `via: PARENT_EXPERIENCE` 를 붙인다.
- **은행에 없는 이력서 경험 행**도 후보로 넣는다(`type: RESUME_ROW`, 출처 `source{resumeId, resumeName, section, index, resumeVersion, textHash, excerpt}`). 그래서 가져오기 전에도 검색이 비지 않는다. RESUME_ROW에는 LEXICAL과 REQUIREMENT(행 `techStack` 과 본문)만 적용한다. **3단계는 이 후보만으로 동작한다.** 4단계부터 연결된 행은 경험 후보로 바뀌고 미연결 행만 남는다. 응답 형식은 그대로 두고 자료원만 바꾸는 전환 경로다(10.4 R29). 같은 (kind, title_key) 행은 한 줄로 묶고 출처를 모두 싣는다.

| 신호 | 계산 | 출처 반영 |
|---|---|---|
| REQUIREMENT | **요구는 공고에서 뽑고, 내 어휘로 뽑지 않는다**(10.3 R12). ① 요구 목록은 클라이언트가 공고 원문에서 뽑아 `requirements[{text, field, kind: SKILL\|QUALIFICATION, quote}]` 로 넘긴다. 내 자료에 없는 기술도 요구로 남아야 `uncovered` 에 나오기 때문이다. 넘기지 않으면 서버가 기술 환경 목록과 일반 별칭 시드 사전(내 어휘가 아니다)으로 뽑고 `query.requirementsSource: SERVER_DICTIONARY` 와 `warnings:["REQUIREMENTS_PARTIAL"]` 를 준다. ② 뽑은 요구를 내 경험과 대조한다. 내 SKILL 어휘(4단계부터 `keywords`, 3단계는 행 `techStack`)는 별칭 일치와 검색 확장에만 쓴다. ③ `kind: QUALIFICATION`(경력 연수, 학력, 고용 조건)은 점수에 넣지 않고 `qualifications[]` 로 공고 인용과 함께 돌려준다. 충족 판단은 사용자와 클라이언트가 한다. 일치는 정확 일치, 별칭 일치, ASCII 토큰 포함 관계("AWS EC2"⊇"AWS")이고 포함 관계는 0.5배다. **칸마다 가중이 다르다**: 지원자격·요구 역량 1, 담당업무 0.7, 우대 역량 0.5, 기술 환경 0.3(초기값, 골드셋으로 조정). 채널 안 점수는 Σ 칸 가중 × 희소도 `ln(1 + N/df)` 다(N = 후보 경험 수, df = 그 기술을 가진 경험 수, 3단계는 행 묶음 수). 1 ≤ df ≤ N 이라 늘 ln 2 이상이고, 흔한 기술일수록 작다. 예전 식 `log(N/(1+df))` 는 N = df = 1 에서 −0.69가 되어 일치가 감점이 됐다(10.2 R7). `why` 에 맞은 칸(`field`)과 공고 인용(`quote`)을 적는다 | AI·추정 0.5배 |
| DOMAIN | 대상 회사 `company_industries` 정규화 값 = 경험의 DOMAIN 키워드(USER/AI), 또는 3절의 파생 도메인(추정) | AI·추정 0.5배 |
| PAST_USE | 같은 직무(3) > 같은 회사(2) > 같은 업종(1)에 written_for로 낸 답변이 uses로 쓴 사건 | USER 1배, AI 0.5배 |
| COMPETENCY | 문항 유형 코드가 사건에 붙어 있음 | AI 0.5배 |
| PAST_QUESTION | 문항↔과거 문항 벡터 상위 30 → 리랭커 K=10 → 그 문항들이 uses로 쓴 사건. FIT 유형이면 같은 회사·업종으로 제한 | USER·AI 모두. AI는 0.5배 |
| DENSE | 질의 임베딩과 사건 벡터, 경험 조각 벡터(max-sim)의 cosine. owner로 좁힌 뒤 Java에서 정확히 정렬 | — |
| LEXICAL | 기존 `rank()`(df를 루프 밖으로 뺀 판)를 사건 텍스트, 경험 텍스트(사실·소재집·메모), RESUME_ROW 텍스트에 적용. 직무 다섯 칸 본문은 요구 태그 유무와 관계없이 **항상** 질의에 넣는다 | — |

**`uncovered[]`**: 받쳐 줄 후보가 없는 요구의 목록이다. 요구를 공고에서 뽑으므로 내 자료에 없는 기술도 여기에 나온다. 요구가 서버 사전으로만 뽑혔으면(`REQUIREMENTS_PARTIAL`) 빠진 요구가 있을 수 있다고 함께 알린다. **지원자격·요구 역량 칸에서 나온 키만** 넣는다. 우대 역량·기술 환경 칸의 키는 결핍으로 보고하지 않는다(Databricks, Tableau가 나열됐다고 결핍이 아니다). 결핍을 단정하지 않도록 두 가지 장치를 둔다.
- 일치하지 않은 이유를 구분한다: `{key, reason: NO_MATCH | TAG_MISMATCH_POSSIBLE}`. 후보 텍스트에 그 키 문자열이 보이면 `TAG_MISMATCH_POSSIBLE` 이다.
- 미등록 행이 있거나(`counts.unlinkedRows > 0`), 사건 없는 경험의 비율이 절반을 넘으면 `coverageIncomplete:true` 를 함께 준다. SKILL 규칙: 이때 AI는 "경험이 없다"고 단정하지 않고, 가져오기나 사건 정리를 권한다.

**`alreadyUsedFor[]`**: 후보 사건이 written_for로 이미 쓰인 회사·직무다.
- 지금 지원하는 회사와 같으면 `sameCompany:true` 다.
- FIT 유형 답변을 다른 회사용으로 쓴 경우에는 `otherCompanySpecific:true` 를 주고, SKILL에서 그 답변 본문의 재사용을 금지한다.

### 4.3 융합, 필터, 다양성, 트랜잭션

1. **융합은 가중 RRF(k=60)** 다. 척도가 다른 점수는 더하지 않는다. 후보의 신호별 기여는 `w × 1/(k + rank)` 이고, `w` 는 그 후보의 출처 가중(USER 1, AI·추정 0.5)이다. 0.5배를 신호 목록 안의 순위에만 넣으면, 후보가 하나이거나 모두 AI 기록일 때 순위가 그대로라 기여가 줄지 않는다(10.2 R7). 신호가 드문드문한 구조에서 RRF가 "몇 개 목록에 나왔나"로 기우는지는 4.5 평가로 확인한다.
2. **신호는 하나씩 켠다.** 3단계는 LEXICAL·REQUIREMENT, 5단계에 DENSE를 더한다. DOMAIN·PAST_USE·COMPETENCY·PAST_QUESTION은 골드셋에서 이득이 확인된 것만 6단계에 넣는다.
3. 계정당 후보가 수백 개라 신호마다 후보를 자르지 않는다.
4. 하드 필터는 USER 간선에만 적용한다.
5. **다양성**: 상위 `limit` 안에서 한 경험 묶음에는 사건을 2개까지만 펼친다.
6. **트랜잭션**: 오케스트레이터 `ExperienceSearch` 에는 `@Transactional` 이 없다.
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
- 실측 CPU 지연은 질의 19ms, 문서 142ms다. 적재는 백그라운드라 CPU로 시작하고, GPU는 5단계 측정 뒤에 판단한다.
- 리랭커 CPU 지연은 재지 않았다. 5단계에서 재고, 목표를 넘으면 그때 GPU 오버라이드를 켠다.

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
- **경험**: 경험 원본의 사실(`facts`)·소재집(`dossier`)·메모 텍스트를 필드·문단 경계로 480토큰 이하 조각으로 나눈다. 점수는 조각들의 max-sim이다. 기업별 이력서 문구는 경험 조각에 넣지 않는다(미연결 행만 RESUME_ROW 후보로 따로 본다). 사건이 생겨도 경험 조각은 유지한다. 사건으로 정리되지 않은 업무가 설명이나 성과에만 있을 수 있기 때문이다.
- **자소서**: 질문 1조각, 답변은 480토큰 조각들.

**계산 시점 (dirty + 원문 버전 + 해시 + 스위퍼, D14)**
- 쓰기 경로는 대상에 `embedding_dirty = true, source_revision = source_revision + 1` 만 표시한다. dirty가 이미 true여도 버전은 오른다.
  - 경험의 `source_revision` 이 오르는 때: 사실·소재집·메모·키워드가 바뀔 때. 이력서 저장은 경험 임베딩을 건드리지 않는다(10.4). 사실 개정 번호(`revision`)와는 다른 값이다. `revision` 은 사실이 바뀔 때만 오른다.
  - 자소서와 사건은 텍스트를 바꾸면 오른다.
- 스위퍼는 `@Scheduled(fixedDelay = 3s)` 로 돈다.
  1. 테이블마다 `WHERE embedding_dirty ORDER BY updated_at LIMIT 32` 로 `id`, `source_revision`, 텍스트를 읽는다.
  2. 조각별 `text_hash` 를 비교해 바뀐 조각만 `/embed` 한 번으로 계산한다. 트랜잭션 밖이다.
  3. **한 트랜잭션**에서 대상 행을 `… WHERE id = ? AND source_revision = ? FOR UPDATE` 로 다시 읽고, 맞을 때만 조각을 교체하고 dirty를 푼다. 버전이 달라졌으면 아무것도 쓰지 않고 다음 회차에 다시 계산한다(10.2 R6). `updated_at` 은 비교하지 않는다. 이력서 원문이 바뀌어도 경험의 `updated_at` 은 그대로일 수 있기 때문이다.
- 스케줄러를 두지 않는다는 기존 결정의 이유(`docs/decisions.md:85-86`, 꺼져 있던 기간을 건너뜀)는 이 경우에 해당하지 않는다. dirty 플래그가 남아 있어 재기동 후 따라잡는다.
- embedder가 죽어 있어도 저장은 성공하고, 검색은 `degraded` 표시와 함께 동작한다.

### 4.5 평가

- **기본 골드셋**: 0단계에서 사용자가 실제 지원 건으로 만든 질의 10~20개와 정답(1.2). 코드를 바꾸기 전에 지금의 `self_intro_list` 로 기준선을 잰다.
- **보조 골드셋 (silver)**: 자소서 답변 본문에 프로젝트명이나 경력 회사명이 문자열로 나오는 경우를 약한 정답으로 쓴다. 사람은 표본만 검토한다.
- **누적 골드셋**: USER written_for와 USER uses를 가진 답변. 이때 그 답변 자신의 링크는 PAST_USE와 PAST_QUESTION에서 뺀다(leave-one-out). 같은 사용자 라벨이 신호와 정답 양쪽에 들어가는 자기 강화 편향이 남으므로 보조 지표로만 쓴다.
- **지표**: Recall@5, nDCG@10, **문항 유형 분류 정확도**(정규식 상위 1·상위 2 적중률). 타사 혼입은 둘로 나눠 잰다(10.3 R18).
  - **타사 문구 혼입률**: 상위 5개 중 다른 회사 전용 FIT 답변(지원동기·포부·직무 이해)의 비율. 0이어야 한다.
  - **경험 재사용률**: 상위 5개 중 다른 회사 지원에 쓴 적 있는 경험·사건의 비율. 정상적인 재사용이라 낮출 목표가 아니다. `alreadyUsedFor` 표시가 맞는지 보는 지표다.
  - PoC 스크립트의 1등 지표는 Hit@1이다. 정답이 여러 개인 질의에서는 Recall@1과 다르다.
  - 합성 문항의 표현 바꾸기 평가는 의미 검색의 가능성만 보여 준다. 개인 경험 선택과 사실 정확성은 입증하지 않는다.
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
  - 행 번호를 쓰는 수정·삭제와 문서 전체 교체는 `expectedVersion` 필수, 행 추가는 선택이다(T2). 모든 쓰기 응답은 새 `version` 을 준다.
  - 그래서 `McpTools.java:123` 이 모든 `update` 에 붙이는 "전체 교체" 문구를 도구별 설명으로 바꾼다. SKILL.md 공통 규칙(`plugins/jobsight/skills/jobsight/SKILL.md:23-25`, "수정은 전체 교체", "지우려면 null")도 도구별 규칙으로 나눈다.
- **오류에는 필드 경로를 넣는다.** `McpSchema.validateShape`(`B/mcp/McpSchema.java:43`)에 경로 인자를 하나 넘긴다. 업무 오류 코드는 REST와 MCP가 같다(2.3).
- text와 structuredContent에 이중으로 싣는 것은 호환을 위해 유지하고, 실리는 내용 자체를 줄인다.

### 5.2 새 도구 (5개, 54 → 59)

| 도구 | 입력 | 출력 | 쓰기 |
|---|---|---|---|
| `experience_index` | `kinds?[]`, `ids?[]`(10개 이하. 주면 상세 모드), `cursor?` | **맨 앞**: `counts`(종류별 경험 수, 사건 수, AI 기록 수, 미등록 행 수, 출처 미상 자소서 수)와 `complete`. 미등록 행이 있으면 `importHint`(웹 가져오기 화면 안내). 경험 한 줄: `{id, kind, title, period, skills[], domains[], episodes[{id, title, competencies}], inResumes[{id,name}], usedInSelfIntros}`. 그다음 **`unlinkedRows[]`**: 은행에 없는 행을 (kind, title_key)로 묶은 것. 정렬은 PROJECT → CAREER → ACTIVITY → TRAINING → OTHER, 한 쪽에 60개. 상세 모드는 사실·소재집·근거(인용·주변 문맥·위치·해시, 첨부는 메타와 웹 주소), 개정 번호와 검토 상태, 연결된 이력서 행의 개정 번호와 "재검토 필요"·"원본 반영 검토" 표시, 사건 STAR, 키워드(trust), 이 사건을 쓴 자소서 `{id, question, company · position, trust}` | × |
| `experience_search` | `positionId?`, `postingId?`, `posting?`(저장하지 않는 공고 다섯 칸, 4.1), `requirements?[]`(클라이언트가 공고에서 뽑은 요구, 4.2), `question?`, `q?`, `competencies?[]`, `kinds?[]`, `limit?=8 (≤20)` | `query`(직무, 회사, 업종, 요구 키와 칸, 문항 유형과 신뢰도, 이 직무용 직접 출처 자소서 `{id, question}`), `results[]`(5.4), `uncovered[]`, `coverageIncomplete`, `warnings[]`, `degraded[]` | × |
| `experience_save` | `{id?, expectedVersion?, kind, title, startYm, memo, facts?, dossier?, changeKind?: ADD\|CORRECT, skills?[], domains?[], episodes?[{id?, title, situation, task, action, result, outcomes[], competencies[], sortOrder}], removeEpisodeIds?[]}`. null=유지. `facts`·`dossier` 는 6.5 모양이다. 기존 사실 값을 바꾸거나 지우면 `changeKind:CORRECT`, 추가만이면 ADD다. 서버가 판정해 맞지 않으면 400. 근거에 인용이 없거나 로컬 경로뿐이면 400. 키워드는 이름으로 받아 서버가 정규화한다 | `{id, version, revision, needsReview[](정정으로 재검토가 필요해진 이력서·자소서), episodes[{id, version}], normalizedSkills[], warnings[]}` | ○ (출처 AI) |
| `resume_add_experiences` | `id`, `items[{experienceId, from?: FACTS\|ROW, rowRef?{resumeId, section, index}}]`, `expectedVersion?`(행 추가라 선택) | 경험마다 행을 만들어 종류에 맞는 섹션 끝에 붙인다. 기본(`FACTS`)은 경험 원본의 사실로 중립 문구를 만들고, `ROW` 는 사용자가 고른 기존 기업용 행의 문구에서 시작한다. `FACTS` 는 행에 `experienceId` 와 지금의 `experienceRevision` 을 쓰고, `ROW` 는 복사한 행의 `experienceId`·`experienceRevision` 을 그대로 가져온다(10.5 R32). 정정 전 개정의 문구를 복사하면 새 행에도 "재검토 필요"가 붙는다. 전부 들어가거나 전부 실패한다. 이미 있는 경험은 건너뛴다. 반환은 outline 모양과 새 `version` | ○ |
| `writing_guide_get` | 없음 | `{version, body, updatedAt}`. 경험별 주의사항은 `experience_index` 상세에 있다(6.7) | × |

**3단계 최소판** (경험 테이블 전): `experience_index` 는 계정의 모든 이력서 경험 행을 `(kind, title_key, 시작 연월 숫자)` 로 묶어 `groups[{kind, title, period, sources[{resumeId, resumeName, section, index, resumeVersion}]}]` 와 `counts`·`complete` 만 준다. 가져오기 계획(6.3)과 같은 묶음 함수를 쓴다. AI가 새 이력서를 만들 때 이 목차와 대조하게 해서 "프로젝트 누락"을 먼저 막는다. `experience_search` 첫 판은 이 묶음을 RESUME_ROW 후보로 쓴다.

웹 전용으로 두는 것 (사람이 검토해야 하는 작업):
- 경험 가져오기와 병합
- 경험 원본 검토 확정, 제출본 확인, 작성 기준 수정
- 경험·사건 삭제
- AI 기록 확정과 삭제
- 키워드 별칭 편집

### 5.3 바뀌는 기존 도구 (모두 `McpActions` 래퍼)

| 도구 | 변경 |
|---|---|
| `resume_get` | `view?: full\|outline` 을 둔다. outline은 `version`, 섹션별 `count`, 행 머리 `{index, experienceId, title, period}`, `missing[]`, `unlinkedRows[]` 다. **full이어도 응답 머리에 `missing` 요약을 붙인다** |
| `missing[]` 정의 | 다음 두 가지를 합친 것이다. 계정의 은행 경험 중 이 이력서에 없는 것, 그리고 **다른 이력서에만 있는 미연결 행 묶음**(가져오기 계획과 같은 그룹 키). `possibleMatch` 가 있으면 `maybeUnlinked` 로 표시한다 |
| `resume_create` | 응답에 `missing` 요약을 붙인다. AI가 생성 때 일부만 고른 경우도 응답 기본값으로 드러난다 |
| `resume_update` | content·positionIds·빠진 섹션이 null이면 유지한다(1단계). `expectedVersion` 은 **필수**다(문서 전체 교체, 1단계). **`LINKED_ROWS_MISSING` 가드**(4단계): 저장 전 문서의 `experienceId` 중 새 문서에서 사라진 것은 `removedExperienceIds` 에 모두 적혀 있어야 하고, 아니면 409다. 래퍼에 있으므로 웹에는 걸리지 않는다. 설명은 "섹션 일부 편집은 `resume_row_*`, 경험 행 추가는 `resume_add_experiences`"로 바꾼다 |
| `resume_row_*` | `FOR UPDATE` 로 직렬화한다. `update`·`delete` 는 `expectedVersion` **필수**, `add` 는 선택이다. 다르면 409 `RESUME_STALE`. update는 병합한다(`experienceId` 보존). 반환은 개요와 새 `version` 이다. 모두 1단계 |
| `self_intro_list` | 필터 `positionId?`·`postingId?`·`companyId?`·`questionType?`·`unlinked?` 와 `limit?=10` 을 둔다. 반환은 `answerPreview`(300자), `score`, `matchedTerms`, 출처 `{direct:[{position, company, trust}], indirect:[{resume, position, company}]}`, `questionTypes`, `episodes[{id,title,experienceId,experienceRevision}]`, `alreadyUsedFor[]` 다. **직접 출처가 0건이면** 빈 배열 대신 `{fallback:"indirect", results:[…각 항목 "출처 미상"]}` 을 준다. `q` 의 100자 제한을 스키마에 드러내고, 넘으면 400을 준다. 원문은 `self_intro_get` 으로 읽는다. 웹 REST 목록은 그대로 둔다 |
| `self_intro_create` / `_update` | `positionIds?`, `episodes?[{id, experienceId, experienceRevision}]`, `questionTypes?`, `writingContext?`(6.7) 를 추가한다. `episodes` 를 보내면 항목의 세 키가 모두 필수다. 경험 id를 사건의 현재 소속에서 추론하거나 병합 target으로 치환하지 않는다. 소유자, 개정 존재, 사건과 경험의 정체성 검사는 6.8을 따른다. 이 필드는 REST에도 생기지만 선택이고 null=유지라 웹이 새 필드를 보내지 않는 기존 경로에는 영향이 없다. 규칙: 생성 시 "이력서 또는 직무 1개 이상", 수정 시 0개 허용. 지금은 생성과 수정이 같은 DTO의 `@Size(min = 1)` 을 공유하므로(`B/selfintro/dto/SelfIntroductionRequest.java:12`) 생성용과 수정용 요청을 분리한다. 빈 목록에서 `ids.get(0)` 로 `IndexOutOfBoundsException` 을 던지는 `requireOwnedResumes`(`SelfIntroductionService.java:118-123`)도 함께 고친다 |
| `posting_update` | `positions`·`steps` 가 null이면 유지한다. id 없는 직무는 이름으로 기존 직무와 맞춘다(1단계) |
| `self_intro_get` | 사건 연결을 `episodes[{id,title,experienceId,experienceRevision}]` 로 돌려준다. 저장된 작성 근거 쌍을 그대로 반환한다. 조회나 재저장 때 현재 사건 소속에서 경험 id를 다시 계산하지 않는다(6.8) |
| `posting_set_status` | `SUBMITTED` 로 바꾸면 그 공고의 기록 당시 연결 자료로 제출 기록(LINKED)을 남기고 응답에 `submission{id, level}` 을 싣는다. 같은 요청을 다시 보내면 같은 기록을 돌려준다(6.6). 제출본 확인은 웹에서 한다 |
| 리소스 설명 | 스킬 설명(`SKILL.md:3`)과 `resources/list` 설명(`McpController.java:54-55`)에 자기소개와 경험을 넣는다 |

### 5.4 응답 예시 (`experience_search`)

6단계까지 켠 모습이다. `trust` 는 관계를 누가 확인했나이고, 원문이 어디 있는지는 `source` 다.

```json
{
  "query": {"position": "B사 · 서버 개발", "saved": true, "industries": ["핀테크"],
            "requirements": [{"key": "kafka", "field": "requiredSkills"}, {"key": "kubernetes", "field": "requiredSkills"},
                             {"key": "tableau", "field": "techStack"}],
            "questionTypes": [], "directSelfIntros": []},
  "context": {"dataVersion": "9f3c…", "searchConfig": "search-2026-11", "embeddingModel": "multilingual-e5-small-ko-v2",
              "indexGeneration": "2026-11-02T09:12:00Z", "indexBehind": false},
  "coverageIncomplete": false, "warnings": [], "degraded": [],
  "uncovered": [{"key": "kubernetes", "field": "requiredSkills", "reason": "NO_MATCH"}],
  "results": [
    {"rank": 1, "type": "EXPERIENCE", "experienceId": "…", "revision": 3, "reviewed": true,
     "experience": "결제 정산 배치 (PROJECT, 2025.03~2025.06)",
     "why": [
       {"signal": "REQUIREMENT", "matched": ["Kafka"], "field": "requiredSkills", "trust": "확정"},
       {"signal": "DOMAIN", "matched": ["핀테크"], "trust": "추정"},
       {"signal": "DENSE", "rank": 2, "source": {"field": "facts.results", "evidenceId": "ev2"}}],
     "episodes": [
       {"episodeId": "…", "title": "정산 배치 지연 원인 추적", "resultPreview": "배치 40분 → 6분",
        "competencies": [{"code": "PROBLEM_SOLVING", "trust": "확정"}],
        "why": [
          {"signal": "REQUIREMENT", "matched": ["Kafka"], "field": "requiredSkills", "via": "PARENT_EXPERIENCE", "trust": "확정"},
          {"signal": "PAST_USE", "detail": "A사 · 백엔드 인턴 '문제 해결' 문항에 사용", "trust": "AI 기록"}],
        "alreadyUsedFor": [{"company": "A사", "position": "백엔드 인턴", "sameCompany": false, "otherCompanySpecific": false}]}]},
    {"rank": 2, "type": "RESUME_ROW", "title": "사내 메시지 큐 이전",
     "source": {"resumeId": "…", "resumeName": "백엔드 v3", "section": "projects", "index": 2, "resumeVersion": 14},
     "why": [{"signal": "LEXICAL", "matched": ["kafka"]}]}
  ]
}
```

### 5.5 두 증상을 구조적으로 막는 방법

**(a) 이력서를 쓸 때 프로젝트를 빠뜨린다**

| 원인 후보 | 막는 장치 |
|---|---|
| 병렬 행 추가의 lost update, 웹 저장이 AI 결과를 덮어씀 | 행 잠금, `version` 409 (둘 다 1단계) |
| 웹에서 행을 지운 뒤 AI가 예전 행 번호로 다른 행을 고침 | 행 번호 수정·삭제에 `expectedVersion` 필수. 409면 같은 번호로 재시도하지 않고, 다시 읽어 대상을 내용으로 찾는다(1단계) |
| 통째 교체에서 섹션·필드를 빠뜨리면 삭제됨 | null=유지·행 병합(1단계), `LINKED_ROWS_MISSING`(4단계) |
| **AI가 새 이력서를 만들 때 처음부터 일부만 고름** | `resume_create`·`resume_get` 응답 머리의 `missing` 요약. 다른 이력서에만 있는 미연결 행 묶음까지 포함한다. 대조가 AI의 선택이 아니라 응답 기본값이 된다. 경험 테이블 전에는 3단계 `experience_index` 최소판이 같은 대조를 준다 |
| 응답이 커서 뒤쪽 `projects` 가 잘림 | 쓰기 응답은 개요다. 목차는 PROJECT를 맨 앞에 두고 `counts`·`complete` 도 맨 앞에 둔다 |
| AI가 행을 다시 타이핑하다 필드를 틀림 | `resume_add_experiences`: id만 넘기면 서버가 경험 원본(또는 고른 기업용 행)에서 행을 만든다 |

**(b) 직무·회사에 맞지 않는 자소서 경험을 가져온다**

| 원인 후보 | 막는 장치 |
|---|---|
| 자소서에 "어느 지원용"이라는 관계가 없음 | `self_introduction_positions`(2단계). 직접 출처가 없을 때는 간접 출처를 "출처 미상"으로 표시해 돌려준다. AI가 필터를 버리고 다시 부르지 않게 하려는 것이다 |
| 부분 문자열 BM25, 결과 상한 없음, 출처 없음 | 의미 검색(5단계), `limit`, `why[]`, `trust`, `alreadyUsedFor[]` |
| 문항이 비슷하다는 이유로 다른 회사용 지원동기를 가져옴 | FIT 유형의 PAST_QUESTION은 같은 회사·업종으로 제한한다. 다른 회사용이면 `otherCompanySpecific` 표시와 SKILL의 재사용 금지 |
| 공고의 기술 환경 나열을 필수 자격으로 읽음 | 다섯 칸 구분, 칸별 가중, `uncovered` 는 지원자격·요구 역량 칸만(4.2) |
| 맞는 경험이 없어도 비슷한 것을 억지로 고름 | `uncovered[]`. 단 커버리지가 불완전하면 `coverageIncomplete` 를 주어 결핍 단정을 막는다 |
| AI가 잘못 남긴 연결이 이후 검색을 오염 | 하드 필터는 USER만 쓰고, AI 기록은 후보별 RRF 기여가 0.5배다(4.3). 웹 확정은 사람만 한다 |
| SKILL을 읽지 않는 클라이언트가 예전 경로로 감 | 예전 도구(`self_intro_list`, `resume_get`, `resume_create`) 응답에 출처, `alreadyUsedFor`, `missing` 을 직접 싣는다 |

### 5.6 SKILL.md 절차 (`plugins/jobsight/skills/jobsight/SKILL.md`)

1. **이력서 작성·수정**
   1. `experience_index` 를 호출하고 `complete:true` 를 확인한다.
   2. `resume_get(view=outline)` 을 호출한다.
   3. `missing[]` 과 `unlinkedRows[]` 를 사용자에게 번호 목록으로 확인받는다.
   4. 은행에 있는 경험이면 `resume_add_experiences` 를 쓰고, 없으면 `experience_save` 뒤에 추가한다.
   5. 응답의 `count` 가 계획과 같은지 대조한다.

   규칙: 행 도구는 병렬로 부르지 않는다. 직전 응답의 `version` 을 다음 호출의 `expectedVersion` 으로 넘긴다. **409를 받으면 같은 행 번호로 다시 보내지 않는다.** 이력서를 다시 읽고, 고칠 행을 제목·기간으로 다시 찾은 뒤 보낸다. 찾지 못하면 사용자에게 묻는다.
2. **지원 맞춤 경험 찾기**
   1. 등록된 직무면 `positionId`, 아니면 공고 본문을 다섯 칸으로 나눠 `posting` 으로 넘겨 `experience_search` 를 부른다. **고르기 전에는 공고를 저장하지 않는다.** 공고 여러 개를 추릴 때도 같다.
   2. 후보를 `why` 와 원문 출처(`source`)와 함께 보여 준다. `why` 에 DENSE만 있으면 "의미상 비슷할 뿐"이라고 밝힌다. `uncovered` 를 알리되, `coverageIncomplete` 면 결핍을 단정하지 않는다. `trust: 확정` 은 "사용자가 연결을 확인함"이지 성과 검증이 아니라고 구분해 말한다.
   3. 사용자가 지원할 공고와 경험을 고른다. 고른 공고만 `posting_create` 로 저장한다.
   4. `writing_guide_get` 과 후보의 주의사항(`cautions`)을 읽고 작성한다. "재검토 필요"·"AI 기록" 표시가 있는 사실은 사용자에게 확인받는다.
   5. `self_intro_create(positionIds, episodes, questionTypes, writingContext)` 로 저장한다. 이 연결은 "AI 기록"이고 웹에서 확정해야 한다고 사용자에게 알린다.
3. **새 경험 등록과 경험 정리** (중앙 관리, 6.5)
   1. 사용자가 준 설명·자료를 읽고 `experience_index` 로 기존 경험과 겹치는지 먼저 찾는다. 자소서 답변은 "어느 경험을 썼나"를 찾는 데만 쓰고 사실 근거로 쓰지 않는다.
   2. 역할·행동·결과·측정 범위를 근거(인용·주변 문맥·위치)와 함께 정리하고, 확인하지 못한 것과 자료끼리 어긋나는 것을 따로 적는다. 로컬 파일은 경로가 아니라 필요한 발췌를 근거로 넣는다.
   3. 사용자가 요청하고 검토한 범위만 저장한다. 겹치는 경험이 있으면 새 경험이 아니라 새 개정(`changeKind` ADD·CORRECT)으로 저장한다. 저장된 내용은 "AI 기록"이고 사용자가 웹에서 검토해 확정한다.
   4. JobSight가 검색 자료를 갱신한다(4.4). 이후 어느 환경에서도 같은 경험 id와 근거를 조회한다.
4. 수정 규칙은 5.1의 도구별 규칙으로 바꿔 쓴다.

---

## 6. 정본 관계와 기존 데이터 이전

### 6.1 정본 규칙

| 정보 | 정본 |
|---|---|
| 경험의 존재와 정체 | `experiences.id` (행에는 `experienceId` 앵커) |
| **경험 사실** (기간, 개인 역할, 행동, 결과, 측정 범위, 근거) | **경험 원본** `experiences.facts`. 개정마다 `experience_revisions` 에 남는다 |
| 작성 재료 (상세 서술, 문항별 활용 포인트, 표현 예시, 주의) | 같은 레코드의 `experiences.dossier`. 어느 사실 개정에서 만들었는지(`dossier_based_on`) 남긴다 |
| 기업별 표현 | 이력서 행의 실제 문구 + `experienceId` + 쓴 `experienceRevision`. 병합 뒤에도 고쳐 쓰지 않는다 |
| 사건, 사건 결과, 역량·키워드 태그 | 새 테이블 |
| 이력서 ↔ 경험 관계 | `resume_experiences` (JSON에서 파생, FK) |
| 답변을 어디에 냈고 무엇을 썼나 | `self_introduction_*` (쓴 경험 개정 번호 포함) |
| 무엇을 제출했나 | `submissions` (확인 수준 포함, 6.6) |
| 개인 작성 기준 | `writing_guides` + 경험별 `dossier.cautions` (6.7) |

- **경험 원본이 정본이고 이력서 행은 표현이다** (10.4 R23). 새 회사에 지원할 때 과거 이력서에서 경험을 다시 뽑지 않는다. 원본을 먼저 읽고, 과거 지원서는 표현과 전개를 참고하는 자료로 쓴다.
- **원본이 바뀌어도 과거 문서는 그대로다** (R25). 이력서 행은 쓴 문구를 그대로 보존하고, 화면에서 최신 원본으로 다시 그리지 않는다.
- **참조는 (경험 id, 개정 번호) 쌍으로 고정한다** (10.5 R31). 이력서 행과 자소서 연결은 작성 당시의 쌍을 그대로 든다. 병합된 경험은 지우지 않고 `merged_into` 로 target을 가리키므로, 쌍은 언제나 개정 이력에서 찾을 수 있다. "지금 어느 경험인가"는 `merged_into` 를 따라가서 구한다.
- **차이는 세 가지로 나눈다** (R25). 모든 문구 차이에 경고하면 사용자가 경고를 무시하게 된다.
  - 표현 축약·순서 변경: 정상적인 기업별 편집이다. 표시하지 않는다.
  - 행에만 있는 새 사실(원본에 없는 수치·기술 키): "원본 반영 검토"로 보여 준다. 원본을 자동으로 바꾸지 않는다.
  - 원본 사실 정정(`CORRECT` 개정): 작성 근거 경험부터 `merged_into` 를 따라 현재 대표 경험까지의 **모든 경험**에서, 참조한 개정보다 나중에 만들어진 `CORRECT` 개정을 검사한다(6.8). source나 중간 경험의 정정도 포함한다. 개정 번호는 경험마다 다르므로 개정 시각으로 비교한다. 추가(`ADD`) 개정에는 붙이지 않는다.
- 이 전환으로 대표 행, "가장 최근에 수정한 이력서" 대체 규칙, 보존본이 필요 없어졌다(10.4). 기업용 문구가 사라져도 사실은 원본에 남기 때문이다.
- **같은 키를 세 곳에 함께 추가해야 한다.** `experienceId`·`experienceRevision` 을 `ResumeContent` 의 `Activity`·`Experience`·`Project`·`Training`, `ResumeRowRequest`, `frontend/src/types/resume.ts` 에 함께 넣는다. Jackson 3는 모르는 키를 조용히 버리므로, 한 곳이라도 빠지면 저장은 성공하고 값만 사라진다. PUT→GET 왕복 테스트로 막는다.
- `docs/decisions.md:50` 의 "본문 JSON에 ID를 섞지 않는다"에 대한 예외다(D2).

### 6.2 저장할 때 자동 연결: 모든 저장 경로가 거치는 함수 하나

`ResumeExperienceLinker.link(owner, resume, content)` 는 create, update, copy, `mutateRows`, `updateBasic`, 가져오기, 병합에서 **같은 트랜잭션 안에서** 호출된다. 선행 조건은 T9 계정 잠금, T1 잠금, T2 version이다.

1. 4개 섹션에서 행을 모은다.
2. `experienceId` 가 있는 행은 세 가지를 검사한다.
   - 그 경험이 내 것인지 count로 확인한다. 없으면 404다.
   - 종류와 섹션이 맞는지 확인한다. 다르면 400 `EXPERIENCE_SECTION_MISMATCH` 다.
   - 한 이력서 안에서 같은 경험이 두 번 나오면 400이다. 병합을 따라간 지금의 경험 id로 비교한다.
3. `experienceId` 가 없는 행은 `(kind, title_key)` 로 찾는다. **정확히 1건이 맞고 시작 연월 숫자가 충돌하지 않을 때만** 연결한다. 그 밖에는 미연결로 둔다. 새 경험은 만들지 않는다.
4. 연결한 행에 `experienceId` 를 써 넣는다. 행에 `experienceRevision` 이 없으면 그 경험의 지금 개정 번호를 쓴다. `experienceId` 가 병합된 경험이면 `merged_into` 를 따라 target과 연결하되, 행의 `experienceId`·`experienceRevision` 은 고쳐 쓰지 않는다. `resume_experiences` 에는 연결(`experience_id`)과 작성 근거(`based_on_*`)를 함께 두고 차이만 반영한다.
5. **행 문구로 경험 원본을 바꾸지 않는다.** 행에만 있는 새 사실(원본에 없는 수치·기술 키)은 저장하지 않고, 질의 때 "원본 반영 검토"로 보여 줄 뿐이다(6.1).
6. 이력서 저장은 경험의 검색 텍스트를 바꾸지 않으므로 경험 임베딩을 건드리지 않는다(4.4).
7. **웹 저장에서 이전에 있던 `experienceId` 가 사라지면** 응답에 `unlinkedWarnings[]` 를 싣는다. 웹에서 조용히 끊기는 것을 막기 위해서다. 웹은 이를 토스트로 보여 준다.

예상 비용은 SQL 4~6회이고 외부 호출은 없다.

### 6.3 기존 데이터 가져오기 (웹 기능, 마이그레이션 SQL 아님)

같은 경험인지는 사람이 확인해야 한다. 또 마이그레이션은 데이터를 지우면 안 된다(`docs/decisions.md:98-99`).

1. **계획 (dry-run)**
   - 계정의 미연결 경험 행을 `(kind, title_key, 시작 연월 숫자)` 로 묶는다.
   - 묶음마다 구성 행 `[{resumeId, resumeName, section, index, period}]` 을 싣는다.
   - 제목 키가 서로 포함 관계이거나, 기간 숫자는 같은데 제목이 다르면 `mergeCandidates` 로 표시한다. 자동으로 합치지 않는다.
   - 묶음에 관련된 이력서의 `version` 을 계획에 싣는다.
2. **적용**
   - 묶음마다 `CREATE | SKIP | LINK_TO(experienceId)` 를 받는다.
   - T9 계정 잠금을 얻고, 계정의 이력서를 id 오름차순으로 `FOR UPDATE` 잠근다. 계획에 실린 `version` 과 다른 이력서가 있으면 409로 거절하고 계획을 다시 만들게 한다.
   - CREATE는 정체성(kind, title, start_ym), SKILL 키워드(`techStack` 분해, USER), **경험 원본 초안**을 만든다. 초안의 사실은 구성 행들에서 모으고, 사실마다 근거(`RESUME_ROW` 인용·해시)를 단다. 행끼리 다른 사실(기간, 수치)은 `facts.conflicts[]` 에 적고 고르지 않는다. 초안은 검토 전이라 "AI 기록"이다. 구성 행 전부에 `experienceId` 와 `experienceRevision: 1` 을 쓰고, 행 텍스트는 바꾸지 않는다.
   - 여러 번 돌려도 결과가 같다.
3. **병합**: 먼저 충돌을 검사하고, 한 트랜잭션에서 처리한다. 잠금은 T9 순서(계정 → 이력서 → 경험)를 따르고, 경험을 먼저 잠그지 않는다.
   - **충돌 검사** (10.2 R5): source와 target이 같은 이력서에 함께 있으면, 병합 뒤 그 이력서에 같은 `experienceId` 행이 둘이 되어 6.2의 중복 금지와 충돌한다. 계획에서 그 이력서와 두 행을 보여 주고, 사용자가 한 행을 지우거나 다른 경험에 연결할 때까지 409 `MERGE_CONFLICT` 로 거절한다. 자동으로 행을 지우거나 서술을 덮어쓰지 않는다.
   1. 사건의 `experience_id` 를 target으로 바꾼다. 사건을 참조하는 FK는 `(id, owner_id)` 뿐이라 막히지 않는다.
   2. 키워드 연결을 `ON CONFLICT DO NOTHING` 으로 옮긴다.
   3. **이력서 JSON은 고쳐 쓰지 않는다.** source를 쓰는 이력서들의 `resume_experiences.experience_id` 만 target으로 바꾼다(`based_on_*` 은 그대로). T9 계정 잠금이 같은 계정의 저장과 순서를 정한다.
   4. source의 사실과 근거를 target에 합쳐 새 개정(`MERGE`)을 만든다. 서로 다른 사실 값은 `facts.conflicts[]` 로 남기고 사용자가 고른다. 과거 문서가 든 (경험 id, 개정 번호)는 바꾸지 않는다. target의 `source_revision` 을 올린다.
   5. **source는 지우지 않는다**(10.5 R31). `merged_into = target` 으로 표시하고 목록과 검색에서 숨긴다. source id로 조회하면 target으로 안내한다. source의 개정 이력이 남아 있으므로, source의 4차 개정을 쓴 자소서도 그 근거를 그대로 찾는다.
4. **사건 나누기와 자소서 연결**은 5.6 "경험 정리" 절차(AI 제안 → 사용자 승인)로 한다.

**운영 데이터 검증 원칙**:
- 운영 DB 사본에서는 내용 없이 집계만 비교하는 SQL만 돌린다. 계정별로 `경험 섹션 행 수 = 묶음 구성 행 수 + 미연결 행 수` 가 맞는지 본다.
- 사람이 계획을 직접 보는 검토는 본인 계정이나 합성 데이터에서만 한다.
- 근거: `docs/decisions.md:171-179`(관리자는 집계 숫자만, 업무 데이터 내용은 열지 않는다).

### 6.4 삭제와 복제

- **이력서 복제**: `experienceId` 와 `experienceRevision` 을 그대로 복사한다.
- **이력서 삭제**: `resume_experiences` 가 CASCADE로 지워진다. 경험 원본과 개정 이력, 자소서, 제출 기록은 남는다. 기업용 문구가 사라져도 사실은 원본에 있으므로 따로 보존할 것이 없다.
- **경험 삭제**: 병합으로 남은 경험(`merged_into` 있음)은 지우지 않는다. 과거 참조가 가리키기 때문이다. 이력서가 쓰고 있으면 409와 이력서 이름 목록을 돌려준다. 이 확인과 삭제 사이에 다른 요청이 연결해도 지연 FK가 삭제를 막는다(2.3). 사건이 자소서에 쓰이고 있으면 `detach:true` 없이는 409 `EPISODE_IN_USE`, `detach:true` 면 연결을 먼저 지우고 응답에 끊긴 자소서 수를 싣는다.
- **직무 삭제**: `self_introduction_positions` 가 CASCADE로 지워진다. 공고 수정이 직무를 지웠다 다시 만드는 경로는 1단계에서 먼저 막는다(T3).
- **계정 삭제**: 지연 FK 두 개가 있어도 통과해야 한다. 임시 테이블로는 확인했고(2.3), 실제 스키마는 4단계 테스트에 넣는다.

### 6.5 경험 원본, 소재집, 근거 (중앙 관리, 4단계)

JobSight가 개인 취업 컨텍스트의 중앙 관리 지점이다(10.4 R23). 어느 PC의 Codex든 같은 스킬과 계정 연결만으로 같은 경험 id, 사실, 근거, 작성 기준을 받는다. Codex는 조회, 새 경험 정리, 지원 문서 작성을 맡는다. 원문을 읽어 출처와 함께 정리하고 계속 갱신하는 LLM wiki의 방식은 이 레코드 안에서 쓴다. 별도 wiki 서버나 서버 쪽 LLM은 두지 않는다.

```
새 설명·자료 → Codex: 기존 경험과 중복 조회 → 사실·근거 정리, 미확인 표시 → 사용자 요청·검토 범위만 저장(새 경험 또는 새 개정)
→ JobSight: 개정 이력·검색 자료 갱신 → 어느 환경에서든 같은 경험 id와 근거 조회 → 문항·공고 맞춤 컨텍스트로 초안
```

**한 레코드, 두 역할** (R24). 같은 행에 두되 역할을 나눈다.

| 칸 | 역할 | 내용 |
|---|---|---|
| `facts.period`, `facts.role`, `facts.actions[]`, `facts.results[]` | 사실의 기준 | 기간, 개인 역할(팀 성과와 개인 기여를 나눈다), 직접 한 행동, 결과 |
| `facts.measurement` | 사실의 기준 | `{environment, scope}`. 표본 개선을 전체 성과로 키우지 않게 |
| `facts.evidence[]` | 사실의 기준 | 아래 근거. 위 칸의 주장마다 근거 `id` 를 단다 |
| `facts.unverified[]`, `facts.conflicts[]` | 사실의 기준 | 확인하지 못한 것, 자료끼리 어긋나는 것. 숨기지 않는다 |
| `dossier.narrative`, `dossier.questionUses[]`, `dossier.phrasings[]` | 작성 재료 | 상세 서술, 문항 유형별 활용 포인트(`competencies` 코드), 표현 예시 |
| `dossier.cautions[]` | 작성 재료 | 과장되거나 특정 회사에 묶인 표현에 대한 주의 |

- `dossier_based_on` 이 `revision` 과 다르면 "소재집 갱신 필요"다. 같은 행에 있어도 사실을 고친 뒤 서술을 갱신하지 않으면 어긋나기 때문이다.
- 사실이 바뀌면 `revision` 을 올리고 `experience_revisions` 에 당시 사실·소재집·사건을 함께 남긴다. 기존 사실 값을 바꾸거나 지우면 `CORRECT`, 추가만이면 `ADD` 다. 서버가 이전 개정과 비교해 판정하고, 요청의 `changeKind` 가 틀리면 400이다.
- `reviewed_revision` 이 `revision` 보다 낮으면 "AI 기록"이다. 사용자가 웹에서 검토하면 확정된다.
- 사건 STAR(900자 상한)는 검색용 요약이다. 긴 원문을 STAR에 억지로 맞추지 않고 상세는 소재집에 둔다.
- 검색(LEXICAL·DENSE)은 사실과 소재집을 경험 텍스트로 쓴다. `unverified`·`cautions` 는 검색에 넣지 않고 결과에 함께 싣는다.

**근거와 MCP 접근** (R27). 근거를 서버에 저장하는 것과 Codex가 읽을 수 있는 것은 별개다.

| 키 | 내용 |
|---|---|
| `id`, `sourceType` | `RESUME_ROW\|ATTACHMENT\|URL\|NOTE` |
| `ref` | `RESUME_ROW` 는 `{resumeId, section, index, resumeVersion}`, `ATTACHMENT` 는 첨부 id, `URL` 은 https 주소(저장소는 커밋을 고정한 주소) |
| `quote`, `context` | 근거 문장과 필요한 주변 문맥. 텍스트·코드 발췌는 여기에 TEXT로 저장한다(인용 1000자, 문맥 3000자 상한) |
| `location`, `hash`, `capturedAt` | 원문 안의 위치(줄·쪽·섹션), 발췌의 해시, 뽑은 시각 |

- **로컬 경로만 있는 근거는 받지 않는다**(400). 다른 PC에서 열 수 없다. private 저장소처럼 다른 환경에서 열지 못하는 원문도 `quote`·`context` 만으로 확인할 수 있어야 한다.
- MCP로는 `experience_index` 상세 모드가 근거의 인용·문맥·위치·해시를 돌려준다. 첨부는 메타(파일명, 형식, 크기)와 `PUBLIC_BASE_URL` 기준 웹 주소만 준다. 첨부 바이너리를 MCP로 넘기거나 첨부 형식을 넓히는 일은 하지 않는다(2.6).
- **자소서 답변은 근거로 쓰지 않는다.** `sourceType` 에 자소서가 없다. 내가 쓴 글이 다시 사실 근거가 되는 순환을 막는다.

### 6.6 제출 기록 (제출·작성 기준 트랙)

상태 변경은 기록의 계기일 뿐 제출 사실의 증명이 아니다(10.4 R26). 지금 `changeStatus` 는 상태만 바꾼다(`B/posting/JobPostingService.java:97`). 과거 지원 건을 뒤늦게 등록하거나, 제출 뒤 고친 이력서가 연결돼 있을 수 있다.

| 확인 수준 | 언제 생기나 | 부르는 이름 |
|---|---|---|
| `LINKED` | 공고가 `SUBMITTED` 로 바뀔 때. 그 공고 직무에 연결된 이력서(`resume_positions`)와 written_for 자소서를 스냅샷으로 남긴다 | "기록 당시 연결 자료". 제출본이라 부르지 않는다 |
| `USER_CONFIRMED` | 사용자가 웹에서 실제로 낸 문서·이력서 버전·첨부를 골라 확인할 때 | "사용자가 확인한 제출본" |
| `RECEIPT_VERIFIED` | 접수 메일 발췌·접수 번호·첨부 같은 근거를 붙여 대조할 때. 자동 대조는 하지 않는다 | "접수 근거와 대조한 제출본" |

- 스냅샷 내용의 해시(`content_hash`)로 같은 요청의 재시도가 중복 기록을 만들지 않게 한다. 내용이 같으면 기존 기록을 돌려준다.
- 기록은 고치지 않고 쌓는다. 기록이 가리키는 첨부는 나중에 정리 작업을 만들어도 지우지 않는다.
- PAST_USE와 `alreadyUsedFor` 는 `USER_CONFIRMED` 이상이 있으면 그 스냅샷을 쓰고, 없으면 지금 연결을 쓰되 "제출본 미확인"을 표시한다.

### 6.7 작성 기준과 재현 정보 (제출·작성 기준 트랙)

**작성 기준** (R30): 계정당 Markdown 문서 하나(`writing_guides`)와 경험별 `dossier.cautions` 로 시작한다. 공통 절차는 스킬에, "내가 선호하는 표현"과 "이 성과를 설명할 때 지킬 범위"는 계정 자료에 둔다. MCP는 `writing_guide_get` 으로 읽고, 수정은 처음에는 웹에서 한다. 복잡한 개인화 시스템은 만들지 않는다.

**재현 정보** (R28): `experience_search`·`self_intro_list` 응답에 `context` 를 싣는다.

| 키 | 값 |
|---|---|
| `dataVersion` | 후보로 쓴 자료의 `(id, version)` 목록 해시 |
| `searchConfig` | 신호 구성·가중값을 바꿀 때 올리는 코드 상수 |
| `embeddingModel` | 임베딩 모델 id와 리비전 |
| `indexGeneration` | 계정 임베딩의 마지막 갱신 시각 |
| `indexBehind` | 계정에 아직 계산하지 않은 dirty 행이 있으면 true. 의미 검색이 원문보다 늦을 수 있다고 함께 알린다 |

- 같은 `context` 와 같은 질의·필터면 같은 결과를 돌려준다. 동점은 id로 정렬한다.
- 자소서를 MCP로 저장할 때 `writingContext` 에 위 값, 작성 기준 `version`, 쓴 경험 개정 번호를 남긴다.
- 같은 컨텍스트여도 모델과 실행 설정에 따라 최종 문장은 달라진다. 보장하는 것은 같은 사실 기반, 검색 기준, 작성 규칙과 검토 이력이다.

### 6.8 병합 경로의 정정과 자소서 작성 근거 계약

작성 근거 `(experienceId, experienceRevision)` 와 현재 사건의 소속은 다를 수 있다. 사실 개정은 작성 당시의 경험에서 찾고, 현재 정체성은 병합 경로로 확인한다. 이력서와 자소서에 같은 규칙을 적용한다.

1. 작성 근거 경험과 개정을 소유자 범위에서 찾는다. 개정의 `created_at` 이 비교 기준 시각이다.
2. 작성 근거 경험부터 `merged_into` 의 끝까지 순회한다. **source, 중간 경험, 마지막 target을 모두 포함**한다. 각 경험의 `CORRECT` 개정 중 기준 시각보다 뒤의 것이 하나라도 있으면 재검토가 필요하다. `ADD`·`MERGE` 만으로 경고를 지우거나 만들지 않는다.
3. 계정 경험을 한 번에 읽어 Java Map에서 순회한다. 방문 id를 기록하고 자기 참조나 순환은 `409 MERGE_CYCLE` 로 거절한다. 병합 입력도 같은 검사를 거친다. 소유자가 다른 경로는 404다. 재귀 SQL이나 그래프 DB는 필요 없다.

자소서 REST/MCP 생성·수정 및 조회 계약:

- `episodes` 생략·null은 기존 연결 유지, `[]` 는 비움이다. 목록을 보내면 각 항목은 `{id, experienceId, experienceRevision}` 세 키가 필수다. 빠진 키나 양의 정수가 아닌 개정은 `400 EPISODE_REFERENCE_REQUIRED` 다.
- 사건과 작성 근거 경험은 모두 내 것이어야 한다(아니면 404). `(experienceId, experienceRevision)` 개정도 실제로 존재해야 한다(없으면 `404 EXPERIENCE_REVISION_NOT_FOUND`).
- 사건의 현재 소속과 작성 근거 경험이 병합 경로의 같은 대표 경험에 도달해야 한다. 아니면 `400 EPISODE_EXPERIENCE_MISMATCH` 다. 이 검사는 같은 경험임을 확인할 뿐, 과거 근거 쌍을 현재 소속으로 바꾸지 않는다. 사실의 개정과 사건 STAR의 현재 편집 버전은 구분한다.
- 검사를 모두 통과한 뒤 전달받은 쌍을 그대로 저장하고, `self_intro_get`·`self_intro_list` 에서 그대로 돌려준다. 다른 연결과 문항은 보존하며, 여러 사건 중 하나라도 실패하면 요청 전체가 실패한다.
- 예: 사건 E가 A에서 B로 옮겨졌고 A와 B 모두 4차 개정이 있어도, `{id:E, experienceId:A, experienceRevision:4}` 는 A의 4차 개정을 가리킨다. 숫자 4만으로 B의 4차 개정을 선택하지 않는다.

합성 자료의 실행 가능한 계약 검증은 [경험 병합 계약 PoC](../../../poc/experience-lineage/README.md)에 둔다. 서버 구현이나 DB 통합 테스트가 아니다. 4단계 구현에서 같은 사례를 REST·MCP 서비스 회귀 테스트로 옮긴다.

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

리뷰를 받아 순서를 바꿨다(10.2 R9·R11). 3·4차 리뷰로 4단계 내용과 제출·작성 기준 트랙이 바뀌었다(10.4). 마이그레이션 파일 번호 `V{n}` 은 병합 순서대로 매긴다.

| 단계 | 내용 | 검증 | 되돌리기 |
|---|---|---|---|
| **0. 측정** | 1.2의 표 전부. 바꾸는 코드는 nginx `log_format`, MCP 호출 로그 한 줄뿐이다. 수작업 골드셋을 만든다 | 기준선 수치(SQL 수, waterfall, `$upstream_response_time`, 골드셋 Recall@5·혼입률) 기록. nginx를 바꿨으므로 로그인 왕복 | 커밋 revert |
| **1. 손실과 잘못된 대상 수정 막기** | ① batch fetch(P1) ② 직렬 요청 줄이기: 자소서 동시 요청(적용함), 첨부 메타 동봉(P5) ③ `findOwnedForUpdate` 를 `update`·`updateBasic`·`mutateRows` 에 적용(T1) ④ `V{n}__resume_version` 과 `expectedVersion`: 행 번호 수정·삭제와 전체 교체는 필수, 추가는 선택, 모든 쓰기 응답에 새 `version`(T2) ⑤ `resume_update` null=유지(T3), 행 갱신 병합(T4), 행 도구 개요 반환(T5) ⑥ `posting_update` null=유지와 이름 매칭(T3). 2단계 written_for가 직무 id에 기대므로 먼저 막는다 ⑦ `AttachmentField.vue:52,65` 즉시 삭제 제거(T7) ⑧ 도구 설명과 SKILL의 도구별 수정 규칙, 행 도구 순차 호출과 409 처리 규칙(5.6) | 병렬 `addRow` 2건 → 2행. AI가 읽은 `version` 으로 0번 수정을 보내기 전에 웹에서 0번을 지우면 409이고 다른 행은 그대로. `version` 없는 행 수정 → 400. `projects` 를 뺀 `resume_update` 뒤에도 행이 남는지. 이름만 보낸 `posting_update` 에서 직무 id와 `resume_positions` 보존. 복제본에서 첨부를 교체한 뒤 원본 첨부 GET 200. `now at version vN`. SQL 수와 waterfall을 기준선과 비교 | 항목별 커밋 revert. `version` 열은 남아도 무해 |
| **2. 자소서 직접 출처와 목록** | `V{n}__self_intro_relations`, `self_intro_list` MCP 래퍼(필터, 미리보기, limit, 출처, fallback, `alreadyUsedFor`), 원문은 `self_intro_get`, 자소서 생성·수정의 `positionIds`·`questionTypes`, 요청 DTO 분리, 웹 "어느 지원용?" 후보 칩과 AI 기록 확정 UI, 정규식 문항 유형 | 과거 답변이 출처와 함께 정확히 조회되는지. 골드셋 재측정, 특히 타사 혼입률. 다른 계정 직무 id로 연결 → 404. 웹 자소서 목록 응답이 바뀌지 않았는지(답변 전문). `q` 101자 → 400 | 낮음. 테이블은 무시해도 된다 |
| **3. 공고를 저장하지 않는 경험 찾기 (첫 완성 목표)** | 마이그레이션 없음. `experience_search` 첫 판: 입력은 `positionId` 또는 저장하지 않는 `posting`(4.1)과 클라이언트가 뽑은 `requirements[]`(4.2), 후보는 RESUME_ROW 묶음뿐, 신호는 LEXICAL·REQUIREMENT(다섯 칸, 칸별 가중), 결과마다 원문 출처 `source`. `experience_index` 최소판(5.2). `resume_get(view=outline)`(version, 섹션별 count, 행 머리, `unlinkedRows`). SKILL 2번 절차를 "고른 뒤에 저장"으로 바꾼다. **전환 경로다**: 4단계에서 응답 형식은 그대로 두고 자료원만 경험 원본으로 바꾼다. 이력서 검색과 경험 검색을 따로 운영하지 않는다 | 공고 20개를 넘겨도 `job_postings` 행 수가 그대로인지. 기술 환경 칸에만 있는 키가 `uncovered` 에 나오지 않는지. 내 자료에 없는 필수 기술이 `uncovered` 에 나오는지. 지원 자격이 점수에 섞이지 않고 `qualifications[]` 로 나오는지. 골드셋에서 정답 경험 행이 상위 5에 있는지와 출처가 맞는지. 하네스에 붙여 "프로젝트 누락"이 줄었는지 | 낮음. 도구 등록을 빼면 끝난다 |
| **4. 경험 원본과 근거 (중앙 관리)** | `V{n}__experiences`(`facts`, `dossier`, `revision`, `experience_revisions`, 지연 FK 두 개), `experienceId`·`experienceRevision` 세 곳 동시 추가, 링커, 웹 가져오기(원본 초안과 충돌 칸)·병합(충돌 검사)·경험 원본 편집과 검토 화면·"원본 반영 검토"·"재검토 필요" 표시, `ApiPaths`·`routes.ts`·`nginx.conf` 표 동시 갱신, MCP `experience_save`(사실 개정, `changeKind`)·`resume_add_experiences`(원본 또는 고른 기업용 행에서)·`experience_index` 전체판(근거 발췌 포함), T9 계정 잠금, REST·MCP 공통 오류 변환, `missing[]`·`LINKED_ROWS_MISSING`, `experience_search` 자료원을 경험 원본으로 교체(응답 형식 유지), 별칭 시드 사전, 자소서 연결의 경험 개정 번호 | `experienceId`·`experienceRevision` PUT→GET 왕복. 경험 원본을 고쳐도 과거 이력서 행의 문구와 개정 번호가 그대로인지. 정정(`CORRECT`) 개정 뒤 그 경험을 쓴 이력서·자소서에 "재검토 필요"가 붙고, 추가(`ADD`)·표현 차이에는 붙지 않는지. 행에만 있는 새 수치가 "원본 반영 검토"로 나오는지. 링커 멱등. 제목 키가 2건 맞으면 미연결로 남는지. 복합 FK가 다른 소유자 연결을 거부. 같은 이력서에 있는 두 경험 병합 → 409. 병합 뒤에도 source 개정을 쓴 자소서·이력서의 (경험 id, 개정) 참조가 개정 이력에서 찾아지는지, 병합 뒤 target에 정정이 생기면 source 옛 개정을 쓴 문서에 "재검토 필요"가 붙는지. `ROW` 복사가 원래 행의 개정 번호를 가져오는지. **사건이 있는 경험의 병합**. 사용 중 확인 직후 연결한 경험의 삭제가 거부되는지. 경험·사건 삭제가 REST 409와 MCP 도구 오류 코드로 같게 나오는지(Testcontainers). 로컬 경로만 있는 근거 → 400. 계정 삭제 cascade 통과. `REAL[]` 매핑 validate 통과. 운영 사본은 집계 비교 SQL만. 골드셋 재측정. 로그인 왕복 | 중간. MCP 등록과 화면을 끄면 멈춘다. 이력서 행 텍스트는 원본 그대로다 |
| **5. 의미 검색과 필요시 리랭커** | `embedder` 컨테이너(기본 CPU), `compose.gpu.yaml`, `V{n}__self_intro_embeddings`, 스위퍼(원문 버전 조건, D13·D14), `self_intro_list(q)` 에 DENSE + 리랭커 K=10, `experience_search` 에 DENSE와 재현 정보 `context`(6.7). **벡터 저장은 `REAL[]` 과 pgvector를 비교해 정한다**(D11). 컨테이너 3 → 4. `.env.example`·`compose.yaml`·`docs/local-dev.md` 동기화. **문항 의미 검색은 `self_introductions` 만 쓰므로 경험 은행과 무관하다.** 골드셋에서 BM25 부족이 확인되면 이 단계의 자소서 부분만 2단계 바로 뒤로 당긴다(R11) | 골드셋에서 앞 단계보다 회수율이 오르는지. CPU 리랭커 지연 실측. embedder를 멈춘 상태에서 저장 성공과 `degraded`, 재기동 후 dirty 0으로 수렴. 스위퍼가 읽은 뒤 원문이 바뀌면 그 회차는 쓰지 않는지. 해시가 같은 조각은 재계산하지 않는지 | 낮음. 컨테이너를 내리면 이전 검색으로 돌아간다. DB 이미지는 그대로다 |
| **6. 추가 신호와 정리** | DOMAIN·PAST_USE·COMPETENCY·PAST_QUESTION 중 골드셋에서 이득이 확인된 것만, 병합 UI 다듬기, `experience_index` 점검 플래그(사건 없는 경험, 역량 없는 사건, 어디에도 쓰이지 않은 사건, AI 기록 수, 미등록 행, 출처 미상 자소서, truncated), 별칭 편집 UI, 사용률 로그를 근거로 쓰이지 않는 CRUD 도구 정리 검토 | 신호마다 켜기 전후 골드셋 기여 기록. 계정 단위 플래그 수 추적 | 낮음 |
| **성능 트랙** (단계와 별개) | `V{n}__indexes`(1.5), P3·P4·P6~P11, T6(행 추가의 섹션 상한 검증). 측정하면서 진행한다 | 토큰 조회 `EXPLAIN` Index Scan. SQL 상한 테스트. 1.0 방식으로 다시 잰 지연. nginx를 건드리면 로그인 왕복 | 코드 revert. 인덱스는 남아도 무해 |
| **제출·작성 기준 트랙** (2단계 이후 언제든) | `V{n}__submissions_and_guides`: 제출 기록(6.6, `SUBMITTED` 전환 때 LINKED 기록, 웹에서 사용자 확인·접수 근거 첨부), 작성 기준 문서(6.7)와 MCP `writing_guide_get`, 자소서 `writing_context` | 같은 상태 변경을 두 번 보내도 기록이 하나인지. 뒤늦게 등록한 지원 건이 LINKED로만 남고 제출본이라 부르지 않는지. 제출 기록이 가리키는 첨부가 정리 대상에서 빠지는지. 작성 기준 버전이 자소서 `writing_context` 에 남는지 | 낮음. 기록 테이블은 무시해도 된다 |

### 함께 갱신할 문서

4단계의 추가 회귀 사례는 6.8과 [계약 PoC](../../../poc/experience-lineage/README.md)를 따른다. source의 정정 뒤 병합, 중간 경험의 정정 뒤 재병합, target의 이후 정정, 추가만 있는 경우를 각각 검사한다. REST와 MCP 모두 작성 근거 id가 빠진 요청, 없는 개정, 타인 소유자, 다른 경험의 사건 연결을 거절하고, 같은 개정 번호를 가진 source와 target이 있어도 저장·조회 왕복에서 원래 쌍을 유지해야 한다.

| 문서 | 갱신 내용 |
|---|---|
| `docs/experience-graph-search.md` | 이 설계로 다시 쓴다. stories/links/pgvector 구상과 V22 예약은 폐기한다 |
| `docs/resumes.md` | 불변 조건 3(행 도구), 4(입력 null=유지, 저장 모양은 그대로 `[]` 정규화), `experienceId`, `version`(행 번호 수정·삭제와 전체 교체는 필수) |
| `docs/self-introductions.md` | 불변 조건 2(`:22`, "이력서 또는 직무 1개 이상"), 연결 대상 확장 |
| `docs/attachments.md` | 검증 3번(`:60`)과 함정(`:71`): 교체해도 이전 파일은 남는다(고아 허용). 제출 기록이 가리키는 첨부는 나중에 정리 작업을 만들어도 지우지 않는다 |
| `docs/job-postings.md` | `SUBMITTED` 전환 때 제출 기록(LINKED)이 생긴다는 것과 확인 수준(6.6) |
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
| D1 | 이력서는 JSONB 하나, 섹션 테이블 없음 (`docs/decisions.md:145-146`) | 이력서는 계속 JSONB 문서다. 경험 원본(사실·소재집·개정 이력)과 사건은 테이블로 둔다. 이력서 행은 그 경험의 기업별 표현이고 쓴 경험 개정 번호를 든다 | 원 결정의 되돌릴 조건("섹션별 질의가 필요해지면", `:146`)이 경험 4개 섹션에 대해 충족됐다. 여러 환경에서 같은 사실을 쓰려면 사실의 기준이 표현 문서 밖에 있어야 한다(10.4) |
| D2 | 본문 JSON에 ID를 섞지 않는다 (`:50`) | 경험 행에 `experienceId` 앵커를 허용한다. 관계의 정본은 FK 테이블 `resume_experiences` 다 | id 없는 행에 정체를 줄 다른 방법이 없다 |
| D3 | 다형 `links` + `stories` + `tags text[]` 구상 (`docs/experience-graph-search.md`) | 폐기한다. 관계 종류별 FK 조인 테이블과 복합 FK를 쓴다 | 다형 간선에는 FK를 걸 수 없어 고아 간선이 남는다 |
| D4 | 자소서는 이력서와만 N:M, `resumeIds` 1개 이상 (`docs/self-introductions.md:22`, `SelfIntroductionRequest.java:12`) | 직무·사건·문항 유형으로 연결을 넓힌다. 생성 시 "이력서 또는 직무 1개 이상", 수정 시 0개 허용. DTO를 분리한다 | "어느 회사용 답변인가"를 판별할 수 없는 구조가 증상 (b)의 원인 후보다 |
| D5 | `resume_update` 통째 교체 (`docs/resumes.md:44`), 섹션 null → `[]` (`docs/resumes.md:45`), SKILL "수정은 전체 교체" (`SKILL.md:23-25`) | `resume_*`·`posting_update` 만 null=유지, `[]`=비움. 저장되는 모양은 그대로 정규화한다. 그 밖의 update 도구는 전체 교체를 유지한다 | AI의 섹션 누락이 조용한 삭제로 이어진다 |
| D6 | 임베딩은 CPU·JVM ONNX (`docs/decisions.md` "임베딩은 GPU 가 아니라 CPU 로" 절) | Python embedder 컨테이너로 바꾼다. 기본은 CPU이고, GPU는 선택 오버라이드다 | 리랭커(크로스 인코더)가 생겼고, 같은 서비스가 GPU로 옮겨 갈 길이 필요하다. 검색 경로는 CPU로도 충분하다는 원 판단은 유지한다 |
| D7 | 태그 선택지는 계정 기존 값에서 모은다 (`docs/job-postings.md:140`) | 역량·문항 유형은 시스템 고정 코드 18개로 둔다 | 문항과 사건이 같은 어휘를 써야 매칭된다 |
| D8 | 메모리 BM25 (`:50`) | LEXICAL 신호로 유지하고 df 계산만 고친다. 존치는 실데이터 측정으로 정한다 | 합성 PoC는 제거 근거로 부족하다 |
| D9 | `job_postings.target_position_id` | 쓰지도 지우지도 않는다 | 데이터 삭제 금지 원칙 |
| D10 | 첨부 교체 시 즉시 삭제 (`docs/attachments.md:71`, `AttachmentField.vue:51`) | 즉시 삭제를 없앤다. 정리 작업은 두지 않는다 | 복제 이력서가 id를 공유해 원본의 파일이 사라진다 |
| D11 | 임베딩 저장은 pgvector `vector(384)` (`docs/experience-graph-search.md:149`) | **5단계에서 정한다.** 후보는 `REAL[]` + Java 정확 cosine, 또는 pgvector(공식 `pgvector/pgvector:pg17` 이미지)의 SQL 정확 검색 | pgvector는 인덱스 없이도 소유자로 거른 뒤 상위 k개만 돌려준다. `REAL[]` 은 검색마다 계정 벡터 전부를 JVM으로 가져온다. 반대로 pgvector 이미지는 Debian 기반이라 alpine에서 바꾸면 콜레이션이 달라져 REINDEX가 필요할 수 있고, 백업·복원이 확장에 묶인다. "인덱스가 필요 없으니 확장도 필요 없다"는 처음 근거는 약했다(10.3 R17) |
| D12 | e5 접두 "질의 `query:`, 문서 `passage:`" (`:158`) | 문항↔문항은 양쪽 `query:` (4.4 표) | 확정된 맥락에 맞춘다 |
| D13 | `owner_id` 없이 id만 보는 조회 메서드 금지 (`docs/experience-graph-search.md:161`) | 시스템 배치 예외: 임베딩 스위퍼만 허용한다. 별도 패키지·리포지토리이고, 대상은 dirty·임베딩 컬럼뿐, 내용은 embedder 외로 내보내지 않는다 | 계정과 무관한 백그라운드 적재가 필요하다 |
| D14 | 스케줄러 없음 (`docs/decisions.md:85-86`) | 임베딩 dirty 스위퍼에 한해 `@Scheduled` 를 쓴다. 해시 비교(`:203`)는 유지한다 | 원 결정의 반대 이유(꺼진 기간을 건너뜀)는 dirty 플래그가 남는 구조에서는 해당하지 않는다 |
| D15 | (이 설계 안) 경험 서술의 정본은 이력서 행 (6.1 옛 판, 10.1 P-3) | 경험 원본(`facts`)이 정본이고 이력서 행은 표현이다 | 표현 문서를 사실의 기준으로 삼아 대표 행·보존본 같은 땜질이 이어졌고(10.2 R1, 10.3 R13), 중앙 관리 목표와 맞지 않는다(10.4 R23) |

---

## 9. 위험과 열린 질문

### 9.1 위험

| 위험 | 영향 | 대응 |
|---|---|---|
| 지연 원인이 DB가 아님 — **실측으로 확인됨(1.0)** | P1·P3의 체감 효과가 작다 | P1·P3은 정확성·확장성 근거로만 한다. 체감은 직렬 단계(P2·P5)와 진입 경로(N1)로 줄인다 |
| 제목 키 자동 연결이 다른 경험을 같은 것으로 묶음 | 잘못된 연결 | 정확히 1건 일치하고 연월이 충돌하지 않을 때만 연결한다. "다른 경험에 연결"과 병합으로 고친다 |
| 콜드 스타트 (사건과 연결이 적음) | 그래프 신호가 약하다 | 3단계가 경험 테이블 없이 RESUME_ROW 후보로 동작한다. 의미 검색(5단계), `coverageIncomplete`, 간접 출처 fallback |
| AI 기록을 사용자가 확정하지 않음 | PAST_USE가 계속 0.5배이고 하드 필터에서 빠진다 | 경험 목록 배지로 확정을 유도한다. 0.5배여도 신호는 남는다 |
| 고정 역량 코드가 일부 문항을 덮지 못함 | SEMANTIC으로만 검색된다 | 코드 추가는 INSERT 한 줄과 enum 한 줄이다 |
| 키워드 표기 흔들림 | REQUIREMENT를 놓친다 | 별칭 시드 사전, 토큰 포함 일치, `TAG_MISMATCH_POSSIBLE`, 요구 본문을 늘 LEXICAL·DENSE 질의에 넣음 |
| 사건 900자 상한이 사용자에게 짧음 | 입력 불편 | 5단계에서 토크나이저로 재측정한 뒤 조정한다. 넘치면 조각 임베딩으로 바꾼다 |
| `REAL[]` 매핑이 validate와 맞지 않음 | 4단계 기동 실패 | 첫 커밋에서 확인하고, 안 되면 `bytea` |
| 4번째 컨테이너 운영 | 운영 부담 | 기본 CPU. 멈춰도 저장과 검색은 계속 동작한다 |
| 384차원이 모델에 묶임 | 모델 교체 시 전체 재적재 | 조각 테이블을 비우고 dirty를 세워 스위퍼로 재적재한다 |
| 검색 품질 수치가 없음 | 신호 하나하나의 기여를 모른다 | 0단계 수작업 골드셋부터 단계마다 다시 잰다 |
| 웹에서 이력서만 고쳐 경험 원본이 낡음 | AI가 옛 사실로 초안을 쓴다 | 행에만 있는 새 수치·기술 키를 "원본 반영 검토"로 보여 주고 경험 화면과 `experience_index` 에 표시한다. 모든 문구 차이에는 경고하지 않는다(경고 피로, 6.1) |
| 제출 기록을 실제 제출본으로 오해 | 뒤늦게 등록하거나 제출 뒤 고친 문서가 제출본으로 남는다 | 확인 수준을 나누고 LINKED는 "기록 당시 연결 자료"라고만 부른다(6.6) |
| 지연 FK 위반이 커밋 때 남 | 링커 저장이 원인 모를 오류처럼 보인다 | 커밋 예외를 409로 바꾸고, 단독 삭제는 `SET CONSTRAINTS … IMMEDIATE`(2.3) |
| 칸별 가중 초기값이 틀림 | 우대·기술 환경 일치가 과소·과대 평가된다 | 3단계 골드셋으로 조정한다 |
| 소재집이 원문과 어긋남 | AI가 과장된 근거로 초안을 쓴다 | 근거마다 인용·해시, 미확인·충돌 칸, "갱신 필요" 표시, 웹 검토(6.5) |
| 계정 단위 직렬화로 한 계정의 쓰기가 줄을 섬 | 같은 계정의 동시 저장이 기다린다 | 트랜잭션을 짧게 두고 외부 호출을 빼 둔다. 1인 쓰기 규모라 허용한다(T9) |

### 9.2 열린 질문 (사용자 결정 필요)

1. ~~AI가 관계를 확정하게 할지~~ → **닫음**: 확정은 웹에서만 한다. MCP 쓰기는 "AI 기록"이다.
2. ~~이력서 버전마다 경험 문구를 다르게 쓰는지~~ → **닫음**: 경험 원본이 정본이고 기업별 문구는 이력서 행에 보존한다(6.1, 10.4).
3. ~~어느 이력서 버전을 실제로 제출했는지 기록이 필요한지~~ → **닫음**: 제출 기록을 확인 수준과 함께 남긴다(6.6).
4. 사건 단위 기술 태그가 필요한지.
5. 서버 로그나 MCP 대화 기록을 볼 수 있는지. "프로젝트 누락"의 원인을 가른다.
6. 수작업 골드셋 10~20개를 언제 만들 수 있는지(0단계 선행 조건).
7. GPU 오버라이드를 켤 시점. 5단계 CPU 리랭커 실측 뒤에 정한다.

### 9.3 검증하지 못한 것

- SQL 횟수와 응답 크기는 코드에서 낸 추정이다. 지연은 인증 없는 가벼운 요청으로만 쟀다(1.0).
- 직접 확인한 코드 사실: `ResumeBoard.vue:62` 의 Promise.all, `application.yml` 에 배치 페치 없음, `mutateRows` 의 잠금 없는 조회, `replacePositions` 의 새 Set 대입, `AttachmentField` 의 즉시 삭제, `frontend/nginx.conf` 에 gzip 없음, `postgres:17-alpine`, 최신 마이그레이션 V21, MCP의 컨트롤러 리플렉션 호출, `RuntimeException` → `INTERNAL_ERROR`, 행 도구의 `int index`(`McpActions.java:70,77`), 직무 칸 라벨(`PositionDetail.vue:97-103`, `experience` 는 "취득 경험")과 공고의 지원자격(`JobPosting.java:57`).
- 동시 `resume_row_add` 의 lost update, `resume_update` 의 섹션 소거, 생성 시 선택 누락 중 무엇이 "프로젝트 누락"의 실제 원인인지. 재현하지 않았다.
- Spring Authorization Server JDBC의 타입 없는 토큰 조회 쿼리 모양, 만료 행 자동 정리 여부.
- `REAL[]` ↔ `float[]` Hibernate 7 매핑, 리랭커 CPU 지연, `experience_search` p95, 실데이터 검색 품질, 사건 900자 상한의 토큰 수.
- 각 MCP 클라이언트(Claude, ChatGPT, Codex)의 도구 출력 상한과 잘림 방식.
- 실제 스키마에서 계정 삭제 cascade가 통과하는지. 지연 FK 구조가 통과함은 임시 테이블로 확인했다(2.3). 4단계 테스트에 넣는다.
- 클라이언트가 공고에서 뽑은 요구의 정확도와, 넘기지 않았을 때 서버 시드 사전의 회수율.
- 소재집 추출의 사실 정확도. 합성 문항 PoC는 표현 바꾸기에 대한 의미 검색 가능성만 보여 준다.
- 계정 권고 잠금과 지연 FK를 함께 쓸 때의 실제 대기 시간. 4단계 통합 테스트에서 잰다.
- 사실 정정(`CORRECT`)과 추가(`ADD`)를 서버가 이전 개정과 비교해 판정하는 규칙이 실데이터에서 맞는지.
- 행에만 있는 새 사실을 수치·기술 키로 찾는 방식의 누락률. 행동 서술의 새 사실은 이 방식으로 잡히지 않는다.
- 실제 계정의 이력서당 직무 수 분포. written_for 후보 칩의 유용성과 관련이 있다.

---

## 10. 반박 처리 내역

### 10.1 설계 단계 반박 (40건)

| # | 영역·심각도 | 반박 요지 | 처리 | 이유와 반영 위치 |
|---|---|---|---|---|
| I1 | invariants·major | MCP 도구가 컨트롤러를 리플렉션으로 호출하므로 응답과 가드를 바꾸면 웹도 바뀐다 | 채택 | 코드로 확인(`McpTools.java:37-40,75,83,160`, `ResumeQuestions.vue:46`). MCP 전용 동작은 `McpActions` 에 둔다. 5.1, 5.3 |
| I2 | invariants·major | "SKILL.md:25와 일치"가 거짓이고 도구별 규칙이 혼재한다 | 채택 | `SKILL.md:23-25`, `McpTools.java:123`, `docs/resumes.md:45` 확인. 도구별 규칙으로 나누고 D5에 추가. 5.1, 8 |
| I3 | invariants·major | outcomes FK에 ON UPDATE가 없어 병합이 실패한다 | 채택(다른 방식) | `outcomes` 테이블을 없애고 `episodes.outcomes TEXT[]` 로 바꿔 FK 자체를 없앴다. 병합 테스트는 4단계에 넣었다. 2.5, 6.3 |
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
| A4 | ai-effect·major | 4단계 검색에 태깅 없이 동작하는 신호가 없다 | 채택 | 의미 검색을 4단계로 앞당기고, 경험 은행보다 먼저 둔다. LEXICAL·DENSE 대상에 경험 텍스트, 미연결 행, 답변을 넣었다. 10.2 R9 이후에는 3단계 RESUME_ROW 검색(LEXICAL·REQUIREMENT)이 태깅 없이 동작하는 신호를 맡고, 의미 검색은 5단계다. 7 |
| A5 | ai-effect·major | REQUIREMENT가 정확 일치뿐이라 변별력이 없고 표기가 흔들리며 본문을 무시한다 | 채택 | 희소도 가중, 별칭 시드(4단계), 토큰 포함 일치, 요구 본문을 늘 질의에. 4.2 |
| A6 | ai-effect·major | DOMAIN을 자동으로 채우는 길이 없다 | 일부 채택 | 이력서→직무→회사 업종 파생을 "추정"으로 채택했다. 임베딩 유사도 매칭은 근거가 없어 보류. 3, 4.2 |
| A7 | ai-effect·major | 직무를 등록하지 않으면 FIT 신호가 없다 | 일부 채택 → 10.2 R3으로 바꿈 | 공고 등록 필수 단계를 없애고, 저장하지 않는 `posting` 입력을 3단계에 넣었다. 4.1 |
| A8 | ai-effect·major | FIT 유형의 PAST_QUESTION이 타사 답변을 가져온다 | 채택 | 같은 회사·업종으로 제한하고 `otherCompanySpecific` 표시. 4.1, 4.2 |
| A9 | ai-effect·major | 생성 시 선택 누락은 막히지 않는다 | 채택 | `missing[]` 을 다른 이력서의 미연결 행까지 넓히고, `resume_create`·`resume_get` 응답에 요약을 붙였다. 0단계 측정 항목에 추가. 5.3, 5.5 |
| A10 | ai-effect·major | 경험 전체 텍스트가 512토큰을 넘는다 | 채택 | `experience_chunks` + max-sim. 4.4 |
| A11 | ai-effect·major | 검색이 낡은 정본만 본다 | 채택 | 서술 정본을 이력서 행으로 일원화하고, 저장 때 연결 경험을 dirty로 만든다. 어느 행을 보는지는 10.2 R1로 바꿨다. 6.1 |
| A12 | ai-effect·minor | outcomes FK (I3과 같음) | 채택 | I3 참조 |
| A13 | ai-effect·minor | 예전 도구 경로로 가면 같은 증상이 난다 | 채택 | 예전 도구 응답에 출처, `alreadyUsedFor`, `missing` 을 싣는다. 사용률 로그로 도구 정리 검토(6단계). 5.3, 5.5 |
| A14 | ai-effect·minor | 골드셋이 저절로 생기지 않는다 | 채택 | 수작업 골드셋을 기본으로, silver label을 보조로. 4.5 |
| A15 | ai-effect·minor | 정규식 단일 유형 오분류가 모드를 바꾼다 | 채택 | 상위 2개 다중 라벨과 신뢰도, 분류 정확도 지표. 4.1, 4.5 |
| P-1 | perf-ops·major | 측정 없이 원인을 단정했다 | 채택 | 0단계 신설(`log_format`, waterfall), 결론을 가설로 낮췄다. 0, 1.2 |
| P-2 | perf-ops·major | written_for가 경험 은행 뒤에 묶였다 | 채택 | 단독 단계로 두었다(10.2 R9 이후 2단계). 7 |
| P-3 | perf-ops·major | 정본 이중화의 유지비가 크다 | 채택(일부 변형) | 경험에는 정체성만 둔다. 정체성 구분용 `start_ym` 과 행이 없는 경험용 `memo` 만 더했다. 2.1, 6.1 |
| P-4 | perf-ops·major | 도구 12개 증가는 과하다 | 채택 | 4개로 줄였다. outline은 `resume_get` 의 `view`, 가져오기·병합·삭제는 웹, 연결은 save 필드. 5.2 |
| P-5 | perf-ops·major | 간선 신뢰 열과 검토 UI가 과하다 | 일부 채택 | status·confidence, 저장된 제안, REJECTED를 없앴다. A2(AI 확정 오염)를 막으려고 `source` 한 열과 웹 ✓ 버튼만 남겼다. 2.5, 3 |
| P-6 | perf-ops·major | pgvector 소스 빌드는 AGE 기각 이유와 같은 비용이다 | 채택 | `REAL[]` + Java cosine, DB 이미지 유지. D11 |
| P-7 | perf-ops·major | 신호 7개와 GPU 컨테이너가 근거 없이 많다 | 일부 채택 | embedder는 기본 CPU, GPU는 실측 뒤 오버라이드로 켠다. 다만 "DENSE는 나중" 제안은 A4와 PoC(dense 0.8 vs BM25 0.2)를 근거로 기각하고 앞당겼다. 신호별 기여는 단계마다 골드셋으로 판정한다. 10.2 R7에서 신호를 하나씩 켜는 순서로 바꿨다. 4.3, 7 |
| P-8 | perf-ops·major | 평가를 지금은 잴 수 없다 | 채택 | 코드 변경 전 수작업 골드셋, leave-one-out은 보조로. 4.5 |
| P-9 | perf-ops·minor | 웹 경로에서 연결이 조용히 끊긴다 | 채택(자동 연결은 유지) | T2를 링커의 선행 조건으로 명시하고, 웹 응답에 `unlinkedWarnings[]`, PUT→GET 왕복 테스트를 넣었다. 제목 키 자동 연결은 미연결 행을 줄이는 이득이 있어 정확히 1건 조건으로 유지한다. 6.2 |
| P-10 | perf-ops·minor | 고아 첨부 정리는 당장 필요 없다 | 채택 | 즉시 삭제만 없애고 정리 작업은 두지 않는다. 1.4 T7 |
| P-11 | perf-ops·minor | OAuth 만료 행 정리가 더 근본적이다 | 채택 | 행 수와 `EXPLAIN` 을 먼저 보고 정리 작업을 검토한다. 라이브러리 동작은 미검증. 1.3 P10 |
| P-12 | perf-ops·minor | `expectedVersion` 필수는 호출을 깨뜨린다 | 채택 → 10.2 R2로 일부 되돌림 | 행 추가는 선택으로 남기고, 행 번호 수정·삭제와 전체 교체는 필수로 바꿨다. 호출이 깨지는 비용보다 엉뚱한 행 덮어쓰기의 비용이 크다. T2 |
| P-13 | perf-ops·minor | `kw_norm` IMMUTABLE과 생성 컬럼 문제 | 채택 | Java `KeyNormalizer` 와 일반 컬럼. 매핑 함정은 문서화. 2.5 |
| P-14 | perf-ops·minor | 검증 안 된 외부 인용 | 채택 | PG19 SQL/PGQ와 JobMatchAI 인용을 삭제했다. 그래프 DB 기각 근거는 계정 단위, 고정 깊이, 운영 비용 셋으로 충분하다. 0, 4.3 |

### 10.2 외부 설계 리뷰 (2026-11-02)

리뷰는 로컬 `b9c2b4e` 기준이다. 지적(R1~R8, R10)을 이 문서와 코드에 대조해 모두 사실로 확인했다. **보완**은 리뷰에 없던 것을 이번에 더한 것이다.

| # | 지적 | 처리 | 반영 위치 |
|---|---|---|---|
| R1 | [P1] "가장 최근에 수정한 이력서 행"을 정본으로 삼으면, 축약본 이력서의 연락처만 고쳐도 검색·복사 원본이 축약본으로 바뀐다. 마지막 연결 이력서를 지우면 서술이 사라진다 | 채택. 검색은 연결 행 전부(출처별), 복사는 대표 행(`is_primary`). 보완: 마지막 연결이 사라질 때만 `detached_rows` 에 옮긴다. 남은 사본이 없을 때라 두 벌이 어긋나지 않는다 | 2.1, 2.5, 4.4, 5.2, 6.1~6.4 |
| R2 | [P1] 행 잠금과 선택적 버전 검사로는 행 번호 밀림을 막지 못한다 | 채택. 행 번호 수정·삭제와 전체 교체는 `expectedVersion` 필수, 추가는 선택, 가져오기 계획에 이력서 버전. 409면 같은 번호로 자동 재시도하지 않는다. 보완: 쓰기 응답이 새 `version` 을 돌려줘 연속 호출이 다시 읽지 않는다(요청당 약 0.3초) | 1.4 T2, 5.1, 5.3, 5.6, 6.3 |
| R3 | [P1] 공고 등록을 필수로 하면 조회와 저장의 경계가 깨진다(고르기 전의 공고까지 저장) | 채택. `experience_search` 가 저장하지 않는 `posting` 을 받는다. 새 도구·테이블 없이 3단계 첫 판에 넣는다 | 4.1, 5.2, 5.6, 7 |
| R4 | [P1] 기술 환경은 필수 자격이 아니다. 사건이 있는 경험은 경험 자체가 후보에서 빠진다 | 채택. 보완: 다섯 칸은 이미 있다(공고 `qualifications`, 직무 `responsibilities`·`required_skills`·`preferred_skills`·`position_tech_stack`). 칸별 가중, `uncovered` 는 지원자격·요구 역량 칸만. 경험과 사건을 모두 후보로 두고 묶는다. 물려받은 기술은 `via: PARENT_EXPERIENCE` | 2.1, 2.6, 4.2, 5.4 |
| R5 | [P1] 사용 중 확인 직후의 연결이 CASCADE로 지워진다. 같은 이력서에 있는 두 경험을 병합하면 중복 금지와 충돌한다 | 채택. 경험·사건 쪽 FK를 지연 `NO ACTION` 으로 바꿨다. 보완: 지연 없는 `NO ACTION`·`RESTRICT` 는 계정 삭제를 실패시킴을 임시 테이블로 확인했다. 병합은 충돌 검사 뒤 409로 거절한다 | 2.3, 2.5, 6.3, 6.4 |
| R6 | [P2] dirty와 `updated_at` 비교로는 낡은 벡터 저장을 막지 못한다 | 채택. `source_revision`. 같은 버전일 때만 한 트랜잭션에서 조각 교체와 dirty 해제 | 2.5, 4.4 |
| R7 | [P2] `log(N/(1+df))` 가 음수가 된다. 출처 가중이 RRF에서 사라진다. 신호 7개를 한꺼번에 넣는다 | 채택. `ln(1 + N/df)`, 후보별 가중 RRF, 신호를 단계마다 하나씩 | 4.2, 4.3, 7 |
| R8 | [P2] USER를 사실 검증 완료로 읽으면 안 된다 | 채택. `trust` 는 관계 확인 출처로 정의하고, 원문 출처 `source` 를 따로 돌려준다 | 3, 5.4, 5.6 |
| R9 | 도입 순서와 첫 완성 목표 "공고 하나를 저장하지 않고 평가하고, 경험의 원문 출처를 돌려준다" | 채택. 한 곳만 다르다(R11) | 0, 7 |
| R10 | 인증 없는 가벼운 요청의 지연을 인증 요청까지 "2ms 미만"으로 일반화했다 | 채택 | 0 |
| R11 | (우리 쪽 이견) 리뷰 순서대로면 문항 의미 검색이 경험 정체성 뒤로 밀린다 | 문항 의미 검색은 `self_introductions` 만 써서 경험 은행과 무관하다. 골드셋에서 BM25 부족이 확인되면 2단계 바로 뒤로 당긴다 | 7 |

### 10.3 외부 설계 리뷰 2차 (2026-11-02, 원격 `6fb5688`)

리뷰 결론: 설계 1단계 구현을 시작해도 된다. 브랜치는 수정 후 병합. 지난 지적 재판정은 R2·R3·R6~R10 해소, R1·R4·R5 부분 해소다. 아래는 남은 지적과 처리다. **보완**은 리뷰에 없던 것을 우리가 더한 것이다.

| # | 지적 | 처리 | 반영 위치 |
|---|---|---|---|
| R12 | [P1] 요구를 내 SKILL 어휘에서만 찾으면 내게 없는 필수 기술이 `uncovered` 에서 사라진다 | 채택. 요구는 공고에서 뽑는다(클라이언트 `requirements[]`, 없으면 서버 시드 사전과 `REQUIREMENTS_PARTIAL`). 내 어휘는 별칭·확장에만 쓴다. 지원 자격은 점수와 분리해 `qualifications[]` 로 돌려준다 | 4.1, 4.2, 5.2, 7 |
| R13 | [P1] 새 연결이 생기면 보존본을 비워, 제목만 있는 행을 연결하면 상세 서술이 사라진다. `resumeVersion` 만으로는 근거를 다시 확인할 수 없다 | 채택. 보존본은 사용자가 웹에서 대체할 때만 비운다. 보완: 배열 `detached_rows`(해시로 중복 제거)로 바꿔 덮어쓰기도 없앴다. 검색 `source` 에 `textHash`·`excerpt`, 소재집 근거에 인용·해시 | 2.5, 6.1, 6.2, 6.5 |
| R14 | [P1] 마지막 연결·대표 행 경쟁을 경험 잠금으로 막으면, 일반 저장(이력서 → 경험)과 병합(경험 → 이력서)의 순서가 엇갈려 교착이 난다 | 채택. 경험 관계를 바꾸는 쓰기는 계정 단위로 직렬화하고, 그다음 이력서 → 경험 순서로 잠근다. 보완: `app_users` 행 대신 `pg_advisory_xact_lock` 을 써서 로그인 쓰기와 분리 | 1.4 T9, 6.2, 6.3, 6.4 |
| R15 | [P2] 지연 FK 오류가 MCP에서 `INTERNAL_ERROR` 로 뭉개진다. 경험 삭제는 사건 → 자소서 FK도 걸린다 | 채택. 영향받는 지연 제약을 모두 `IMMEDIATE` 로 당기고, REST·MCP 공통 오류 변환을 두고, Testcontainers로 통합 테스트한다 | 2.3, 5.1, 7 |
| R16 | LLM wiki 방식(원문에서 출처와 함께 정리·갱신)은 부분 도입할 가치가 크다 | 채택. 경험 소재집 `experiences.dossier`: 파생 자료, 근거 인용·해시, 미확인·충돌, 자소서는 근거 아님. 0절 판단을 "저장소로는 안 씀, 방식은 들임"으로 고쳤다 | 0, 2.1, 5.2, 5.6, 6.5 |
| R17 | pgvector 기각 근거("인덱스가 없으니 확장도 필요 없다")가 약하다 | 채택. D11을 5단계 비교 항목으로 내렸다. 보완: 공식 이미지가 Debian 기반이라 alpine에서 바꾸면 콜레이션 차이로 REINDEX가 필요할 수 있다 | 0, 7, D11 |
| R18 | [P2] PoC가 `answer: null` 에서 `TypeError`. `Recall@1` 은 실제로 Hit@1. 타사 혼입률이 경험 재사용과 지원동기 문구 혼입을 섞는다 | 채택. `search_poc.py` 를 고쳤다(self-check에 null 답변 추가, 이름을 Hit@1로). 지표를 타사 문구 혼입률과 경험 재사용률로 나눴다 | 4.5, `poc/self-intro-search/search_poc.py` |
| R19 | `source_revision` 을 이력서 저장마다 올리면 기본정보만 바꿔도 재계산 대상이 된다 | 채택. 연결 행 원문 필드의 해시가 바뀔 때만 올린다 | 4.4, 6.2 |
| R20 | `expectedVersion` 필수 전환 때 구 클라이언트에 안전하게 실패해야 한다 | 채택. 오류에 현재 버전을 싣지 않고 다시 읽으라고만 안내한다. 서버가 버전을 대신 채우지 않는다 | 1.4 T2 |
| R21 | [P1, 코드] 늦게 온 이전 이력서 응답이 화면과 문항을 덮는다 | 채택. `ResumeBoard`·`ResumeQuestions` 에 요청 순번을 두고, 이력서가 바뀌면 문항 상태를 비운다. 순서를 뒤집는 테스트 3개를 더했고, 고치기 전 코드에서 셋 다 실패함을 확인했다 | `docs/resumes.md`, 프런트 |
| R22 | 옛 `stories/links/pgvector` 구상이 새 설계와 함께 안내된다 | 채택. `docs/experience-graph-search.md` 의 해당 절 제목에 (폐기)를 붙이고 머리 안내를 고쳤다 | 그 문서 |
| — | 브랜치를 인프라 · UI 성능 · 설계와 PoC로 나눠 병합 | 보류. 사용자 결정 대기 | — |

리뷰가 "실제 실행 검증이 남았다"고 한 Compose 분리는 워크트리 스택(8089)에서 스모크 PASS를 확인했다. 기본값(8088)으로 메인 체크아웃을 띄우는 경로는 다시 돌리지 않았다.

### 10.4 3·4차 리뷰: 중앙 관리 전환 (2026-11-02, 원격 `2f906a7`)

3차 리뷰는 로컬 소재집을 중심에 둔 안을 접고, JobSight를 개인 취업 컨텍스트의 중앙 관리 지점으로 삼자고 했다. 우리는 저장 엔진 판단은 유효하고 틀린 것은 정본 위치라고 답했다. 4차 의견은 이 방향에 동의하면서, 정본을 옮기면 문서·검색·제출 기록의 계약도 함께 바꿔야 한다는 조건을 붙였다. 둘 다 반영했다. 코드 사실(`changeStatus` 는 상태만 바꾼다, MCP 첨부 도구가 없다)은 직접 확인했다.

| # | 지적 | 처리 | 반영 위치 |
|---|---|---|---|
| R23 | [아키텍처] JobSight를 중앙 컨텍스트 서비스로 두고, 경험 원본을 정본으로 삼아야 한다. 이력서 행을 정본으로 둔 결정(6.1 옛 판, 10.1 P-3)이 목표와 맞지 않는다 | 채택. 표현 문서를 사실의 기준으로 삼아 R1·R13 땜질이 이어졌다는 진단에 동의한다. 대표 행·"가장 최근" 대체 규칙·보존본을 없앴다 | 0, 2.1, 6.1, D1, D15 |
| R24 | 사실과 소재집은 한 레코드에 두되 역할을 나누고, 소재집이 어느 사실 개정에서 만들어졌는지 남겨야 한다 | 채택(한 레코드는 우리 제안, 개정 기준은 리뷰 보완). `dossier_based_on`, `experience_revisions` | 2.5, 6.5 |
| R25 | 이력서 행은 `experienceId` 만이 아니라 쓴 개정 번호와 실제 문구를 보존해야 한다. 원본 수정이 과거 문서를 바꾸면 안 된다. 차이 경고는 표현·새 사실·정정으로 나눠야 한다 | 채택 | 2.5, 5.2, 6.1, 6.2 |
| R26 | `SUBMITTED` 전환 때 남긴 자료를 실제 제출본이라 부르면 안 된다. 확인 수준을 나누고 재시도 중복을 막아야 한다 | 채택. LINKED / USER_CONFIRMED / RECEIPT_VERIFIED, `content_hash` | 2.5, 5.3, 6.6 |
| R27 | 근거를 서버에 두는 것과 Codex가 읽을 수 있는 것은 별개다. 첨부 MCP 도구가 없다 | 채택. 텍스트 발췌(인용·문맥·위치·해시)를 서버에 두고 MCP로 읽는다. 로컬 경로만 있는 근거는 400. 첨부 형식은 넓히지 않는다 | 2.6, 6.5 |
| R28 | 재현 기준은 자료 버전 + 질의·필터 + 검색 설정 + 모델 + 인덱스 세대여야 한다. 작성 기준 버전도 남기고, 인덱스가 늦으면 알려야 한다 | 채택 | 5.3, 5.4, 6.7 |
| R29 | 3단계는 전환 경로로 명시하고, 같은 응답 형식에서 자료원만 바꿔야 한다 | 채택 | 4.2, 7 |
| R30 | 작성 기준은 계정당 문서 하나와 경험별 주의사항으로 시작한다 | 채택(우리 제안에 리뷰 동의) | 2.5, 5.2, 6.7 |
| — | 작성 검토 이력 테이블, 접수 근거 자동 대조, 첨부 바이너리의 MCP 전달 | 보류. 필요가 확인될 때 | 2.6 |

### 10.5 5차 리뷰: 세 PR 검토 (2026-11-02, #1 `6dd0940`, #2 `af50316`, #3 `4d77774`)

P1 1건, P2 3건. 설계에 해당하는 두 건을 여기 남긴다. 나머지 두 건은 해당 PR에서 고쳤다: #1 두 작업공간의 로그인 쿠키 충돌은 `docs/local-dev.md` 에 브라우저 프로필 분리를 적었고, #2 저장 중 이동 경쟁은 `ResumeBoard.save` 에 조회 순번 확인과 테스트를 더했다.

| # | 지적 | 처리 | 반영 위치 |
|---|---|---|---|
| R31 | [P1] 병합이 source를 지워 개정 이력도 함께 사라진다. 자소서 연결에는 개정 번호만 있어, 사건이 target으로 옮겨지면 그 번호를 target 기준으로 잘못 읽는다. 이력서도 작성 당시와 다른 개정을 가리키게 된다 | 채택. source는 `merged_into` 로 남기고 지우지 않는다. 참조는 (경험 id, 개정 번호) 쌍으로 고정하고, 자소서 연결에 경험 id를 더했다. 이력서 JSON은 병합 때 고쳐 쓰지 않는다. "재검토 필요"는 개정 시각으로 비교한다 | 2.3, 2.5, 6.1, 6.2, 6.3, 6.4, 7 |
| R32 | [P2] `resume_add_experiences(from: ROW)` 가 과거 문구를 복사하면서 최신 개정 번호를 기록해, 정정 전 문구를 최신 사실 근거처럼 만들고 "재검토 필요"를 놓친다 | 채택. `ROW` 는 원래 행의 (경험 id, 개정 번호)를 그대로, `FACTS` 만 최신 개정을 쓴다 | 5.2, 7 |

### 10.6 6차 리뷰: 병합 경로와 작성 근거 입력 (2026-11-02)

| # | 지적 | 처리 | 반영 위치 |
|---|---|---|---|
| R33 | target의 정정만 검사하면 source에서 정정된 뒤 병합된 문서의 경고가 사라진다 | 작성 근거 경험부터 마지막 대표 경험까지 모든 경로의 이후 정정을 검사한다. source와 중간 경험의 이력을 포함하고, 순환과 다른 소유자의 경로는 거절한다 | 6.1, 6.8, 7 |
| R34 | 자소서 입력은 사건 id와 개정 번호뿐이라 병합 전 작성 근거의 경험 id를 전달할 수 없다 | `episodes` 항목에 `experienceId`를 필수로 추가한다. 소유자, 개정 존재, 같은 경험 정체성을 검사하고 입력받은 쌍을 변경 없이 저장·조회한다 | 5.3, 6.8, 7 |

합성 계약 검증 12개와 기존 문항 검색·임베딩 스크립트 자체 점검을 통과했다. `poc/experience-lineage/check_contract.py`는 설계의 참조 모델이며 서버·DB 구현 검증을 대신하지 않는다. 로컬 8089 기존 서비스의 스모크는 PASS (58 checks)이고 이번 작업에서 서비스를 재배포하지 않았다.
