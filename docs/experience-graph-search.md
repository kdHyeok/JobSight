# 경험 그래프 검색 (구상 — 미구현)

> **2026-11-02**: 저장 구조는 [설계](02-design/features/experience-graph.design.md) 가 대체한다. 이 문서의 `stories`·`links` 다형 간선·`pgvector` 구상과 V22 예약은 그 설계의 D3·D11 로 폐기 대상이다. 착수할 때 이 문서를 그 설계 기준의 한 화면 문서로 다시 쓴다. 임베딩 모델 선택과 실측(아래)은 그대로 유효하다.

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
- 임베딩은 **저장 시 동기 계산**하되 **바뀐 행만** 한다(`ResumeService`/`SelfIntroductionService` 의 save 경로).
  본문 해시가 그대로면 다시 계산하지 않는다. 큐는 두지 않는다 — 다만 아래 실측이 한계를 그었다.
  CPU 에서 문서 1건이 142ms 다(PyTorch 기준). 경험 10건을 한 번에 저장하면 1.4초가 응답에 그대로 붙는다.
  그래서 해시 비교가 선택이 아니라 필수다. 그래도 넘치면 그때 비동기로 옮긴다 — ONNX 로 재 보기 전에는 큐를 만들지 않는다.

## 도입할 것

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

## 단계

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
- **접두를 빠뜨리면 성능이 떨어진다.** 한국어 미세조정본도 원본 e5 와 같이 질의는 `query: `, 문서는 `passage: ` 로 시작해야 한다. 한쪽만 붙이는 실수가 흔하다.
- BM25 점수와 cosine 은 척도가 달라 더하면 안 된다. 순위만 쓰는 RRF 로 합친다.
- 이력서 복제는 `storyId` 를 그대로 복사한다(같은 경험). 자소서 문항은 지금처럼 복제하지 않는다.
- `links` 양끝이 다른 소유자면 저장 시 거부. `owner_id` 없이 id 만 보고 조회하는 메서드를 만들지 않는다.
