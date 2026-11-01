#!/usr/bin/env python3
"""임베딩 모델 동시 검색 처리량 측정 — 문서 검색 경로에 쓸 모델을 그 서버에서 직접 재 본다.

무엇을 재나:
    동시 검색 1 / 10 / 30 건을 두 가지 방식으로 흘린다.
      thread  N 개 요청이 각각 따로 encode 를 부른다. 배칭 없는 서버(요청 하나 = 추론 하나)의 모습이다.
      batch   같은 N 건을 encode 한 번으로 묶는다. 동적 배칭을 붙였을 때의 상한이다.
    같은 동시성에서 두 줄을 비교하면 배칭을 구현할 값어치가 있는지 바로 보인다.

    입력은 두 종류다.
      query    검색창에 들어오는 짧은 질의. 사용자가 기다리는 경로다.
      passage  적재되는 자기소개 답변 한 문항. e5 는 512 토큰에서 잘리고 bge-m3 는 통째로 먹는다.
               질의만 재면 두 모델이 실제로 갈리는 지점을 놓친다.

준비:
    python3 -m venv .venv && .venv/bin/pip install "sentence-transformers>=3"
    # Pascal(GTX 10xx, sm_61) 에서는 torch 가 cu126 계열이어야 한다. CUDA 13 빌드에는 sm_61 커널이 없다.
    #   .venv/bin/pip install torch --index-url https://download.pytorch.org/whl/cu126

실행:
    python3 scripts/embedding-bench.py                       # 후보 2개 × 가능한 장치 × 동시 1/10/30
    python3 scripts/embedding-bench.py --device cuda
    python3 scripts/embedding-bench.py --model BAAI/bge-m3 --concurrency 1 --concurrency 30
    python3 scripts/embedding-bench.py --payload query --mode thread   # 검색 지연만 빠르게
    python3 scripts/embedding-bench.py --self-check          # 스크립트 자체 확인, 모델 안 받는다

읽는 법:
    지연 p95 가 사용자가 체감하는 검색 응답의 하한이다. 여기에 DB 왕복과 렌더링이 더 얹힌다.
    처리량(건/초)은 그 동시성에서 서버가 소화하는 양이다. 동시성을 올려도 처리량이 안 늘면 이미 포화다.
    thread 의 p95 가 batch 보다 크게 나쁘면 큐잉으로 밀린 것이다 — 배칭 도입의 근거가 된다.
"""

import argparse
import json
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

# 후보 모델과 그 모델이 요구하는 접두. e5 계열은 접두를 빼면 학습 분포와 어긋나 품질이 떨어진다.
# bge-m3 는 접두를 쓰지 않는다. 한쪽에만 붙이는 실수가 흔해 모델마다 표로 묶어 둔다.
MODELS = {
    "dragonkue/multilingual-e5-small-ko-v2": {"query": "query: ", "passage": "passage: "},
    "BAAI/bge-m3": {"query": "", "passage": ""},
}

DEFAULT_CONCURRENCY = [1, 10, 30]

# 검색창에 실제로 들어올 법한 짧은 질의. 동시 요청이 같은 문장만 반복하지 않도록 여러 개를 돌려 쓴다.
RAW_QUERIES = [
    "팀에서 의견이 갈렸을 때 어떻게 조율했는지",
    "협업하면서 갈등을 해결한 경험",
    "가장 어려웠던 프로젝트와 해결 과정",
    "리더십을 발휘해 성과를 낸 사례",
    "실패를 통해 배운 점",
    "새로운 기술을 빠르게 익힌 경험",
]

# 적재되는 쪽. 자기소개 답변 한 문항 분량이다.
# 질의만 재면 두 모델이 갈리는 지점을 놓친다 — e5 는 512 토큰에서 자르고 bge-m3 는 8192 까지 받는다.
_PASSAGE_SENTENCES = [
    "4명 규모의 팀에서 백엔드를 맡아 채용 공고 수집 파이프라인을 만들었습니다. ",
    "수집 주기를 두고 팀원과 의견이 갈렸을 때 각 방식의 비용을 수치로 정리해 합의했습니다. ",
    "기존 배치는 하루 한 번 전체를 다시 긁는 구조였고 실패하면 그날 데이터가 통째로 비었습니다. ",
    "증분 수집으로 바꾸면서 변경 감지 키를 공고 ID와 수정 시각의 조합으로 잡았습니다. ",
    "결과적으로 수집 실패율이 12%에서 0.8%로 줄었고 운영 부담도 함께 낮아졌습니다. ",
    "이 과정에서 합의의 근거를 숫자로 만들어 두면 설득이 빨라진다는 것을 배웠습니다. ",
]

DEFAULT_PASSAGE_CHARS = 1200  # 자기소개 답변 한 문항 정도. --passage-chars 로 바꾼다


def passage_text(seed, target_chars):
    """목표 글자 수까지 문장을 이어 붙인다. seed 로 시작 문장을 돌려 동시 요청이 같은 입력을 안 쓰게 한다."""
    parts, index = [], seed
    length = 0
    while length < target_chars:
        sentence = _PASSAGE_SENTENCES[index % len(_PASSAGE_SENTENCES)]
        parts.append(sentence)
        length += len(sentence)
        index += 1
    return "".join(parts)


def build_texts(prefixes, count, kind, passage_chars=DEFAULT_PASSAGE_CHARS):
    """동시 요청 수만큼 입력을 만든다. 접두는 모델별·입력 종류별 표에서 온다."""
    prefix = prefixes[kind]
    if kind == "query":
        return [prefix + RAW_QUERIES[i % len(RAW_QUERIES)] for i in range(count)]
    return [prefix + passage_text(i, passage_chars) for i in range(count)]


def percentile(values, ratio):
    """정렬 후 위치로 고른다. 표본이 수십~수백 개라 보간은 의미가 없다."""
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(ratio * (len(ordered) - 1))))
    return ordered[index]


def encode(model, texts, batch_size):
    """encode 는 numpy 를 돌려주므로 GPU 라도 반환 시점에 이미 동기화돼 있다."""
    return model.encode(texts, batch_size=batch_size, normalize_embeddings=True)


def measure_batch(model, texts, rounds):
    """N 건을 encode 한 번으로. 요청 하나가 겪는 지연은 묶음 전체가 끝나는 시간이다."""
    level = len(texts)
    waves = []
    for _ in range(rounds):
        started = time.perf_counter()
        encode(model, texts, level)
        waves.append(time.perf_counter() - started)
    latencies = [wave for wave in waves for _ in range(level)]
    return waves, latencies


def measure_threads(model, texts, rounds):
    """N 개 스레드가 한 시점에 같이 출발해 각자 1건씩 encode 한다.

    Barrier 의 action 으로 출발 시각을 잡는다. 스레드를 깨우는 시간이 파동 시간에 섞이지 않게 한다.
    지연은 출발선부터 재므로, GPU 앞에서 큐잉으로 밀린 시간이 그대로 들어간다. 그게 사용자가 겪는 값이다.
    """
    level = len(texts)
    wave_started = [0.0]
    barrier = threading.Barrier(
        level, action=lambda: wave_started.__setitem__(0, time.perf_counter())
    )

    def one_request(index):
        barrier.wait()
        encode(model, [texts[index]], 1)
        return time.perf_counter()

    waves, latencies = [], []
    with ThreadPoolExecutor(max_workers=level) as pool:
        for _ in range(rounds):
            barrier.reset()
            futures = [pool.submit(one_request, i) for i in range(level)]
            ends = [future.result() for future in futures]
            waves.append(max(ends) - wave_started[0])
            latencies.extend(end - wave_started[0] for end in ends)
    return waves, latencies


def measure(model, texts, rounds, mode):
    fn = measure_batch if mode == "batch" else measure_threads
    fn(model, texts, max(1, rounds // 10))  # 워밍업. 첫 호출은 커널 컴파일과 할당이 섞인다.
    waves, latencies = fn(model, texts, rounds)
    return {
        "level": len(texts),
        "mode": mode,
        "p50_ms": percentile(latencies, 0.50) * 1000,
        "p95_ms": percentile(latencies, 0.95) * 1000,
        "throughput": len(texts) * len(waves) / sum(waves),
    }


def token_length(model, text):
    """모델이 실제로 먹는 토큰 수. max_seq_length 로 잘린 뒤의 값이라 512 상한이 그대로 보인다."""
    tokenized = model.tokenize([text])
    for key in ("input_ids", "inputs_embeds"):
        if key in tokenized:
            return int(tokenized[key].shape[-1])
    return -1


def describe_devices(requested):
    try:
        import torch
    except ImportError:
        print("torch 가 없다. sentence-transformers 를 설치하면 함께 들어온다.", file=sys.stderr)
        raise SystemExit(1)

    if not torch.cuda.is_available():
        print("장치: cpu 만 보인다 (torch.cuda.is_available() == False)")
        if requested == "cuda":
            raise SystemExit("cuda 를 요청했지만 torch 가 GPU 를 못 본다.")
        return ["cpu"]

    capability = torch.cuda.get_device_capability(0)
    arches = torch.cuda.get_arch_list()
    tag = "sm_%d%d" % capability
    print("장치: %s / 컴퓨트 능력 %s" % (torch.cuda.get_device_name(0), tag))
    print("torch %s (cuda %s) 빌드 아키텍처: %s" % (torch.__version__, torch.version.cuda, ", ".join(arches)))
    # cubin 은 같은 major 세대 안에서 위로 호환된다. sm_61 GPU 는 sm_60 커널로 돈다.
    # 그래서 정확히 일치하는 태그가 없어도 같은 major 의 더 낮은 minor 가 있으면 문제없다.
    usable = [a for a in arches if a.startswith("sm_%d" % capability[0])
              and int(a.split("_")[1][1:]) <= capability[1]]
    if not usable:
        # Pascal 에서 실제로 밟는 함정이다. 여기서 말해 주지 않으면 한참 뒤 커널 오류로 나온다.
        print("  경고: 이 torch 빌드에 %s 를 감당할 커널이 없다. cuda 측정은 실패한다." % tag)
        print("        cu126 계열 torch 를 설치하거나 --device cpu 로만 잰다.")
    elif tag not in arches:
        print("  참고: %s 커널은 없지만 %s 로 돈다 (같은 세대 상위 호환)." % (tag, usable[-1]))
    if capability[0] == 6:
        print("  참고: Pascal 의 FP16 처리량은 FP32 의 1/64 다. --dtype fp16 은 오히려 느려진다.")
    return ["cpu", "cuda"] if requested == "all" else [requested]


def run(model_name, device, args, levels, kinds, modes):
    import torch
    from sentence_transformers import SentenceTransformer

    if args.torch_threads:
        # CPU 에서 동시 30 을 thread 로 흘리면 요청 스레드 30 × torch 내부 스레드 12 로 과다구독된다.
        # 실제 서버는 보통 여기를 묶는다. 안 묶은 값과 묶은 값이 크게 다르므로 손잡이를 남긴다.
        torch.set_num_threads(args.torch_threads)

    kwargs = {}
    if args.dtype != "fp32":
        kwargs["model_kwargs"] = {"torch_dtype": torch.float16 if args.dtype == "fp16" else torch.bfloat16}

    started = time.perf_counter()
    model = SentenceTransformer(model_name, device=device, **kwargs)
    load_seconds = time.perf_counter() - started
    if args.max_length:
        model.max_seq_length = args.max_length
    prefixes = MODELS.get(model_name) or {"query": "", "passage": ""}
    print("  적재 %.1fs · max_seq_length %d · 차원 %d"
          % (load_seconds, model.max_seq_length, model.get_sentence_embedding_dimension()),
          file=sys.stderr)

    if device == "cuda":
        torch.cuda.reset_peak_memory_stats()

    rows = []
    for kind in kinds:
        tokens = token_length(model, build_texts(prefixes, 1, kind, args.passage_chars)[0])
        for level in levels:
            texts = build_texts(prefixes, level, kind, args.passage_chars)
            for mode in modes:
                print("  재는 중: %s / 동시 %d / %s" % (kind, level, mode), file=sys.stderr)
                try:
                    row = measure(model, texts, args.rounds, mode)
                except Exception as error:  # OOM 같은 한 조합의 실패로 나머지를 버리지 않는다
                    print("    실패: %s" % error, file=sys.stderr)
                    if device == "cuda":
                        torch.cuda.empty_cache()
                    continue
                row.update(model=model_name, device=device, kind=kind, tokens=tokens,
                           dimension=model.get_sentence_embedding_dimension())
                rows.append(row)

    peak_mib = torch.cuda.max_memory_allocated() / (1024 * 1024) if device == "cuda" else None
    for row in rows:
        row["peak_mib"] = peak_mib
        row["load_seconds"] = load_seconds
        row["torch_threads"] = torch.get_num_threads()

    del model
    if device == "cuda":
        torch.cuda.empty_cache()
    return rows


def report(rows):
    header = "%-34s %-5s %4s %-8s %6s %-7s %5s %11s %11s %13s %9s" % (
        "모델", "장치", "차원", "입력", "토큰", "방식", "동시",
        "지연 p50(ms)", "지연 p95(ms)", "처리량(건/초)", "VRAM(MiB)")
    print()
    print(header)
    print("-" * len(header))
    for row in rows:
        print("%-34s %-5s %4d %-8s %6d %-7s %5d %11.1f %11.1f %13.1f %9s" % (
            row["model"][:34], row["device"], row["dimension"], row["kind"], row["tokens"],
            row["mode"], row["level"], row["p50_ms"], row["p95_ms"], row["throughput"],
            "-" if row["peak_mib"] is None else "%.0f" % row["peak_mib"]))


def main():
    parser = argparse.ArgumentParser(description="임베딩 모델 동시 검색 처리량 측정")
    parser.add_argument("--model", action="append", dest="models", help="반복 지정 가능. 없으면 후보 2개")
    parser.add_argument("--device", default="all", choices=["all", "cpu", "cuda"])
    parser.add_argument("--concurrency", action="append", type=int, dest="levels",
                        help="반복 지정 가능. 없으면 1, 10, 30")
    parser.add_argument("--payload", default="both", choices=["both", "query", "passage"],
                        help="query 는 검색 질의, passage 는 적재되는 자기소개 답변 길이")
    parser.add_argument("--passage-chars", type=int, default=DEFAULT_PASSAGE_CHARS)
    parser.add_argument("--mode", default="both", choices=["both", "thread", "batch"])
    parser.add_argument("--rounds", type=int, default=20, help="조합 하나당 반복 파동 수")
    parser.add_argument("--dtype", default="fp32", choices=["fp32", "fp16", "bf16"])
    parser.add_argument("--max-length", type=int, default=0, help="0 이면 모델 기본값")
    parser.add_argument("--torch-threads", type=int, default=0, help="0 이면 torch 기본값(코어 수)")
    parser.add_argument("--json", dest="json_path", help="측정 행을 JSON 으로도 남긴다. 나눠 돌린 결과를 합칠 때 쓴다")
    args = parser.parse_args()

    devices = describe_devices(args.device)
    models = args.models or list(MODELS)
    levels = args.levels or DEFAULT_CONCURRENCY
    kinds = ["query", "passage"] if args.payload == "both" else [args.payload]
    modes = ["thread", "batch"] if args.mode == "both" else [args.mode]
    print()

    rows = []
    for model_name in models:
        for device in devices:
            print("모델 적재: %s @ %s" % (model_name, device), file=sys.stderr)
            try:
                rows.extend(run(model_name, device, args, levels, kinds, modes))
            except Exception as error:
                print("  적재 실패: %s" % error, file=sys.stderr)

    if not rows:
        raise SystemExit("측정된 조합이 없다.")
    if args.json_path:
        with open(args.json_path, "w", encoding="utf-8") as handle:
            json.dump(rows, handle, ensure_ascii=False, indent=2)
        print("JSON 저장: %s" % args.json_path, file=sys.stderr)
    report(rows)


def self_check():
    """모델을 받지 않고 측정 뼈대만 확인한다. 가짜 모델로 동시 경로까지 태운다."""
    assert percentile([1, 2, 3, 4, 5], 0.50) == 3
    assert percentile([1, 2, 3, 4, 5], 0.95) == 5
    assert percentile([5, 1, 3], 0.0) == 1      # 정렬되지 않은 입력
    assert percentile([7], 0.95) == 7           # 표본 1개

    prefixes = {"query": "query: ", "passage": "passage: "}
    assert build_texts(prefixes, 2, "query") == ["query: " + RAW_QUERIES[0], "query: " + RAW_QUERIES[1]]
    assert build_texts({"query": "", "passage": ""}, 8, "query")[6] == RAW_QUERIES[0]  # 풀보다 많이 요청하면 돌려 쓴다
    passages = build_texts(prefixes, 3, "passage", 300)
    assert all(text.startswith("passage: ") for text in passages)
    assert len(set(passages)) == 3              # 동시 요청끼리 같은 입력을 안 쓴다
    assert 300 <= len(passages[0]) - len("passage: ") < 300 + 80
    assert len(build_texts(prefixes, 1, "passage", 2000)[0]) >= 2000

    class FakeModel:
        """스레드 하나가 5ms 를 쓰는 척한다. 배치는 건당 1ms."""
        def encode(self, texts, batch_size=None, normalize_embeddings=None):
            time.sleep(0.005 if len(texts) == 1 else 0.001 * len(texts))
            return [[0.0]] * len(texts)

    fake = FakeModel()
    for mode in ("thread", "batch"):
        row = measure(fake, build_texts(prefixes, 4, "query"), 5, mode)
        assert row["level"] == 4 and row["mode"] == mode
        assert row["p95_ms"] >= row["p50_ms"] > 0
        assert row["throughput"] > 0
    print("self-check ok")


if __name__ == "__main__":
    if "--self-check" in sys.argv:
        self_check()
    else:
        main()
