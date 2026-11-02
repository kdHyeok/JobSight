# 기본은 CPU 빌드다. GPU(Pascal, sm_61)는 compose.gpu.yaml 이 TORCH_INDEX 를 cu126 으로 바꾼다.
# cu126 은 sm_61 커널을 담은 마지막 줄기라 torch 정확 버전을 고정한다(docs/experience-graph-search.md).
FROM python:3.12.13-slim
ARG TORCH_VERSION=2.14.1
ARG TORCH_INDEX=cpu
RUN pip install --no-cache-dir "torch==${TORCH_VERSION}" --index-url "https://download.pytorch.org/whl/${TORCH_INDEX}" \
 && pip install --no-cache-dir "sentence-transformers==6.2.0"
# 이름 있는 볼륨은 처음 붙을 때 이미지 디렉터리의 소유자를 물려받는다 — /models 를 미리 만들어 둔다.
RUN useradd --system --uid 10001 --create-home embedder && mkdir /models && chown embedder /models
WORKDIR /app
COPY server.py .
RUN python server.py --self-check
USER embedder
# 모델 파일은 이미지에 굽지 않고 볼륨(/models)에 받는다. 첫 기동 때 한 번 내려받는다.
ENV HF_HOME=/models EMBEDDER_PORT=8000 PYTHONUNBUFFERED=1
EXPOSE 8000
CMD ["python", "server.py"]
