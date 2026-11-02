# JobSight 경험-지원 관계 구조 최종 설계

작성 2026-11-02 · 상태: **설계, 미구현** (1.3 의 P2, 1.4 의 T1·T2·T4·T5 와 이력서 쪽 T3 적용 — 1단계 1a. 1.3 의 P5 와 1.4 의 T7 적용 — 1단계 1b. 1.4 의 공고 쪽 T3 와 5.3 `posting_update` 적용 — 1단계 1c. 1.3 의 P1 적용 — 1단계 1d. 7절 2단계 적용: V23 자소서 직접 출처·문항 유형, `self_intro_list`·`get` 래퍼, 생성·수정 DTO 분리, 웹 확정 — 2단계, 구현 차이는 5.3 표. 7절 3단계 적용: `KeyNormalizer`·묶음 함수, `experience_search` 첫 판, `experience_index` 최소판, `resume_get(view=outline)`·`missing`, MCP 호출 로그 — 3단계, 구현에서 정한 것은 7절 "3단계 구현 메모". 7절 4a 적용: 앵커 확장(`ResumeContent` 앵커 키·`fillRow` 통과·P8) 뒤 `V25__experiences`, `OwnerLock`, `ResumeWriter`·링커, 경험 서비스·가져오기, 자소서 작성 근거 쌍, MCP `experience_save`·`experience_index` 전체판·`resume_add_experiences`, 검색 자료원 교체, 공용 오류 변환 23503·23505 — 4a, 구현에서 정한 것과 설계와 달라진 것은 7절 "4a 구현 메모". 7절 4b 적용: 경험 화면(/experiences)·가져오기·이력서 편집기 경험 연결·자소서 쓴 경험 — 4b, 구현에서 정한 것은 7절 "4b 구현 메모") · 근거: 코드 분석 5갈래, 외부 조사, 3안 독립 설계와 심사, 반박 40건 처리. 1절은 같은 날 공개 도메인 실측으로 고쳤다. 같은 날 외부 설계 리뷰 7건과 보완 4건을 반영했다(10.2절). 이어서 받은 2차 리뷰도 반영했다(10.3절). 3·4차 리뷰로 경험의 정본을 이력서 행에서 경험 원본으로 옮기고 JobSight를 중앙 관리 지점으로 정했다(10.4절). 5차 리뷰로 병합 때 과거 개정을 보존하고, 복사한 행은 원래 개정 번호를 지키게 했다(10.5절). 아키텍처·DB 리뷰 반영(10.7절).

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
- **아키텍처·DB 리뷰와 사용자 결정** (10.7절)
  1. 4단계는 최소판이다: 경험 원본·개정 이력·행 앵커·링커·가져오기·MCP 세 도구·자소서 작성 근거 쌍. 사건(STAR), 병합 계보, 키워드 테이블, `ROW` 복사, "원본 반영 검토"는 4c(보류, 6.10)로 옮겼다. 중복 경험은 "행을 다른 경험에 다시 연결 + 쓰지 않는 경험 삭제"로 처리한다.
  2. 3단계가 4단계 산출물에 기대지 않는다. `KeyNormalizer`·별칭 시드 사전, 묶음 기반 `missing[]`, 4단계 모양의 `experience_index` 최소판을 3단계에 둔다.
  3. 행 앵커를 잃지 않게 한다(웹 `fillRow` 통과, 개정 이어받기, 클라이언트가 개정을 바꾸지 못함). 앵커 필드는 링커보다 한 배포 먼저 낸다.
  4. T9 권고 잠금은 `lock_timeout` 바로 다음 문장에서, 이름공간 키로, 헬퍼 하나에서 잡는다. 개정 참조에 FK·CHECK 를 건다. 이력서 content 는 관문 하나(`ResumeWriter`)로만 쓴다.
  5. 첨부 삭제 설계를 확정했다(6.9). 구현은 파이프라인 뒤다.
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

**첫 완성 목표**는 "공고 하나를 저장하지 않고 평가하고, 내 경험의 원문 출처를 찾아 돌려준다"이다(3단계). 경험 테이블 없이 기존 이력서 행만 읽어도 만들 수 있다. 그래서 경험 은행과 검색 신호를 다 만들기 전에 하네스에 붙여 효과를 확인할 수 있다. 3단계는 4단계 산출물에 기대지 않는다. 정규화(`KeyNormalizer`)·별칭 시드 사전과 묶음 기반 `missing[]` 도 3단계에서 만든다(10.7).

**4단계는 최소판으로 시작한다** (2026-11-02 사용자 결정, 10.7 R45). 경험 원본(사실·소재집·개정 이력), 이력서 행 앵커와 링커, 웹 가져오기, MCP `experience_save`·`experience_index`·`resume_add_experiences`(원본에서만), 자소서 작성 근거 `(경험 id, 개정)` 쌍까지다. 사건(STAR)·병합 계보·키워드 테이블·`ROW` 복사·"원본 반영 검토"는 필요가 확인될 때 여는 4c(6.10)로 옮겼다.

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
2026-11-02(1b): 첨부 메타를 [3] 의 이력서 응답 `attachments` 에 실었다(P5). 이제 [4] 는 없다. 응답에 빠진 id(남의 것·지워진 것)만 칸이 직접 부른다.

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
| P1 | 배치 페치 설정이 없다(`application.yml:10-18`). 그래서 위 EAGER 컬렉션들에서 1+N이 난다 | **적용함(2026-11-02, 1d)**: `spring.jpa.properties.hibernate.default_batch_fetch_size: 100`. 실측(`PositionListQueryCountIntegrationTest`, 기업 5·공고 5·직무 10, 컬렉션마다 행 있음): 직무 목록 1회 SQL **39 → 9**. 테스트가 상한 9를 단언한다 | 직무 목록 SQL → 10회 안팎(실측 9) | 완료 |
| P2 | 자소서 요청이 편집기가 그려진 뒤에야 나갔다(`F/ResumeQuestions.vue` onMounted) | **적용함(2026-11-02)**: 이력서·직무와 같은 `Promise.all` 로 옮기고 `questions` prop 으로 내렸다. 실패하면 `null` 을 넘겨 문항 칸이 직접 다시 부른다. `Promise.all` 을 풀어 직무를 배경으로 돌리는 것은 하지 않는다 — HTTP/2 병렬이라 이득이 서버 처리 시간 차이(ms)뿐이다 | 직렬 단계 하나, 약 0.3초 | 완료 |
| P3 | 자소서: 소유자 전체를 읽고 자바에서 거른다(`B/selfintro/SelfIntroductionService.java:41-45`). LAZY `resumeIds`(`B/selfintro/SelfIntroduction.java:27-31`)는 1d 전에는 행마다 SQL을 냈다. 1d 의 배치 페치는 LAZY 컬렉션에도 적용되므로 지금은 자소서 100개씩 묶여 읽힌다(설정에서 나온 결론이고, 이 행을 구현할 때 실측한다). 인덱스 `idx_self_introduction_resumes_resume`(`M/V20__self_introduction_resumes.sql:10-11`)를 쓰지 않는다 | `join s.resumeIds r where s.ownerId=:o and r=:rid` 조인 쿼리 | SQL 은 1d 로 이미 1+S → 1+⌈S/100⌉(대개 2)다. 남은 이득은 읽는 행 수: 소유자 자소서 전체 → 그 이력서의 자소서만, 그리고 인덱스 사용 | 트랙 |
| P4 | 이력서 목록이 content JSONB까지 읽는다(`B/resume/ResumeService.java:52-56`) | JPQL 생성자 projection | JSONB 전송 0 | 트랙 |
| P5 | 첨부 메타를 편집기가 그려진 뒤 필드마다 GET한다(`F/AttachmentField.vue:23-33`). 첨부가 있는 이력서에서는 **남은 직렬 단계**다 | **적용함(2026-11-02)**: 이력서 상세 응답에 `attachments:{id→meta}` 동봉. 새 경로가 아니라 응답 필드라 경로 표는 그대로다. 이력서를 돌려주는 모든 응답이 같은 모양으로 싣고(생성·저장·복제·`resume_get` 포함), 첨부가 없으면 조회하지 않는다. 남의 것·지워진 id 는 소유자 조건에서 빠지고 그 칸만 `/meta` 를 부른다 | 직렬 단계 하나(약 0.3초), HTTP A건 → 0 | 완료 |
| P6 | 편집기가 쓰는 직무 값은 이름·기업·공고·`updatedAt` 정도(`F/ResumeEditor.vue:88-95`)인데 `PositionResponse` 전체를 받는다 | `GET /api/positions?view=summary`. 쿼리 파라미터라 경로 표는 그대로다. `position_list` MCP 스키마에 `view` 가 노출되는지 확인하고, 노출된다면 설명을 단다 | 응답 크기 감소 | 트랙 |
| P7 | 소유 확인 때문에 엔티티 전체를 로드한다(`ResumeService.java:204-213`) | id 목록 projection 또는 count | 저장 SQL 감소 | 트랙 |
| P8 | 컬렉션을 새 Set으로 갈아 끼워 저장마다 전체 DELETE와 INSERT가 나간다(`Resume.java:69-70`, `Position.java:116,121`, `Company.java:108-109`) | `clear()+addAll()` 로 바꾼다(`SelfIntroduction.java:54-56` 방식). **`Resume.replacePositions`(`Resume.java:75-78`)는 4단계 전에 한다**(10.7 R47): 저장마다 `resume_positions` 를 전부 DELETE·INSERT 해서, 직무 삭제의 cascade 와 같은 행을 다른 순서로 잡아 교착할 수 있다. 4단계는 이력서 저장마다 링커까지 돌아 트랜잭션이 길어진다 | 바뀐 부분만 갱신 | 트랙(`Resume` 은 4단계 전) |
| P9 | 저장소 `frontend/nginx.conf` 에는 gzip과 캐시 헤더가 없다. 다만 공개 도메인은 Cloudflare가 br 압축과 엣지 캐시(`cf-cache-status: HIT`, `max-age=14400`)를 이미 한다(1.0). 폰트는 CSS `@import` 로 직렬 로딩한다(`frontend/src/styles/main.css:1`) | 해시 자산에 `immutable`·1년 캐시, 폰트는 `<link rel=preconnect>` + `<link>`. nginx gzip은 N1-(B)로 갈 때만 의미가 있다 | 재방문 자산 왕복 0, 폰트 체인 단축 | 트랙 |
| P10 | MCP 요청마다 `findByToken(…, ACCESS_TOKEN)` 를 호출한다(`B/mcp/McpOAuthConfig.java:286`). `access_token_value`(`M/V21__persist_mcp_oauth_tokens.sql:13`)에 인덱스가 없다 | 먼저 행 수와 `EXPLAIN` 을 본다. 만료 행이 쌓였으면 정리 작업을 먼저 검토하고, 그다음 hash 인덱스(1.5) | seq scan 제거 | 트랙 |
| P11 | 키 입력마다 stringify를 2회 한다(`F/ResumeEditor.vue:53-57`). `rank()` 의 df 계산이 O(N²)이다(`SelfIntroductionService.java:82-91`) | 원본 쪽은 computed로 한 번만 계산하고, df는 루프 밖으로 뺀다 | 타이핑 지연과 CPU 감소 | 트랙 |

### 1.4 트랜잭션과 동시성 (지연보다 우선)

| # | 문제 (근거) | 처방 | 단계 |
|---|---|---|---|
| T1 | **이력서 lost update.** `mutateRows` 는 잠금 없이 읽고 문서 전체를 저장한다(`ResumeService.java:148-157,195-198`). `@Version` 도 없다. 그래서 병렬 `resume_row_add` 두 건 중 한 행이 사라질 수 있다. 웹 저장이 AI가 추가한 행을 덮어쓸 수도 있다 | `ResumeRepository.findOwnedForUpdate` 와 `setLocalLockTimeout` 를 추가한다. `B/posting/JobPostingRepository.java:25-33` 패턴을 그대로 옮긴다. `update`·`updateBasic`·`mutateRows` 와 뒤 단계의 링커·가져오기(4단계)·병합(4c)이 모두 이 조회를 쓴다 | 1 |
| T2 | 화면이나 AI가 예전에 읽어 둔 문서로 덮어쓴다. **잠금만으로는 행 번호의 의미를 지키지 못한다**(10.2 R2): AI가 `projects[0]` = Planetory를 읽은 뒤 웹에서 0번을 지우면 JOBIS가 0번으로 올라오고, AI의 `index=0` 수정이 JOBIS에 Planetory 내용을 쓴다. 이때도 잠금은 정상적으로 잡힌다 | `resumes.version` 과 `@Version` 을 둔다. **행 번호로 수정·삭제하는 요청과 문서 전체 교체는 `expectedVersion` 필수**, 행 추가는 선택이다. MCP 입력 스키마에도 필수로 표시한다. 없으면 400 `VERSION_REQUIRED`, 다르면 409 `RESUME_STALE` 이다. 두 오류 모두 현재 버전을 싣지 않고 "`resume_get` 으로 다시 읽고 대상을 확인하라"고만 안내한다. 버전을 실어 주면 클라이언트가 확인 없이 그 값으로 다시 보낼 수 있다. 서버가 버전을 대신 채워 통과시키는 호환 처리도 하지 않는다(10.3 R20). 웹 편집기는 항상 보낸다. 모든 쓰기 응답이 새 `version` 을 돌려줘서, 연속 호출이 매번 다시 읽지 않고 이어진다(요청 하나가 약 0.3초, 1.0). `ObjectOptimisticLockingFailureException` 은 `GlobalExceptionHandler` 와 `McpController` 양쪽에서 409로 바꾼다. 지금은 `RuntimeException` → `INTERNAL_ERROR` 로 뭉갠다(`B/mcp/McpController.java:77-78`) | 1 |
| T3 | **null이 삭제로 동작한다.** content가 null이면 빈 문서가 된다(`ResumeService.java:216-219`). 빠진 섹션은 `[]` 가 된다(`B/resume/ResumeContent.java:34-40`). positionIds가 null이면 직무 연결이 끊긴다(`ResumeService.java:72-73,204-206`). `syncPositions` 는 null을 "공고 제목 직무 하나"로 바꾸고, 목록에 없는 직무를 지운다(`B/posting/JobPostingService.java:151-156` 이하) | **`resume_*`·`posting_update` 만** null=유지, `[]`=비움으로 바꾼다. 그 밖의 update 도구는 지금처럼 전체 교체다(5.1). `syncPositions` 는 id 없는 항목을 이름으로 기존 직무와 맞춘다 | 1 |
| T4 | `resume_row_update` 가 행을 통째로 교체한다. 배열만 `carryArrays` 로 옮기고, 빠진 문자열 필드는 지운다(`ResumeService.java:113-146`) | 행 갱신도 병합한다(null=유지, `""`=비움). 그러면 `carryArrays` 는 필요 없다 | 1 |
| T5 | 행 도구가 이력서 전체를 돌려주고(`ResumeService.java:156`), MCP는 그것을 text와 structuredContent에 두 번 싣는다(`McpController.java:65-66`) | 반환을 `{version, section, count, index, 행 머리}` 로 줄인다 | 1 |
| T6 | 행 추가가 섹션 상한 검증을 거치지 않는다(`mutateRows` 는 `normalized()` 만 호출한다) | `mutateRows` 끝에서 문서 전체를 Bean Validation으로 검증한다. `resume_add_experiences` 의 "전부 들어가거나 전부 실패" 가 이 검증을 전제로 하므로 4단계로 옮겼다(10.7 R49). 4단계에서는 `ResumeWriter`(6.2) 가 모든 저장에 한 번 건다 | 4 |
| T7 | **첨부 손실.** 교체나 비우기를 하면 이전 첨부를 즉시 DELETE한다(`F/AttachmentField.vue:51-52,65`). 그런데 복제 이력서는 같은 id를 공유한다(같은 파일 51행 주석, `docs/attachments.md:71`). 그래서 복제본에서 교체하면 원본의 파일이 사라지고, 저장하지 않고 나가도 이미 지워져 있다 | **적용함(2026-11-02)**: 즉시 삭제만 없앤다(교체·제거 모두). 화면에서 쓰지 않게 된 `deleteAttachment` 도 지웠고 `DELETE` 경로는 남긴다. 고아 첨부 정리 작업은 **만들지 않는다.** 기존 문서가 고아 첨부를 함정으로 받아들이고 있고, 용량이 실제 문제가 될 때 추가한다. 그때 기준은 "7일 이상, 같은 owner의 어느 이력서도 참조하지 않음"이고, JSONB 내부 질의가 생긴다는 점을 1.5에 반영한다. 사용자가 직접 지우는 길은 6.9(설계 확정, 구현은 파이프라인 뒤) | 1 |
| T8 | 트랜잭션 안에서 임베딩 서비스를 부르면 DB 연결 획득 3초 제한(`docs/backend.md:18`)에 걸린다 | 쓰기는 `embedding_dirty` 만 표시하고, 계산은 트랜잭션 밖 스위퍼가 한다(4.4) | 5 |
| T9 | 이력서와 경험을 함께 잠그는 쓰기(링커가 도는 저장, 가져오기, 경험 삭제)에서 순서가 엇갈리면 교착이 생긴다. 예: 일반 저장은 이력서 → 경험, 옛 판의 병합(4c)은 경험 → 이력서(10.3 R14). 처음 판은 권고 잠금을 "트랜잭션 첫 문장"에서 잡아 `lock_timeout` 보다 앞섰다. 그러면 대기에 상한이 없어 연결 풀(Hikari 기본 10)이 마를 수 있다(10.7 R36) | **경험 관계를 바꾸는 쓰기는 계정 단위로 직렬화한다.** 아래 「T9 잠금 규약」 | 4 |

**T9 잠금 규약** (4단계. 10.3 R14, 10.7 R36)

1. 헬퍼 하나(`OwnerLock`)에 둔다. 트랜잭션에서 `SET LOCAL lock_timeout = '3s'` **바로 다음 문장**으로 `SELECT pg_advisory_xact_lock(hashtextextended('exp-graph:' || :ownerId::text, 0))` 을 부른다. `lock_timeout` 은 권고 잠금 대기에도 걸린다. `'exp-graph:'` 접두어는 다른 용도의 권고 잠금과 키가 겹치지 않게 하는 이름공간이다.
2. 부르는 곳: 경험을 건드리는 모든 쓰기·삭제(`experience_save`, 웹 경험 편집·검토 확정, 경험 삭제, 가져오기 적용), 이력서 create·copy·delete 와 `lockOwned` 를 거치는 모든 이력서 쓰기(`update`·`updateBasic`·`mutateRows`, 링커가 돈다), 자소서 작성 근거 쓰기, 제출 기록 LINKED 스냅샷(6.6).
3. 그다음 이력서(id 오름차순) → 경험(id 오름차순) 순서로만 행을 잠근다. 경험을 먼저 잠그지 않는다.
4. 사용 중 확인(경험 삭제의 409)은 이 잠금 아래에서 한다. 지연 FK(2.3)는 잠금을 거치지 않은 경로를 막는 방어선으로 남긴다.
5. 행 잠금 수준: JPA `PESSIMISTIC_WRITE` 는 Hibernate 7.4.1 PostgreSQL 방언에서 `for no key update` 다(javap 확인, 10.7). 네이티브 SQL 로 직접 잠글 때도 `FOR NO KEY UPDATE` 를 쓴다. `FOR UPDATE` 는 자식 INSERT 의 FK 검사(`FOR KEY SHARE`)와 충돌해, 다른 트랜잭션의 연결 행 INSERT 가 그 행 잠금을 기다린다. **예외는 첨부 삭제 하나다**(6.9). 그쪽은 `for key share` 와 충돌시키려고 의도적으로 `for update` 를 쓴다.
6. 대기 초과(SQLState 55P03)와 교착(40P01)은 1단계 리뷰 보정의 공용 오류 변환으로 409 `BUSY` 다(7절).
7. `app_users` 행을 잠그지 않고 권고 잠금을 쓰는 것은 로그인 쓰기와 엉키지 않게 하려는 것이다. 해시 충돌은 두 계정을 잠깐 함께 줄 세울 뿐이다. 외부 호출(embedder, LLM)은 이 트랜잭션에 넣지 않는다. 1인 쓰기 규모라 계정 단위 직렬화의 대기는 허용한다.

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
  - JSONB GIN: JSONB 내부를 질의하는 코드가 없다. 첨부 삭제의 사용처 판정(6.9)은 계정 이력서 몇 건의 텍스트 포함 검사라 GIN 없이 한다. 자동 정리 작업을 넣게 되면 다시 판단한다.
  - 벡터 인덱스: 계정당 수백 행이라 필요 없다.
- `docs/experience-graph-search.md` 의 V22 stories 예약은 이 설계로 대체하고 푼다.

### 1.6 기대 효과와 회귀 방지

- 기대 효과(추정): 이력서 화면 1회 SQL 백여 회 → 10회 안팎, API 요청 4+A건 → 3~4건. 실제 체감 효과는 0단계와 같은 방법으로 다시 잰다.
- 회귀 방지: Testcontainers와 `Statistics#getPrepareStatementCount()` 로 요청 1회의 SQL 수 상한을 단언한다. 1d에서 직무 목록(`PositionService.findAll`)에 상한 9로 넣었다(`PositionListQueryCountIntegrationTest`, 통계는 이 테스트에서만 켠다).
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
| 사건 | **4c(보류, 6.10)**. `episodes` (STAR) | 4단계 최소판에는 없다. 자소서 연결과 검색의 단위는 경험이고, 상세 서술은 소재집에 둔다(6.5) |
| 결과 여러 개 | **4c**. `episodes.outcomes TEXT[]` | 4단계에서는 `facts.results[]` 다 |
| 역량·문항 유형 | `competencies` (시스템 고정 코드 18개) | 문항 유형(2단계)과 사건 태그(4c)가 같은 어휘를 쓴다 |
| 기술·도메인 키 | `experiences.skills TEXT[]`·`domains TEXT[]` (4단계) | 값은 `KeyNormalizer`(3단계 산출물)로 정규화한 키다. 계정별 `keywords`·`experience_keywords` 테이블은 4c |
| 회사 도메인 | 기존 `company_industries` | |
| 직무 요구 | 이미 있는 다섯 칸: 공고 `job_postings.qualifications`(지원자격), 직무 `responsibilities`(담당업무), `required_skills`(요구 역량), `preferred_skills`(우대 역량), `position_tech_stack`(기술 환경). 저장하지 않은 공고도 같은 다섯 칸으로 받는다(4.1) | 칸을 섞지 않는다. 기술 환경 목록을 필수 자격으로 계산하지 않는다. `positions.experience` 는 "취득 경험"(입사하면 얻는 것, `PositionDetail.vue:103`)이라 요구로 쓰지 않는다. `position_requirements` 테이블은 두지 않는다(2.6) |
| "이 답변을 어디에 냈나" | `self_introduction_positions` (신규) | **직접 출처** |
| "이 답변에 어떤 경험을 썼나" | `self_introduction_experiences` (4단계) | 작성 근거 `(경험 id, 개정 번호)` 쌍. 사건 단위 연결(`self_introduction_episodes`)은 4c |
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
 └─N:M─ experiences    self_introduction_experiences  uses: (경험 id, 개정) 작성 근거 ← 4단계

experiences (PROJECT|ACTIVITY|CAREER|TRAINING|OTHER, 사실·소재집·skills[]·domains[])                        ← 4단계
 ├─1:N─ experience_revisions (쌓기만, seq 로 순서 비교)
 └─N:M─ resumes                                             resume_experiences ← JSON 행 experienceId·experienceRevision 에서 파생

(4c 보류, 6.10) episodes(STAR) ─N:M─ competencies · keywords + experience_keywords · self_introduction_episodes · merged_into

(기존) resumes ─N:M─ positions ─N:1─ job_postings ─N:1─ companies ─1:N─ company_industries
submissions (공고별 제출 기록, 확인 수준) · writing_guides (계정당 작성 기준 하나)        ← 제출·작성 기준 트랙
```

모든 간선에는 `source`(USER|AI) 열 하나만 둔다.
- 제안·거절 상태, confidence, 저장된 자동 제안은 없다.
- 제안은 질의할 때 계산해서 보여 주기만 한다(3절).
- 다형 간선 `links` 는 쓰지 않는다. FK를 걸 수 없어 고아 간선이 남고, `docs/decisions.md:50` 의 FK 원칙과 충돌한다.

### 2.3 카디널리티와 삭제

4단계 최소판 기준이다. 사건·키워드·병합 관계의 삭제 규칙은 6.10(4c)에 옮겨 두었다.

| 관계 | 카디널리티 | 삭제 시 |
|---|---|---|
| 이력서 ↔ 경험 | N:M (한 이력서 안에서 한 경험은 한 행). 행은 쓴 경험 개정 번호를 함께 든다 | 이력서를 지우면 연결만 사라진다. 경험 원본은 남는다. 이력서가 쓰는 경험은 **DB가 삭제를 막는다**: 경험 쪽 FK 두 개(경험, 개정)가 `NO ACTION DEFERRABLE INITIALLY DEFERRED` 다. 서비스는 T9 잠금 아래에서 먼저 확인해 409 `EXPERIENCE_IN_USE` 와 이력서 이름 목록을 준다 |
| 경험 → 개정 이력 | 1:N | 경험을 지우면 CASCADE. 개정 이력은 고치지 않고 쌓기만 한다 |
| 자소서 ↔ 경험 (작성 근거) | N:M (10 이하). 행은 `(경험 id, 개정)` 쌍 | 자소서를 지우면 CASCADE. 경험 쪽은 개정 참조 FK 가 지연 `NO ACTION` 이다. 근거로 쓰인 경험을 지우려 하면 `detach:true` 없이는 409 `EXPERIENCE_IN_USE`(자소서 목록 포함), `detach:true` 면 근거 행을 먼저 지운다 |
| 공고 → 제출 기록 | 1:N | 공고를 지우면 CASCADE. 기록이 가리키는 첨부는 `submission_attachments` 의 FK 가 삭제를 막는다(6.6, 6.9) |
| 자소서 ↔ 직무 / 문항 유형 | N:M (10 / 3 이하, 2단계) | 양쪽 CASCADE |

**지연 FK로 두는 이유** (2026-11-02 확인, 워크트리 PostgreSQL 17, 롤백되는 임시 테이블)

계정 → 경험·이력서 양쪽이 CASCADE이고 연결 → 이력서도 CASCADE인 구조에서, 연결 → 경험 FK만 바꿔 가며 지웠다.

| 연결 → 경험 FK | 계정 삭제 | 연결된 경험 단독 삭제 |
|---|---|---|
| `NO ACTION` | **실패** (`violates foreign key constraint`) | 실패 |
| `RESTRICT` | **실패** | 재지 않음 |
| `NO ACTION DEFERRABLE INITIALLY DEFERRED` | 통과 | 커밋 때 실패 |

- 4단계 최소판에서 지연 FK 는 `resume_experiences`·`self_introduction_experiences` → 경험 쪽만 남는다: `resume_experiences_experience_fk`, `resume_experiences_revision_fk`, `self_introduction_experiences_revision_fk`(2.5). 제출 트랙의 `submission_attachments_attachment_fk` 도 같은 구조다.
- **계정 삭제 cascade 를 위해 지연이 필요한지 4단계 첫 마이그레이션에서 실제 스키마로 다시 확인한다.** 최소판도 "계정 → 경험·이력서·자소서 CASCADE, 연결 → 이력서·자소서 CASCADE, 연결 → 경험·개정 NO ACTION" 이라 위 실측과 같은 모양이다. 실측은 임시 테이블로 한 것이고, `NO ACTION`·`RESTRICT` 면 깨지고 `DEFERRABLE INITIALLY DEFERRED` 면 통과했다.
- 위반이 커밋 때 나므로, 경험 단독 삭제 서비스는 먼저 영향받는 지연 제약 셋을 `SET CONSTRAINTS resume_experiences_experience_fk, resume_experiences_revision_fk, self_introduction_experiences_revision_fk IMMEDIATE` 로 당기고 삭제 뒤 flush한다(10.3 R15). 그러면 위반이 그 문장에서 나서 409로 바꾸기 쉽다.
- **지연 FK 를 쓰는 쓰기는 커밋 전에 `SET CONSTRAINTS ALL IMMEDIATE` 로 위반을 당긴다.** 링커 저장(`resume_experiences` INSERT)과 자소서 작성 근거 저장이 해당한다. 경험이 지워진 뒤에 커밋하는 링커 저장도 이 문장에서 실패한다.
- 그래도 커밋 시점에 나는 지연 FK 위반은 `TransactionSystemException`·`JpaSystemException` 으로 감싸져 온다. 공용 오류 변환은 원인 체인을 끝까지 따라가 SQLState 를 찾는다(아래).
- **오류 코드는 REST와 MCP가 같아야 한다.** 1단계 리뷰 보정이 REST·MCP 공용 오류 변환을 만들었다(잠금 대기·교착 → 409 `BUSY`, REST 404 코드 `NOT_FOUND`, 7절). 4단계는 여기에 더한다.
  - SQLState 23503(FK 위반): 제약 이름과 오류의 테이블 필드로 세분한다. PostgreSQL 은 삭제 쪽 위반이면 참조되는 테이블을, 삽입 쪽 위반이면 참조하는 테이블을 싣는다. 경험 쪽 지연 FK 셋의 삭제 쪽 → 409 `EXPERIENCE_IN_USE`, 삽입 쪽(확인 뒤 경험이 지워짐) → 409 `EXPERIENCE_CHANGED`. 그 밖의 소유자 복합 FK 의 삽입 쪽 → 404(2.4). 이름을 모르는 FK → 409 `CONFLICT`.
  - SQLState 23505(유일성 위반) → 409 `CONFLICT`.
  - 업무 예외 `EXPERIENCE_IN_USE`, `EXPERIENCE_STALE`, `EXPERIENCE_REVISION_MISMATCH`, `EXPERIENCE_REVISION_NOT_FOUND`, `EXPERIENCE_SECTION_MISMATCH`, `EXPERIENCE_ROW_DUPLICATE`, `EXPERIENCE_REFERENCE_REQUIRED`, `RESUME_STALE`, `VERSION_REQUIRED`, `LINKED_ROWS_MISSING` 도 같은 함수로 REST 와 MCP 도구 오류 코드에 함께 쓴다. `GlobalExceptionHandler` 만 고치면 MCP 경로가 남는다.
  - 경험 삭제의 409, 계정 삭제 성공, MCP 도구 오류 코드를 실제 JPA 트랜잭션 경로(Testcontainers)로 통합 테스트한다(4단계).

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
-- V{n}__experiences.sql  (4단계 최소판: 경험 원본·개정 이력·이력서 연결·자소서 작성 근거. 사건·병합·키워드 테이블은 4c, 6.10)
CREATE TABLE experiences (
    id                UUID PRIMARY KEY,
    owner_id          UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    kind              VARCHAR(10)  NOT NULL CHECK (kind IN ('PROJECT','ACTIVITY','CAREER','TRAINING','OTHER')),
    title             VARCHAR(200) NOT NULL,          -- 경력은 회사명
    title_key         VARCHAR(200) NOT NULL,          -- Java KeyNormalizer(3단계 산출물)가 채운다(생성 컬럼 아님)
    start_ym          VARCHAR(200),                   -- 정체성 구분용. 사용자가 쓴 문자열 그대로
    memo              VARCHAR(2000),                  -- 자유 메모. 검색 텍스트에 더한다
    skills            TEXT[]       NOT NULL DEFAULT '{}', -- SKILL 키. KeyNormalizer 로 정규화한 값, 중복 없음. keywords 테이블은 4c
    domains           TEXT[]       NOT NULL DEFAULT '{}', -- DOMAIN 키. 같은 규칙
    facts             JSONB        NOT NULL DEFAULT '{}', -- 경험 원본: 사실의 기준(6.5). 배열 항목은 안정 id 를 든다
    dossier           JSONB,                          -- 소재집: 사실에서 만든 작성 재료(6.5). 상세 서술·문항별 활용·표현 예시·주의
    revision          INT          NOT NULL DEFAULT 1,  -- 사실 개정 번호. facts 내용 해시가 바뀔 때만 +1 하고 experience_revisions 에 남긴다
    dossier_based_on  INT,                            -- 소재집을 만든 사실 개정 번호. revision 과 다르면 '소재집 갱신 필요'
    reviewed_revision INT,                            -- 사용자가 웹에서 검토를 마친 개정 번호. revision 보다 낮거나 없으면 'AI 기록'
    version           BIGINT       NOT NULL DEFAULT 0,  -- 동시 수정 검사(@Version)
    embedding_dirty   BOOLEAN      NOT NULL DEFAULT true, -- 4단계는 표시만 한다. 5단계 스위퍼가 읽는다
    source_revision   BIGINT       NOT NULL DEFAULT 0,  -- 검색 텍스트(사실·소재집·메모·skills·domains)가 바뀔 때마다 +1 (4.4)
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    UNIQUE (id, owner_id),
    CONSTRAINT experiences_facts_object_ck      CHECK (jsonb_typeof(facts) = 'object'),
    CONSTRAINT experiences_revision_ck          CHECK (revision >= 1),
    CONSTRAINT experiences_dossier_based_on_ck  CHECK (dossier_based_on  BETWEEN 1 AND revision),  -- null 은 통과
    CONSTRAINT experiences_reviewed_revision_ck CHECK (reviewed_revision BETWEEN 1 AND revision)
);
CREATE INDEX experiences_owner_key_idx ON experiences (owner_id, kind, title_key);
CREATE INDEX experiences_embedding_dirty_idx ON experiences (updated_at) WHERE embedding_dirty;
-- 4c 에서 merged_into 를 더하면 위 두 인덱스를 WHERE merged_into IS NULL 부분 인덱스로 바꾼다(6.10)

CREATE TABLE experience_revisions (                 -- 개정 이력. 당시의 사실·소재집을 보존한다. 고치지 않고 쌓기만 한다
    seq           BIGINT      GENERATED ALWAYS AS IDENTITY, -- 순서 비교용. '재검토 필요'는 created_at 이 아니라 seq 로 판정한다(6.1)
    owner_id      UUID        NOT NULL,
    experience_id UUID        NOT NULL,
    revision      INT         NOT NULL CHECK (revision >= 1),
    change_kind   VARCHAR(8)  NOT NULL CHECK (change_kind IN ('INIT','ADD','CORRECT')),  -- 'MERGE' 는 4c 에서 넓힌다
    facts_hash    VARCHAR(64) NOT NULL,             -- 정규화한 facts 의 SHA-256. 직전 개정과 같으면 개정을 만들지 않는다(6.5)
    facts         JSONB       NOT NULL,
    dossier       JSONB,                            -- 이 개정을 만들 때의 소재집
    source        VARCHAR(4)  NOT NULL CHECK (source IN ('USER','AI')),
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (experience_id, revision),
    CONSTRAINT experience_revisions_ref_key UNIQUE (experience_id, revision, owner_id),  -- 개정 참조 FK 의 대상
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE
);

ALTER TABLE resumes ADD CONSTRAINT resumes_id_owner_key UNIQUE (id, owner_id);
CREATE TABLE resume_experiences (                   -- JSON 행 experienceId·experienceRevision 에서 파생. ResumeWriter 의 링커만 쓴다(6.2)
    owner_id               UUID NOT NULL,
    resume_id              UUID NOT NULL,
    experience_id          UUID NOT NULL,           -- 지금 연결된 경험
    based_on_experience_id UUID NOT NULL,           -- 이 기업용 문구를 쓸 때 본 경험. JSON 행의 experienceId. 4단계에서는 experience_id 와 같다(병합은 4c)
    based_on_revision      INT  NOT NULL CHECK (based_on_revision >= 1),  -- 그때의 개정 번호
    PRIMARY KEY (resume_id, experience_id),
    FOREIGN KEY (resume_id, owner_id) REFERENCES resumes (id, owner_id) ON DELETE CASCADE,
    -- CASCADE 가 아니다: 쓰이는 경험의 삭제를 DB 가 막는다. 지연이어야 계정 삭제 연쇄가 통과한다(2.3)
    CONSTRAINT resume_experiences_experience_fk
      FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT resume_experiences_revision_fk
      FOREIGN KEY (based_on_experience_id, based_on_revision, owner_id) REFERENCES experience_revisions (experience_id, revision, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX resume_experiences_experience_idx ON resume_experiences (experience_id);
CREATE INDEX resume_experiences_revision_idx   ON resume_experiences (based_on_experience_id, based_on_revision);

CREATE TABLE self_introduction_experiences (        -- uses: 답변의 작성 근거 (경험 id, 개정) 쌍(6.8). 사건 단위 연결은 4c
    owner_id             UUID       NOT NULL,
    self_introduction_id UUID       NOT NULL,
    experience_id        UUID       NOT NULL,
    experience_revision  INT        NOT NULL CHECK (experience_revision >= 1),
    source               VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, experience_id),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE,
    -- 개정 참조 FK 하나로 경험 존재까지 보장한다. 경험 삭제는 개정을 CASCADE 로 지우므로 여기서 막힌다
    CONSTRAINT self_introduction_experiences_revision_fk
      FOREIGN KEY (experience_id, experience_revision, owner_id) REFERENCES experience_revisions (experience_id, revision, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX self_introduction_experiences_experience_idx ON self_introduction_experiences (experience_id, experience_revision);
```

옮긴 DDL: `experience_chunks` 는 5단계 블록으로 옮겼다(벡터 열 모양은 D11 결정 뒤에 정한다). `episodes`·`keywords`·`experience_keywords`·`episode_competencies`·`self_introduction_episodes`·`merged_into` 는 6.10(4c) 스케치로 옮겼다.

```sql
-- V{n}__experience_embeddings.sql  (5단계: 경험 의미 검색. 벡터 열 모양은 D11 비교 뒤에 정한다)
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
```

```sql
-- V{n}__submissions_and_guides.sql  (제출·작성 기준 트랙, 2단계 이후)
ALTER TABLE job_postings ADD CONSTRAINT job_postings_id_owner_key UNIQUE (id, owner_id);
ALTER TABLE attachments  ADD CONSTRAINT attachments_id_owner_key  UNIQUE (id, owner_id);
CREATE TABLE submissions (                          -- 공고별 제출 기록(6.6). 고치지 않고 쌓는다
    id              UUID PRIMARY KEY,
    owner_id        UUID         NOT NULL,
    posting_id      UUID         NOT NULL,
    level           VARCHAR(16)  NOT NULL CHECK (level IN ('LINKED','USER_CONFIRMED','RECEIPT_VERIFIED')),
    content_hash    VARCHAR(64)  NOT NULL,          -- 스냅샷 내용의 해시. 같은 요청을 다시 보내면 같은 기록을 돌려준다
    resumes         JSONB        NOT NULL,          -- [{resumeId, name, version, content}]
    self_intros     JSONB        NOT NULL,          -- [{id, question, answer, updatedAt, experienceRevisions}]
    receipt         JSONB,                          -- RECEIPT_VERIFIED 의 근거(접수 메일 발췌, 접수 번호, 첨부 id)
    recorded_at     TIMESTAMPTZ  NOT NULL,
    UNIQUE (posting_id, content_hash, level),
    UNIQUE (id, owner_id),
    FOREIGN KEY (posting_id, owner_id) REFERENCES job_postings (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX submissions_owner_idx ON submissions (owner_id, posting_id);

CREATE TABLE submission_attachments (               -- 스냅샷이 가리키는 첨부(옛 attachment_ids UUID[] 를 정규화). DB 가 그 첨부의 삭제를 막는다(6.9)
    owner_id      UUID NOT NULL,
    submission_id UUID NOT NULL,
    attachment_id UUID NOT NULL,
    PRIMARY KEY (submission_id, attachment_id),
    FOREIGN KEY (submission_id, owner_id) REFERENCES submissions (id, owner_id) ON DELETE CASCADE,
    CONSTRAINT submission_attachments_attachment_fk   -- 2.3 과 같은 이유로 지연 NO ACTION(계정 삭제 연쇄)
      FOREIGN KEY (attachment_id, owner_id) REFERENCES attachments (id, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX submission_attachments_attachment_idx ON submission_attachments (attachment_id);  -- 첨부 삭제 판정이 인덱스 질의가 된다

CREATE TABLE writing_guides (                       -- 계정당 작성 기준 하나(6.7)
    owner_id   UUID PRIMARY KEY REFERENCES app_users (id) ON DELETE CASCADE,
    body       TEXT        NOT NULL,                -- 선호 문체, 피할 표현, 성과 설명 범위. Markdown
    version    INT         NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE self_introductions ADD COLUMN writing_context JSONB;  -- 작성 당시 재현 정보(6.7)
```

엔티티 규칙과 함정:
- 새 엔티티에는 EAGER 컬렉션을 두지 않는다. `experiences` 에는 `@Version` 을 둔다(사건은 4c).
- `ExperienceKind`, `Competency`, `LinkSource` 는 Java enum으로 둔다. MCP 입력 스키마에 enum으로 나가므로 AI가 없는 값을 지어낼 수 없다.
- 입력 DTO는 평평한 record와 enum으로 만든다(`docs/mcp-plugin.md:137` 의 스키마 생성기 제약).
- `ddl-auto: validate`(`application.yml:14`)에서 `skills`·`domains` 의 `TEXT[]` 는 4단계 첫 커밋에서 매핑을 확인한다. `REAL[]` 를 `float[]` 로 매핑하려면 `@JdbcTypeCode(SqlTypes.ARRAY)` 가 필요할 것으로 본다(미검증). 안 되면 `bytea` 로 바꾸고 Java에서 변환한다. 처음 `REAL[]` 열을 만드는 단계(5단계, D11 결정 뒤)의 첫 커밋에서 확인한다.
- **`KeyNormalizer` 와 별칭 시드 사전은 3단계 산출물이다**(10.7 R42). 3단계가 묶음 키 `(kind, title_key, 시작 연월)` 과 4.2 REQUIREMENT 의 요구 추출에 쓰고, 4단계는 같은 클래스를 재사용해 `title_key`·`skills`·`domains` 를 채운다. DB 함수나 생성 컬럼 대신 Java 한 곳에서 계산해 일반 컬럼에 저장한다. 별칭 시드 사전(`k8s→kubernetes`, `js→javascript` 등)도 이 클래스에 둔다. 4c 의 `keywords.norm_key` 도 같은 클래스다.
- **`Persistable` (10.7 R48)**: 기존 엔티티는 생성자에서 UUID 를 채우고 `@Version` 이 primitive `long` 이라(`Resume.java:49-63`) Spring Data 가 새 엔티티로 보지 못해 `save` 가 merge(+SELECT 1회)가 된다. 가져오기의 대량 insert 를 하는 새 엔티티(`Experience`, `ExperienceRevision`)는 `Persistable#isNew` 를 구현한다.
- **이력서 content 는 `ResumeWriter` 하나로만 쓴다**(6.2, 10.7 R40). `ResumeRepository.save*` 를 다른 곳에서 부르면 실패하는 아키텍처 테스트를 둔다.

### 2.6 지금 만들지 않는 것

| 항목 | 이유 | 추가 시점 |
|---|---|---|
| `position_requirements`(필수/우대 태그) | 이미 있는 다섯 칸(2.1)을 칸별로 쓰면 첫 판 신호에 충분하다 | 칸 본문에서 키를 놓치는 사례가 골드셋에서 확인될 때 |
| `outcomes` 테이블 | 결과를 가리키는 관계가 없다 | 결과 단위 연결이 필요해질 때 |
| 간선 status·confidence, 저장된 자동 제안, REJECTED | 1인 규모에 검토 UI까지 붙어 과하다. 제안은 질의 때 계산한다 | 저장된 제안이 실제로 필요해질 때 마이그레이션으로 열 추가 |
| 접수 근거 자동 대조 | 제출 기록은 확인 수준만 남긴다. 접수 메일·파일과 제출 스냅샷을 자동으로 대조하지 않는다(6.6) | 대조 수요가 확인될 때 |
| 작성 검토 이력 테이블 | 초기에는 작성 기준 문서 하나와 경험별 주의사항으로 충분하다 | 검토 결과를 질의해야 할 때 |
| 첨부 바이너리의 MCP 전달, 첨부 형식 확대 | 근거는 텍스트 발췌로 서버에 둔다. 첨부는 메타와 웹 주소만 MCP로 준다(6.5) | 발췌로 부족한 사례가 나올 때 |
| 사건(STAR)·병합 계보·키워드 테이블·`ROW` 복사·"원본 반영 검토" | 4단계 최소판에서 뺐다(사용자 결정, 10.7 R45). 6.10 에 설계와 다시 열 때의 조건을 둔다 | 4c: 필요가 확인될 때 |
| 사건 단위 기술 태그 | 사건(4c)은 경험의 기술 태그를 물려받는다. 물려받은 일치는 `why` 에 `via: PARENT_EXPERIENCE` 로 표시한다(4.2) | 4c 뒤 필요가 보일 때 |
| 고아 첨부 자동 정리 작업 | 1.4 T7. 사용자가 지우는 길은 6.9 에 둔다 | 용량이 문제가 될 때 |
| `job_postings.target_position_id` 정리 | 쓰지도 지우지도 않는다(D9) | — |

---

## 3. 관계를 누가 어떻게 만드는가

| 관계 | 사용자 (웹) | AI (MCP) | 질의 때 계산하는 후보 (저장 안 함) |
|---|---|---|---|
| 이력서 행 → 경험 | "경험에서 불러오기", "경험으로 등록", "다른 경험에 연결" | `resume_add_experiences`, 행 도구의 `experienceId` | 저장할 때 링커가 연결한다. (kind, title_key)가 정확히 1건 일치하고 시작 연월 숫자가 충돌하지 않을 때만 연결한다(6.2). 파생 관계라 출처가 없다. 같은 경험이 둘로 등록됐으면 병합(4c) 대신 행을 남길 경험에 다시 연결하고 쓰지 않는 경험을 지운다(6.4) |
| 경험 → 기술 | 자동완성 칩 | `experience_save(skills)` | 웹 "경험 가져오기"를 하면 이력서 `techStack` 원문을 `, / · \|` 기준으로 나눈다. 값은 `experiences.skills` 에 정규화 키로 둔다. 키별 출처(USER/AI)는 두지 않고 경험의 검토 상태(`reviewed_revision`)를 따른다. 키별 출처는 4c `experience_keywords` |
| 경험 → 도메인 | 칩(선택) | `experience_save(domains)` | **파생**: 경험이 실린 이력서 → 직무 → 회사 업종, 경력 행의 회사가 기업 탭에 있으면 그 회사 업종. trust "추정" |
| 사건 → 역량 (4c) | 칩 | 4c `experience_save(episodes[].competencies)` | 5단계 이후: 사건 임베딩과 역량 설명 문장의 최근접 상위 2개를 화면에 "추천"으로만 보여 준다 |
| 자소서 → 직무 (written_for) | 편집기에서 직무 선택. 출처가 없는 답변에는 "어느 지원용?" 후보 칩을 띄운다 | `self_intro_create/update(positionIds)` | 연결 이력서들의 직무 **전부**를 후보로 보여 준다. 개수 제한은 없고, 사용자가 클릭 한 번으로 고른다 |
| 자소서 → 경험 (uses, 작성 근거) | "쓴 경험" 선택. 선택할 때의 경험 개정을 함께 저장한다 | `self_intro_create/update(experiences[{experienceId, experienceRevision}])` (6.8) | 5단계: 답변 조각과 경험 조각의 cosine 상위 후보. 사건 단위(`episodes`)는 4c |
| 자소서 → 문항 유형 | 칩 | `questionTypes` | 정규식 상위 2개를 신뢰도와 함께 계산한다(4.1) |

규칙:
1. **서버는 외부 LLM을 부르지 않는다.** 추출은 사용자가 쓰는 MCP 클라이언트가 한다.
2. **출처는 요청 주체가 정한다.** 웹 세션 요청은 `USER`, MCP 토큰 요청(`B/mcp/McpPrincipal.java` 주체)은 항상 `AI` 다. `confirm` 같은 인자는 두지 않는다. 서버가 검증할 수 없기 때문이다.
3. **확정은 웹에서만 한다.**
   - 웹은 AI 간선을 "AI 기록" 칩으로 보여 주고, ✓를 누르면 `USER` 로 바꾸고 ✗를 누르면 삭제한다. 경험 목록에는 "AI 기록 n건" 배지를 단다.
   - 2단계 구현: ✓는 웹 전용 `POST /api/self-introductions/{id}/confirm` 으로 바로 확정하고, ✗는 편집기 초안에서 빼 `저장` 으로 보낸다. 수정 PUT 은 남는 연결의 출처를 바꾸지 않으므로 웹 저장이 AI 기록을 저절로 확정하지 않는다.
   - 제안은 저장하지 않으므로 거절 기억이 필요 없다.
4. **검색은 출처에 따라 신호를 다르게 쓴다.** 타사 자료가 섞이는 것을 막는 핵심 규칙이다.
   - **하드 필터**(회사·직무 필터, `kinds`, `competencies`)는 `USER` 간선만 쓴다.
   - **PAST_USE**는 `USER` 를 1배, `AI` 를 0.5배로 반영하고 `why` 에 "AI 기록"으로 표시한다. AI가 잘못 남긴 연결이 사람이 확정한 것과 같은 무게로 증폭되지 않게 하려는 것이다.
   - 태그 신호(REQUIREMENT, DOMAIN, COMPETENCY)는 `AI` 와 "추정"을 0.5배로 반영한다.
5. **`trust` 문자열**은 `"확정"`(USER), `"AI 기록"`, `"추정"`(파생·계산) 셋 중 하나다. 웹과 MCP가 같은 값을 쓴다.
   - `trust` 는 **이 관계를 누가 확인했나**만 뜻한다(10.2 R8). "확정"은 사용자가 연결이 맞다고 눌렀다는 뜻이지, 실제 구현·개인 기여·운영 성과를 검증했다는 뜻이 아니다. SKILL에도 이 구분을 적는다.
   - 원문 출처는 따로 돌려준다: 이력서 행은 `source{resumeId, resumeName, section, index, resumeVersion, textHash, excerpt}`, 경험은 `{experienceId, revision}`(사건 `{episodeId, version}` 은 4c).

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

**4단계 최소판에서 읽는 법** (10.7 R45): 사건은 4c 다. 4c 전의 후보는 경험(`type: EXPERIENCE`)과 미연결 행(`type: RESUME_ROW`) 둘이고 응답 형식은 같다. 아래의 사건 후보, `episodes[]` 묶음, `via: PARENT_EXPERIENCE`, 사건 태그를 쓰는 COMPETENCY 와 사건 벡터는 4c 가 열린 뒤에 켠다. PAST_USE·PAST_QUESTION 은 사건 대신 `self_introduction_experiences` 의 경험을 쓴다.

**후보 단위**
- **사건과 경험을 모두 후보로 둔다** (10.2 R4). 사건이 있는 경험도 경험 자체가 후보다(`type: EXPERIENCE`). Planetory에 '전처리 최적화' 사건만 있고 'HDFS 구축'은 경험 설명에만 있으면, HDFS 질의는 경험 후보로 돌아온다.
- **같은 근거는 묶는다.** 한 경험과 그 사건이 함께 오르면 경험 한 줄 아래에 사건을 `episodes[]` 로 묶는다. 순위는 묶음 안 최고 점수다.
- **물려받은 일치는 경로를 밝힌다.** 사건에는 기술 태그가 없어 경험의 태그로 일치한다. 이때 `why` 에 `via: PARENT_EXPERIENCE` 를 붙인다.
- **은행에 없는 이력서 경험 행**도 후보로 넣는다(`type: RESUME_ROW`, 출처 `source{resumeId, resumeName, section, index, resumeVersion, textHash, excerpt}`). 그래서 가져오기 전에도 검색이 비지 않는다. RESUME_ROW에는 LEXICAL과 REQUIREMENT(행 `techStack` 과 본문)만 적용한다. **3단계는 이 후보만으로 동작한다.** 4단계부터 연결된 행은 경험 후보로 바뀌고 미연결 행만 남는다. 응답 형식은 그대로 두고 자료원만 바꾸는 전환 경로다(10.4 R29). 같은 (kind, title_key) 행은 한 줄로 묶고 출처를 모두 싣는다.

| 신호 | 계산 | 출처 반영 |
|---|---|---|
| REQUIREMENT | **요구는 공고에서 뽑고, 내 어휘로 뽑지 않는다**(10.3 R12). ① 요구 목록은 클라이언트가 공고 원문에서 뽑아 `requirements[{text, field, kind: SKILL\|QUALIFICATION, quote}]` 로 넘긴다. 내 자료에 없는 기술도 요구로 남아야 `uncovered` 에 나오기 때문이다. 넘기지 않으면 서버가 기술 환경 목록과 일반 별칭 시드 사전(내 어휘가 아니다)으로 뽑고 `query.requirementsSource: SERVER_DICTIONARY` 와 `warnings:["REQUIREMENTS_PARTIAL"]` 를 준다. ② 뽑은 요구를 내 경험과 대조한다. 내 SKILL 어휘(4단계부터 `experiences.skills`, 3단계는 행 `techStack` 을 `KeyNormalizer` 로 정규화한 값)는 별칭 일치와 검색 확장에만 쓴다. ③ `kind: QUALIFICATION`(경력 연수, 학력, 고용 조건)은 점수에 넣지 않고 `qualifications[]` 로 공고 인용과 함께 돌려준다. 충족 판단은 사용자와 클라이언트가 한다. 일치는 정확 일치, 별칭 일치, ASCII 토큰 포함 관계("AWS EC2"⊇"AWS")이고 포함 관계는 0.5배다. **칸마다 가중이 다르다**: 지원자격·요구 역량 1, 담당업무 0.7, 우대 역량 0.5, 기술 환경 0.3(초기값, 골드셋으로 조정). 채널 안 점수는 Σ 칸 가중 × 희소도 `ln(1 + N/df)` 다(N = 후보 경험 수, df = 그 기술을 가진 경험 수, 3단계는 행 묶음 수). 1 ≤ df ≤ N 이라 늘 ln 2 이상이고, 흔한 기술일수록 작다. 예전 식 `log(N/(1+df))` 는 N = df = 1 에서 −0.69가 되어 일치가 감점이 됐다(10.2 R7). `why` 에 맞은 칸(`field`)과 공고 인용(`quote`)을 적는다 | AI·추정 0.5배 |
| DOMAIN | 대상 회사 `company_industries` 정규화 값 = 경험의 DOMAIN 키워드(USER/AI), 또는 3절의 파생 도메인(추정) | AI·추정 0.5배 |
| PAST_USE | 같은 직무(3) > 같은 회사(2) > 같은 업종(1)에 written_for로 낸 답변이 uses로 쓴 경험(4c 뒤에는 사건) | USER 1배, AI 0.5배 |
| COMPETENCY (4c 뒤) | 문항 유형 코드가 사건에 붙어 있음 | AI 0.5배 |
| PAST_QUESTION | 문항↔과거 문항 벡터 상위 30 → 리랭커 K=10 → 그 문항들이 uses로 쓴 경험(4c 뒤에는 사건). FIT 유형이면 같은 회사·업종으로 제한 | USER·AI 모두. AI는 0.5배 |
| DENSE | 질의 임베딩과 경험 조각 벡터(max-sim), 4c 뒤에는 사건 벡터의 cosine. owner로 좁힌 뒤 Java에서 정확히 정렬 | — |
| LEXICAL | 기존 `rank()`(df를 루프 밖으로 뺀 판)를 경험 텍스트(사실·소재집·메모), RESUME_ROW 텍스트, 4c 뒤에는 사건 텍스트에 적용. 직무 다섯 칸 본문은 요구 태그 유무와 관계없이 **항상** 질의에 넣는다 | — |

**`uncovered[]`**: 받쳐 줄 후보가 없는 요구의 목록이다. 요구를 공고에서 뽑으므로 내 자료에 없는 기술도 여기에 나온다. 요구가 서버 사전으로만 뽑혔으면(`REQUIREMENTS_PARTIAL`) 빠진 요구가 있을 수 있다고 함께 알린다. **지원자격·요구 역량 칸에서 나온 키만** 넣는다. 우대 역량·기술 환경 칸의 키는 결핍으로 보고하지 않는다(Databricks, Tableau가 나열됐다고 결핍이 아니다). 결핍을 단정하지 않도록 두 가지 장치를 둔다.
- 일치하지 않은 이유를 구분한다: `{key, reason: NO_MATCH | TAG_MISMATCH_POSSIBLE}`. 후보 텍스트에 그 키 문자열이 보이면 `TAG_MISMATCH_POSSIBLE` 이다.
- 미등록 행이 있거나(`counts.unlinkedRows > 0`), 사실(`facts`)이 빈 경험의 비율이 절반을 넘으면 `coverageIncomplete:true` 를 함께 준다(4c 뒤에는 사건 없는 경험 비율도). SKILL 규칙: 이때 AI는 "경험이 없다"고 단정하지 않고, 가져오기나 경험 정리를 권한다.

**`alreadyUsedFor[]`**: 후보 경험(4c 뒤에는 사건)이 written_for 답변의 작성 근거로 이미 쓰인 회사·직무다.
- 지금 지원하는 회사와 같으면 `sameCompany:true` 다.
- FIT 유형 답변을 다른 회사용으로 쓴 경우에는 `otherCompanySpecific:true` 를 주고, SKILL에서 그 답변 본문의 재사용을 금지한다.

### 4.3 융합, 필터, 다양성, 트랜잭션

1. **융합은 가중 RRF(k=60)** 다. 척도가 다른 점수는 더하지 않는다. 후보의 신호별 기여는 `w × 1/(k + rank)` 이고, `w` 는 그 후보의 출처 가중(USER 1, AI·추정 0.5)이다. 0.5배를 신호 목록 안의 순위에만 넣으면, 후보가 하나이거나 모두 AI 기록일 때 순위가 그대로라 기여가 줄지 않는다(10.2 R7). 신호가 드문드문한 구조에서 RRF가 "몇 개 목록에 나왔나"로 기우는지는 4.5 평가로 확인한다.
2. **신호는 하나씩 켠다.** 3단계는 LEXICAL·REQUIREMENT, 5단계에 DENSE를 더한다. DOMAIN·PAST_USE·COMPETENCY·PAST_QUESTION은 골드셋에서 이득이 확인된 것만 6단계에 넣는다.
3. 계정당 후보가 수백 개라 신호마다 후보를 자르지 않는다.
4. 하드 필터는 USER 간선에만 적용한다.
5. **다양성**: 상위 `limit` 안에서 한 경험 묶음에는 사건을 2개까지만 펼친다(4c 뒤).
6. **트랜잭션**: 오케스트레이터 `ExperienceSearch` 에는 `@Transactional` 이 없다.
   1. 질의 벡터를 받는다. 트랜잭션 밖이고 타임아웃은 2초, 실패하면 `degraded:["dense"]` 로 표시하고 계속한다.
   2. readOnly 메서드 하나로 SQL 4~6회와 벡터 로드를 한다.
   3. 리랭커도 트랜잭션 밖에서 부른다.

SQL 스케치. 직무 소유는 먼저 `findByIdAndOwnerId` 로 확인한다.

```sql
-- REQUIREMENT 후보 (키 매칭과 희소도는 Java 에서. 요구 키는 KeyNormalizer 로 정규화한다)
-- 출처 가중: 검토를 마친 경험(reviewed_revision = revision)이면 1, 아니면 0.5 (키별 출처는 4c keywords)
SELECT id, skills, reviewed_revision = revision AS reviewed
  FROM experiences
 WHERE owner_id = :o;

-- PAST_USE (4c 뒤에는 self_introduction_episodes 의 사건 단위)
SELECT sie.experience_id, sie.source AS use_src, sip.source AS for_src,
       CASE WHEN p2.id = :pid THEN 3 WHEN jp.company_id = :companyId THEN 2 ELSE 1 END AS w,
       c.name || ' · ' || p2.name AS used_for
  FROM self_introduction_experiences sie
  JOIN self_introduction_positions sip ON sip.self_introduction_id = sie.self_introduction_id AND sip.owner_id = :o
  JOIN positions    p2 ON p2.id = sip.position_id AND p2.owner_id = :o
  JOIN job_postings jp ON jp.id = p2.posting_id   AND jp.owner_id = :o
  JOIN companies    c  ON c.id  = jp.company_id   AND c.owner_id  = :o
 WHERE sie.owner_id = :o
   AND (p2.id = :pid OR jp.company_id = :companyId
        OR EXISTS (SELECT 1 FROM company_industries ci WHERE ci.company_id = c.id AND ci.industry = ANY (:industries)));
-- 가중 = w × (use_src='USER' and for_src='USER' ? 1 : 0.5). 경험별 최대값으로 순위.

-- DENSE: 벡터를 읽어 Java 에서 정확 cosine (5단계. 사건 벡터는 4c 뒤)
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
- 쓰이는 곳: `self_intro_list(q)` 와 PAST_QUESTION. PAST_QUESTION 은 6단계 신호라, 리랭커를 6단계로 미룰지는 열린 질문이다(9.2).
- 사건 재정렬(4c 뒤)은 평가에서 이득이 확인될 때만 켠다.

**접두 규칙** (`docs/experience-graph-search.md:158` 을 이 표로 바꾼다)

| 비교 | 질의 쪽 | 문서 쪽 |
|---|---|---|
| 직무·문항·자유 문장 → 경험 조각(4c 뒤 사건) | `query: ` | `passage: ` |
| 문항 ↔ 문항 | `query: ` | `query: ` |
| 문항·자유 문장 → 답변 조각 | `query: ` | `passage: ` |

**길이 처리** (1200자가 512토큰에서 조용히 잘린 실측, `docs/experience-graph-search.md:150`):
- **사건 (4c)**: STAR 4필드 + 성과 합계 900자 상한을 서비스에서 검사한다. 900자는 실측 비례로 낸 추정이고, 토크나이저로 다시 잰다. 그래도 `truncated` 가 오면 저장 응답에 경고를 싣는다.
- **경험**: 경험 원본의 사실(`facts`)·소재집(`dossier`)·메모 텍스트를 필드·문단 경계로 480토큰 이하 조각으로 나눈다. 점수는 조각들의 max-sim이다. 기업별 이력서 문구는 경험 조각에 넣지 않는다(미연결 행만 RESUME_ROW 후보로 따로 본다). 사건이 생겨도 경험 조각은 유지한다. 사건으로 정리되지 않은 업무가 설명이나 성과에만 있을 수 있기 때문이다.
- **자소서**: 질문 1조각, 답변은 480토큰 조각들.

**계산 시점 (dirty + 원문 버전 + 해시 + 스위퍼, D14)**
- 쓰기 경로는 대상에 `embedding_dirty = true, source_revision = source_revision + 1` 만 표시한다. dirty가 이미 true여도 버전은 오른다.
  - 경험의 `source_revision` 이 오르는 때: 사실·소재집·메모·`skills`·`domains` 가 바뀔 때. 이력서 저장은 경험 임베딩을 건드리지 않는다(10.4). 사실 개정 번호(`revision`)와는 다른 값이다. `revision` 은 사실이 바뀔 때만 오른다.
  - 자소서(4c 뒤에는 사건도)는 텍스트를 바꾸면 오른다.
- 스위퍼는 `@Scheduled(fixedDelay = 3s)` 로 돈다.
  1. 테이블마다 `WHERE embedding_dirty ORDER BY updated_at LIMIT 32` 로 `id`, `source_revision`, 텍스트를 읽는다. 잠그지 않는다.
  2. 조각별 `text_hash` 를 비교해 바뀐 조각만 `/embed` 한 번으로 계산한다. 트랜잭션 밖이다.
  3. **행마다 따로 짧은 트랜잭션**을 연다. 첫 문장은 CAS 한 문장 `UPDATE … SET embedding_dirty = false WHERE id = ? AND source_revision = ?` 다. 바뀐 행 수가 1일 때만 같은 트랜잭션에서 조각을 교체하고 커밋한다. 0이면 원문이 그사이 바뀐 것이라 아무것도 쓰지 않고 다음 회차에 다시 계산한다(10.2 R6). 여러 경험 행을 한 트랜잭션에서 잠그지 않으므로 잠금 순서 교착이 없다(10.7 R52). 처음 판은 한 트랜잭션에서 여러 행을 `FOR UPDATE` 로 다시 읽었다. `updated_at` 은 비교하지 않는다. 이력서 원문이 바뀌어도 경험의 `updated_at` 은 그대로일 수 있기 때문이다.
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
| `experience_index` | `kinds?[]`, `ids?[]`(10개 이하. 주면 상세 모드), `cursor?` | 응답은 늘 `{counts, complete, importHint?, experiences[], unlinkedRows[]}` 모양이다. 3단계 최소판도 같은 모양이다(아래, 10.7 R44). **맨 앞**: `counts`(종류별 경험 수, AI 기록 수, 미등록 행 수, 출처 미상 자소서 수)와 `complete`. 미등록 행이 있으면 `importHint`(웹 가져오기 화면 안내). 경험 한 줄: `{id, kind, title, period, revision, reviewed, skills[], domains[], inResumes[{id,name}], usedInSelfIntros}`. 그다음 **`unlinkedRows[]`**: 은행에 없는 행을 `(kind, title_key, 시작 연월 숫자)` 로 묶은 것(3단계 묶음 함수). 정렬은 PROJECT → CAREER → ACTIVITY → TRAINING → OTHER, 한 쪽에 60개. 상세 모드는 `version`(다음 `experience_save` 의 `expectedVersion`), 사실·소재집·근거(인용·주변 문맥·위치·해시, 첨부는 메타와 웹 주소), 개정 번호와 검토 상태, 연결된 이력서 행의 개정 번호와 "재검토 필요" 표시, `skills`·`domains`, 이 경험을 작성 근거로 쓴 자소서 `{id, question, company · position, experienceRevision, trust}`. 사건·"원본 반영 검토"는 4c | × |
| `experience_search` | `positionId?`, `postingId?`, `posting?`(저장하지 않는 공고 다섯 칸, 4.1), `requirements?[]`(클라이언트가 공고에서 뽑은 요구, 4.2), `question?`, `q?`, `competencies?[]`, `kinds?[]`, `limit?=8 (≤20)` | `query`(직무, 회사, 업종, 요구 키와 칸, 문항 유형과 신뢰도, 이 직무용 직접 출처 자소서 `{id, question}`), `results[]`(5.4), `uncovered[]`, `coverageIncomplete`, `warnings[]`, `degraded[]` | × |
| `experience_save` | `{id?, expectedVersion?, kind, title, startYm, memo, facts?, dossier?, skills?[], domains?[]}`. null=유지. `facts`·`dossier` 는 6.5 모양이다. **기존 경험(`id` 있음)에 `facts`·`dossier` 를 보내면 `expectedVersion` 이 필수**다. 없으면 400 `VERSION_REQUIRED`, 다르면 409 `EXPERIENCE_STALE`(현재 버전은 싣지 않는다, T2 와 같은 이유). `changeKind` 입력은 없다(10.7 R39). 서버가 `facts` 배열 항목의 안정 `id` 로 ADD·CORRECT 를 판정한다(6.5). `facts` 내용 해시가 직전 개정과 같으면 개정을 만들지 않는다. 근거에 인용이 없거나 로컬 경로뿐이면 400. 기술·도메인은 이름으로 받아 `KeyNormalizer` 로 정규화한다. 사건 필드(`episodes`, `removeEpisodeIds`)는 4c | `{id, version, revision, changeKind: INIT\|ADD\|CORRECT\|NONE, needsReview[](정정으로 재검토가 필요해진 이력서·자소서), normalizedSkills[], warnings[]}` | ○ (출처 AI) |
| `resume_add_experiences` | `id`, `items[{experienceId}]`, `expectedVersion?`(행 추가라 선택) | 경험마다 행을 만들어 종류에 맞는 섹션 끝에 붙인다. 경험 원본의 사실로 중립 문구를 만들고(`FACTS`), 행에 `experienceId` 와 지금의 `experienceRevision` 을 쓴다. 기존 기업용 행에서 복사하는 `ROW` 는 4c 다(6.10). 전부 들어가거나 전부 실패한다. 이 전제는 섹션 상한 검증(T6, 4단계)이다. 이미 있는 경험은 건너뛴다. 반환은 outline 모양과 새 `version` | ○ |
| `writing_guide_get` | 없음 | `{version, body, updatedAt}`. 경험별 주의사항은 `experience_index` 상세에 있다(6.7) | × |

**3단계 최소판** (경험 테이블 전, 10.7 R44): `experience_index` 는 4단계와 같은 모양 `{counts, complete, experiences: [], unlinkedRows: groups}` 를 준다. `experiences` 는 늘 빈 배열이고, `unlinkedRows` 는 계정의 모든 이력서 경험 행을 `(kind, title_key, 시작 연월 숫자)` 로 묶은 `[{kind, title, period, sources[{resumeId, resumeName, section, index, resumeVersion}]}]` 다. `counts` 는 3단계에서 셀 수 있는 것(미등록 행 수, 묶음 수, 출처 미상 자소서 수)만 싣는다. 그래서 4단계에서 SKILL 이 읽는 형식이 바뀌지 않는다(10.4 R29 를 목차에도 적용). 묶음 함수와 `KeyNormalizer` 는 3단계 산출물이고, 가져오기 계획(6.3)과 `missing[]`(5.3)이 같은 함수를 쓴다. AI가 새 이력서를 만들 때 이 목차와 대조하게 해서 "프로젝트 누락"을 먼저 막는다. `experience_search` 첫 판은 이 묶음을 RESUME_ROW 후보로 쓴다.

웹 전용으로 두는 것 (사람이 검토해야 하는 작업):
- 경험 가져오기(병합은 4c)
- 경험 원본 검토 확정, 제출본 확인, 작성 기준 수정
- 경험 삭제와 "다른 경험에 연결"(중복 처리, 6.4)
- AI 기록 확정과 삭제
- 키워드 별칭 편집(4c `keywords` 뒤)

### 5.3 바뀌는 기존 도구 (모두 `McpActions` 래퍼)

| 도구 | 변경 |
|---|---|
| `resume_get` | `view?: full\|outline` 을 둔다(3단계). outline은 `version`, 섹션별 `count`, 행 머리 `{index, experienceId, title, period}`(`experienceId` 는 4단계부터 값이 찬다), `missing[]`, `unlinkedRows[]` 다. **full이어도 응답 머리에 `missing` 요약을 붙인다**(3단계부터) |
| `missing[]` 정의 | **3단계에 당겼다**(10.7 R43). 항목 모양은 3·4단계가 같다: `{kind, title, period, experienceId, maybeUnlinked, sources[{resumeId, resumeName, section, index}]}`. ① 3단계: **다른 이력서에만 있는 미연결 행 묶음** 중 이 이력서에 같은 묶음 키 행이 없는 것. 묶음 키는 `experience_index` 의 `unlinkedRows` 와 같은 `(kind, title_key, 시작 연월 숫자)` 다. `experienceId` 는 null 이다. ② 4단계: 같은 형식에 **계정의 은행 경험 중 이 이력서에 없는 것**을 더한다(`experienceId` 가 찬다, `sources` 는 그 경험을 쓰는 다른 이력서 행). 이 이력서의 미연결 행과 제목 키가 맞을 가능성이 있으면 `maybeUnlinked:true` 다 |
| `resume_create` | 응답에 `missing` 요약을 붙인다(3단계부터). AI가 생성 때 일부만 고른 경우도 응답 기본값으로 드러난다 |
| `resume_update` | content·positionIds·빠진 섹션이 null이면 유지한다(1단계). `expectedVersion` 은 **필수**다(문서 전체 교체, 1단계). **`LINKED_ROWS_MISSING` 가드**(4단계): 저장 전 문서의 `experienceId` 중 새 문서에서 사라진 것은 `removedExperienceIds` 에 모두 적혀 있어야 하고, 아니면 409다. 래퍼에 있으므로 웹에는 걸리지 않는다. 설명은 "섹션 일부 편집은 `resume_row_*`, 경험 행 추가는 `resume_add_experiences`"로 바꾼다 |
| `resume_row_*` | `FOR UPDATE` 로 직렬화한다. `update`·`delete` 는 `expectedVersion` **필수**, `add` 는 선택이다. 다르면 409 `RESUME_STALE`. update는 병합한다(`experienceId` 보존). 반환은 개요와 새 `version` 이다. 모두 1단계. **4단계**: `update`·`delete` 는 행 번호 `index` 대신 `experienceId` 로도 대상을 지목한다(둘 중 하나, 10.7 R50). 행 번호가 밀려도 같은 경험 행을 찾는다. 행 요청(`ResumeRowRequest`)에는 `experienceId` 만 있고 `experienceRevision` 은 없다(6.2) |
| `self_intro_list` | 필터 `positionId?`·`postingId?`·`companyId?`·`questionType?`·`unlinked?` 와 `limit?=10` 을 둔다. 반환은 `answerPreview`(300자), `score`, `matchedTerms`, 출처 `{direct:[{position, company, trust}], indirect:[{resume, position, company}]}`, `questionTypes`, `episodes[{id,title,experienceId,experienceRevision}]`, `alreadyUsedFor[]` 다. **직접 출처가 0건이면** 빈 배열 대신 `{fallback:"indirect", results:[…각 항목 "출처 미상"]}` 을 준다. `q` 의 100자 제한을 스키마에 드러내고, 넘으면 400을 준다. 원문은 `self_intro_get` 으로 읽는다. 웹 REST 목록은 그대로 둔다. **적용함(2단계)**, 구현에서 정한 것: ① 응답은 늘 객체 `{fallback, total, complete, results}` 다(직접 출처가 있으면 `fallback:null`). 배열과 객체를 오가면 클라이언트가 두 모양을 다뤄야 한다 ② `nextCursor` 는 두지 않고 `total`·`complete` 와 `limit` 상한 50을 둔다. 계정당 문항 수백 개 규모에서 커서는 이르다 ③ 직무·공고·기업 필터는 USER·AI 직접 출처를 모두 맞추고 항목마다 `trust` 를 단다. 3절 규칙 4의 "하드 필터는 USER만"은 `experience_search` 의 신호 계산에 적용한다. 목록은 점수를 매기지 않고 출처를 보여 주는 도구라, AI가 방금 남긴 연결을 찾지 못하게 하는 쪽이 더 해롭다고 봤다 ④ fallback 은 직접 출처가 **없는** 답변만 준다. 다른 직무용으로 쓴 것이 분명한 답변을 "출처 미상"으로 섞으면 타사 혼입이 생긴다 ⑤ `episodes` 는 4단계 ⑥ 웹 REST 목록·상세는 답변 전문을 유지하고, 편집기용 `positionLinks`·`questionTypes` 만 덧붙였다. 이력서 목록에는 "어느 지원용?" 후보 계산용 `positionIds` 를 덧붙였다 ⑦ (2단계 리뷰) `questionTypes` 는 4.1 순서대로 USER 태그 → 정규식이고 AI 태그는 덧붙기만 한다. 처음 구현은 AI 태그 하나가 정규식 FIT 추정을 가려 `otherCompanySpecific` 을 껐다 ⑧ (2단계 리뷰) 연결된 직무·이력서의 이름만 projection 으로 읽는다. 처음 구현은 요청마다 계정 직무 전체(`PositionService.findAll`)와 이력서 엔티티(content JSONB)를 읽어 P4 를 MCP 경로에 퍼뜨렸다. SQL 상한 실측 상세 6·목록 6·필터 7을 테스트가 단언한다(1.6) |
| `self_intro_create` / `_update` | `positionIds?`, `episodes?[{id, experienceId, experienceRevision}]`, `questionTypes?`, `writingContext?`(6.7) 를 추가한다. `episodes` 를 보내면 항목의 세 키가 모두 필수다. 경험 id를 사건의 현재 소속에서 추론하거나 병합 target으로 치환하지 않는다. 소유자, 개정 존재, 사건과 경험의 정체성 검사는 6.8을 따른다. 이 필드는 REST에도 생기지만 선택이고 null=유지라 웹이 새 필드를 보내지 않는 기존 경로에는 영향이 없다. 규칙: 생성 시 "이력서 또는 직무 1개 이상", 수정 시 0개 허용. 지금은 생성과 수정이 같은 DTO의 `@Size(min = 1)` 을 공유하므로(`B/selfintro/dto/SelfIntroductionRequest.java:12`) 생성용과 수정용 요청을 분리한다. 빈 목록에서 `ids.get(0)` 로 `IndexOutOfBoundsException` 을 던지는 `requireOwnedResumes`(`SelfIntroductionService.java:118-123`)도 함께 고친다. **적용함(2단계)**: `positionIds`(10개 이하)·`questionTypes`(3개 이하), `SelfIntroductionCreateRequest`(`@AssertTrue` 로 이력서 또는 직무 1개 이상)·`SelfIntroductionUpdateRequest`(0개 허용) 분리, `requireOwned` 는 빈 목록이면 조회하지 않고 없는 id 로 404. 수정에서 남는 연결은 출처를 유지하고 새 연결만 요청 주체의 출처를 단다. 확정은 PUT 인자가 아니라 웹 전용 `POST /api/self-introductions/{id}/confirm` 이다(MCP 미등록, `docs/decisions.md`). `episodes`·`writingContext` 는 4단계·제출 트랙. **(2단계 리뷰) MCP 수정은 `positionIds`·`questionTypes` 를 기존 연결에 더하기만 한다** — 5.2 의 "AI 기록 확정과 삭제는 웹 전용"을 따른다. 처음 구현은 웹과 같이 "목록으로 맞춤"이라 AI 가 확정(USER) 연결을 지워 타사 혼입 방어가 풀렸다. 더한 결과가 상한을 넘으면 400 `TOO_MANY_LINKS`, 빼기는 웹 저장(✗)에서만. **(2단계 보정) 동시 수정 보호**: `V24__self_intro_version`, 수정·확정은 행 잠금(409 `SELF_INTRO_BUSY`) 뒤 `request.expectedVersion` 확인 — 웹 PUT 은 필수, MCP 는 `resumeIds`·`question`·`answer` 를 바꿀 때 필수(연결만 더하면 선택), 없으면 400 `VERSION_REQUIRED`, 다르면 409 `SELF_INTRO_STALE`. 쓰기 응답과 `self_intro_list`·`get` 항목에 `version`(docs/self-introductions.md 불변 조건 12) |
| `posting_update` | `positions`·`steps` 가 null이면 유지한다. id 없는 직무는 이름으로 기존 직무와 맞춘다(1단계). **적용함(1c)**: 래퍼가 아니라 `JobPostingService` 에 두어 REST PUT 도 같다. 웹 폼은 항상 배열을 보내 동작이 그대로이고, 웹에서 지웠다 같은 이름으로 다시 넣은 직무도 행을 유지해야 연결이 끊기지 않기 때문이다 |
| `self_intro_get` | **4단계**: 작성 근거를 `experiences[{experienceId, experienceRevision, title, trust}]` 로 돌려준다. 저장된 쌍을 그대로 반환하고 다시 계산하지 않는다(6.8). 사건 단위 `episodes[{id,title,experienceId,experienceRevision}]` 는 4c. **2단계**: `McpActions.getSelfIntro` 래퍼로 목록과 같은 항목 모양(출처·trust·문항 유형·`alreadyUsedFor`)에 답변 전문을 준다 |
| `posting_set_status` | `SUBMITTED` 로 바뀌면 그 공고의 기록 당시 연결 자료로 제출 기록(LINKED)을 남기고 응답에 `submission{id, level}` 을 싣는다. 같은 요청을 다시 보내면 같은 기록을 돌려준다(6.6). 제출본 확인은 웹에서 한다. **LINKED 기록은 이 MCP 래퍼가 아니라 `JobPostingService` 의 상태 전이 한 곳에서 남긴다**(10.7 R53). `SUBMITTED` 로 가는 경로가 웹 드래그(`changeStatus`), 공고 수정, 공고 생성 셋이기 때문이다. 스냅샷은 T9 잠금 아래에서 읽는다 |
| 리소스 설명 | 스킬 설명(`SKILL.md:3`)과 `resources/list` 설명(`McpController.java:54-55`)에 자기소개와 경험을 넣는다 |

**4단계 최소판에서 위 표를 읽는 법** (10.7 R45): `self_intro_list`·`self_intro_create`/`_update` 행의 `episodes[{id, experienceId, experienceRevision}]` 는 4단계에서 **`experiences[{experienceId, experienceRevision}]`** 로 바꿔 읽는다. 사건 id 가 없어 두 키가 필수다. 계약은 6.8 이다(소유자·개정 존재 검사, 받은 쌍을 그대로 저장·조회). MCP 수정은 2단계 `positionIds` 와 같이 기존 연결에 더하기만 한다. 이미 연결된 경험은 건너뛰고, 빼기와 개정 바꾸기는 웹 저장에서만 한다. 사건 단위 `episodes` 와 "사건과 경험의 정체성 검사"는 4c(6.10)다.

### 5.4 응답 예시 (`experience_search`)

6단계까지 켜고 4c(사건)까지 연 모습이다. 4c 전에는 `episodes[]`·`via`·`competencies` 가 없고, 결과는 경험과 RESUME_ROW 뿐이며 형식은 같다. `trust` 는 관계를 누가 확인했나이고, 원문이 어디 있는지는 `source` 다.

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
| **AI가 새 이력서를 만들 때 처음부터 일부만 고름** | `resume_create`·`resume_get` 응답 머리의 `missing` 요약(3단계부터). 3단계는 다른 이력서에만 있는 미연결 행 묶음, 4단계는 같은 형식에 은행 경험을 더한다. 대조가 AI의 선택이 아니라 응답 기본값이 된다. 3단계 `experience_index` 최소판도 같은 대조를 준다 |
| 응답이 커서 뒤쪽 `projects` 가 잘림 | 쓰기 응답은 개요다. 목차는 PROJECT를 맨 앞에 두고 `counts`·`complete` 도 맨 앞에 둔다 |
| AI가 행을 다시 타이핑하다 필드를 틀림 | `resume_add_experiences`: id만 넘기면 서버가 경험 원본에서 행을 만든다(고른 기업용 행에서 복사는 4c) |
| 웹 저장·AI 수정에서 행 앵커가 빠져 연결이 끊김 | `fillRow` 앵커 통과, 저장된 앵커 이어받기, 클라이언트가 개정을 바꾸지 못함(6.2, 4단계) |

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

   규칙: 행 도구는 병렬로 부르지 않는다. 직전 응답의 `version` 을 다음 호출의 `expectedVersion` 으로 넘긴다. **409를 받으면 같은 행 번호로 다시 보내지 않는다.** 이력서를 다시 읽고, 고칠 행을 제목·기간으로 다시 찾은 뒤 보낸다. 찾지 못하면 사용자에게 묻는다. 4단계부터는 경험 행을 `experienceId` 로 지목한다. 행을 고쳐 보낼 때 `experienceId`·`experienceRevision` 을 지우거나 바꾸지 않는다.
2. **지원 맞춤 경험 찾기**
   1. 등록된 직무면 `positionId`, 아니면 공고 본문을 다섯 칸으로 나눠 `posting` 으로 넘겨 `experience_search` 를 부른다. **고르기 전에는 공고를 저장하지 않는다.** 공고 여러 개를 추릴 때도 같다.
   2. 후보를 `why` 와 원문 출처(`source`)와 함께 보여 준다. `why` 에 DENSE만 있으면 "의미상 비슷할 뿐"이라고 밝힌다. `uncovered` 를 알리되, `coverageIncomplete` 면 결핍을 단정하지 않는다. `trust: 확정` 은 "사용자가 연결을 확인함"이지 성과 검증이 아니라고 구분해 말한다.
   3. 사용자가 지원할 공고와 경험을 고른다. 고른 공고만 `posting_create` 로 저장한다.
   4. `writing_guide_get` 과 후보의 주의사항(`cautions`)을 읽고 작성한다. "재검토 필요"·"AI 기록" 표시가 있는 사실은 사용자에게 확인받는다.
   5. `self_intro_create(positionIds, experiences, questionTypes, writingContext)` 로 저장한다. `experiences` 는 쓴 경험과 읽은 개정의 쌍이다(4c 뒤에는 사건 단위 `episodes`). 이 연결은 "AI 기록"이고 웹에서 확정해야 한다고 사용자에게 알린다.
3. **새 경험 등록과 경험 정리** (중앙 관리, 6.5)
   1. 사용자가 준 설명·자료를 읽고 `experience_index` 로 기존 경험과 겹치는지 먼저 찾는다. 자소서 답변은 "어느 경험을 썼나"를 찾는 데만 쓰고 사실 근거로 쓰지 않는다.
   2. 역할·행동·결과·측정 범위를 근거(인용·주변 문맥·위치)와 함께 정리하고, 확인하지 못한 것과 자료끼리 어긋나는 것을 따로 적는다. 로컬 파일은 경로가 아니라 필요한 발췌를 근거로 넣는다.
   3. 사용자가 요청하고 검토한 범위만 저장한다. 겹치는 경험이 있으면 새 경험이 아니라 그 경험에 저장해 새 개정을 만든다. `experience_index` 상세의 `version` 을 `expectedVersion` 으로 넘기고, `facts` 배열 항목의 기존 `id` 는 그대로 둔다. 고친 항목은 같은 `id` 로, 새 항목은 `id` 없이 보낸다. ADD·CORRECT 는 서버가 판정해 응답 `changeKind` 로 준다. 저장된 내용은 "AI 기록"이고 사용자가 웹에서 검토해 확정한다.
   4. 같은 경험이 둘로 등록된 것을 찾으면 합치지 않는다(병합은 4c). 웹에서 행을 남길 경험에 "다른 경험에 연결"로 다시 잇고, 쓰지 않게 된 경험을 지우라고 안내한다.
   5. JobSight가 검색 자료를 갱신한다(4.4). 이후 어느 환경에서도 같은 경험 id와 근거를 조회한다.
4. 수정 규칙은 5.1의 도구별 규칙으로 바꿔 쓴다.

---

## 6. 정본 관계와 기존 데이터 이전

### 6.1 정본 규칙

| 정보 | 정본 |
|---|---|
| 경험의 존재와 정체 | `experiences.id` (행에는 `experienceId` 앵커) |
| **경험 사실** (기간, 개인 역할, 행동, 결과, 측정 범위, 근거) | **경험 원본** `experiences.facts`. 개정마다 `experience_revisions` 에 남는다 |
| 작성 재료 (상세 서술, 문항별 활용 포인트, 표현 예시, 주의) | 같은 레코드의 `experiences.dossier`. 어느 사실 개정에서 만들었는지(`dossier_based_on`) 남긴다 |
| 기업별 표현 | 이력서 행의 실제 문구 + `experienceId` + 쓴 `experienceRevision`. 원본이 바뀌어도 고쳐 쓰지 않는다 |
| 기술·도메인 키 | `experiences.skills`·`domains` (정규화 키). 사건·역량 태그와 계정별 키워드 테이블은 4c |
| 이력서 ↔ 경험 관계 | `resume_experiences` (JSON에서 파생, FK). JSON 이 정본이고 표는 `ResumeWriter` 가 같은 트랜잭션에서 맞춘다(6.2) |
| 답변을 어디에 냈고 무엇을 썼나 | `self_introduction_positions`(2단계), `self_introduction_experiences`(쓴 경험과 개정, 4단계) |
| 무엇을 제출했나 | `submissions` (확인 수준 포함, 6.6) |
| 개인 작성 기준 | `writing_guides` + 경험별 `dossier.cautions` (6.7) |

- **경험 원본이 정본이고 이력서 행은 표현이다** (10.4 R23). 새 회사에 지원할 때 과거 이력서에서 경험을 다시 뽑지 않는다. 원본을 먼저 읽고, 과거 지원서는 표현과 전개를 참고하는 자료로 쓴다.
- **원본이 바뀌어도 과거 문서는 그대로다** (R25). 이력서 행은 쓴 문구를 그대로 보존하고, 화면에서 최신 원본으로 다시 그리지 않는다.
- **참조는 (경험 id, 개정 번호) 쌍으로 고정한다** (10.5 R31). 이력서 행과 자소서 연결은 작성 당시의 쌍을 그대로 든다. 쌍은 FK 로 `experience_revisions` 를 가리키므로(2.5, 10.7 R38) 경험을 지우지 않는 한 언제나 개정 이력에서 찾는다. 병합(4c)이 들어와도 이 계약은 같다.
- **차이는 두 가지만 표시한다** (R25). 모든 문구 차이에 경고하면 사용자가 경고를 무시하게 된다.
  - 표현 축약·순서 변경: 정상적인 기업별 편집이다. 표시하지 않는다.
  - 원본 사실 정정(`CORRECT` 개정): 같은 경험에서 **참조한 개정보다 `seq` 가 큰 `CORRECT` 개정**이 있으면 "재검토 필요"다. 추가(`ADD`) 개정에는 붙이지 않는다. 비교는 `created_at` 이 아니라 `experience_revisions.seq` 로 한다. `created_at` 은 같은 트랜잭션의 두 개정에 같은 값이 들어가고 시계에 기대기 때문이다. 같은 경험 안에서는 개정 번호로도 되지만, 4c 병합 경로 검사(6.10)는 경험을 가로질러 비교해야 하므로 처음부터 `seq` 로 한다.
  - 행에만 있는 새 사실(원본에 없는 수치·기술 키)을 찾는 "원본 반영 검토" 휴리스틱은 4c 로 미뤘다(6.10). 누락률을 재지 못했고(9.3), 행동 서술의 새 사실은 이 방식으로 잡히지 않는다.
- 이 전환으로 대표 행, "가장 최근에 수정한 이력서" 대체 규칙, 보존본이 필요 없어졌다(10.4). 기업용 문구가 사라져도 사실은 원본에 남기 때문이다.
- **같은 키를 함께 추가해야 한다.** `experienceId`·`experienceRevision` 을 `ResumeContent` 의 `Activity`·`Experience`·`Project`·`Training` 과 `frontend/src/types/resume.ts` 에 넣는다. `ResumeRowRequest` 에는 **`experienceId` 만** 넣는다. 행 도구는 개정을 정하지 않는다(6.2, 10.7 R35). Jackson 3는 모르는 키를 조용히 버리므로, 한 곳이라도 빠지면 저장은 성공하고 값만 사라진다. PUT→GET 왕복 테스트로 막는다.
- **웹은 앵커를 그대로 돌려보내야 한다.** 지금 `fillRow`(`frontend/src/types/resume.ts:271-283`)는 `SECTIONS` 의 필드 키만 복사해서 다른 키를 버린다. 앵커 키 둘을 그대로 통과시키게 고치고, 프런트 테스트 "로드 → 다른 칸 수정 → 저장 payload 에 두 키가 그대로"로 고정한다.
- **배포 순서는 확장 → 사용이다.** 앵커 필드(백엔드 record 와 `fillRow` 통과)를 링커보다 **한 배포 먼저** 낸다. 링커가 먼저 나가면, 앵커를 모르는 웹이 저장할 때마다 링커가 쓴 키를 지운다.
- `docs/decisions.md:50` 의 "본문 JSON에 ID를 섞지 않는다"에 대한 예외다(D2).

### 6.2 저장할 때 자동 연결: 이력서 content 의 단일 관문

**`ResumeWriter`** (10.7 R40): 이력서 content 를 쓰는 곳은 이 관문 하나다. create, update, copy, `mutateRows`, `updateBasic`, 가져오기 적용, `resume_add_experiences` 가 모두 거친다. JSON 과 `resume_experiences` 를 두 곳에 쓰므로, 관문을 우회한 저장 하나가 둘을 어긋나게 한다. 그래서 `ResumeRepository.save*` 를 `ResumeWriter` 밖에서 부르면 실패하는 아키텍처 테스트(ArchUnit 등)를 둔다. 선행 조건은 T9 계정 잠금, T1 행 잠금, T2 version이고, 같은 트랜잭션 안에서 다음 순서로 돈다.

1. 섹션 상한 검증(T6).
2. 링커 `ResumeExperienceLinker.link(owner, before, after)` (아래).
3. 첨부 규칙 B(6.9). 첨부 삭제가 구현될 때 이 자리에 붙인다.
4. 저장과 `resume_experiences` 차이 반영, 그다음 `SET CONSTRAINTS ALL IMMEDIATE` 로 지연 FK 위반을 이 문장에서 낸다(2.3).

**링커**. `before` 는 저장된 문서다(생성이면 빈 문서, 복사면 원본 문서).

1. 4개 섹션에서 행을 모은다.
2. **앵커 이어받기** (10.7 R35).
   - `experienceId` 는 있고 `experienceRevision` 이 없는 행은 `before` 의 같은 `experienceId` 행에서 개정을 이어받는다. `before` 에 없던 새 연결일 때만 그 경험의 지금 개정을 쓴다. **최신 개정은 새로 연결되는 경험에만 쓴다.** 처음 판은 개정이 없으면 늘 지금 개정을 써서, 정정 전 문구가 최신 사실 근거처럼 바뀌고 "재검토 필요"가 사라졌다.
   - 두 키가 모두 빠진 행은 `before` 에 같은 `(kind, title_key, 시작 연월 숫자)` 행이 정확히 하나 있고 그 행에 앵커가 있으면 그 앵커를 이어붙인다. 앵커를 모르는 구 번들 웹과 앵커를 빠뜨린 AI 요청 대비다.
3. **개정 검사** (10.7 R35). 개정은 클라이언트가 바꾸지 못한다.
   - 요청 행이 보낸 `experienceRevision` 이 `before` 의 같은 경험 행 값과 다르면 400 `EXPERIENCE_REVISION_MISMATCH` 다.
   - `before` 에 없던 경험 행에 개정이 실려 오면(다른 이력서에서 붙여 넣은 행 등) 그 개정이 `experience_revisions` 에 있고 지금 개정 이하인지 검사한다. 없으면 404 `EXPERIENCE_REVISION_NOT_FOUND`, 지금 개정보다 크면 400 `EXPERIENCE_REVISION_MISMATCH`. 웹 "다른 경험에 연결"은 행의 `experienceId` 를 바꾸고 `experienceRevision` 을 비워 보낸다. 새 연결이라 지금 개정을 받는다.
   - **개정을 올리는 길은 둘뿐이다.** ① 웹 "원본으로 갱신": PUT 본문의 `refreshExperienceIds[]` 로 보내고, 서버가 그 경험 행의 개정을 지금 개정으로 바꾼다. 문구는 사용자가 편집기에서 고친다. 새 경로가 아니라 요청 필드라 경로 표는 그대로다. MCP `resume_update` 래퍼는 이 필드를 받지 않는다. ② `resume_add_experiences`: 새로 붙이는 행에만 지금 개정을 쓴다.
4. `experienceId` 가 있는 행은 세 가지를 더 검사한다.
   - 그 경험이 내 것인지 count로 확인한다. 없으면 404다.
   - 종류와 섹션이 맞는지 확인한다. 다르면 400 `EXPERIENCE_SECTION_MISMATCH` 다.
   - 한 이력서 안에서 같은 경험이 두 번 나오면 400 `EXPERIENCE_ROW_DUPLICATE` 다.
5. 키가 없는 행은 `(kind, title_key)` 로 찾는다. **정확히 1건이 맞고 시작 연월 숫자가 충돌하지 않을 때만** 연결하고 그 경험의 지금 개정을 쓴다. 그 밖에는 미연결로 둔다. 새 경험은 만들지 않는다.
6. `resume_experiences` 에는 행의 `experienceId` 를 `experience_id`·`based_on_experience_id` 둘 다에, 행의 개정을 `based_on_revision` 에 두고 차이만 반영한다(4c 병합 전에는 두 id 가 같다).
7. **행 문구로 경험 원본을 바꾸지 않는다.**
8. 이력서 저장은 경험의 검색 텍스트를 바꾸지 않으므로 경험 임베딩을 건드리지 않는다(4.4).
9. **웹 저장에서 이전에 있던 `experienceId` 가 사라지면** 응답에 `unlinkedWarnings[]` 를 싣는다. 웹에서 조용히 끊기는 것을 막기 위해서다. 웹은 이를 토스트로 보여 준다.

예상 비용은 SQL 4~6회이고 외부 호출은 없다.

**일관성 점검과 롤백 런북** (10.7 R40)
- 멱등 `rebuildResumeExperiences(owner)`: T9 잠금 아래에서 계정 이력서 JSON 의 앵커로 `resume_experiences` 를 지우고 다시 만든다. 여러 번 돌려도 같다. 운영 점검과 롤백 뒤 복구에 쓴다.
- JSON↔표 대조 질의. 결과가 0건이어야 한다. 운영 DB 에서는 계정별 건수만 본다(6.3 운영 데이터 원칙).

```sql
WITH j AS (
  SELECT r.owner_id, r.id AS resume_id, (e->>'experienceId')::uuid AS eid, (e->>'experienceRevision')::int AS rev
    FROM resumes r,
         LATERAL jsonb_array_elements(coalesce(r.content->'projects', '[]') || coalesce(r.content->'activities', '[]')
                                   || coalesce(r.content->'experiences', '[]') || coalesce(r.content->'trainings', '[]')) e
   WHERE e->>'experienceId' IS NOT NULL)
SELECT owner_id, count(*) AS mismatches FROM (
  (SELECT owner_id, resume_id, eid, rev FROM j
   EXCEPT SELECT owner_id, resume_id, based_on_experience_id, based_on_revision FROM resume_experiences)
  UNION ALL
  (SELECT owner_id, resume_id, based_on_experience_id, based_on_revision FROM resume_experiences
   EXCEPT SELECT owner_id, resume_id, eid, rev FROM j)
) d GROUP BY owner_id;
```

- 롤백: 링커가 없는 코드로 되돌린 동안의 저장은 표를 낡게 한다. 다시 올린 뒤 계정마다 `rebuildResumeExperiences` 를 한 번 돈다. 앵커 확장보다 앞선 코드로는 되돌리지 않는다(7절 4단계 되돌리기).

### 6.3 기존 데이터 가져오기 (웹 기능, 마이그레이션 SQL 아님)

같은 경험인지는 사람이 확인해야 한다. 또 마이그레이션은 데이터를 지우면 안 된다(`docs/decisions.md:98-99`).

1. **계획 (dry-run)**
   - 계정의 미연결 경험 행을 `(kind, title_key, 시작 연월 숫자)` 로 묶는다.
   - 묶음마다 구성 행 `[{resumeId, resumeName, section, index, period}]` 을 싣는다.
   - 제목 키가 서로 포함 관계이거나, 기간 숫자는 같은데 제목이 다르면 `mergeCandidates` 로 표시한다. 자동으로 합치지 않는다. 같은 경험이면 사용자가 한 묶음을 CREATE 하고 나머지를 그 경험에 `LINK_TO` 로 잇는다.
   - 묶음에 관련된 이력서의 `version` 을 계획에 싣는다.
   - 묶음 함수와 `KeyNormalizer` 는 3단계 산출물을 그대로 쓴다(5.2 3단계 최소판, `missing[]`).
2. **적용**
   - 묶음마다 `CREATE | SKIP | LINK_TO(experienceId)` 를 받는다.
   - T9 계정 잠금을 얻고, 계정의 이력서를 id 오름차순으로 잠근다(T1 과 같은 `for no key update`). 계획에 실린 `version` 과 다른 이력서가 있으면 409로 거절하고 계획을 다시 만들게 한다.
   - CREATE는 정체성(kind, title, start_ym), `skills`(`techStack` 을 나눠 `KeyNormalizer` 로 정규화), **경험 원본 초안**을 만든다. 초안의 사실은 구성 행들에서 모으고, 사실 항목마다 안정 `id` 와 근거(`RESUME_ROW` 인용·해시)를 단다. 행끼리 다른 사실(기간, 수치)은 `facts.conflicts[]` 에 적고 고르지 않는다. 초안은 검토 전이라 "AI 기록"이다. 개정 1(`INIT`)을 남기고, 구성 행 전부에 `experienceId` 와 `experienceRevision: 1` 을 쓰고, 행 텍스트는 바꾸지 않는다. 행은 `ResumeWriter` 로 쓴다.
   - `LINK_TO` 는 구성 행에 그 경험의 `experienceId` 와 지금 개정을 쓴다(새로 연결되는 경험이라 6.2-2 의 규칙과 같다).
   - 대량 insert 는 `Persistable` 엔티티로 한다(2.5).
   - 여러 번 돌려도 결과가 같다.
3. **중복 처리** (병합 대신, 사용자 결정 10.7 R45): 같은 경험이 둘로 등록됐으면 웹에서 행을 남길 경험에 "다른 경험에 연결"로 다시 잇고(이력서 저장, 6.2), 쓰지 않게 된 경험을 지운다(6.4). 자소서 작성 근거가 그 경험을 가리키면 먼저 웹 자소서 편집기에서 남길 경험으로 바꾸거나, 삭제 때 `detach:true` 로 끊는다. 병합 계보(`merged_into`)와 병합 절차는 4c 다(6.10).
4. **사건 나누기**는 4c 다. 4단계의 자소서 연결은 5.6 "경험 정리" 절차(AI 제안 → 사용자 승인)로 경험 단위로 한다.

**운영 데이터 검증 원칙**:
- 운영 DB 사본에서는 내용 없이 집계만 비교하는 SQL만 돌린다. 계정별로 `경험 섹션 행 수 = 묶음 구성 행 수 + 미연결 행 수` 가 맞는지 본다.
- 사람이 계획을 직접 보는 검토는 본인 계정이나 합성 데이터에서만 한다.
- 근거: `docs/decisions.md:171-179`(관리자는 집계 숫자만, 업무 데이터 내용은 열지 않는다).

### 6.4 삭제와 복제

- **이력서 복제**: T9 잠금 아래에서 원본을 잠그고 읽어 `experienceId` 와 `experienceRevision` 을 그대로 복사한다. 링커의 `before` 는 원본 문서다(6.2).
- **이력서 삭제**: T9 잠금 아래에서 지운다. `resume_experiences` 가 CASCADE로 지워진다. 경험 원본과 개정 이력, 자소서, 제출 기록은 남는다. 기업용 문구가 사라져도 사실은 원본에 있으므로 따로 보존할 것이 없다.
- **경험 삭제**: 웹 전용이다. T9 잠금 아래에서 다음 순서로 한다.
  1. 이력서가 쓰고 있으면 409 `EXPERIENCE_IN_USE` 와 이력서 이름 목록을 돌려준다. 이력서 행은 사용자가 지우거나 다른 경험에 연결해야 한다.
  2. 자소서 작성 근거가 가리키면 `detach:true` 없이는 409 `EXPERIENCE_IN_USE`(자소서 목록 포함), `detach:true` 면 근거 행을 먼저 지우고 응답에 끊긴 자소서 수를 싣는다.
  3. 지연 제약 셋을 `IMMEDIATE` 로 당기고 지운 뒤 flush 한다(2.3). 잠금을 거치지 않은 경로가 그사이 연결해도 지연 FK 가 막는다.
- **중복 경험 처리**: 병합은 4c 다. 행을 남길 경험에 다시 연결하고 쓰지 않는 경험을 위 순서로 지운다(6.3-3).
- **직무 삭제**: `self_introduction_positions` 가 CASCADE로 지워진다. 공고 수정이 직무를 지웠다 다시 만드는 경로는 1단계에서 먼저 막는다(T3).
- **계정 삭제**: 지연 FK 셋이 있어도 통과해야 한다. 임시 테이블로는 확인했고(2.3), 실제 스키마는 4단계 첫 마이그레이션의 테스트에 넣는다.

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

- `facts` 는 JSON 객체여야 한다(DB CHECK, 2.5). **배열 칸의 항목은 안정 `id` 를 든다**(`actions[]`, `results[]`, `evidence[]`, `unverified[]`, `conflicts[]`). `id` 는 경험 안에서 유일한 문자열이고, `id` 없이 온 새 항목에는 서버가 붙인다. 클라이언트는 고친 항목을 같은 `id` 로 보낸다.
- `dossier_based_on` 이 `revision` 과 다르면 "소재집 갱신 필요"다. 같은 행에 있어도 사실을 고친 뒤 서술을 갱신하지 않으면 어긋나기 때문이다.
- **개정 판정** (10.7 R39). `experience_save`·웹 경험 편집이 같은 규칙을 쓴다. 클라이언트의 `changeKind` 입력은 없고, 판정 결과를 응답 `changeKind` 로 준다.
  1. 정규화한 `facts`(키 정렬 JSON)의 SHA-256 이 직전 개정의 `facts_hash` 와 같으면 개정을 만들지 않는다(`NONE`). 소재집·메모·`skills`·`domains` 만 바뀐 저장이 여기에 해당한다.
  2. 다르면 `revision` 을 올리고 `experience_revisions` 에 당시 사실·소재집을 남긴다. 판정은 사실 주장 칸(`period`, `role`, `measurement`, `actions[]`, `results[]`)을 직전 개정과 비교해서 한다.
     - 배열 칸: 직전 개정과 `id` 로 짝짓는다. 새 `id` 만 늘었으면 `ADD`, 기존 `id` 의 값이 바뀌거나 기존 `id` 가 사라지면 `CORRECT`.
     - 단일 값 칸: 비어 있던 값을 채우면 `ADD`, 있던 값을 바꾸거나 지우면 `CORRECT`.
     - 근거·미확인·충돌 칸(`evidence[]`, `unverified[]`, `conflicts[]`)만 바뀌었으면 `ADD` 다. 주장 자체는 그대로라 기업용 문구의 재검토가 필요 없다.
  3. 기존 경험에 `facts`·`dossier` 를 보내면 `expectedVersion` 이 필수다(5.2). 개정은 `@Version` 검사와 같은 트랜잭션에서 T9 잠금 아래에 만든다.
- `reviewed_revision` 이 `revision` 보다 낮으면 "AI 기록"이다. 사용자가 웹에서 검토하면 확정된다.
- 사건 STAR(900자 상한, 검색용 요약)는 4c 다. 4단계는 상세를 소재집에 둔다.
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
- `ATTACHMENT` 근거는 첨부를 가리킨다. 첨부 삭제(6.9)가 구현돼 있으면 이 단계에서 사용처 판정에 경험 사실과 개정 이력의 절을 더하고, 근거를 새로 적는 경험 쓰기에도 규칙 B 를 건다(6.9 체크리스트).

### 6.6 제출 기록 (제출·작성 기준 트랙)

상태 변경은 기록의 계기일 뿐 제출 사실의 증명이 아니다(10.4 R26). 지금 `changeStatus` 는 상태만 바꾼다(`B/posting/JobPostingService.java:97`). 과거 지원 건을 뒤늦게 등록하거나, 제출 뒤 고친 이력서가 연결돼 있을 수 있다.

| 확인 수준 | 언제 생기나 | 부르는 이름 |
|---|---|---|
| `LINKED` | 공고가 `SUBMITTED` 로 바뀔 때. 그 공고 직무에 연결된 이력서(`resume_positions`)와 written_for 자소서를 스냅샷으로 남긴다 | "기록 당시 연결 자료". 제출본이라 부르지 않는다 |
| `USER_CONFIRMED` | 사용자가 웹에서 실제로 낸 문서·이력서 버전·첨부를 골라 확인할 때 | "사용자가 확인한 제출본" |
| `RECEIPT_VERIFIED` | 접수 메일 발췌·접수 번호·첨부 같은 근거를 붙여 대조할 때. 자동 대조는 하지 않는다 | "접수 근거와 대조한 제출본" |

- **LINKED 기록은 `JobPostingService` 의 상태 전이 한 곳에서 남긴다**(10.7 R53). `SUBMITTED` 로 가는 경로가 웹 드래그(`changeStatus`), 공고 수정, 공고 생성 셋이라 MCP 래퍼에 두면 둘이 빠진다. 스냅샷은 T9 잠금 아래에서 읽는다. 이력서·자소서 저장과 엇갈리지 않게 하려는 것이다.
- 스냅샷 내용의 해시(`content_hash`)로 같은 요청의 재시도가 중복 기록을 만들지 않게 한다. 내용이 같으면 기존 기록을 돌려준다.
- 기록은 고치지 않고 쌓는다. 기록이 가리키는 첨부는 `submission_attachments`(2.5)로 정규화한다. 복합 FK 가 `ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED` 라 DB 가 그 첨부의 삭제를 막고, 첨부 삭제 판정(6.9)이 인덱스 질의가 된다. 스냅샷에 첨부를 적을 때도 규칙 B(6.9)를 건다.
- PAST_USE와 `alreadyUsedFor` 는 `USER_CONFIRMED` 이상이 있으면 그 스냅샷을 쓰고, 없으면 지금 연결을 쓰되 "제출본 미확인"을 표시한다.
- 확인 수준을 둘(`LINKED`·`USER_CONFIRMED`)로 줄일지는 열린 질문이다(9.2). 접수 근거 자동 대조를 하지 않으므로 `RECEIPT_VERIFIED` 는 사용자가 근거를 붙였다는 표시일 뿐이다.

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

### 6.8 자소서 작성 근거 계약 (4단계)

병합 경로의 정정 검사와 사건 단위 계약(이 절의 옛 내용)은 4c 로 옮겼다(6.10). 아래는 4단계 최소판의 계약이고, 이력서 행의 작성 근거(6.1·6.2)와 같은 규칙을 쓴다.

- **입력**: REST·MCP 자소서 생성·수정의 `experiences?[{experienceId, experienceRevision}]`(10개 이하). 각 항목의 두 키가 필수다. 빠진 키나 1 미만 개정은 `400 EXPERIENCE_REFERENCE_REQUIRED` 다.
  - REST(웹): 생략·null 은 기존 연결 유지, `[]` 는 비움, 목록이면 그 목록으로 맞춘다. 남는 연결은 출처를 유지하고 새 연결만 요청 주체의 출처를 단다(2단계 `positionIds` 와 같다).
  - MCP: 기존 연결에 더하기만 한다. 이미 연결된 경험은 건너뛴다. 빼기와 개정 바꾸기는 웹에서만 한다(2단계 리뷰의 "MCP 수정은 더하기만"과 같은 이유).
- **검사**: 경험이 내 것이어야 한다(아니면 404). `(experienceId, experienceRevision)` 개정이 실제로 있어야 한다(없으면 `404 EXPERIENCE_REVISION_NOT_FOUND`). DB 에서는 `self_introduction_experiences_revision_fk` 가 같은 것을 지킨다.
- **저장·조회**: 검사를 모두 통과한 뒤 받은 쌍을 그대로 저장하고, `self_intro_get`·`self_intro_list` 에서 그대로 돌려준다. 조회나 재저장 때 지금 개정으로 다시 계산하지 않는다. 다른 연결과 문항은 보존하며, 항목 하나라도 실패하면 요청 전체가 실패한다.
- **재검토**: 같은 경험에서 참조한 개정보다 `seq` 가 큰 `CORRECT` 개정이 있으면 "재검토 필요"를 붙인다(6.1).
- **잠금**: 쓰기는 T9 잠금 아래에서 하고, 커밋 전에 `SET CONSTRAINTS ALL IMMEDIATE` 로 지연 FK 위반을 당긴다(2.3).

### 6.9 첨부 파일 삭제 (설계 확정, 구현은 파이프라인 뒤)

2026-11-02 사용자 결정(10.7 R46). T7 로 화면의 즉시 삭제를 없앤 뒤로 첨부를 지울 길이 없다. 사용자가 지우는 길을 둘 둔다. 자동 정리 작업은 여전히 만들지 않는다(2.6).

| 길 | 언제 | 조건 |
|---|---|---|
| (1) 저장 뒤 묻기 | 이력서 저장·삭제가 **성공한 뒤**, 그 요청으로 빠진 첨부(저장 전 문서에는 있고 저장 뒤 문서에는 없는 id)가 있을 때 | **매번 묻는다**("이 파일도 지울까요?"). 답을 기억하지 않는다. 서버가 409 를 주면 "다른 곳에서 쓰고 있어 남겨 둔다"고 알린다 |
| (2) 첨부 서랍 | 이력서 목록의 [첨부 파일] 서랍 | **어디서도 쓰지 않고 올린 지 7일이 지난 파일만** 지울 수 있다. 7일은 다른 탭에서 올리고 아직 저장하지 않은 파일을 지우지 않으려는 여유다. 서버는 두 길을 구분하지 않으므로 7일 조건은 서랍 화면이 건다 |

**서버 규약**

1. **삭제** `DELETE /api/attachments/{id}`(기존 경로) 는 한 트랜잭션에서 다음을 한다.
   1. 첨부 행을 네이티브 `SELECT id FROM attachments WHERE id = :id AND owner_id = :o FOR UPDATE` 로 잡는다. 없으면 404. **JPA `PESSIMISTIC_WRITE` 는 쓰지 않는다.** PostgreSQL 에서 `for no key update` 라 아래 규칙 B 의 `for key share` 와 충돌하지 않고, 판정과 새 참조 쓰기가 엇갈린다(T9 잠금 규약 5).
   2. 사용처를 판정한다. 계정 이력서 content 의 텍스트 포함 검사다: `SELECT id, name FROM resumes WHERE owner_id = :o AND strpos(content::text, :id::text) > 0`. 앞으로 생기는 참조원은 `UNION ALL` 로 더한다(아래 체크리스트). 쓰는 곳이 있으면 409 `ATTACHMENT_IN_USE` 와 쓰는 곳 이름 목록이다.
   3. 없으면 지운다.
2. **규칙 B**: 첨부 id 를 새로 적는 쓰기는 그 첨부 행을 `for key share` 로 잡는다. 이력서 content 를 쓰는 한 지점(`ResumeWriter`, 6.2)에 둔다. 새 문서의 `ResumeContent.attachmentIds()` 중 저장 전 문서에 없던 id 를 모아 `SELECT id FROM attachments WHERE id = ANY(:ids) AND owner_id = :o FOR KEY SHARE` 로 잡고, 개수가 모자라면(내 파일이 아니거나 없음) 400 `ATTACHMENT_NOT_FOUND` 다. **이미 저장된 값은 검사하지 않고 통과한다.** 예전에 지워진 id 를 든 문서도 계속 저장된다.
   - 두 잠금이 충돌하므로 순서가 정해진다. 쓰기가 먼저면 삭제는 쓰기 커밋을 기다렸다가 새 content 를 보고 409 를 준다. 삭제가 먼저면 쓰기는 삭제 커밋 뒤 행을 찾지 못해 400 을 준다.
3. **`copy`** 는 원본을 잠그고 읽는다(`lockOwned`). 복사본의 첨부 id 는 모두 새로 적는 값이라 규칙 B 를 거친다.
4. **서랍 목록**: 같은 경로 `/api/attachments` 에 GET 메서드를 더해 `{id, filename, contentType, sizeBytes, createdAt, usedBy[{type, id, name}]}` 를 준다. 사용처는 위 판정과 같은 질의를 계정 단위로 한 번 돈다(계정당 첨부 A × 이력서 R 번의 텍스트 검사. 수백 건 규모에서 충분하다).
5. **바꾸지 않는 것**: 마이그레이션, 경로 표(`ApiPaths`·`routes.ts`·`nginx.conf`), MCP 도구. MCP 로는 첨부를 지우지 않는다.

**체크리스트: 참조원이 늘 때 판정에 절을 더한다**

| 참조원 | 언제 | 판정에 더할 절 | 쓰기 쪽 |
|---|---|---|---|
| 이력서 content | 지금 | 위 1-2 | 규칙 B(`ResumeWriter`) |
| 경험 근거 `facts.evidence[]` 의 `ATTACHMENT` | 4단계가 들어와 있으면 | `UNION ALL SELECT id, title FROM experiences WHERE owner_id = :o AND strpos(facts::text, :id::text) > 0` 과, 과거 개정이 가리키는 것도 지키려면 `experience_revisions.facts` 에 같은 절 | `experience_save`·웹 경험 편집에 규칙 B |
| 제출 기록 `submission_attachments` | 제출 트랙이 들어와 있으면 | `UNION ALL SELECT … FROM submission_attachments WHERE attachment_id = :id`(인덱스 질의). FK 가 삭제를 막는 것은 방어선이다 | LINKED·사용자 확인 스냅샷에 규칙 B |

검증(구현할 때): 복제본이 쓰는 첨부를 원본 저장 뒤 묻기로 지우면 409 이고 파일이 남는다. 쓰기와 삭제를 동시에 보내면 둘 중 하나만 성공한다(Testcontainers, 두 트랜잭션). 7일이 안 된 미사용 파일은 서랍에 삭제 버튼이 없다. 남의 첨부 id 를 새로 적으면 400 이고, 이미 저장돼 있던 지워진 id 는 저장을 막지 않는다.

### 6.10 4c (보류): 사건·병합 계보·키워드 — 필요가 확인될 때

2026-11-02 사용자 결정으로 4단계를 최소판으로 줄이면서 아래를 미뤘다(10.7 R45). 지우지 않고 여기 모아 둔다. 다시 열 때는 이 절의 조건부터 지킨다.

| 미룬 것 | 4단계 최소판의 대신 | 다시 열 신호 |
|---|---|---|
| 사건(STAR) `episodes`, `episode_competencies`, `self_introduction_episodes`, `EPISODE_*` 오류 코드 | 자소서 작성 근거는 `self_introduction_experiences` 의 `(경험 id, 개정)` 쌍. 상세 서술은 `dossier` | 골드셋에서 경험 단위 후보가 너무 굵어 문항 맞춤이 떨어지는 사례 |
| 병합 계보 `merged_into`, 병합 절차(옛 6.3-3), 병합 경로 정정 검사(옛 6.8), `MERGE_CONFLICT`·`MERGE_CYCLE` | 행을 다른 경험에 다시 연결 + 쓰지 않는 경험 삭제(6.3-3, 6.4) | 중복 경험이 잦고, 지우면 자소서 작성 근거를 잃는 경우가 실제로 생길 때 |
| `keywords`·`experience_keywords` | `experiences.skills`·`domains TEXT[]` (`KeyNormalizer`). 키별 출처 대신 경험의 검토 상태 | 계정별 별칭 편집이나 키별 출처가 필요할 때 |
| `resume_add_experiences` 의 `ROW` 복사 | `FACTS` 만 | 기업용 문구를 다시 쓰자는 요구가 확인될 때 |
| "원본 반영 검토" 휴리스틱 | 표시 없음. "재검토 필요"(정정)만 | 웹에서 이력서만 고쳐 원본이 낡는 사례가 확인될 때 |

**병합을 넣을 때 지킬 조건** (리뷰 P1, 10.7 R37). 병합된 source 가 링커·검색·스위퍼에 새면 같은 제목 키가 2건이 되어, 링커의 "정확히 1건" 조건(6.2-5)이 영구히 실패하고 그 행은 계속 미연결로 남는다.
- `experiences_owner_key_idx`·`experiences_embedding_dirty_idx` 를 `WHERE merged_into IS NULL` 부분 인덱스로 바꾼다.
- 모든 경험 조회(링커의 제목 키 찾기, 검색, 스위퍼, `missing[]`, `experience_index`)에 `AND merged_into IS NULL` 을 넣는다.
- 병합 때 source 의 조각(`experience_chunks`)을 지우고 `embedding_dirty` 를 끈다.
- 키워드 연결 이동은 USER 를 우선하는 `ON CONFLICT (experience_id, keyword_id) DO UPDATE SET source = CASE WHEN experience_keywords.source = 'USER' OR EXCLUDED.source = 'USER' THEN 'USER' ELSE 'AI' END` 로 한다. 옛 판의 `DO NOTHING` 은 target 의 AI 연결이 source 의 USER 확정을 지웠다.
- `merged_into` 참조 쪽 인덱스 `CREATE INDEX experiences_merged_into_idx ON experiences (merged_into) WHERE merged_into IS NOT NULL` 을 둔다.
- 병합 target(다른 경험이 `merged_into` 로 가리키는 경험)의 삭제는 409 다.
- 잠금은 T9 순서(계정 → 이력서 → 경험)를 따르고 경험을 먼저 잠그지 않는다. 경험 행은 `FOR NO KEY UPDATE` 로 잡는다.
- "재검토 필요"는 아래 병합 경로 전체에서 `seq` 로 비교한다.

**DDL 스케치** (옛 4단계 DDL. 4단계 최소판 위에 더하는 모양으로 고쳤다)

```sql
-- V{n}__experience_episodes_merge.sql  (4c, 보류)
ALTER TABLE experiences ADD COLUMN merged_into UUID;   -- 병합된 source 면 target id. 지우지 않고 남겨 과거 (경험 id, 개정) 참조를 보존한다
ALTER TABLE experiences ADD CONSTRAINT experiences_merged_into_fk
    FOREIGN KEY (merged_into, owner_id) REFERENCES experiences (id, owner_id) DEFERRABLE INITIALLY DEFERRED;
-- 위 조건: owner_key·dirty 인덱스를 WHERE merged_into IS NULL 로 다시 만들고, merged_into 참조 쪽 인덱스를 둔다
ALTER TABLE experience_revisions ADD COLUMN episodes JSONB NOT NULL DEFAULT '[]';   -- 당시의 사건
-- experience_revisions.change_kind CHECK 에 'MERGE' 를 더한다

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
    embedding       REAL[],                          -- D11 결정을 따른다
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

CREATE TABLE episode_competencies (
    owner_id        UUID        NOT NULL,
    episode_id      UUID        NOT NULL,
    competency_code VARCHAR(40) NOT NULL REFERENCES competencies (code),
    source          VARCHAR(4)  NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (episode_id, competency_code),
    FOREIGN KEY (episode_id, owner_id) REFERENCES episodes (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX episode_competencies_owner_code_idx ON episode_competencies (owner_id, competency_code);

CREATE TABLE keywords (
    id       UUID PRIMARY KEY,
    owner_id UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    kind     VARCHAR(6)   NOT NULL CHECK (kind IN ('SKILL','DOMAIN')),
    name     VARCHAR(100) NOT NULL,
    norm_key VARCHAR(100) NOT NULL,                 -- KeyNormalizer + 별칭 시드 사전 적용 결과(3단계 산출물)
    UNIQUE (owner_id, kind, norm_key),
    UNIQUE (id, owner_id)
);

CREATE TABLE experience_keywords (                  -- 옮길 때 experiences.skills·domains 에서 채운다
    owner_id      UUID       NOT NULL,
    experience_id UUID       NOT NULL,
    keyword_id    UUID       NOT NULL,
    source        VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (experience_id, keyword_id),
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (keyword_id, owner_id)    REFERENCES keywords (id, owner_id)    ON DELETE CASCADE
);
CREATE INDEX experience_keywords_keyword_idx ON experience_keywords (keyword_id);

CREATE TABLE self_introduction_episodes (           -- uses, 사건 단위
    owner_id             UUID       NOT NULL,
    self_introduction_id UUID       NOT NULL,
    episode_id           UUID       NOT NULL,
    experience_id        UUID       NOT NULL,   -- 답변을 쓸 때 사건이 속했던 경험. 병합으로 사건이 옮겨가도 바꾸지 않는다
    experience_revision  INT        NOT NULL CHECK (experience_revision >= 1),
    source               VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, episode_id),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE,
    CONSTRAINT self_introduction_episodes_revision_fk
      FOREIGN KEY (experience_id, experience_revision, owner_id) REFERENCES experience_revisions (experience_id, revision, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT self_introduction_episodes_episode_fk   -- 2.3 과 같은 이유로 지연 NO ACTION
      FOREIGN KEY (episode_id, owner_id) REFERENCES episodes (id, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX self_introduction_episodes_episode_idx ON self_introduction_episodes (episode_id);
```

**삭제 규칙** (옛 2.3)

| 관계 | 카디널리티 | 삭제 시 |
|---|---|---|
| 경험 → 사건 | 1:N (0개 허용) | 경험을 지우면 사건도 CASCADE |
| 경험 ↔ 키워드 | N:M | 양쪽 CASCADE |
| 사건 ↔ 역량 | N:M, 사건당 3개 이하 | CASCADE |
| 자소서 ↔ 사건 | N:M (10 이하) | 자소서 쪽 CASCADE. 사건 쪽은 지연 `NO ACTION`. 연결된 사건(그 사건을 가진 경험 포함)을 지우려 하면 `detach:true` 없이는 409 `EPISODE_IN_USE`, `detach:true` 면 연결 행을 먼저 지운다. 경험 삭제는 사건을 연쇄로 지우므로 `self_introduction_episodes_episode_fk` 도 `IMMEDIATE` 로 당긴다 |
| 병합된 경험 | — | 지우지 않는다. 과거 참조가 가리키기 때문이다. 병합 target 의 삭제는 409 |

**병합 절차** (옛 6.3-3). 먼저 충돌을 검사하고, 한 트랜잭션에서 처리한다.
- **충돌 검사** (10.2 R5): source와 target이 같은 이력서에 함께 있으면, 병합 뒤 그 이력서에 같은 경험 행이 둘이 되어 6.2의 중복 금지와 충돌한다. 계획에서 그 이력서와 두 행을 보여 주고, 사용자가 한 행을 지우거나 다른 경험에 연결할 때까지 409 `MERGE_CONFLICT` 로 거절한다. 자동으로 행을 지우거나 서술을 덮어쓰지 않는다.
1. 사건의 `experience_id` 를 target으로 바꾼다. 사건을 참조하는 FK는 `(id, owner_id)` 뿐이라 막히지 않는다.
2. 키워드 연결을 위 조건의 `ON CONFLICT … DO UPDATE` 로 옮긴다.
3. **이력서 JSON은 고쳐 쓰지 않는다.** source를 쓰는 이력서들의 `resume_experiences.experience_id` 만 target으로 바꾼다(`based_on_*` 은 그대로). 링커는 행의 `experienceId` 가 병합된 경험이면 `merged_into` 를 따라 target 과 연결하되 행의 앵커는 고쳐 쓰지 않는다. 중복 검사는 병합을 따라간 지금의 경험 id 로 한다.
4. source의 사실과 근거를 target에 합쳐 새 개정(`MERGE`)을 만든다. 서로 다른 사실 값은 `facts.conflicts[]` 로 남기고 사용자가 고른다. 과거 문서가 든 (경험 id, 개정 번호)는 바꾸지 않는다. target의 `source_revision` 을 올린다.
5. **source는 지우지 않는다**(10.5 R31). `merged_into = target` 으로 표시하고 목록과 검색에서 숨긴다. source 의 조각을 지우고 dirty 를 끈다. source id로 조회하면 target으로 안내한다. source의 개정 이력이 남아 있으므로, source의 4차 개정을 쓴 자소서도 그 근거를 그대로 찾는다.

<a id="68-병합-경로의-정정과-자소서-작성-근거-계약"></a>
**병합 경로의 정정과 사건 단위 작성 근거 계약** (옛 6.8, 10.6 R33·R34)

작성 근거 `(experienceId, experienceRevision)` 와 현재 사건의 소속은 다를 수 있다. 사실 개정은 작성 당시의 경험에서 찾고, 현재 정체성은 병합 경로로 확인한다. 이력서와 자소서에 같은 규칙을 적용한다.

1. 작성 근거 경험과 개정을 소유자 범위에서 찾는다. 그 개정의 `seq` 가 비교 기준이다(옛 판은 `created_at`).
2. 작성 근거 경험부터 `merged_into` 의 끝까지 순회한다. **source, 중간 경험, 마지막 target을 모두 포함**한다. 각 경험의 `CORRECT` 개정 중 기준 `seq` 보다 큰 것이 하나라도 있으면 재검토가 필요하다. `ADD`·`MERGE` 만으로 경고를 지우거나 만들지 않는다.
3. 계정 경험을 한 번에 읽어 Java Map에서 순회한다. 방문 id를 기록하고 자기 참조나 순환은 `409 MERGE_CYCLE` 로 거절한다. 병합 입력도 같은 검사를 거친다. 소유자가 다른 경로는 404다. 재귀 SQL이나 그래프 DB는 필요 없다.

사건 단위 자소서 REST/MCP 계약(4단계 6.8 의 `experiences` 와 함께 쓴다):
- `episodes` 생략·null은 기존 연결 유지, `[]` 는 비움이다. 목록을 보내면 각 항목은 `{id, experienceId, experienceRevision}` 세 키가 필수다. 빠진 키나 양의 정수가 아닌 개정은 `400 EPISODE_REFERENCE_REQUIRED` 다. MCP 수정은 더하기만 한다.
- 사건과 작성 근거 경험은 모두 내 것이어야 한다(아니면 404). `(experienceId, experienceRevision)` 개정도 실제로 존재해야 한다(없으면 `404 EXPERIENCE_REVISION_NOT_FOUND`).
- 사건의 현재 소속과 작성 근거 경험이 병합 경로의 같은 대표 경험에 도달해야 한다. 아니면 `400 EPISODE_EXPERIENCE_MISMATCH` 다. 이 검사는 같은 경험임을 확인할 뿐, 과거 근거 쌍을 현재 소속으로 바꾸지 않는다. 사실의 개정과 사건 STAR의 현재 편집 버전은 구분한다.
- 검사를 모두 통과한 뒤 전달받은 쌍을 그대로 저장하고, `self_intro_get`·`self_intro_list` 에서 그대로 돌려준다. 다른 연결과 문항은 보존하며, 여러 사건 중 하나라도 실패하면 요청 전체가 실패한다.
- 예: 사건 E가 A에서 B로 옮겨졌고 A와 B 모두 4차 개정이 있어도, `{id:E, experienceId:A, experienceRevision:4}` 는 A의 4차 개정을 가리킨다. 숫자 4만으로 B의 4차 개정을 선택하지 않는다.

합성 자료의 실행 가능한 계약 검증은 [경험 병합 계약 PoC](../../../poc/experience-lineage/README.md)에 둔다. 서버 구현이나 DB 통합 테스트가 아니다. 4c 구현에서 같은 사례를 REST·MCP 서비스 회귀 테스트로 옮긴다. PoC 는 개정 순서를 시각으로 비교하므로 옮길 때 `seq` 로 바꾼다.

**그 밖에 미룬 동작**
- `experience_save` 의 사건 필드: `episodes?[{id?, title, situation, task, action, result, outcomes[], competencies[], sortOrder}]`, `removeEpisodeIds?[]`, 응답 `episodes[{id, version}]`.
- `resume_add_experiences` 의 `items[{experienceId, from?: FACTS|ROW, rowRef?{resumeId, section, index}}]`: `ROW` 는 사용자가 고른 기존 기업용 행의 문구에서 시작하고, 복사한 행의 `experienceId`·`experienceRevision` 을 그대로 가져온다(10.5 R32). 정정 전 개정의 문구를 복사하면 새 행에도 "재검토 필요"가 붙는다.
- "원본 반영 검토": 행에만 있는 새 사실(원본에 없는 수치·기술 키)을 질의 때 보여 준다. 원본을 자동으로 바꾸지 않는다. 행동 서술의 새 사실은 이 방식으로 잡히지 않는다.
- `experience_index` 의 사건 칸(`episodes[{id, title, competencies}]`, 사건 수, 상세의 사건 STAR)과 점검 플래그(사건 없는 경험, 역량 없는 사건, 어디에도 쓰이지 않은 사건).

**4c 회귀 사례** (옛 7절 4단계 검증): 같은 이력서에 있는 두 경험 병합 → 409. 병합 뒤에도 source 개정을 쓴 자소서·이력서의 (경험 id, 개정) 참조가 개정 이력에서 찾아지는지. 병합 뒤 target 에 정정이 생기면 source 옛 개정을 쓴 문서에 "재검토 필요"가 붙는지. source 의 정정 뒤 병합, 중간 경험의 정정 뒤 재병합, 추가만 있는 경우. 같은 개정 번호를 가진 source 와 target 이 있어도 저장·조회 왕복에서 원래 쌍을 유지하는지. `ROW` 복사가 원래 행의 개정 번호를 가져오는지. 사건이 있는 경험의 병합. 병합된 source 가 링커의 제목 키 찾기·검색·스위퍼에 나오지 않는지.

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

리뷰를 받아 순서를 바꿨다(10.2 R9·R11). 3·4차 리뷰로 4단계 내용과 제출·작성 기준 트랙이 바뀌었다(10.4). 아키텍처·DB 리뷰와 사용자 결정으로 3단계를 넓히고, 4단계를 최소판 4a(백엔드)·4b(웹)로 줄였다. 미룬 것은 4c(6.10)다(10.7). 마이그레이션 파일 번호 `V{n}` 은 병합 순서대로 매긴다.

| 단계 | 내용 | 검증 | 되돌리기 |
|---|---|---|---|
| **0. 측정** | 1.2의 표 전부. 바꾸는 코드는 nginx `log_format`, MCP 호출 로그 한 줄뿐이다. 수작업 골드셋을 만든다 | 기준선 수치(SQL 수, waterfall, `$upstream_response_time`, 골드셋 Recall@5·혼입률) 기록. nginx를 바꿨으므로 로그인 왕복 | 커밋 revert |
| **1. 손실과 잘못된 대상 수정 막기** | ① batch fetch(P1) ② 직렬 요청 줄이기: 자소서 동시 요청(적용함), 첨부 메타 동봉(P5) ③ `findOwnedForUpdate` 를 `update`·`updateBasic`·`mutateRows` 에 적용(T1) ④ `V{n}__resume_version` 과 `expectedVersion`: 행 번호 수정·삭제와 전체 교체는 필수, 추가는 선택, 모든 쓰기 응답에 새 `version`(T2) ⑤ `resume_update` null=유지(T3), 행 갱신 병합(T4), 행 도구 개요 반환(T5) ⑥ `posting_update` null=유지와 이름 매칭(T3). 2단계 written_for가 직무 id에 기대므로 먼저 막는다 ⑦ `AttachmentField.vue:52,65` 즉시 삭제 제거(T7) ⑧ 도구 설명과 SKILL의 도구별 수정 규칙, 행 도구 순차 호출과 409 처리 규칙(5.6) | 병렬 `addRow` 2건 → 2행. AI가 읽은 `version` 으로 0번 수정을 보내기 전에 웹에서 0번을 지우면 409이고 다른 행은 그대로. `version` 없는 행 수정 → 400. `projects` 를 뺀 `resume_update` 뒤에도 행이 남는지. 이름만 보낸 `posting_update` 에서 직무 id와 `resume_positions` 보존. 복제본에서 첨부를 교체한 뒤 원본 첨부 GET 200. `now at version vN`. SQL 수와 waterfall을 기준선과 비교 | 항목별 커밋 revert. `version` 열은 남아도 무해 |
| **1단계 리뷰 보정** (`fix/stage1-review`) | **적용함.** `resume_basic_update` 필드 병합(null=유지, `""`=비움. 그 전에는 `ResumeService.updateBasic` 이 basic 블록을 통째로 바꿨다), 잠금 대기·교착 → 409 `BUSY`, 공고 수정의 행 잠금, REST·MCP 공용 오류 변환(REST 404 코드 `NOT_FOUND`), 낡은 문구 정리. 4단계의 오류 변환(2.3)과 T9 대기 처리가 이 공용 변환에 더해진다 | 그 브랜치의 테스트·스모크 기록을 따른다(이 문서 작업에서 다시 돌리지 않았다) | 커밋 revert |
| **2. 자소서 직접 출처와 목록** | `V{n}__self_intro_relations`, `self_intro_list` MCP 래퍼(필터, 미리보기, limit, 출처, fallback, `alreadyUsedFor`), 원문은 `self_intro_get`, 자소서 생성·수정의 `positionIds`·`questionTypes`, 요청 DTO 분리, 웹 "어느 지원용?" 후보 칩과 AI 기록 확정 UI, 정규식 문항 유형. **보정**: ① 자소서 동시 수정 보호(`V24__self_intro_version`, 잠금 + `expectedVersion`, 5.3 `self_intro_update`) ② 공고·직무·기업 삭제는 CASCADE 를 유지하고, 상세의 `linkedSelfIntroCount` 로 삭제 확인창·SKILL 이 사라질 직접 출처 수를 미리 알린다 | 과거 답변이 출처와 함께 정확히 조회되는지. 골드셋 재측정, 특히 타사 혼입률. 다른 계정 직무 id로 연결 → 404. 웹 자소서 목록 응답이 바뀌지 않았는지(답변 전문). `q` 101자 → 400. 같은 읽기에서 두 번 수정하면 두 번째 409·첫 내용 유지. 직무·공고·기업 상세의 수가 맞고 남의 자소서는 세지 않는지. `now at version v24` | 낮음. 테이블은 무시해도 된다. `version` 열과 응답 필드는 남아도 무해 |
| **3. 공고를 저장하지 않는 경험 찾기 (첫 완성 목표)** | **적용함**(`feat/experience-search-v0`, 아래 "3단계 구현 메모"). 마이그레이션 없음. 4단계 산출물에 기대지 않는다(10.7 R42~R44). ① **`KeyNormalizer`·별칭 시드 사전**(2.5). 4단계가 재사용한다 ② **묶음 함수** `(kind, title_key, 시작 연월 숫자)`. `unlinkedRows`·`missing[]`·4단계 가져오기 계획(6.3)이 같이 쓴다 ③ `experience_search` 첫 판: 입력은 `positionId` 또는 저장하지 않는 `posting`(4.1)과 클라이언트가 뽑은 `requirements[]`(4.2), 후보는 RESUME_ROW 묶음뿐, 신호는 LEXICAL·REQUIREMENT(다섯 칸, 칸별 가중, 요구 키는 `KeyNormalizer`), 결과마다 원문 출처 `source` ④ `experience_index` 최소판을 4단계 모양 `{counts, complete, experiences: [], unlinkedRows}` 로(5.2) ⑤ `resume_get(view=outline)`(version, 섹션별 count, 행 머리, `missing[]`, `unlinkedRows`), full `resume_get`·`resume_create` 응답 머리의 묶음 기반 `missing` 요약(5.3) ⑥ SKILL 1번(목차·outline·`missing` 대조)과 2번("고른 뒤에 저장") 절차. **전환 경로다**: 4단계에서 응답 형식은 그대로 두고 자료원만 경험 원본으로 바꾼다. 이력서 검색과 경험 검색을 따로 운영하지 않는다 | 공고 20개를 넘겨도 `job_postings` 행 수가 그대로인지. 기술 환경 칸에만 있는 키가 `uncovered` 에 나오지 않는지. 내 자료에 없는 필수 기술이 `uncovered` 에 나오는지. 지원 자격이 점수에 섞이지 않고 `qualifications[]` 로 나오는지. `KeyNormalizer` 단위 테스트(별칭, 대소문자·공백·기호). 다른 이력서에만 있는 프로젝트를 빼고 만든 `resume_create` 응답의 `missing` 에 그 묶음이 나오는지. `experience_index` 응답에 `experiences: []` 가 늘 있는지. 골드셋에서 정답 경험 행이 상위 5에 있는지와 출처가 맞는지. 하네스에 붙여 "프로젝트 누락"이 줄었는지 | 낮음. 도구 등록과 응답 필드를 빼면 끝난다 |
| **4a. 경험 원본 백엔드 (최소판)** | **적용함**(`feat/experience-bank-backend`, 아래 "4a 구현 메모"). **먼저 내는 배포(확장)**: ① P8 `Resume.replacePositions` 를 `clear()+addAll()` 로(1.3) ② 앵커 확장: `ResumeContent` 4개 행 record 에 `experienceId`·`experienceRevision`, `ResumeRowRequest` 에 `experienceId` 만, 프런트 `fillRow` 앵커 통과와 그 테스트(이 항목만 프런트를 함께 낸다, 6.1). 링커는 아직 없다. **그다음 배포(사용)**: ③ `V{n}__experiences`(2.5 최소판: `experiences`·`experience_revisions`(`seq`)·`resume_experiences`·`self_introduction_experiences`, 개정 참조 FK·CHECK, 지연 FK 셋) ④ `OwnerLock` 과 호출자 전부(T9 잠금 규약) ⑤ `ResumeWriter`(T6 섹션 상한, 링커, 차이 반영, `SET CONSTRAINTS ALL IMMEDIATE`), 우회 저장 금지 아키텍처 테스트, `rebuildResumeExperiences`·JSON↔표 대조 질의(6.2) ⑥ 링커: 앵커 이어받기·개정 검사·`refreshExperienceIds`(6.2) ⑦ 경험 서비스: 생성·수정(개정 판정, `facts_hash`, `expectedVersion`, 6.5), 검토 확정, 삭제(409·`detach`, 6.4), 가져오기 계획·적용(`Persistable`, 6.3) ⑧ 자소서 `experiences` 쌍(REST·MCP, 6.8) ⑨ MCP `experience_save`·`experience_index` 전체판·`resume_add_experiences`(`FACTS`), 행 도구의 `experienceId` 지목(5.2·5.3) ⑩ `missing[]` 에 은행 경험을 더하고, `resume_update` 래퍼에 `LINKED_ROWS_MISSING` ⑪ `experience_search` 자료원을 경험 원본으로 교체(응답 형식 유지, REQUIREMENT 는 `experiences.skills`) ⑫ 공용 오류 변환에 23503(제약 이름·테이블 필드)·23505 와 원인 체인 탐색(2.3) ⑬ 경험 REST 경로를 `ApiPaths`·`routes.ts`·`nginx.conf` 표에 함께 | 확장 배포: `experienceId`·`experienceRevision` PUT→GET 왕복, 프런트 "로드 → 다른 칸 수정 → 저장 payload 에 두 키 그대로". 사용 배포: `now at version vN`. 실제 스키마에서 계정 삭제 cascade 통과(지연 FK 가 필요한지 재확인). `TEXT[]` 매핑 validate 통과. 링커 멱등. 제목 키가 2건 맞으면 미연결로 남는지. 개정 없는 행이 저장된 개정을 이어받는지, 키가 빠진 행이 저장된 앵커를 이어받는지, 클라이언트가 바꾼 개정 → 400, 없는 개정 → 404, `refreshExperienceIds` 만 개정을 올리는지. 대조 질의 0건, 우회 저장을 아키텍처 테스트가 잡는지. 경험 원본을 고쳐도 과거 이력서 행의 문구와 개정 번호가 그대로인지. 정정(`CORRECT`) 뒤 그 경험을 쓴 이력서·자소서에 "재검토 필요"가 붙고, 추가(`ADD`)·근거만 바뀐 개정·표현 차이에는 붙지 않는지. 같은 `facts` 재저장은 개정을 만들지 않는지. `facts` 를 보내며 `expectedVersion` 이 없으면 400. 복합 FK가 다른 소유자 연결을 거부(404). 사용 중 확인 직후 다른 트랜잭션이 연결한 경험의 삭제가 거부되는지. 경험 삭제의 409 가 REST 와 MCP 도구 오류 코드로 같게 나오는지, 커밋 시점 지연 FK 위반도 409 인지(Testcontainers). 같은 계정의 동시 저장이 줄을 서고 `lock_timeout` 을 넘기면 409 `BUSY`, 다른 계정은 기다리지 않는지. 로컬 경로만 있는 근거 → 400. 운영 사본은 집계 비교 SQL만. 골드셋 재측정. 로그인 왕복 | 중간. **확장 배포보다 앞선 코드로는 되돌리지 않는다.** 그 코드는 앵커 키를 모르는 record 로 읽어 저장할 때마다 키를 버린다. 사용 배포만 되돌리면 JSON 앵커는 남고, 다시 올린 뒤 `rebuildResumeExperiences` 를 돈다. 테이블은 남아도 무해하고 MCP 등록을 빼면 멈춘다 |
| **4b. 경험 웹 화면** | **적용함**(`feat/experience-bank-web`, 아래 "4b 구현 메모"). ① 경험 목록·원본 편집(사실 항목 `id` 유지, `expectedVersion`)·검토 확정(`reviewed_revision`)·"AI 기록"·"소재집 갱신 필요" 표시 ② 가져오기 화면(계획 → 묶음별 CREATE/SKIP/LINK_TO, `mergeCandidates` 표시, 409 면 계획 다시) ③ 이력서 편집기: "경험에서 불러오기"·"경험으로 등록"·"다른 경험에 연결", "재검토 필요" 표시와 "원본으로 갱신"(`refreshExperienceIds`), `unlinkedWarnings` 토스트 ④ 경험 삭제(409 목록 표시, `detach` 확인)로 끝나는 중복 처리 흐름(6.4) ⑤ 자소서 편집기의 "쓴 경험" 선택(`experiences` 쌍) | `npm run type-check && npm test`. 편집기 저장 payload 에 앵커가 그대로인지. "원본으로 갱신" 뒤 재검토 표시가 사라지는지. 다른 경험에 연결 → 쓰지 않는 경험 삭제 흐름이 끝까지 되는지. 로그인 왕복 | 낮음. 화면을 빼면 된다. 백엔드는 그대로다 |
| **4c. (보류)** | 사건(STAR)·병합 계보·키워드 테이블·`ROW` 복사·"원본 반영 검토". 설계와 다시 열 때의 조건은 6.10 | 6.10 의 4c 회귀 사례 | — |
| **5. 의미 검색과 필요시 리랭커** | `embedder` 컨테이너(기본 CPU), `compose.gpu.yaml`, `V{n}__self_intro_embeddings`, `V{n}__experience_embeddings`(`experience_chunks`, 2.5), 스위퍼(원문 버전 조건, 행마다 CAS 한 문장, D13·D14, 4.4), `self_intro_list(q)` 에 DENSE + 리랭커 K=10(6단계로 미룰지는 9.2), `experience_search` 에 DENSE와 재현 정보 `context`(6.7). **벡터 저장은 `REAL[]` 과 pgvector를 비교해 정한다**(D11, 9.2). 컨테이너 3 → 4. `.env.example`·`compose.yaml`·`docs/local-dev.md` 동기화. **문항 의미 검색은 `self_introductions` 만 쓰므로 경험 은행과 무관하다.** 골드셋에서 BM25 부족이 확인되면 이 단계의 자소서 부분만 2단계 바로 뒤로 당긴다(R11) | 골드셋에서 앞 단계보다 회수율이 오르는지. CPU 리랭커 지연 실측. embedder를 멈춘 상태에서 저장 성공과 `degraded`, 재기동 후 dirty 0으로 수렴. 스위퍼가 읽은 뒤 원문이 바뀌면 그 회차는 쓰지 않는지. 해시가 같은 조각은 재계산하지 않는지. 벡터 열 매핑 validate 통과 | 낮음. 컨테이너를 내리면 이전 검색으로 돌아간다. **단 pgvector 를 고르면 일방향이다**: DB 이미지 교체(alpine → Debian)와 REINDEX 가 따라온다(D11) |
| **6. 추가 신호와 정리** | DOMAIN·PAST_USE·COMPETENCY(4c 뒤)·PAST_QUESTION 중 골드셋에서 이득이 확인된 것만, `experience_index` 점검 플래그(사실이 빈 경험, AI 기록 수, 미등록 행, 출처 미상 자소서, truncated. 사건 플래그는 4c 뒤), 별칭 편집 UI(4c `keywords` 뒤), 사용률 로그를 근거로 쓰이지 않는 CRUD 도구 정리 검토 | 신호마다 켜기 전후 골드셋 기여 기록. 계정 단위 플래그 수 추적 | 낮음 |
| **성능 트랙** (단계와 별개) | `V{n}__indexes`(1.5), P3·P4·P6~P11. P8 의 `Resume` 은 4a 앞에 한다. T6 은 4단계로 옮겼다. 측정하면서 진행한다 | 토큰 조회 `EXPLAIN` Index Scan. SQL 상한 테스트. 1.0 방식으로 다시 잰 지연. nginx를 건드리면 로그인 왕복 | 코드 revert. 인덱스는 남아도 무해 |
| **제출·작성 기준 트랙** (2단계 이후 언제든) | `V{n}__submissions_and_guides`: 제출 기록(6.6: `SUBMITTED` 전환 때 `JobPostingService` 한 곳에서 LINKED 기록, T9 아래 스냅샷, `submission_attachments` 와 `attachments` 의 `UNIQUE (id, owner_id)`, 웹에서 사용자 확인·접수 근거 첨부), 작성 기준 문서(6.7)와 MCP `writing_guide_get`, 자소서 `writing_context` | 같은 상태 변경을 두 번 보내도 기록이 하나인지. 웹 드래그·공고 수정·공고 생성 세 경로 모두 LINKED 가 남는지. 뒤늦게 등록한 지원 건이 LINKED로만 남고 제출본이라 부르지 않는지. 기록이 가리키는 첨부의 삭제를 DB 가 막는지. 계정 삭제 cascade 통과. 작성 기준 버전이 자소서 `writing_context` 에 남는지 | 낮음. 기록 테이블은 무시해도 된다 |
| **첨부 삭제 — 파이프라인 뒤** | 6.9 그대로: 저장·이력서 삭제 성공 뒤 매번 묻기, [첨부 파일] 서랍(미사용·7일), `DELETE` 의 네이티브 `for update`·사용처 판정·409 `ATTACHMENT_IN_USE`, 규칙 B(`ResumeWriter`, 400 `ATTACHMENT_NOT_FOUND`), `copy` 원본 잠금, 서랍 목록 GET. 마이그레이션·경로 표·MCP 도구는 바꾸지 않는다. 4단계·제출 트랙이 먼저 들어와 있으면 판정에 그 절을 더한다(6.9 체크리스트) | 6.9 의 검증 | 낮음. 화면 버튼을 빼면 멈춘다. 지운 파일은 되돌릴 수 없다 |

**운영 선행 조건과 메모** (10.7 R54)
- **백업·복원**: 지금은 마이그레이션 전 수동 `pg_dump`(`docs/nginx-deploy.md:140`)뿐이다. 정기 백업, 다른 기기 사본, 복원 리허설 절차가 없다. **4단계(4a 사용 배포) 전 선행 조건**으로 둔다: 정기 `pg_dump -Fc`, 다른 기기로 사본, 빈 DB 에 복원해 스모크를 돌리는 리허설 한 번. 절차는 `docs/nginx-deploy.md` 에 적는다.
- **MCP 호출 로그**: 0단계 측정(1.2)을 위해 3단계 앞뒤에 도구 이름·대상 id·ms·결과 코드를 INFO 한 줄로 넣는다. 본문은 남기지 않는다. 3단계에서 넣었다(아래 줄).
- **되돌리기 조건**: 4단계는 앵커 확장 배포보다 앞선 코드로 되돌리지 않는다. 5단계는 pgvector 를 고르면 일방향이다. 위 표의 되돌리기 칸에 적었다.
- **마이그레이션 번호**: 병렬 브랜치에서 `V{n}` 이 겹치지 않는지 병합 전에 본다. 지금 파이프라인은 순차라 해당 없다.
- **MCP 호출 로그(적용함, 3단계)**: `McpController.callTool` 이 `mcp tool=<이름> target=<첫 UUID 인자 또는 -> ms=<n> result=<OK|오류 코드>` 를 INFO 한 줄로 남긴다. 인자·응답 본문은 남기지 않고, 도구 이름은 `[A-Za-z0-9_]` 만 남겨 로그 줄을 깨지 못한다.

**3단계 구현 메모** (구현에서 정한 것, 4단계는 같은 형식에 자료원만 바꾼다)
- 코드: `B/experience/` — `KeyNormalizer`(정규화·별칭 시드·사전 추출·연월 숫자), `RowGroups`(묶음 함수), `ExperienceService`(목차·outline·missing), `ExperienceSearch`(검색). BM25 는 `B/common/Bm25` 로 옮기고 df 를 검색어마다 한 번만 센다. `SelfIntroductionService` 도 이것을 쓴다.
- 묶음 키는 `kind|key(제목)|yearMonth(시작 연월)` 이다. 연월은 "2024.3"·"2024.03"·"2024년 3월" 이 모두 `202403`, 연도만 있으면 `2024`. 경험 행은 프로젝트·경력(제목은 `company`)·대내외활동·교육이수이고, **제목이 빈 행은 묶지 않는다.** null 행은 건너뛰되 행 번호는 원래 위치를 쓴다(행 도구 `index` 와 같음).
- `experience_index`: 입력은 `kinds?`, `cursor?`(정수 오프셋), `limit?`(기본·최대 60, 테스트용으로 열었다). 상세 모드 `ids` 는 경험 원본이 생기는 4단계에 더한다. `importHint` 는 가져오기 화면이 없어 싣지 않는다(4단계). `counts` 는 `{unlinkedRows, groups, unknownSourceSelfIntros}`.
- `resume_get(view=outline)` 의 `unlinkedRows` 는 **그 이력서의** 행 묶음이다(계정 전체는 `experience_index`). full `resume_get`·`resume_create` 는 `McpActions` 래퍼가 응답 맨 앞에 `missing` 을 붙인다. 웹 REST 응답은 그대로다. `maybeUnlinked` 는 3단계에서 "이 이력서에 종류·제목 키가 같고 시작 연월만 다른 행이 있음"이다.
- `experience_search` 입력은 `{request:{positionId? | posting?, requirements?, limit?}}` 로 감쌌다(5.2 의 맨 위 인자와 다름). `McpTools` 가 record 인자만 Bean Validation 하므로 `requirements[]` 의 칸·종류 필수 검사를 받으려면 record 여야 한다. 둘 다 없거나 둘 다 있으면 400 `QUERY_REQUIRED`. `postingId`·`question`·`q`·`competencies`·`kinds` 는 3단계에 없다.
- REQUIREMENT 일치: 행 `techStack` 의 기술 키와 정확·별칭 일치 1, 본문·`techStack` 의 영문 토큰·이웃 토큰 결합·한글 사전어 언급 0.5(토큰 포함 관계를 본문 언급까지 넓혔다. 경력·활동 행은 `techStack` 이 없기 때문). 요구 `text` 는 기술 이름이고 문장은 `quote` 에 둔다. 서버 사전 추출은 `techStack` 목록 전부와 네 칸 본문의 사전어다. 지원자격 본문만 넘기면 그 안의 사전어(기술 키)만 지원자격 칸 요구가 되어 결핍 보고 대상이다. "경력 3년 이상" 같은 자격 문장은 서버가 분류하지 않으므로 `qualifications[]` 는 비고, `REQUIREMENTS_PARTIAL` 이 그것을 알린다. `TAG_MISMATCH_POSSIBLE` 은 정확·언급 일치가 모두 없는데 행 평문 키에 그 키가 보일 때다("Spring-Batch" 처럼 기호로 끊긴 표기). 본문에 토큰으로 보이는 키는 0.5 언급 일치라 결핍이 아니다.
- 결과 출처는 대표 행 `source`(가장 최근에 고친 이력서의 행, `textHash` 는 행 평문 SHA-256 앞 16자, `excerpt` 앞 200자)와 같은 묶음의 다른 행 `alsoIn[]` 로 나눴다. `context` 는 `dataVersion`(계정 이력서 `(id, version)` 해시)·`searchConfig` 둘뿐이다. `coverageIncomplete` 는 3단계에서 늘 true 다(모든 행이 미등록).
- RRF 출처 가중은 3단계 후보(사용자가 쓴 이력서 행)가 모두 1이다. 동점은 묶음 키 오름차순.
- 골드셋 검증 칸은 실제 지원 건 골드셋(0단계) 전까지 **소형 합성 골드셋**으로 고정한다: `ExperienceSearchIntegrationTest.goldSetOrdersByFieldWeightMatchRarityAndKey`(행 6개, 공고 본문을 비워 LEXICAL 을 끄고 REQUIREMENT 순서를 전부 단언 — 칸 가중, 정확 1·포함 0.5, `ln(1+N/df)`, RRF 방향, 동점 키 정렬 중 하나만 바꿔도 깨진다)와 출처 행 번호. 실제 골드셋 측정과 "프로젝트 누락" 하네스는 남아 있다.

**4a 구현 메모** (구현에서 정한 것과 설계와 달라진 것. 문서: `docs/experiences.md`)
- 배포 두 묶음: 확장 커밋(앵커 키·`fillRow`·P8) → 사용 커밋(V25 이후 전부). 마이그레이션 번호는 `V25`.
- **설계와 다름 ① 23503 의 쪽 판정**: 2.3 은 "삭제 쪽이면 참조되는 테이블, 삽입 쪽이면 참조하는 테이블을 싣는다"고 봤으나, 실제 스키마로 재 보니 경험을 지울 때도 오류의 테이블 필드는 참조하는 쪽(`resume_experiences`)이었다. 그래서 `ApiErrors` 는 서버 메시지 머리(`insert or update on table` = 삽입 쪽)로 가른다. DB `lc_messages` 가 영어라는 가정이 붙는다(compose 의 postgres 이미지 기본값). `docs/decisions.md` 에 남겼다. 이를 위해 PostgreSQL 드라이버를 `implementation` 으로 올렸다.
- **설계와 다름 ② `ExperienceRevision` 은 엔티티가 아니다**: `seq` 가 `GENERATED ALWAYS AS IDENTITY` 이고 복합 키라, 넣기·읽기 두 문장을 JDBC(`ExperienceRevisions`)로 한다. `Persistable` 은 대량 insert 하는 `Experience` 에만 둔다(R48 의 목적은 같다).
- **설계와 다름 ③ 우회 금지 아키텍처 테스트**는 ArchUnit 이 아니라 소스 검사(`ResumeWriterGateTest`)다. 의존성을 들이지 않으려고 했고, `Resume.update` 를 패키지 전용으로 내려 다른 패키지는 content 를 바꿀 수도 없게 했다.
- 웹(USER) 경험 생성과 `facts` 를 보낸 웹 수정은 검토 확정을 겸한다(`reviewed_revision = revision`). 메모·skills·domains·소재집만 고친 웹 수정은 확정하지 않는다 — 사람이 보지 않은 AI 사실이 '확정'으로 바뀌면 안 된다(리뷰 반영). MCP(AI) 저장만 'AI 기록'이다. 가져오기 CREATE 초안도 'AI 기록'이다(6.3).
- 가져오기(리뷰 반영): `decisions` 에 같은 묶음 key 가 둘이면 400 `DUPLICATE_DECISION`. CREATE 초안은 사실 상한에 맞춘다 — 2000자를 넘는 행 설명은 2000자 항목 여러 개로 나누고(같은 근거 id), 배열 50개를 넘는 몫은 `unverified` 에 적고, 기간·위치는 200자로 자른 뒤 저장 경로와 같은 `normalize` 를 거친다.
- `resume_add_experiences` 의 FACTS 행은 이력서 칸 상한(이름 200, role·techStack 500, 설명·성과 5000)에 맞춰 자르고 `…` 를 붙인다(리뷰 반영). 원문은 경험 원본에 남는다.
- 공용 오류 변환은 23505·23503 을 409 로 바꾸되, 경험 쪽 지연 FK 셋이 아닌 위반은 SQLState·제약 이름을 WARN 로그로 남긴다(리뷰 반영, 코드 버그 추적용).
- `experience_index` 목록은 경험 한 줄 뒤에 미등록 묶음이 이어지는 한 줄로 `cursor`·`limit`(기본·최대 60)을 자른다. 상세 모드 `ids` 는 10개 이하이고 응답 모양은 같다(`experiences[]` 가 상세, `unlinkedRows` 는 []). `counts` 는 `{experiences(종류별), aiRecords, unlinkedRows, groups, unknownSourceSelfIntros}`.
- `resume_add_experiences` 입력은 `request:{items[]}`(record 검증 때문, 3단계 `experience_search` 와 같은 이유)이고 반환은 `resume_get(view=outline)` 모양(새 `version` 포함)이다. 행 문구는 사실에서만 만든다: 프로젝트는 `role`·`techStack`(skills)·`description`(행동)·`outcome`(결과), 나머지는 `description` 하나. `facts.period` 의 `~` 뒤를 `endYm` 으로 쓴다.
- 쓰이는 경험의 `kind` 를 바꾸면 409 `EXPERIENCE_IN_USE` 다(섹션이 어긋난다). 행 도구는 `index`·`experienceId` 중 정확히 하나(아니면 400 `TARGET_REQUIRED`). 행 갱신에서 다른 `experienceId` 를 보내면 이전 개정을 버리고 새 연결로 본다.
- `experience_search`: `coverageIncomplete` 는 미연결 행이 남았거나 경험 원본이 없을 때 true. EXPERIENCE 결과의 `source` 는 null(근거는 `experience_index` 상세의 `facts.evidence`)이고 `alsoIn` 은 그 경험을 쓰는 이력서 행이다. `searchConfig` 는 `search-v1-2026-11-experience-bank`.
- 자소서 MCP 목록·상세 SQL 상한이 작성 근거 컬렉션 1회만큼 올랐다(6·6·7·7 → 7·7·8·8, 1.6).
- `rebuildResumeExperiences` 는 `ResumeWriter.rebuild(owner)`(OwnerLock 아래 호출)와 같은 SQL 이고, 대조 질의는 `ResumeWriter.mismatches(owner)` 다. 운영에서 부를 REST 경로는 만들지 않았다 — 런북은 같은 SQL 을 psql 로 계정마다 돈다(`docs/experiences.md`).
- **운영 선행 조건(R54) 미수행**: 정기 백업·다른 기기 사본·복원 리허설 절차는 `docs/nginx-deploy.md` 에 적었고 수행하지 않았다. 4a 사용 배포 전에 한다.

**4b 구현 메모** (구현에서 정한 것. 백엔드는 바꾸지 않았다. 문서: `docs/experiences.md` "웹 화면", `docs/frontend.md`)
- 새 SPA 경로 `/experiences`(탑바 세그먼트 "경험")는 `routes.ts` 의 `ROUTES.experiences` 와 `nginx.conf` 상단 표에 더했다. REST 경로는 4a 에서 `ApiPaths.EXPERIENCES`·`API.experience*` 로 이미 있어 `ApiPaths` 는 바꾸지 않았다(SPA 경로는 백엔드 상수가 없다).
- 경험 수정 폼은 사실을 고쳤을 때만 `facts` 를 보낸다(고친 칸만 비교: 기간·역할·측정·한 일·결과). 메모·기술만 고친 저장이 'AI 기록'을 확정하지 않게 하려는 4a 결정과 맞춘 것이다. 근거·미확인·충돌 칸은 화면에서 고치지 않고 받은 그대로 돌려보낸다. 메모·시작 연월은 null 이 "유지"라 지운 칸은 빈 문자열로 보낸다.
- "재검토 필요"는 이력서 편집기가 앵커 경험의 상세(`GET /api/experiences?ids=`, 10개씩)를 읽어 `개정 이력` 에서 판정한다(행 개정보다 큰 `CORRECT` 가 있으면). 서버 판정(`seq`)과 같은 결과다 — 개정 번호가 경험마다 `seq` 순으로 오른다.
- "다른 경험에 연결"·"경험에서 불러오기"는 `experienceRevision` 키를 아예 빼서 보낸다(링커가 새 연결을 지금 개정으로 잇는다). "경험으로 등록"은 행으로 웹 생성(확정)을 먼저 하고 행에 `experienceId` 만 단다 — 연결은 이력서를 저장할 때 확정된다.
- 중복 처리 흐름의 삭제: 409 를 받으면 상세를 다시 읽어, 이력서 행이 없고 자소서 근거만 남았으면 "근거 끊고 삭제" 확인을 띄워 `detach=true` 로 지운다. 이력서가 쓰면 서버 메시지(이력서 이름)를 드로어에 보인다. 409 코드는 둘 다 `EXPERIENCE_IN_USE` 라 메시지 문구가 아니라 다시 읽은 사용처로 가른다.
- 가져오기의 `mergeCandidates` 는 안내만 한다. 같은 적용 안에서 새로 만들 경험에 LINK_TO 할 수 없으므로(아직 id 가 없다) "하나만 만들고 나머지는 건너뛴 뒤 다시 가져오기에서 연결"로 안내한다.

### 함께 갱신할 문서

4단계의 추가 회귀 사례는 6.8을 따른다. REST와 MCP 모두 작성 근거의 경험 id·개정이 빠진 요청, 없는 개정, 타인 소유 경험을 거절하고, 저장·조회 왕복에서 받은 `(경험 id, 개정)` 쌍을 그대로 유지해야 한다. MCP 수정이 기존 연결을 지우지 않는지도 본다. 병합 경로 사례([계약 PoC](../../../poc/experience-lineage/README.md))는 4c 에서 옮긴다(6.10).

| 문서 | 갱신 내용 |
|---|---|
| `docs/experience-graph-search.md` | 이 설계로 다시 쓴다. stories/links/pgvector 구상과 V22 예약은 폐기한다 |
| `docs/resumes.md` | 불변 조건 3(행 도구), 4(입력 null=유지, 저장 모양은 그대로 `[]` 정규화), `experienceId`, `version`(행 번호 수정·삭제와 전체 교체는 필수) |
| `docs/self-introductions.md` | 불변 조건 2(`:22`, "이력서 또는 직무 1개 이상"), 연결 대상 확장 |
| `docs/attachments.md` | 검증 3번(`:60`)과 함정(`:71`): 교체해도 이전 파일은 남는다(고아 허용). 첨부 삭제를 구현할 때 6.9 의 두 길, 잠금 규약(`for update` 와 규칙 B), 사용처 판정과 참조원 체크리스트. 제출 기록이 가리키는 첨부는 FK 가 삭제를 막는다 |
| `docs/job-postings.md` | `SUBMITTED` 전환 때 제출 기록(LINKED)이 생긴다는 것과 확인 수준(6.6) |
| `docs/mcp-plugin.md` | 도구 수. 문서(`:128`)는 53개인데 코드의 `register` 는 54개라 이미 어긋나 있다. 도구별 수정 규칙, `McpActions` 원칙 |
| `docs/backend.md` | batch fetch, 잠금 패턴(`OwnerLock`, `lock_timeout` 다음 문장, `for no key update` 와 첨부 삭제의 예외), `ResumeWriter` 단일 관문, `Persistable`, `REAL[]` 매핑, `SystemEmbeddingSweepRepository` 규칙 |
| `docs/local-dev.md`, `docs/nginx-deploy.md` | embedder, `compose.gpu.yaml`, 마이그레이션 전 백업, `log_format` |
| `docs/decisions.md` | 8절 D1~D14 |
| `AGENTS.md` | 컨테이너 수와 문서 지도 |
| `plugins/jobsight/skills/jobsight/SKILL.md` | 5.6 |

---

## 8. 되돌리는 기존 결정 (`docs/decisions.md` 에 기록할 것)

| # | 기존 결정 (근거) | 바꾸는 내용 | 이유 |
|---|---|---|---|
| D1 | 이력서는 JSONB 하나, 섹션 테이블 없음 (`docs/decisions.md:145-146`) | 이력서는 계속 JSONB 문서다. 경험 원본(사실·소재집·개정 이력)은 테이블로 둔다(사건은 4c). 이력서 행은 그 경험의 기업별 표현이고 쓴 경험 개정 번호를 든다 | 원 결정의 되돌릴 조건("섹션별 질의가 필요해지면", `:146`)이 경험 4개 섹션에 대해 충족됐다. 여러 환경에서 같은 사실을 쓰려면 사실의 기준이 표현 문서 밖에 있어야 한다(10.4) |
| D2 | 본문 JSON에 ID를 섞지 않는다 (`:50`) | 경험 행에 `experienceId` 앵커를 허용한다. 관계의 정본은 FK 테이블 `resume_experiences` 다 | id 없는 행에 정체를 줄 다른 방법이 없다 |
| D3 | 다형 `links` + `stories` + `tags text[]` 구상 (`docs/experience-graph-search.md`) | 폐기한다. 관계 종류별 FK 조인 테이블과 복합 FK를 쓴다 | 다형 간선에는 FK를 걸 수 없어 고아 간선이 남는다 |
| D4 | 자소서는 이력서와만 N:M, `resumeIds` 1개 이상 (`docs/self-introductions.md:22`, `SelfIntroductionRequest.java:12`) | 직무·사건·문항 유형으로 연결을 넓힌다. 생성 시 "이력서 또는 직무 1개 이상", 수정 시 0개 허용. DTO를 분리한다 | "어느 회사용 답변인가"를 판별할 수 없는 구조가 증상 (b)의 원인 후보다 |
| D5 | `resume_update` 통째 교체 (`docs/resumes.md:44`), 섹션 null → `[]` (`docs/resumes.md:45`), SKILL "수정은 전체 교체" (`SKILL.md:23-25`) | `resume_*`·`posting_update` 만 null=유지, `[]`=비움. 저장되는 모양은 그대로 정규화한다. 그 밖의 update 도구는 전체 교체를 유지한다 | AI의 섹션 누락이 조용한 삭제로 이어진다 |
| D6 | 임베딩은 CPU·JVM ONNX (`docs/decisions.md` "임베딩은 GPU 가 아니라 CPU 로" 절) | Python embedder 컨테이너로 바꾼다. 기본은 CPU이고, GPU는 선택 오버라이드다 | 리랭커(크로스 인코더)가 생겼고, 같은 서비스가 GPU로 옮겨 갈 길이 필요하다. 검색 경로는 CPU로도 충분하다는 원 판단은 유지한다. 리랭커를 6단계로 미루면 이 이유가 약해지므로 5단계 전에 다시 정한다(9.2) |
| D7 | 태그 선택지는 계정 기존 값에서 모은다 (`docs/job-postings.md:140`) | 역량·문항 유형은 시스템 고정 코드 18개로 둔다 | 문항과 사건이 같은 어휘를 써야 매칭된다 |
| D8 | 메모리 BM25 (`:50`) | LEXICAL 신호로 유지하고 df 계산만 고친다. 존치는 실데이터 측정으로 정한다 | 합성 PoC는 제거 근거로 부족하다 |
| D9 | `job_postings.target_position_id` | 쓰지도 지우지도 않는다 | 데이터 삭제 금지 원칙 |
| D10 | 첨부 교체 시 즉시 삭제 (`docs/attachments.md:71`, `AttachmentField.vue:51`) | 즉시 삭제를 없앤다. 정리 작업은 두지 않는다. **적용함(2026-11-02)** — `docs/decisions.md` 「첨부 교체·제거 때 이전 파일을 지우지 않는다」 | 복제 이력서가 id를 공유해 원본의 파일이 사라진다 |
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
| 제목 키 자동 연결이 다른 경험을 같은 것으로 묶음 | 잘못된 연결 | 정확히 1건 일치하고 연월이 충돌하지 않을 때만 연결한다. "다른 경험에 연결"과 쓰지 않는 경험 삭제로 고친다(병합은 4c) |
| 행 앵커 유실 (웹 `fillRow`, 구 번들, AI 가 키를 빠뜨린 요청) | 저장마다 연결이 끊기거나 정정 전 문구가 최신 근거처럼 바뀐다 | `fillRow` 통과, 저장된 앵커·개정 이어받기, 클라이언트 개정 변경 거부, 앵커 확장을 링커보다 한 배포 먼저(6.1·6.2) |
| JSON 과 `resume_experiences` 가 어긋남 | 검색·`missing`·삭제 판정이 틀린다 | `ResumeWriter` 단일 관문과 우회 금지 테스트, 대조 질의, 멱등 rebuild(6.2) |
| 권고 잠금 대기가 길어 연결 풀이 마름 | 같은 계정의 요청이 쌓이면 다른 계정까지 연결을 못 얻는다 | `lock_timeout` 바로 다음 문장에서 잠그고, 초과는 409 `BUSY`(T9 잠금 규약) |
| 백업·복원 절차 없음 | 4단계 마이그레이션이나 운영 실수에서 데이터를 되살리지 못한다 | 4단계 전 선행 조건: 정기 `pg_dump -Fc`, 다른 기기 사본, 복원 리허설(7절 운영 메모) |
| 콜드 스타트 (경험 원본과 연결이 적음) | 그래프 신호가 약하다 | 3단계가 경험 테이블 없이 RESUME_ROW 후보로 동작한다. 의미 검색(5단계), `coverageIncomplete`, 간접 출처 fallback |
| AI 기록을 사용자가 확정하지 않음 | PAST_USE가 계속 0.5배이고 하드 필터에서 빠진다 | 경험 목록 배지로 확정을 유도한다. 0.5배여도 신호는 남는다 |
| 고정 역량 코드가 일부 문항을 덮지 못함 | SEMANTIC으로만 검색된다 | 코드 추가는 INSERT 한 줄과 enum 한 줄이다 |
| 키워드 표기 흔들림 | REQUIREMENT를 놓친다 | 별칭 시드 사전, 토큰 포함 일치, `TAG_MISMATCH_POSSIBLE`, 요구 본문을 늘 LEXICAL·DENSE 질의에 넣음 |
| 사건 900자 상한이 사용자에게 짧음 (4c) | 입력 불편 | 4c 를 열 때 토크나이저로 재측정한 뒤 조정한다. 넘치면 조각 임베딩으로 바꾼다 |
| `REAL[]` 매핑이 validate와 맞지 않음 | 5단계 기동 실패 | 벡터 열을 처음 만드는 5단계 첫 커밋에서 확인하고, 안 되면 `bytea`(D11 결정과 함께) |
| 4번째 컨테이너 운영 | 운영 부담 | 기본 CPU. 멈춰도 저장과 검색은 계속 동작한다 |
| 384차원이 모델에 묶임 | 모델 교체 시 전체 재적재 | 조각 테이블을 비우고 dirty를 세워 스위퍼로 재적재한다 |
| 검색 품질 수치가 없음 | 신호 하나하나의 기여를 모른다 | 0단계 수작업 골드셋부터 단계마다 다시 잰다 |
| 웹에서 이력서만 고쳐 경험 원본이 낡음 | AI가 옛 사실로 초안을 쓴다 | 4단계: SKILL 3번 절차가 원본을 먼저 고치게 하고, 정정은 "재검토 필요"로 이력서 쪽에 알린다. 행에만 있는 새 수치·기술 키를 "원본 반영 검토"로 보여 주는 휴리스틱은 4c(6.10). 모든 문구 차이에는 경고하지 않는다(경고 피로, 6.1) |
| 제출 기록을 실제 제출본으로 오해 | 뒤늦게 등록하거나 제출 뒤 고친 문서가 제출본으로 남는다 | 확인 수준을 나누고 LINKED는 "기록 당시 연결 자료"라고만 부른다(6.6) |
| 지연 FK 위반이 커밋 때 남 | 링커 저장이 원인 모를 오류처럼 보인다 | 지연 FK 를 쓰는 쓰기는 커밋 전에 `SET CONSTRAINTS ALL IMMEDIATE`, 단독 삭제는 해당 제약을 `IMMEDIATE` 로 당긴다. 그래도 커밋에서 나면 원인 체인의 SQLState 로 409(2.3) |
| 칸별 가중 초기값이 틀림 | 우대·기술 환경 일치가 과소·과대 평가된다 | 3단계 골드셋으로 조정한다 |
| 소재집이 원문과 어긋남 | AI가 과장된 근거로 초안을 쓴다 | 근거마다 인용·해시, 미확인·충돌 칸, "갱신 필요" 표시, 웹 검토(6.5) |
| 계정 단위 직렬화로 한 계정의 쓰기가 줄을 섬 | 같은 계정의 동시 저장이 기다린다 | 트랜잭션을 짧게 두고 외부 호출을 빼 둔다. 1인 쓰기 규모라 허용한다(T9) |

### 9.2 열린 질문 (사용자 결정 필요)

1. ~~AI가 관계를 확정하게 할지~~ → **닫음**: 확정은 웹에서만 한다. MCP 쓰기는 "AI 기록"이다.
2. ~~이력서 버전마다 경험 문구를 다르게 쓰는지~~ → **닫음**: 경험 원본이 정본이고 기업별 문구는 이력서 행에 보존한다(6.1, 10.4).
3. ~~어느 이력서 버전을 실제로 제출했는지 기록이 필요한지~~ → **닫음**: 제출 기록을 확인 수준과 함께 남긴다(6.6).
4. 사건 단위 기술 태그가 필요한지. 사건 자체가 4c 라 4c 를 열 때 함께 정한다.
5. 서버 로그나 MCP 대화 기록을 볼 수 있는지. "프로젝트 누락"의 원인을 가른다. MCP 호출 로그를 3단계 앞뒤에 넣는다(7절 운영 메모).
6. 수작업 골드셋 10~20개를 언제 만들 수 있는지(0단계 선행 조건).
7. GPU 오버라이드를 켤 시점. 5단계 CPU 리랭커 실측 뒤에 정한다.
8. ~~4단계 범위~~ → **결정됨(2026-11-02)**: 최소판 먼저. 사건·병합 계보·키워드 테이블·`ROW` 복사·"원본 반영 검토"는 4c(6.10).
9. ~~첨부를 지우는 길~~ → **결정됨(2026-11-02)**: 저장 뒤 매번 묻기와 첨부 서랍(미사용·7일). 구현은 파이프라인 뒤(6.9).
10. **4c 를 언제 여나.** 6.10 표의 "다시 열 신호"가 실제로 보이면 연다. 신호를 무엇으로 잴지(골드셋 사례, 중복 경험 수)는 4단계 사용 뒤에 정한다.
11. **리랭커를 6단계로 미룰지** (10.7 R51). 쓰는 곳이 `self_intro_list(q)` 와 PAST_QUESTION 뿐이고 PAST_QUESTION 은 6단계 신호다. 미루면 5단계는 embedder 만 남는다.
12. **embedder 를 어디서 돌릴지.** 원 결정(`docs/decisions.md` "임베딩은 GPU 가 아니라 CPU 로", JVM 안 ONNX)과 D6(Python 컨테이너)이 갈린다. 11번에서 리랭커를 미루면 D6 의 이유가 약해진다.
13. **D11 을 `REAL[]` 로 닫을지.** 5단계 비교 때 잴 것: `REAL[]` 텍스트 파싱 비용, `bytea` 패킹, 계정별 JVM 캐시(`indexGeneration` 을 키로), 조각 테이블의 `embedding_model` 열, `CHECK (cardinality(embedding) = 384)`.
14. **제출 확인 수준을 둘(`LINKED`·`USER_CONFIRMED`)로 줄일지** (6.6). 접수 근거 자동 대조를 하지 않으므로 셋째 수준의 쓸모가 작다.

### 9.3 검증하지 못한 것

- SQL 횟수와 응답 크기는 코드에서 낸 추정이다. 지연은 인증 없는 가벼운 요청으로만 쟀다(1.0).
- 직접 확인한 코드 사실: `ResumeBoard.vue:62` 의 Promise.all, `application.yml` 에 배치 페치 없음, `mutateRows` 의 잠금 없는 조회, `replacePositions` 의 새 Set 대입, `AttachmentField` 의 즉시 삭제, `frontend/nginx.conf` 에 gzip 없음, `postgres:17-alpine`, 최신 마이그레이션 V21, MCP의 컨트롤러 리플렉션 호출, `RuntimeException` → `INTERNAL_ERROR`, 행 도구의 `int index`(`McpActions.java:70,77`), 직무 칸 라벨(`PositionDetail.vue:97-103`, `experience` 는 "취득 경험")과 공고의 지원자격(`JobPosting.java:57`).
- 동시 `resume_row_add` 의 lost update, `resume_update` 의 섹션 소거, 생성 시 선택 누락 중 무엇이 "프로젝트 누락"의 실제 원인인지. 재현하지 않았다.
- Spring Authorization Server JDBC의 타입 없는 토큰 조회 쿼리 모양, 만료 행 자동 정리 여부.
- `REAL[]` ↔ `float[]` Hibernate 7 매핑, 리랭커 CPU 지연, `experience_search` p95, 실데이터 검색 품질, 사건 900자 상한의 토큰 수.
- 각 MCP 클라이언트(Claude, ChatGPT, Codex)의 도구 출력 상한과 잘림 방식.
- 실제 스키마에서 계정 삭제 cascade가 통과하는지. 지연 FK 구조가 통과함은 임시 테이블로 확인했다(2.3). 4단계 최소판의 개정 참조 FK 까지 포함한 실제 스키마로는 확인하지 않았다. 4단계 첫 마이그레이션의 테스트에 넣는다.
- `lock_timeout` 이 `pg_advisory_xact_lock` 대기에도 걸린다는 것(T9 잠금 규약 1)은 PostgreSQL 문서 기준이고 이 프로젝트에서 재지 않았다. 4단계 통합 테스트에서 두 트랜잭션으로 확인한다.
- 개정 판정 규칙(안정 id, 주장 칸만 CORRECT 판정)이 실데이터의 AI 저장 패턴에서 맞는지.
- 클라이언트가 공고에서 뽑은 요구의 정확도와, 넘기지 않았을 때 서버 시드 사전의 회수율.
- 소재집 추출의 사실 정확도. 합성 문항 PoC는 표현 바꾸기에 대한 의미 검색 가능성만 보여 준다.
- 계정 권고 잠금과 지연 FK를 함께 쓸 때의 실제 대기 시간. 4단계 통합 테스트에서 잰다.
- 사실 정정(`CORRECT`)과 추가(`ADD`)를 서버가 이전 개정과 비교해 판정하는 규칙이 실데이터에서 맞는지.
- 행에만 있는 새 사실을 수치·기술 키로 찾는 방식의 누락률(4c). 행동 서술의 새 사실은 이 방식으로 잡히지 않는다.
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

### 10.7 7차: 아키텍처·DB 리뷰 (2026-11-02)

2단계까지 반영된 `5116abb` 기준이다. 사용자가 "3단계 전에 멈춰서 리뷰 결과를 설계에 반영"하라고 했고, 같은 날 4단계 범위("최소판 먼저")와 첨부 삭제를 결정했다. 코드는 고치지 않고 이 문서만 고쳤다. 1단계 리뷰 보정은 `fix/stage1-review` 에서 따로 적용했다(R55).

**유지하는 결정.** 이번 지적은 모두 경계(앵커 보존, 잠금 순서와 대기, 개정 참조 무결성, 이중 기록, 오류 변환)에 있었고 뼈대를 바꾸라는 것은 없었다. 그래서 다음은 그대로 둔다: PostgreSQL 하나에 관계 종류별 FK 조인 테이블과 `(id, owner_id)` 복합 FK 로 소유자를 격리하는 것(그래프 DB·NoSQL 기각), 경험 원본을 정본으로 두고 `(경험 id, 개정)` 쌍으로 참조하는 것, 지연 FK 를 사용 중 확인의 방어선으로 두는 것, `expectedVersion` 과 행 잠금, MCP 전용 동작을 `McpActions` 래퍼에 두는 것, 서버가 LLM 을 부르지 않고 확정은 웹에서만 하는 것, 3단계를 같은 응답 형식의 전환 경로로 두는 순서. 리뷰 원문의 "유지할 좋은 결정" 목록은 이 문서에 옮기지 않았다.

**근거로 확인한 코드 사실**
- `ResumeService.updateBasic`(`B/resume/ResumeService.java:113-123`)은 basic 블록을 통째로 바꾼다. 1단계 리뷰 보정이 필드 병합으로 고쳤다.
- `fillRow`(`frontend/src/types/resume.ts:271-283`)는 `SECTIONS` 의 필드 키만 복사해서 다른 키를 버린다.
- 옛 2.5 DDL 의 `experiences_owner_key_idx`(옛 381행)에 `merged_into` 조건이 없었다.
- 옛 T9 는 권고 잠금을 "트랜잭션 첫 문장"에서 잡았다. `lock_timeout` 보다 앞선다.
- `GlobalExceptionHandler`(`B/common/GlobalExceptionHandler.java`)에는 `PessimisticLockingFailureException` 처리가 없다. `OptimisticLockingFailureException`(`:62`)과 그 밖의 `Exception`(`:96`)뿐이다(1단계 보정 전 기준).
- Hibernate 7.4.1 PostgreSQL 방언의 `PESSIMISTIC_WRITE` 는 `for no key update` 다(리뷰 과정에서 javap 로 확인).
- 2단계 `self_intro_list` 필터는 USER·AI 연결을 모두 직접 출처로 보고 항목마다 `trust` 를 단다(5.3 ③). 리뷰 P2-10 은 이미 해결돼 있었다.
- 이번에 함께 확인: `Resume.replacePositions`(`B/resume/Resume.java:75-78`)는 새 `LinkedHashSet` 을 대입한다. `Resume` 은 생성자에서 UUID 를 채우고 `@Version` 이 primitive `long` 이다(`Resume.java:49-63`). `McpController` 에는 호출 로그가 없다. `AttachmentService.delete`(`B/attachment/AttachmentService.java:62-63`)는 소유 확인 뒤 바로 지운다. `ResumeContent.attachmentIds()`(`B/resume/ResumeContent.java:60`)가 첨부 칸 전부를 모은다. 계약 PoC 는 개정 순서를 `created_at` 으로 비교한다(`poc/experience-lineage/check_contract.py:21-24`).

| # | 지적·결정 | 판단 | 반영 위치 |
|---|---|---|---|
| R35 | [P1] 앵커 유실: 웹 `fillRow` 가 앵커 키를 버린다. 요청 행에 개정이 없으면 최신 개정으로 채워 정정 전 문구가 최신 근거처럼 된다. 클라이언트가 개정을 바꿔 보낼 수 있다 | 채택. `fillRow` 통과와 프런트 테스트, 저장된 개정·앵커 이어받기(최신 개정은 새 연결에만), 클라이언트 개정 변경 거부(400), 개정을 올리는 길은 웹 "원본으로 갱신"과 `resume_add_experiences` 뿐, `ResumeRowRequest` 에는 `experienceId` 만. 앵커 필드를 링커보다 한 배포 먼저 낸다 | 5.3, 6.1, 6.2, 7(4a) |
| R36 | [P1] T9: 권고 잠금이 `lock_timeout` 보다 앞서 대기 상한이 없고 연결 풀(Hikari 기본 10)이 마를 수 있다. 키에 이름공간이 없다. 호출자 범위가 좁다 | 채택. `SET LOCAL lock_timeout = '3s'` 바로 다음 문장, `hashtextextended('exp-graph:' \|\| owner_id::text, 0)`, 헬퍼 `OwnerLock`, 경험을 건드리는 모든 쓰기·삭제와 이력서 create·copy·delete·`lockOwned` 경로. 사용 중 확인은 잠금 아래, 지연 FK 는 방어선. 네이티브 잠금은 `FOR NO KEY UPDATE`(첨부 삭제만 예외) | 1.4 T9 잠금 규약, 6.4, 6.6 |
| R37 | [P1] 병합된 source 가 링커·검색·스위퍼에 새면 같은 제목 키가 2건이 되어 영구 미연결이 된다(옛 381행 인덱스에 `merged_into` 조건 없음) | 부분 채택. 병합 자체를 4c 로 미뤘다. 넣을 때의 조건을 기록했다: 부분 인덱스, 모든 경험 조회에 `merged_into IS NULL`, source 조각 삭제와 dirty 해제, USER 우선 `ON CONFLICT DO UPDATE`, `merged_into` 참조 쪽 인덱스, target 삭제 409 | 6.10 |
| R38 | [P2-3] 개정 참조에 FK·CHECK 가 없다 | 채택. `experience_revisions` 에 `UNIQUE (experience_id, revision, owner_id)`, 두 연결 표의 개정 참조 지연 FK(이름 붙임), `revision >= 1`, `dossier_based_on`·`reviewed_revision` 은 1..revision, `jsonb_typeof(facts) = 'object'` | 2.5, 6.1 |
| R39 | [P2-11] `experience_save` 의 판정을 클라이언트 `changeKind` 에 기대고, 사실 수정에 `expectedVersion` 이 선택이다 | 채택. `facts`·`dossier` 를 보내면 `expectedVersion` 필수. 배열 항목 안정 id 로 서버가 ADD·CORRECT 판정, `changeKind` 입력 없앰, 판정은 응답으로. `facts` 해시가 같으면 개정 없음 | 5.2, 5.6, 6.5 |
| R40 | [P2-5] 이력서 JSON 과 `resume_experiences` 이중 기록의 일관성 | 채택. 단일 관문 `ResumeWriter`(링커·첨부 규칙 B 를 여기서 부름), `ResumeRepository.save*` 우회 금지 아키텍처 테스트, 멱등 `rebuildResumeExperiences(owner)` 와 JSON↔표 대조 질의를 운영 점검·롤백 런북에 | 2.5, 6.2, 7 |
| R41 | [P2] 오류 변환: 잠금 대기·교착 처리가 없고, 커밋 시점 지연 FK 위반은 `TransactionSystemException`·`JpaSystemException` 으로 감싸져 온다 | 채택. 1단계 보정이 공용 변환(`BUSY`, REST 404 `NOT_FOUND`)을 만들었다. 4단계는 23503(제약 이름·테이블 필드로 세분)·23505 → 409 와 원인 체인 탐색을 더하고, 지연 FK 쓰기는 커밋 전에 `SET CONSTRAINTS ALL IMMEDIATE` | 2.3, 7 |
| R42 | [아키텍처] 3단계가 4단계 산출물(`KeyNormalizer`·별칭 시드 사전)에 기댄다. 4.2 요구 추출과 묶음 키 정규화에 필요하다 | 채택. 3단계 산출물로 옮기고 4단계는 재사용 | 2.5, 4.2, 7 |
| R43 | [아키텍처] `missing[]` 이 4단계에 묶여 "생성 때 일부만 고름"을 3단계에서 막지 못한다 | 채택. 3단계 `resume_get(view=outline)`·`resume_create` 에 묶음 기반 `missing`. 4단계는 같은 형식에 경험 원본 기준을 더한다 | 5.3, 5.5, 7 |
| R44 | [아키텍처] 3단계 `experience_index` 최소판의 형식이 4단계와 달라 SKILL 이 두 번 바뀐다 | 채택. `{counts, complete, experiences: [], unlinkedRows}` 로 낸다(R29 를 목차에도 적용) | 5.2, 7 |
| R45 | 4단계 범위가 크다 | **사용자 결정: 최소판 먼저.** 남김: 경험 원본·개정 이력(`seq`)·행 앵커·링커·`resume_experiences`·가져오기·MCP 세 도구(`resume_add_experiences` 는 `FACTS`)·자소서 작성 근거 쌍·경험 삭제·`missing[]`·`LINKED_ROWS_MISSING`·검색 자료원 교체. 4c 로 옮김: 사건·병합 계보·키워드 테이블(`skills`·`domains TEXT[]` 로 대신)·`ROW` 복사·"원본 반영 검토". 중복은 다시 연결 + 삭제 | 0, 2.1~2.3, 2.5, 3, 4.2, 5.2, 5.3, 6.1~6.8, 6.10, 7, 9 |
| R46 | 첨부를 지울 길이 없다(T7 뒤) | **사용자 결정: 설계 확정, 구현은 파이프라인 뒤.** 저장·삭제 뒤 매번 묻기와 서랍(미사용·7일), `DELETE` 는 네이티브 `for update` 뒤 사용처 판정(409 `ATTACHMENT_IN_USE`), 새로 적는 쓰기는 `for key share`(규칙 B, 400 `ATTACHMENT_NOT_FOUND`) | 6.9, 7 |
| R47 | [P2] `Resume.replacePositions` 가 Set 을 갈아 끼워 저장마다 전부 DELETE·INSERT 해서 직무 삭제 cascade 와 교착할 수 있다 | 채택. P8 `clear()+addAll()` 을 4단계 전에 | 1.3 P8, 7 |
| R48 | [P2] 생성자에서 UUID 를 채우고 version 이 primitive 라 `save` 가 merge(+SELECT)다 | 채택. 가져오기 대량 insert 엔티티는 `Persistable` | 2.5, 6.3 |
| R49 | T6(행 추가 섹션 상한)은 `resume_add_experiences` 의 "전부 또는 전무" 전제다 | 채택. 성능 트랙에서 4단계로, `ResumeWriter` 가 건다 | 1.4, 6.2, 7 |
| R50 | 4단계 이후 행 도구가 행 번호에만 기댄다 | 채택. `experienceId` 로도 지목 | 5.3, 5.6 |
| R51 | 5단계: 리랭커를 6단계로 미룰지, embedder 호스팅(D6 vs 원 결정 JVM ONNX), D11 을 `REAL[]` 로 닫을지 | 보류. 열린 질문과 측정 메모(텍스트 파싱, `bytea` 패킹, 계정별 JVM 캐시, `embedding_model` 열, `cardinality` CHECK) | 4.4, 9.2, D6 |
| R52 | 스위퍼가 한 트랜잭션에서 여러 경험 행을 잠가 순서 교착이 날 수 있다 | 채택. 행마다 트랜잭션, CAS 한 문장 `UPDATE … WHERE source_revision = :rev` | 4.4 |
| R53 | 제출 트랙: LINKED 를 MCP 래퍼에서 남기면 웹 드래그·공고 수정·공고 생성이 빠진다. 첨부 `UUID[]` 는 삭제를 막지 못한다 | 채택. `JobPostingService` 상태 전이 한 곳, `submission_attachments`(복합 FK, 지연 NO ACTION)와 `attachments` 의 `UNIQUE (id, owner_id)`, 스냅샷은 T9 아래. 확인 수준 축소는 보류(열린 질문) | 2.5, 5.3, 6.6, 9.2 |
| R54 | 운영: 정기 백업·다른 기기 사본·복원 리허설이 없다. MCP 호출 로그가 없다. 4·5단계 되돌리기 칸이 낙관적이다. 병렬 브랜치의 V 번호 | 채택(기록). 백업은 4단계 선행 조건, 로그는 3단계 앞뒤, 되돌리기 칸 수정, V 번호는 메모 | 7, 9.1 |
| R55 | 1단계 리뷰 보정 | 적용함(`fix/stage1-review`): `resume_basic_update` 필드 병합, 잠금 대기·교착 409 `BUSY`, 공고 수정 행 잠금, REST·MCP 공용 오류 변환(REST 404 `NOT_FOUND`), 낡은 문구 | 7 |
| R56 | [P2-10] 2단계 목록 필터의 직접 출처 처리 | 해당 없음. 이미 해결돼 있었다(위 코드 사실) | — |
