#!/usr/bin/env python3
"""JobSight embedder — 의미 검색용 임베딩 서버(설계 4.4, docs/experience-graph-search.md).

    POST /embed  {"mode": "query"|"passage", "texts": [...]}
                 -> {"model": ..., "dim": 384, "vectors": [[...]], "truncated": [bool, ...]}
    GET  /health -> {"status": "UP", "model": ..., "dim": 384, "device": ..., "maxTokens": 512}

내부 네트워크(compose `app`)에만 붙는다. nginx 경로가 없고 호스트 포트도 열지 않는다.
리랭커(/rerank)는 6단계로 미뤘다(2026-11-02 사용자 결정).

접두는 이 서버가 붙인다. 호출자는 mode 만 고른다 — e5 는 한쪽에만 붙이면 품질이 떨어진다.
문항↔문항 비교는 양쪽 다 mode=query 다(설계 4.4 접두 규칙 표).
truncated[i] 는 접두를 붙인 입력이 모델 최대 토큰을 넘어 잘렸다는 뜻이다(e5 는 잘라도 오류를 내지 않는다).

    python3 server.py               # 모델을 읽고 0.0.0.0:8000 에서 듣는다
    python3 server.py --self-check  # 모델 없이 요청 검증·접두·응답 모양만 확인한다
"""

import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MODEL_NAME = os.environ.get("EMBEDDER_MODEL", "dragonkue/multilingual-e5-small-ko-v2")
DEVICE = os.environ.get("EMBEDDER_DEVICE", "cpu")
PORT = int(os.environ.get("EMBEDDER_PORT", "8000"))
PREFIX = {"query": "query: ", "passage": "passage: "}
MAX_TEXTS = 64          # 스위퍼 한 회차(행 32개 × 조각 몇 개)를 몇 번에 나눠 보낸다
MAX_CHARS = 8000        # 한 텍스트 상한. 조각은 백엔드가 이보다 훨씬 작게 자른다
MAX_BODY = 2_000_000


class BadRequest(Exception):
    pass


def parse(body):
    """요청 검증. 통과하면 (mode, 접두를 붙인 texts)."""
    try:
        data = json.loads(body)
    except ValueError as error:
        raise BadRequest("JSON 이 아닙니다") from error
    if not isinstance(data, dict):
        raise BadRequest("객체여야 합니다")
    mode = data.get("mode")
    if mode not in PREFIX:
        raise BadRequest("mode 는 query 또는 passage")
    texts = data.get("texts")
    if not isinstance(texts, list) or not texts or len(texts) > MAX_TEXTS:
        raise BadRequest(f"texts 는 1~{MAX_TEXTS}개 배열")
    if not all(isinstance(t, str) and 0 < len(t) <= MAX_CHARS for t in texts):
        raise BadRequest(f"texts 의 항목은 1~{MAX_CHARS}자 문자열")
    return mode, [PREFIX[mode] + t for t in texts]


class Model:
    def __init__(self):
        from sentence_transformers import SentenceTransformer  # 무거운 import 는 self-check 에서 피한다

        self.st = SentenceTransformer(MODEL_NAME, device=DEVICE)
        self.dim = self.st.get_sentence_embedding_dimension()
        self.max_tokens = self.st.max_seq_length
        # 배칭 없이 동시성을 올리면 스레드 경합으로 처리량이 떨어진다(실측). 동시 추론 수만 묶는다.
        self.gate = threading.Semaphore(int(os.environ.get("EMBEDDER_CONCURRENCY", "2")))

    def embed(self, texts):
        with self.gate:
            return self._embed(texts)

    def _embed(self, texts):
        # 특수 토큰을 포함한 길이가 최대를 넘으면 잘린다. 같은 토크나이저로 미리 세어 알려 준다.
        lengths = [len(ids) for ids in self.st.tokenizer(texts, add_special_tokens=True)["input_ids"]]
        vectors = self.st.encode(texts, normalize_embeddings=True, convert_to_numpy=True, batch_size=32)
        return [v.tolist() for v in vectors], [n > self.max_tokens for n in lengths]


def handler(model):
    class Handler(BaseHTTPRequestHandler):
        def send(self, status, payload):
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path != "/health":
                return self.send(404, {"error": "NOT_FOUND"})
            self.send(200, {"status": "UP", "model": MODEL_NAME, "dim": model.dim, "device": DEVICE,
                            "maxTokens": model.max_tokens})

        def do_POST(self):
            if self.path != "/embed":
                return self.send(404, {"error": "NOT_FOUND"})
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY:
                return self.send(400, {"error": "BAD_REQUEST", "message": "본문 크기"})
            try:
                _, texts = parse(self.rfile.read(length))
            except BadRequest as error:
                return self.send(400, {"error": "BAD_REQUEST", "message": str(error)})
            vectors, truncated = model.embed(texts)
            self.send(200, {"model": MODEL_NAME, "dim": model.dim, "vectors": vectors, "truncated": truncated})

        def log_message(self, fmt, *args):  # 본문은 남기지 않는다. 경로·상태만
            sys.stderr.write("embedder %s\n" % (fmt % args))

    return Handler


def self_check():
    mode, texts = parse(json.dumps({"mode": "query", "texts": ["협업 경험"]}))
    assert mode == "query" and texts == ["query: 협업 경험"], texts
    assert parse(json.dumps({"mode": "passage", "texts": ["a"]}))[1] == ["passage: a"]
    for bad in ["[]", "{}", '{"mode":"x","texts":["a"]}', '{"mode":"query","texts":[]}',
                '{"mode":"query","texts":[""]}', '{"mode":"query","texts":[1]}', "not json",
                json.dumps({"mode": "query", "texts": ["a"] * (MAX_TEXTS + 1)}),
                json.dumps({"mode": "query", "texts": ["a" * (MAX_CHARS + 1)]})]:
        try:
            parse(bad)
        except BadRequest:
            continue
        raise AssertionError("통과하면 안 되는 요청: " + bad[:40])
    print("self-check OK")


if __name__ == "__main__":
    if "--self-check" in sys.argv:
        self_check()
        sys.exit(0)
    loaded = Model()
    print(f"embedder ready model={MODEL_NAME} dim={loaded.dim} device={DEVICE} maxTokens={loaded.max_tokens}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), handler(loaded)).serve_forever()
