#!/usr/bin/env python3
"""자기소개 문항 검색 PoC — 어떤 조합이 "같은 뜻의 과거 문항"을 1등으로 올리는지 잰다.

비교하는 다섯 가지:
    bm25          지금 서비스가 쓰는 것 그대로(SelfIntroductionService.rank 를 옮김). 기준선이다.
    dense         e5-small-ko-v2 코사인만
    rrf           bm25 + dense 를 RRF 로 융합 — 설계 문서의 검색 흐름
    dense+rr      dense 상위 K 를 리랭커로 재정렬
    rrf+rr        rrf 상위 K 를 리랭커로 재정렬 — 문항 검색의 최종 후보

    rrf+rr 이 dense+rr 과 같으면 BM25 는 자리값을 못 한 것이다. 그때는 지운다.

준비:
    python3 -m venv .venv && .venv/bin/pip install "sentence-transformers>=3"

실행:
    python3 poc/self-intro-search/search_poc.py                  # 동봉 평가 세트로 전체 비교
    python3 poc/self-intro-search/search_poc.py --query "팀원과 의견이 달랐을 때"
    python3 poc/self-intro-search/search_poc.py --corpus my.json
    python3 poc/self-intro-search/search_poc.py --prefix asymmetric   # e5 접두 방식 비교
    python3 poc/self-intro-search/search_poc.py --self-check     # 모델 없이 뼈대만 확인

실데이터로 바꾸려면 questions.json 과 같은 모양의 파일을 만들어 --corpus 로 넘긴다.
corpus 는 DB 의 self_introductions 에서 id/question/answer 를 뽑으면 되고,
queries 는 손으로 채운다 — 정답 없는 평가는 아무것도 증명하지 못한다.
"""

import argparse
import json
import math
import re
import sys
import time
from pathlib import Path

WORD = re.compile(r"[^\W_]+", re.UNICODE)  # Java 의 [\p{L}\p{N}]+ 와 같다
K1, B = 1.2, 0.75                          # SelfIntroductionService 와 같은 값
RRF_K = 60                                 # 설계 문서의 1/(60+rank)

BI_ENCODER = "dragonkue/multilingual-e5-small-ko-v2"
RERANKER = "dragonkue/bge-reranker-v2-m3-ko"


def tokenize(text):
    return WORD.findall((text or "").lower())


def bm25(corpus, query):
    """SelfIntroductionService.rank 를 그대로 옮긴다.

    원본의 두 가지 특징을 일부러 유지한다. 하나는 term 을 word 의 부분 문자열로 보는 것이고
    (그래서 '협업' 이 '협업으로' 도 잡는다), 다른 하나는 점수가 0 인 문서를 아예 빼는 것이다.
    이 둘을 고치면 기준선이 지금 서비스와 달라져 비교가 의미를 잃는다.
    """
    terms = list(dict.fromkeys(tokenize(query)))
    if not terms or not corpus:
        return []
    docs = {item["id"]: tokenize(item["question"] + " " + item.get("answer", "")) for item in corpus}
    average = sum(len(words) for words in docs.values()) / len(docs)
    total = len(corpus)

    scored = []
    for item in corpus:
        words = docs[item["id"]]
        score = 0.0
        for term in terms:
            frequency = sum(1 for word in words if term in word)
            if frequency == 0:
                continue
            document_frequency = sum(1 for doc in docs.values() if any(term in word for word in doc))
            idf = math.log(1 + (total - document_frequency + 0.5) / (document_frequency + 0.5))
            denominator = frequency + K1 * (1 - B + B * len(words) / average)
            score += idf * frequency * (K1 + 1) / denominator
        if score > 0:
            scored.append((item["id"], score))
    scored.sort(key=lambda pair: -pair[1])
    return [item_id for item_id, _ in scored]


def rrf(*rankings):
    """순위만 쓴다. BM25 점수와 코사인은 척도가 달라 더하면 안 된다."""
    fused = {}
    for ranking in rankings:
        for position, item_id in enumerate(ranking, start=1):
            fused[item_id] = fused.get(item_id, 0.0) + 1.0 / (RRF_K + position)
    return [item_id for item_id, _ in sorted(fused.items(), key=lambda pair: -pair[1])]


def prefixed(text, role, mode):
    """e5 접두. 문항끼리의 검색은 비대칭 검색이 아니라 유사도라 양쪽 다 query: 가 맞다.

    모델 카드는 비대칭 과제에만 query:/passage: 를 나눠 쓰라고 한다. 문항 검색에서 이걸 잘못 쓰면
    조용히 품질만 떨어진다 — 그래서 손잡이로 남겨 두고 직접 비교한다.
    """
    if mode == "symmetric":
        return "query: " + text
    return ("query: " if role == "query" else "passage: ") + text


class Dense:
    def __init__(self, name, mode):
        from sentence_transformers import SentenceTransformer
        self.model = SentenceTransformer(name)
        self.mode = mode
        self.ids, self.matrix = [], None

    def index(self, corpus):
        import numpy
        self.ids = [item["id"] for item in corpus]
        texts = [prefixed(item["question"], "doc", self.mode) for item in corpus]
        self.matrix = numpy.asarray(self.model.encode(texts, normalize_embeddings=True))

    def search(self, query):
        import numpy
        vector = numpy.asarray(self.model.encode([prefixed(query, "query", self.mode)],
                                                 normalize_embeddings=True))[0]
        order = numpy.argsort(-(self.matrix @ vector))
        return [self.ids[index] for index in order]


class Reranker:
    def __init__(self, name):
        from sentence_transformers import CrossEncoder
        self.model = CrossEncoder(name)

    def rerank(self, query, ranking, lookup, top_k):
        """상위 top_k 만 재정렬한다. 나머지는 순서 그대로 뒤에 붙인다."""
        head, tail = ranking[:top_k], ranking[top_k:]
        if not head:
            return ranking
        scores = self.model.predict([(query, lookup[item_id]) for item_id in head])
        reordered = [item_id for _, item_id in sorted(zip(scores, head), key=lambda pair: -pair[0])]
        return reordered + tail


def evaluate(rankings_by_query, queries, cutoff=10):
    """Recall@1 과 MRR@10. 정답이 여러 개면 가장 높이 오른 것을 쓴다."""
    hits, reciprocal = 0, 0.0
    for item in queries:
        ranking = rankings_by_query[item["query"]]
        positions = [ranking.index(expected) for expected in item["expect"] if expected in ranking]
        if not positions:
            continue
        best = min(positions)
        if best == 0:
            hits += 1
        if best < cutoff:
            reciprocal += 1.0 / (best + 1)
    return hits / len(queries), reciprocal / len(queries)


def main():
    here = Path(__file__).parent
    parser = argparse.ArgumentParser(description="자기소개 문항 검색 PoC")
    parser.add_argument("--corpus", default=str(here / "questions.json"))
    parser.add_argument("--query", help="주면 이 질의 하나의 순위를 방식별로 보여 준다")
    parser.add_argument("--top-k", type=int, default=10, help="리랭커에 넘길 후보 수")
    parser.add_argument("--show", type=int, default=5)
    parser.add_argument("--prefix", default="symmetric", choices=["symmetric", "asymmetric"])
    parser.add_argument("--bi-encoder", default=BI_ENCODER)
    parser.add_argument("--reranker", default=RERANKER)
    parser.add_argument("--no-rerank", action="store_true")
    args = parser.parse_args()

    data = json.loads(Path(args.corpus).read_text(encoding="utf-8"))
    corpus, queries = data["corpus"], data.get("queries", [])
    lookup = {item["id"]: item["question"] for item in corpus}
    print("말뭉치 %d건 · 평가 질의 %d건 · 접두 %s" % (len(corpus), len(queries), args.prefix))

    started = time.perf_counter()
    dense = Dense(args.bi_encoder, args.prefix)
    dense.index(corpus)
    print("바이 인코더 적재+색인 %.1fs" % (time.perf_counter() - started), file=sys.stderr)

    reranker = None
    if not args.no_rerank:
        started = time.perf_counter()
        reranker = Reranker(args.reranker)
        print("리랭커 적재 %.1fs" % (time.perf_counter() - started), file=sys.stderr)

    def all_arms(query):
        arms = {"bm25": bm25(corpus, query), "dense": dense.search(query)}
        arms["rrf"] = rrf(arms["bm25"], arms["dense"])
        if reranker:
            arms["dense+rr"] = reranker.rerank(query, arms["dense"], lookup, args.top_k)
            arms["rrf+rr"] = reranker.rerank(query, arms["rrf"], lookup, args.top_k)
        return arms

    if args.query:
        for name, ranking in all_arms(args.query).items():
            print("\n[%s]" % name)
            for position, item_id in enumerate(ranking[:args.show], start=1):
                print("  %d. %-4s %s" % (position, item_id, lookup[item_id]))
        return

    if not queries:
        raise SystemExit("평가 질의가 없다. --query 로 한 건씩 보거나 queries 를 채운다.")

    per_arm, elapsed = {}, {}
    for item in queries:
        started = time.perf_counter()
        for name, ranking in all_arms(item["query"]).items():
            per_arm.setdefault(name, {})[item["query"]] = ranking
        elapsed[item["query"]] = time.perf_counter() - started

    print("\n%-10s %10s %10s" % ("방식", "Recall@1", "MRR@10"))
    print("-" * 32)
    for name, rankings in per_arm.items():
        recall, mrr = evaluate(rankings, queries)
        print("%-10s %10.3f %10.3f" % (name, recall, mrr))

    print("\n질의당 다섯 방식 합산 시간 중앙값 %.0fms"
          % (1000 * sorted(elapsed.values())[len(elapsed) // 2]))

    final_name = "rrf+rr" if "rrf+rr" in per_arm else "rrf"
    print("\n%s 가 1등을 못 맞힌 질의:" % final_name)
    final = per_arm[final_name]
    for item in queries:
        ranking = final[item["query"]]
        positions = [ranking.index(expected) for expected in item["expect"] if expected in ranking]
        best = min(positions) if positions else None
        if best != 0:
            got = lookup[ranking[0]] if ranking else "(결과 없음)"
            print("  %-26s 정답순위 %s · 1등: %s"
                  % (item["query"], "없음" if best is None else best + 1, got[:38]))


def self_check():
    """모델 없이 순위 로직만 확인한다."""
    corpus = [
        {"id": "a", "question": "협업 경험을 쓰시오", "answer": ""},
        {"id": "b", "question": "갈등 해결 경험을 쓰시오", "answer": "협업으로 풀었다"},
        {"id": "c", "question": "지원 동기", "answer": ""},
    ]
    ranked = bm25(corpus, "협업")
    assert set(ranked) == {"a", "b"}, ranked        # 부분 문자열 매칭: '협업으로' 도 잡힌다
    assert "c" not in ranked                        # 점수 0 인 문서는 결과에서 빠진다
    assert bm25(corpus, "없는단어") == []
    assert bm25([], "협업") == []
    assert bm25(corpus, "") == []

    fused = rrf(["x", "y", "z"], ["y", "x", "z"])
    assert set(fused) == {"x", "y", "z"} and fused[-1] == "z"   # 양쪽에서 꼴찌면 꼴찌
    assert rrf(["a"], []) == ["a"]
    assert rrf(["p", "q"], ["p", "q"])[0] == "p"                # 양쪽 1등이 1등
    assert rrf([], []) == []

    rankings = {"질의1": ["a", "b"], "질의2": ["b", "a"]}
    queries = [{"query": "질의1", "expect": ["a"]}, {"query": "질의2", "expect": ["a"]}]
    recall, mrr = evaluate(rankings, queries)
    assert recall == 0.5, recall
    assert abs(mrr - 0.75) < 1e-9, mrr              # 1/1 과 1/2 의 평균

    assert prefixed("x", "doc", "symmetric") == "query: x"
    assert prefixed("x", "doc", "asymmetric") == "passage: x"
    assert prefixed("x", "query", "asymmetric") == "query: x"
    print("self-check ok")


if __name__ == "__main__":
    if "--self-check" in sys.argv:
        self_check()
    else:
        main()
