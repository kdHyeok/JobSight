# 경험 그래프 검색 (3단계 경험 찾기 · 5단계 의미 검색 — 첫 구상의 저장·검색 부분은 폐기)

> **폐기 안내 (2026-11-02)**: 저장 구조와 검색은 [설계](02-design/features/experience-graph.design.md) 가 대체했다. 제목에 **(폐기)** 가 붙은 절 — `stories` 정본, `links` 다형 간선, `pgvector` 컬럼, V22 예약, 단계, JVM 안 ONNX — 은 그 설계의 D3·D6·D11 로 폐기됐으니 구현 근거로 쓰지 않는다. 벡터는 `REAL[]` 로 정했다(D11, 5단계). **지금도 유효한 절**: "지금 동작하는 것"(3단계·5단계 구현), 모델 선택 근거, 실측, 함정(512토큰 잘림·배칭).

## 지금 동작하는 것 — 3단계 경험 찾기 (2026-11-02)

> **4단계(4a) 이후**: 자료원이 경험 원본으로 바뀌었다(응답 형식은 아래와 같다). 앵커가 있는 이력서 행은 경험 원본(`type:"EXPERIENCE"`, REQUIREMENT 정확 일치는
> `experiences.skills`)이 대표하고, 앵커가 없는 행만 아래 RESUME_ROW 묶음이 된다. `experience_index.experiences` 는 경험 원본 목차, `missing` 에 은행 경험이 더해졌다.
> `coverageIncomplete` 는 미연결 행이 남았거나 경험 원본이 없을 때만 true 다. 경험 원본·개정·링커는 [docs/experiences.md](experiences.md).

**목적.** 공고를 저장하지 않고 평가하고, 내 경험의 원문 출처(이력서 행)를 돌려준다. 이력서를 새로 만들 때 다른 이력서에만 있는 경험을 빠뜨리지 않게 한다. 경험 원본 테이블(4단계)이 아직 없어 **자료원은 계정의 이력서 경험 행뿐**이다.

**진입점** (`backend/.../company/experience/`, MCP 전용 — REST·화면 없음)
- `KeyNormalizer`: NFKC·소문자·기호 제거 키, 별칭 시드 사전(`k8s→kubernetes`, `자바→java` …), 본문 언급 추출, 연월 숫자. 4단계가 `title_key`·`skills` 를 채울 때 그대로 쓴다.
- `RowGroups`: 묶음 함수 `(kind, key(제목), 시작 연월 숫자)`. 목차·missing·검색이 모두 이것 하나를 쓴다.
- `ExperienceService`: `experience_index`(`{counts, complete, nextCursor, experiences: [], unlinkedRows}`), `resume_get(view=outline)`, `missing`.
- `ExperienceSearch`: `experience_search`. 신호 LEXICAL(`common/Bm25`)·REQUIREMENT(다섯 칸 가중 × `ln(1+N/df)`), 가중 RRF(k=60), `uncovered`·`qualifications`·`context`.
- 등록은 `McpTools`(`experience_index`·`experience_search`, `resume_get`·`resume_create` 는 `McpActions` 래퍼).

**불변 조건**
1. 검색은 아무것도 저장하지 않는다(`job_postings`·`positions` 행 수 그대로). 고른 공고만 `posting_create` 한다.
2. 모든 조회는 현재 계정으로 한정한다. 남의 직무·이력서 id 는 404, 남의 이력서 행은 후보가 아니다.
3. `uncovered` 는 지원자격·요구 역량 칸의 요구만. 우대·기술 환경 나열은 결핍이 아니다. `QUALIFICATION` 요구는 점수에 넣지 않고 `qualifications[]` 로만 돌려준다.
4. 응답 모양은 4단계와 같다. 4단계는 `experiences[]`·`experienceId` 를 채우고 자료원만 바꾼다(형식 유지, 전환 경로).
5. 같은 `context`(dataVersion·searchConfig)와 같은 질의면 같은 결과다. 동점은 묶음 키 순. 신호·가중을 바꾸면 `ExperienceSearch.SEARCH_CONFIG` 를 올린다.

**실행·검증**: `KeyNormalizerTest`(별칭·대소문자·공백·기호·연월), `ExperienceSearchIntegrationTest`(Testcontainers — 저장 안 함, 기술 환경만 있는 키 제외, 없는 필수 기술 포함, 지원 자격 분리, 우대·담당업무 칸 결핍 제외, `TAG_MISMATCH_POSSIBLE`, 지원자격 본문만 준 서버 사전 fallback, 소형 골드셋 순위·출처, `missing`·outline·목차·쪽 나누기, 소유자 격리), `McpCallLogTest`(호출 로그 한 줄).

**함정**
- 요구 `text` 는 기술 이름("Kafka")이다. 문장을 넣으면 키가 `kafka사용경험` 이 되어 아무것도 맞지 않는다. 문장은 `quote` 에 둔다.
- 제목이 빈 행은 묶지 않는다 — 목차·missing·검색 어디에도 안 나온다.
- 한글 별칭은 본문 부분 문자열로 찾는다. 흔한 단어와 겹치는 별칭(예: "뷰", "노드")은 넣지 않았다. 사전에 넣기 전에 오탐을 생각한다.
- `coverageIncomplete` 는 3단계에서 늘 true 다. AI 는 결핍을 단정하지 않는다.
- 요구 없이 지원자격 본문만 넘기면 서버는 사전의 기술 키만 뽑는다(지원자격 칸 요구, 결핍 보고 대상). "경력 3년" 같은 자격 문장은 `qualifications[]` 에 나오지 않는다 — 자격을 분리하려면 클라이언트가 `kind: QUALIFICATION` 으로 넘긴다.
- 묶음은 이력서 JSON 을 매번 전부 읽는다(계정당 이력서 수십 개 규모 가정). 느려지면 4단계 `resume_experiences` 표로 옮긴다.

## 지금 동작하는 것 — 5단계 의미 검색 (2026-11-02)

**목적.** 단어가 달라도 뜻이 가까운 경험·자소서를 찾는다("팀워크" 질의 → "협업" 서술). `experience_search` 에 DENSE 신호, `self_intro_list(q)` 에 의미 검색을 더한다. 리랭커는 6단계로 미뤘다(2026-11-02 사용자 결정).

**진입점**
- `embedder/server.py`(4번째 컨테이너, Python sentence-transformers, 모델 `dragonkue/multilingual-e5-small-ko-v2`, 384차원): `POST /embed {mode: query|passage, texts[]}` → `{model, dim, vectors, truncated[]}`, `GET /health`. 내부 네트워크만, nginx 경로 없음, 모델은 `embedder-models` 볼륨. 기본 CPU, GPU 는 `compose.gpu.yaml`(torch `2.14.1+cu126` 고정). `/rerank` 는 없다.
- 백엔드 `embedding/`: `EmbedderClient`, `EmbeddingTexts`(조각 나누기·해시), `EmbeddingSweeper`(3초 `fixedDelay`), `SystemEmbeddingSweepRepository`(D13 예외), `DenseIndex`(DENSE 점수·재현 정보).
- 테이블: `V26__self_intro_embeddings`(`self_introductions.embedding_dirty·source_revision`, `self_introduction_chunks`), `V27__experience_embeddings`(`experience_chunks`). 조각마다 `text_hash`·`embedding_model`·`embedding REAL[]`(`CHECK cardinality = 384`)·`embedded_at`.

**접두 규칙** (D12, 접두는 embedder 가 mode 로 붙인다)

| 비교 | 질의 쪽 | 문서 쪽 |
|---|---|---|
| 공고 다섯 칸·자유 문장 → 경험 조각 | `query: ` | `passage: ` |
| 자유 문장(`q`) → 자소서 문항 조각 | `query: ` | `query: `(문항↔문항 비교에도 쓰려고 문항은 query 로 적재) |
| 자유 문장(`q`) → 자소서 답변 조각 | `query: ` | `passage: ` |

**길이 처리.** 조각은 `EmbeddingTexts.MAX_CHARS = 800` 자 이하로 문단·줄·문장 경계에서 나눈다. 토크나이저 실측(2026-11-02, 이 모델): 한국어 서술 글자당 0.50토큰(300자 150, 600자 298, 900자 445, 1200자 596토큰 → 512 넘어 잘림, `truncated:true`), 기술 이름이 섞인 문장 600자 244토큰. 800자 ≈ 400토큰이라 480토큰 목표에 20% 여유가 있다. 그래도 잘리면 스위퍼가 `embedding truncated` 를 WARN 으로 남긴다(본문은 남기지 않는다).
- 자소서: 질문 조각들(QUESTION) + 답변 조각들(ANSWER).
- 경험: 필드별로 따로 나누고 조각마다 제목을 머리에 붙인다 — `memo`(메모·기술), `facts.role`(기간·역할), `facts.actions`, `facts.results`, `facts.evidence`(인용), `dossier.narrative`, `dossier.phrasings`. 미확인·충돌·주의사항은 넣지 않는다(키워드 검색과 같은 범위). 사실이 빈 경험도 제목 한 조각은 있다. 이력서의 기업별 문구는 넣지 않는다(4.4).

**계산 시점.** 쓰기 경로는 `embedding_dirty = true, source_revision + 1` 만 표시한다 — 경험은 저장마다(`Experience.touchSearchText`), 자소서는 질문·답변이 바뀔 때만(연결만 바꾼 저장은 아니다). 스위퍼는 ① 테이블마다 dirty 32행을 잠그지 않고 읽고 ② `text_hash`·모델이 같은 기존 조각은 재사용하고 바뀐 조각만 `/embed`(트랜잭션 밖, mode 별 64개씩) ③ **행마다 짧은 트랜잭션**의 첫 문장 `UPDATE … SET embedding_dirty = false WHERE id = ? AND source_revision = ?` 가 1행일 때만 조각을 지우고 다시 넣는다. 0행이면 그사이 원문이 바뀐 것이라 쓰지 않고 다음 회차에 다시 한다. embedder 모델 이름이 바뀌면(`/health`) 다른 모델 조각을 가진 행을 dirty 로 되돌린다.

**실패 격리(5단계 리뷰, 2026-11-02).** 실패는 행 단위다. `EmbedderClient` 는 연결 실패·타임아웃·JSON 아닌 응답을 `Unavailable` 로, embedder 가 응답했지만 거부한 것(HTTP 비 200, 응답 개수 불일치, `/health` 모델 이름 없음)을 하위 형 `Rejected` 로 던진다. 스위퍼는 `Unavailable`(embedder 장애, 회차 중 모델 변경)만 회차를 멈추고, `Rejected`·원문 JSON 파싱 실패·저장 제약 위반은 **그 행만** 6초·12초·…·최대 1시간 간격으로 미루고 다음 행으로 간다 — 오래된 독 행 하나가 `order by updated_at limit 32` 의 맨 앞에서 뒤의 모든 행(다른 계정·경험 루프)을 막지 않는다. 미룬 행만큼 더 읽어 걸러 내므로 한 회차는 여전히 32행을 처리한다. 미룸 기록은 메모리에만 있어 재기동하면 한 번 다시 시도한다. 로그: 행 실패는 `embedding row failed table= id= failures= retryIn= cause=`(원문 없음) WARN, embedder 장애는 첫 회와 100회(약 5분)마다 WARN, 복구 때 INFO. embedder 는 추론 예외를 연결을 끊지 않고 500 `{"error":"INTERNAL"}` 으로 돌려준다.

**검색.**
- `experience_search`: 질의 텍스트(공고 다섯 칸·기술 환경·라벨, 800자까지)를 **트랜잭션 밖에서** 2초 안에 임베딩하고, 경험 조각과 cosine(정규화라 내적) max-sim 으로 상위 20개에 DENSE 순위를 매겨 가중 RRF 에 더한다. RESUME_ROW(미연결 행)는 DENSE 대상이 아니다. `why` 의 DENSE 항목은 `rank` 와 가장 가까운 조각의 `sourceField`. `context` 에 `embeddingModel`(degraded 면 null)·`indexGeneration`(이 계정 경험 조각의 마지막 적재 시각)·`indexBehind`(dirty 경험이 남음). `searchConfig` 는 `search-v2-2026-11-dense`.
- `self_intro_list(q)`: BM25 와 DENSE(문항·답변 조각 max-sim 상위 30)를 RRF(k=60)로 합친다. `score` 는 RRF 값이다. 응답에 `degraded`.
- **관련도 하한** `app.embedder.min-score = 0.45`(`application.yml`): cosine 이 이보다 낮은 조각은 두 검색 모두 DENSE 에 넣지 않는다. 하한이 없으면 엉뚱한 질의("축구")에도 상위 N 개가 결과와 `total` 을 채운다. 실측(2026-11-02, 실 embedder, 문서 8개 × 질의 12개): 관련 질의의 1위 0.46~0.58(팀워크 0.535, 정산 0.580, 갈등 해결 0.568, 리더십 0.459), 무관 질의의 1위 0.39~0.45(축구 0.385, 요리 0.412, 날씨 0.394, 피아노 0.450, 주식 0.448). 분리가 좁다 — 관련 문서의 2~3위(0.40 안팎)도 잘린다. 모델을 바꾸면 다시 잰다.
- embedder 가 없거나 2초를 넘기면 `degraded:["dense"]` 로 나머지 신호만 쓴다. `app.embedder.url`(`EMBEDDER_URL`)이 빈 값이면 의미 검색을 끈 것이고 같은 표시다.

**D11 실측 — `REAL[]` (pgvector 를 들이지 않았다).** Testcontainers PostgreSQL 17, 계정 하나에 조각 1000개(`SemanticSearchIntegrationTest.realArrayReadCostForThousandChunks` 가 출력):

| 항목 | 값 |
|---|---|
| 저장 크기(`pg_column_size`) | 1,560,000B — 조각당 1,560B(384 × 4B + 헤더) |
| 텍스트 표현(`embedding::text`) | 3,973,000B — 조각당 약 3,970B. 드라이버가 텍스트로 받으면 검색 한 번에 이만큼 온다 |
| 읽기 + `Float[]` 변환 + cosine(최선 5회) | 134~150ms(두 번 돌린 값) |
| 같은 행을 `embedding::text` 문자열로만 받기 | 155ms — 파싱·cosine 을 빼도 줄지 않는다. 비용은 대부분 전송과 서버 쪽 직렬화이고 Java 텍스트 파싱·cosine 은 작다 |
| embedder CPU 지연(이 개발 PC, 실 컨테이너) | 질의 p50 19ms·p95 24ms, 600자 문서 p50 98ms·p95 128ms, 600자 32개 한 번 2.2초 |

계정당 조각 수는 경험 하나에 필드 수만큼(보통 2~6)이고 자소서는 문항당 2~5개라, 계정당 경험 수십~백 개면 경험 조각 수백 개다 — 검색 한 번의 읽기는 수십 ms 다. 다시 볼 조건(수천 개, 수백 ms)은 docs/decisions.md 「벡터 저장은 pgvector 가 아니라」.

**불변 조건**
1. embedder 가 죽어 있어도 저장은 성공하고 검색은 `degraded` 로 동작한다. backend 는 embedder 를 `depends_on` 으로 기다리지 않는다.
2. 조각 조회는 `DenseIndex` 에서 늘 `owner_id` 조건이다. 소유자 조건 없는 조회는 `SystemEmbeddingSweepRepository`(패키지 전용) 하나뿐이고 스위퍼만 쓴다(D13).
3. 스위퍼는 여러 행을 한 트랜잭션에서 잠그지 않는다. 쓰기는 CAS 1행일 때만이다.
4. 같은 모델의 조각끼리만 비교한다(`embedding_model`). 질의 벡터를 만든 모델이 `context.embeddingModel` 이다.

**실행·검증.** `EmbeddingTextsTest`(조각 상한·경계·모드·필드·해시), `SemanticSearchIntegrationTest`(Testcontainers, 가짜 embedder — 죽어 있을 때 저장 성공·degraded·`indexBehind`, 재기동 한 회차에 dirty 0, 동의어 DENSE 와 `why.sourceField`, 답변만 바꾸면 답변 조각만 재계산·연결만 바꾸면 재적재 안 함, 읽은 뒤 원문이 바뀌면 그 회차 미기록, 384 아닌 벡터 거부·CASCADE, D11 실측 출력, 독 행 건너뛰기·미룸, 회차 중 장애는 미루지 않음, 무관 질의 0건, 다른 계정 조각 비노출과 조각 owner_id, 모델 교체 재적재·다른 모델 조각 미사용·회차 중 모델 변경 미기록, 경험 수정의 dirty·source_revision·바뀐 필드만 재계산·CAS), `EmbedderClientTest`(JDK HttpServer — 500·개수 불일치·빈 모델은 `Rejected`, 2초 넘는 응답·연결 거부·JSON 아님은 `Unavailable`). 컨테이너: `docker compose build embedder`(빌드가 `server.py --self-check` — 요청 검증과 가짜 모델로 실제 HTTP 400·200·500·404 왕복), `/health`.

**함정**
- 모델을 바꾸면 차원도 바뀔 수 있다. 384 가 아니면 `CHECK` 가 막는다 — 새 마이그레이션으로 CHECK 를 바꾸고 조각을 비운 뒤 dirty 를 세운다. 같은 384 차원 모델이면 스위퍼가 알아서 다시 채운다.
- 방금 저장한 것은 몇 초 동안 DENSE 에 없다(`indexBehind:true`). 테스트는 `sweepOnce()` 를 직접 부르고 예약 실행은 `app.embedder.sweep=false` 로 끈다.
- 질의는 800자에서 자른다(공고 다섯 칸 전체가 512토큰을 넘을 수 있다). 앞쪽 칸(지원자격·담당업무)이 먼저 들어간다.
- DENSE 는 모든 후보에 값이 있다 — 하한(0.45) 아래는 버리고, 남은 것 중 상위 20(자소서 30)개만 순위에 넣는다. 다 넣으면 관계없는 경험이 결과를 채운다. 테스트의 가짜 벡터는 관련 0.99·무관 0.1 근처라 하한과 무관하다.
- 테스트에서 모델을 바꾸면 스위퍼가 **모든 계정**의 다른 모델 조각을 dirty 로 되돌린다. `modelChangeReembeds…` 는 다른 계정 조각을 먼저 비우고 끝에 원래 모델로 되돌린다.
- 엔티티 저장은 dirty 열을 다시 쓴다. 스위퍼와 엇갈리면 이미 끝난 행이 한 번 더 돌 수 있다(해시가 같아 embedder 는 부르지 않는다).

## 목적 (폐기 — 첫 구상)

자기소개 문항을 검색하면 **같은 뜻의 과거 문항**이 첫 번째로 나오고, 이력서의 활동·경력·프로젝트를
"유사 경험·스토리" 로 이어서 찾을 수 있게 한다. 지금은 `SelfIntroductionService.rank()` 의 메모리 BM25
하나만 있고(단어 포함 여부, 형태소 분석 없음), 이력서 행은 JSONB 안에 id 없이 들어 있어 서로 연결할 수 없다.

계정당 데이터는 수십~수백 건이다. 그래프 DB·검색 서버·Python GraphRAG 파이프라인은 규모에 맞지 않는다.
**Postgres 하나(pgvector) + Java 안 임베딩 + 관계 테이블 1개** 로 끝낸다.

## 저장 구조 (폐기)

```
app_users ─1:N─▶ stories                     ← 경험의 정본. 이력서 버전이 아니라 "나의 경험" 하나가 한 행
                 id · owner_id · kind(activity|career|project) · title · period_text
                 body text · tags text[] · embedding vector(384) · created_at · updated_at

resumes.content.{activities|experiences|projects}[i].storyId   ← 선택. fileId 처럼 id 문자열 참조만 든다
                                                                  이력서 문서 모양(통째 PUT)은 그대로

self_introductions  + question_embedding vector(384) · answer_embedding vector(384)
self_introduction_stories (self_introduction_id, story_id)     ← "이 답변은 이 경험으로 썼다" (N:M, 기존 self_introduction_resumes 와 같은 꼴)

links   ← 그래프 간선. 노드는 새 테이블이 아니라 기존 행이다
        owner_id · src_type · src_id · dst_type · dst_id · relation · weight · source(manual|auto)
        src/dst_type ∈ story | self_introduction | position | company
        relation   ∈ uses(자소서→경험) · similar(경험↔경험) · about(경험→직무/기업) · theme(공통 주제)
```

- **경험은 이력서에서 떼어 정본 한 행으로 둔다.** v1·v2 이력서가 같은 프로젝트를 들고 있어도 노드는 하나다.
  이력서 행에는 `storyId` 만 남기고, 편집기의 "과거 경험 불러오기" 가 story 를 골라 행을 채운다.
  자기소개가 `self_introduction_resumes` 로 여러 이력서에 붙는 것과 같은 결정이다(`docs/decisions.md`).
- **노드 테이블을 새로 만들지 않는다.** 그래프 = 기존 행 + `links` 한 테이블. "엔티티 노드"(기술·주제 키워드)는
  `stories.tags` 배열로 시작한다. 태그가 같은 두 경험이 `theme` 간선이다. 별도 entity 테이블은 태그 검색이 병목일 때.
- **간선은 처음엔 손으로 만든다.** 자소서 편집기에서 "이 경험으로 썼다" 체크 = `uses`. LLM 이 자동으로 뽑는 간선은
  `source=auto` 로 구분해 나중에 넣는다. 자동 추출이 없어도 검색은 동작해야 한다.
- 소유자 격리는 그대로: 모든 조회는 `owner_id` 조건, 타인 데이터는 404. `links` 도 `owner_id` 컬럼을 들고 양끝이 같은 소유자인지 저장 시 검사한다.

## 검색 흐름 (폐기)

```
질의 q
 ├─ BM25 (기존 rank(), 메모리)             → 순위 A
 ├─ 임베딩(q) · cosine (pgvector, owner 한정) → 순위 B     자소서는 question_embedding 우선, answer 는 보조
 └─ RRF 융합  score = Σ 1/(60+rank)          → 상위 N
      └─ 그래프 확장 1-hop: 상위 N 의 links 를 따라 이웃(사용한 경험, 같은 주제의 경험, 같은 직무)을 붙이고
         weight 만큼 가산. 재귀 CTE 로 2-hop 까지만. 그 이상은 계정 규모에서 의미가 없다.
```

- "특정 자기소개 문항이 있으면 유사 문항이 첫 번째" 는 **질문 임베딩끼리의 cosine** 이 결정한다. BM25 는
  `협업` 같은 정확한 단어를 잡고, 임베딩은 `갈등을 해결한 경험` ≈ `팀원과 의견이 달랐던 경험` 을 잡는다.
- "스토리대로 연관지어 검색" 은 `stories.body` 임베딩 + `links` 확장이다. 예: "리더십" 검색 → 임베딩이 경험 3개를 찾고,
  `uses` 간선으로 그 경험을 쓴 자소서 답변까지 함께 나온다.
- 임베딩은 **저장 시 동기 계산**하되 **바뀐 행만** 한다(`ResumeService`/`SelfIntroductionService` 의 save 경로).
  본문 해시가 그대로면 다시 계산하지 않는다. 큐는 두지 않는다 — 다만 아래 실측이 한계를 그었다.
  CPU 에서 문서 1건이 142ms 다(PyTorch 기준). 경험 10건을 한 번에 저장하면 1.4초가 응답에 그대로 붙는다.
  그래서 해시 비교가 선택이 아니라 필수다. 그래도 넘치면 그때 비동기로 옮긴다 — ONNX 로 재 보기 전에는 큐를 만들지 않는다.

## 도입할 것 (폐기)

| 무엇 | 선택 | 이유 |
|---|---|---|
| 벡터 저장·검색 | **pgvector** (`pgvector/pgvector:pg17` 이미지로 교체, `CREATE EXTENSION vector`) | Postgres 17 그대로, 데이터 볼륨 호환, HNSW 인덱스는 계정당 수백 건에선 필요 없음 |
| 임베딩 실행 | **Spring AI `spring-ai-transformers`** 의 `TransformersEmbeddingModel` (ONNX, JVM 안, CPU) | API 키·외부 호출·비용 없음. Spring AI 2.0 이 Boot 4 대응 — 도입 시 버전 재확인. GPU 는 쓰지 않는다(아래) |
| 임베딩 모델 | **dragonkue/multilingual-e5-small-ko-v2** (118M · 384차원 · 512토큰 · Apache-2.0) → 품질 부족 시 **nlpai-lab/KURE-v1** (600M · 1024차원 · 8192토큰) | 아래 「모델 선택 근거」 |
| BM25 | 기존 `rank()` 유지 | `decisions.md` 결정 그대로. DB 로 옮길 땐 ParadeDB `pg_search`(AGPL 주의) 또는 Tiger `pg_textsearch` |
| 융합 | RRF 를 Java 에서 직접 | 라이브러리 필요 없는 10줄 |
| 그래프 | `links` 테이블 + 재귀 CTE | 2-hop 이하, 소규모. Apache AGE(Cypher) 는 다중 홉 패턴 질의가 진짜 필요할 때 |

**안 도입하는 것과 이유**: Spring AI `VectorStore`/`PgVectorStore` 추상화 — 자기 테이블(`vector_store`)에 문서를 넣어
소유자 격리·기존 엔티티와 어긋난다. 임베딩 모델 빈만 쓰고 SQL 은 직접 쓴다. LightRAG·GraphRAG 류 — Python, LLM 인덱싱 비용,
데이터 수백 건에 커뮤니티 요약이 필요 없다. 대신 **아이디어만 가져온다**: 엔티티(태그)-관계 이중 인덱스, 로컬(이웃)·글로벌(주제) 검색 분리.

## 모델 선택 근거

속도가 1순위, 품질이 2순위라는 조건에서 소형 한국어 미세조정 모델을 고른다.

| 모델 | 파라미터 | 차원 | 최대 길이 | 한국어 NDCG@10 | bge-m3 대비 |
|---|---|---|---|---|---|
| multilingual-e5-small-ko-v2 | 118M | 384 | 512 | 0.6925 | 96% |
| KURE-v1 | 600M | 1024 | 8192 | 0.69473 | 101% |

**두 점수는 서로 다른 표에서 왔다. 직접 빼지 마라.** 같은 bge-m3 가 한쪽 표에서 0.7242, 다른 쪽에서 0.68723 이다.
측정 데이터셋 구성이 다르다. 그래서 각 표 안의 bge-m3 를 기준점으로 환산한 마지막 열만 비교에 쓴다. 품질 차이는 5% 남짓이다.

**파라미터 수가 속도 차이를 실제보다 작게 보이게 한다.** e5-small 의 118M 중 약 96M 은 25만 토큰짜리 어휘 조회 테이블이다.
조회는 행렬 연산이 아니라 읽기라서 추론 비용이 없다. 실제로 계산하는 트랜스포머 본체만 보면 약 22M 대 약 300M 으로,
파라미터 비(5배)보다 훨씬 크게 벌어진다. 벡터도 384차원이라 1024차원보다 저장과 코사인이 함께 가볍다.

**GPU(GTX 1060, Pascal)는 이 경로에서 뺀다.** CUDA 13 이 Pascal 지원을 제거했고(설치된 580 드라이버가 LTS 라 2028 중반까지 동작),
표준 임베딩 서버인 TEI 는 컴퓨트 능력 7.5 이상만 빌드하며, Pascal 의 FP16 처리량은 FP32 의 1/64 라 반정밀도 가속이 없다.
PyTorch 도 cu126 계열이 sm_61 을 담은 마지막 줄기다.

## 실측 (2026-09-17)

`scripts/embedding-bench.py`, GTX 1060 Max-Q 6GB vs CPU 12코어, torch 2.14+cu126, fp32, 48개 조합 전부 성공.
값은 p95 지연 / 처리량(건/초). `thread` 는 요청 하나가 추론 하나(배칭 없는 서버), `batch` 는 N 건을 묶었을 때의 상한.

검색 질의 (15~18 토큰)

| 모델 | 동시 | GPU thread | GPU batch | CPU thread | CPU batch |
|---|---|---|---|---|---|
| e5-small-ko-v2 | 1 | 8.7ms / 119 | 11.7ms / 109 | 19ms / 60 | 16ms / 64 |
| | 10 | 163ms / 66 | 12.9ms / 797 | 260ms / 44 | 67ms / 185 |
| | 30 | 438ms / 71 | 24ms / 1403 | 733ms / 44 | 171ms / 218 |
| bge-m3 | 1 | 32ms / 34 | 30ms / 35 | 139ms / 7.9 | 152ms / 7.7 |
| | 30 | 1153ms / 27 | 169ms / 180 | 4169ms / 7.2 | 1439ms / 22.5 |

문서 적재 (자기소개 답변 1200자)

| 모델 | 토큰 | 동시 | GPU thread | GPU batch | CPU thread | CPU batch |
|---|---|---|---|---|---|---|
| e5-small-ko-v2 | 512(잘림) | 1 | 23ms / 43.6 | 24ms / 42.5 | 142ms / 8.1 | 170ms / 7.0 |
| | | 30 | 843ms / 36.7 | 579ms / 52.4 | 4504ms / 6.7 | 4445ms / 7.0 |
| bge-m3 | 637(온전) | 1 | 240ms / 4.2 | 242ms / 4.2 | 2083ms / 0.5 | 2123ms / 0.5 |
| | | 30 | 7404ms / 4.0 | 7637ms / 3.9 | 69.8s / 0.4 | 62.4s / 0.5 |

VRAM 은 e5-small 948MiB · bge-m3 3251MiB(6144 중)로 둘 다 들어간다. sm_61 커널이 없는 빌드였지만 sm_60 으로 정상 동작했다.

**모델 선택은 유지된다.** 질의에서 e5-small 이 bge-m3 보다 7.8배, 문서에서 10배 빠르다. 파라미터 비(5배)보다 큰 격차가
실제로 나왔다 — 위의 어휘 테이블 설명대로다. 다만 문서 쪽 10배는 액면 그대로 보면 안 된다. e5 는 1200자를 512 토큰에서
잘랐으므로 나눠 넣으면 조각 수만큼 비용이 붙어 실질 격차는 그보다 작다.

**GPU 를 빼는 결정도 유지한다.** GPU 이득은 5~8배지만, 사용자가 기다리는 검색 경로에서 e5 CPU 질의가 p95 19ms 다.
GPU 의 8.7ms 와의 차이는 DB 왕복에 묻힌다. 이 숫자는 PyTorch 값이고 계획한 ONNX Runtime CPU 는 소형 BERT 에서
보통 더 빠르므로, 위 CPU 열은 실제 구현의 비관적 하한으로 읽는다.

## 참고 오픈소스

- 벡터: [pgvector](https://github.com/pgvector/pgvector) · [Spring AI PGvector 문서](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) · [Spring AI ONNX 임베딩](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)
- 한국어 임베딩: [dragonkue/multilingual-e5-small-ko-v2](https://huggingface.co/dragonkue/multilingual-e5-small-ko-v2) · [nlpai-lab/KURE-v1](https://huggingface.co/nlpai-lab/KURE-v1) · [KURE 저장소](https://github.com/nlpai-lab/KURE) · [한국어 임베딩 리더보드](https://github.com/su-park/mteb_ko_leaderboard) · [intfloat/multilingual-e5-small](https://huggingface.co/intfloat/multilingual-e5-small)
- Pascal GPU 제약: [NVIDIA 아키텍처 지원 안내](https://developer.nvidia.com/blog/navigating-gpu-architecture-support-a-guide-for-nvidia-cuda-developers/) · [Pascal 튜닝 가이드(FP16 1/64)](https://docs.nvidia.com/cuda/pascal-tuning-guide/index.html) · [TEI Pascal 미지원 이슈](https://github.com/huggingface/text-embeddings-inference/issues/740) · [PyTorch 아키텍처 제거 이슈](https://github.com/pytorch/pytorch/issues/157517)
- 하이브리드·RRF: [ParadeDB Hybrid Search](https://www.paradedb.com/blog/hybrid-search-in-postgresql-the-missing-manual) · [pg_textsearch](https://www.tigerdata.com/blog/introducing-pg_textsearch-true-bm25-ranking-hybrid-retrieval-postgres) · [plpgsql_bm25](https://github.com/jankovicsandras/plpgsql_bm25)
- 그래프-in-Postgres: [Apache AGE](https://age.apache.org/) · [pgvector+AGE 결합](https://techcommunity.microsoft.com/blog/adforpostgresql/combining-pgvector-and-apache-age---knowledge-graph--semantic-intelligence-in-a-/4508781) · [Postgres 그래프 4가지 접근 비교](https://polygres.com/blog/postgres-as-a-graph-database)
- Graph RAG 참고(설계 아이디어): [LightRAG](https://pypi.org/project/lightrag-hku) · [nano-graphrag 해설](https://gonamlui.com/blog/brief-breakdown-of-nano-graphrag-a-lightweight-alternative-to-graphrag) · [오픈소스 Graph RAG 도구 5선](https://typegraph.ai/blog/best-open-source-graph-rag-tools)
- Java 프레임워크 비교: [Spring AI vs LangChain4j](https://dev.to/jamilxt/spring-ai-vs-langchain4j-which-java-ai-framework-should-you-choose-in-2026-4mop)

## 단계 (폐기)

1. `V22__stories_and_links.sql`: `CREATE EXTENSION vector` · `stories` · `self_introduction_stories` · `links` · `self_introductions` 임베딩 컬럼. compose 이미지 교체.
2. `stories` CRUD + 이력서 행 `storyId` + "과거 경험 불러오기". 임베딩 없이도 동작.
3. 임베딩 빈 + 저장 시 계산 + cosine 검색 + RRF. `findAll(query)` 결과 순서만 바뀐다.
4. `links` 편집(자소서 편집기 체크박스) + 1-hop 확장.
5. (선택) LLM 으로 `tags`·`auto` 간선 추출.

## 함정

- **차원은 모델에 묶인다.** `vector(384)` 를 만든 뒤 KURE(1024) 로 바꾸면 컬럼 교체 + 전체 재임베딩. 처음에 모델을 정하고 `.env` 에 차원을 적는다.
- **최대 길이 512 다 — 측정으로 확인했다.** 1200자 답변이 512 토큰에서 **조용히 잘렸다**(같은 입력을 bge-m3 는 637 토큰으로 온전히 먹었다).
  오류도 경고도 없다. 나눠서 임베딩하지 않으면 답변 뒷부분이 검색에서 사라진다. 1만 자면 여덟 조각쯤 된다.
- **배칭 없이 동시성을 올리면 처리량이 떨어진다.** e5 질의를 배칭 없이 흘리면 동시 1 에서 119건/s, 동시 10 에서 66건/s 다.
  GPU 가 직렬화하는 동안 스레드 전환 비용만 더해진다. 묶으면 동시 30 에서 1403건/s 로 20배가 된다.
  **그렇다고 지금 배칭을 만들지는 않는다.** 계정 1000개 규모에서 동시 30 검색은 오지 않는다. 동시 10 이라도 CPU p95 260ms 로 버틴다.
  먼저 동시 임베딩 호출 수를 작은 세마포어로 묶어 스레드 경합만 막고, p95 목표를 넘길 때 배칭을 넣는다.
- **모델 저장소에 ONNX 파일이 없다.** `optimum-cli export onnx` 로 직접 내보낸다. 원본 multilingual-e5-small 과 같은 구조다.
- ONNX 모델 파일은 이미지에 굽지 말고 볼륨으로 마운트한다. 이미지 빌드가 500MB~2GB 늘어난다.
- **접두를 빠뜨리면 성능이 떨어진다.** 한국어 미세조정본도 원본 e5 와 같이 질의는 `query: `, 문서는 `passage: ` 로 시작해야 한다. 한쪽만 붙이는 실수가 흔하다. 문항↔문항은 양쪽 `query: `(D12) — 위 「5단계 의미 검색」의 접두 규칙 표가 정본이다.
- BM25 점수와 cosine 은 척도가 달라 더하면 안 된다. 순위만 쓰는 RRF 로 합친다.
- 이력서 복제는 `storyId` 를 그대로 복사한다(같은 경험). 자소서 문항은 지금처럼 복제하지 않는다.
- `links` 양끝이 다른 소유자면 저장 시 거부. `owner_id` 없이 id 만 보고 조회하는 메서드를 만들지 않는다.
