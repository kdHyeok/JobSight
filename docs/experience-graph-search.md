# 경험 그래프 검색 (구상 — 미구현)

## 목적

자기소개 문항을 검색하면 **같은 뜻의 과거 문항**이 첫 번째로 나오고, 이력서의 활동·경력·프로젝트를
"유사 경험·스토리" 로 이어서 찾을 수 있게 한다. 지금은 `SelfIntroductionService.rank()` 의 메모리 BM25
하나만 있고(단어 포함 여부, 형태소 분석 없음), 이력서 행은 JSONB 안에 id 없이 들어 있어 서로 연결할 수 없다.

계정당 데이터는 수십~수백 건이다. 그래프 DB·검색 서버·Python GraphRAG 파이프라인은 규모에 맞지 않는다.
**Postgres 하나(pgvector) + Java 안 임베딩 + 관계 테이블 1개** 로 끝낸다.

## 저장 구조

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

## 검색 흐름

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
- 임베딩은 **저장 시 동기 계산**한다(`ResumeService`/`SelfIntroductionService` 의 save 경로). 큐·배치 없음.
  본문이 바뀌지 않았으면 다시 계산하지 않는다(해시 비교).

## 도입할 것

| 무엇 | 선택 | 이유 |
|---|---|---|
| 벡터 저장·검색 | **pgvector** (`pgvector/pgvector:pg17` 이미지로 교체, `CREATE EXTENSION vector`) | Postgres 17 그대로, 데이터 볼륨 호환, HNSW 인덱스는 계정당 수백 건에선 필요 없음 |
| 임베딩 실행 | **Spring AI `spring-ai-transformers`** 의 `TransformersEmbeddingModel` (ONNX, JVM 안) | API 키·외부 호출·비용 없음. Spring AI 2.0 이 Boot 4 대응 — 도입 시 버전 재확인 |
| 임베딩 모델 | 1차 **intfloat/multilingual-e5-small** (384차원, MIT) → 품질 부족 시 **nlpai-lab/KURE-v1** (bge-m3 한국어 미세조정, 1024차원, MIT) | e5-small 은 ~500MB 로 컨테이너에 들어간다. KURE 는 한국어 검색 1위권이지만 ~2GB, 차원 바꾸면 재임베딩 |
| BM25 | 기존 `rank()` 유지 | `decisions.md` 결정 그대로. DB 로 옮길 땐 ParadeDB `pg_search`(AGPL 주의) 또는 Tiger `pg_textsearch` |
| 융합 | RRF 를 Java 에서 직접 | 라이브러리 필요 없는 10줄 |
| 그래프 | `links` 테이블 + 재귀 CTE | 2-hop 이하, 소규모. Apache AGE(Cypher) 는 다중 홉 패턴 질의가 진짜 필요할 때 |

**안 도입하는 것과 이유**: Spring AI `VectorStore`/`PgVectorStore` 추상화 — 자기 테이블(`vector_store`)에 문서를 넣어
소유자 격리·기존 엔티티와 어긋난다. 임베딩 모델 빈만 쓰고 SQL 은 직접 쓴다. LightRAG·GraphRAG 류 — Python, LLM 인덱싱 비용,
데이터 수백 건에 커뮤니티 요약이 필요 없다. 대신 **아이디어만 가져온다**: 엔티티(태그)-관계 이중 인덱스, 로컬(이웃)·글로벌(주제) 검색 분리.

## 참고 오픈소스

- 벡터: [pgvector](https://github.com/pgvector/pgvector) · [Spring AI PGvector 문서](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) · [Spring AI ONNX 임베딩](https://docs.spring.io/spring-ai/reference/api/embeddings/onnx.html)
- 한국어 임베딩: [nlpai-lab/KURE-v1](https://huggingface.co/nlpai-lab/KURE-v1) · [nlpai-lab/KoE5](https://huggingface.co/nlpai-lab/KoE5) · BAAI/bge-m3 · intfloat/multilingual-e5-{small,base,large} · [한국어 임베딩 선택 가이드](https://www.data-dynamics.io/en/blog/embedding-model-guide)
- 하이브리드·RRF: [ParadeDB Hybrid Search](https://www.paradedb.com/blog/hybrid-search-in-postgresql-the-missing-manual) · [pg_textsearch](https://www.tigerdata.com/blog/introducing-pg_textsearch-true-bm25-ranking-hybrid-retrieval-postgres) · [plpgsql_bm25](https://github.com/jankovicsandras/plpgsql_bm25)
- 그래프-in-Postgres: [Apache AGE](https://age.apache.org/) · [pgvector+AGE 결합](https://techcommunity.microsoft.com/blog/adforpostgresql/combining-pgvector-and-apache-age---knowledge-graph--semantic-intelligence-in-a-/4508781) · [Postgres 그래프 4가지 접근 비교](https://polygres.com/blog/postgres-as-a-graph-database)
- Graph RAG 참고(설계 아이디어): [LightRAG](https://pypi.org/project/lightrag-hku) · [nano-graphrag 해설](https://gonamlui.com/blog/brief-breakdown-of-nano-graphrag-a-lightweight-alternative-to-graphrag) · [오픈소스 Graph RAG 도구 5선](https://typegraph.ai/blog/best-open-source-graph-rag-tools)
- Java 프레임워크 비교: [Spring AI vs LangChain4j](https://dev.to/jamilxt/spring-ai-vs-langchain4j-which-java-ai-framework-should-you-choose-in-2026-4mop)

## 단계

1. `V22__stories_and_links.sql`: `CREATE EXTENSION vector` · `stories` · `self_introduction_stories` · `links` · `self_introductions` 임베딩 컬럼. compose 이미지 교체.
2. `stories` CRUD + 이력서 행 `storyId` + "과거 경험 불러오기". 임베딩 없이도 동작.
3. 임베딩 빈 + 저장 시 계산 + cosine 검색 + RRF. `findAll(query)` 결과 순서만 바뀐다.
4. `links` 편집(자소서 편집기 체크박스) + 1-hop 확장.
5. (선택) LLM 으로 `tags`·`auto` 간선 추출.

## 함정

- **차원은 모델에 묶인다.** `vector(384)` 를 만든 뒤 KURE(1024) 로 바꾸면 컬럼 교체 + 전체 재임베딩. 처음에 모델을 정하고 `.env` 에 차원을 적는다.
- ONNX 모델 파일은 이미지에 굽지 말고 볼륨으로 마운트한다. 이미지 빌드가 500MB~2GB 늘어난다.
- `multilingual-e5` 는 입력 앞에 `query: ` / `passage: ` 접두를 붙여야 학습 분포와 맞는다. 질의와 문서에 다른 접두를 쓴다.
- BM25 점수와 cosine 은 척도가 달라 더하면 안 된다. 순위만 쓰는 RRF 로 합친다.
- 이력서 복제는 `storyId` 를 그대로 복사한다(같은 경험). 자소서 문항은 지금처럼 복제하지 않는다.
- `links` 양끝이 다른 소유자면 저장 시 거부. `owner_id` 없이 id 만 보고 조회하는 메서드를 만들지 않는다.
