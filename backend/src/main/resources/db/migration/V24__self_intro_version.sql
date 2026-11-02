-- 자소서 동시 수정 보호. 저장할 때마다 오르고, 웹 PUT 과 MCP 의 질문·답변 수정은 이 값을 확인한다 — docs/self-introductions.md 불변 조건 12.
-- AI(MCP)와 웹이 같은 답변을 고친다. 이력서(V22)와 같은 보호다.
ALTER TABLE self_introductions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
