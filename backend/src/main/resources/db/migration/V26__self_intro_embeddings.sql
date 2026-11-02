-- 5단계: 자소서 의미 검색(설계 2.5·4.4). DB 확장 없음 — 벡터는 REAL[] 로 두고 Java 에서 정확 cosine(D11).
-- 쓰기 경로는 dirty·source_revision 만 표시하고, 스위퍼가 CAS 한 문장으로 조각을 교체한다.
ALTER TABLE self_introductions ADD COLUMN embedding_dirty BOOLEAN NOT NULL DEFAULT true;  -- 기존 행 전부 적재 대상
ALTER TABLE self_introductions ADD COLUMN source_revision BIGINT  NOT NULL DEFAULT 0;     -- 질문·답변이 바뀔 때마다 +1
CREATE INDEX self_introductions_embedding_dirty_idx ON self_introductions (updated_at) WHERE embedding_dirty;

CREATE TABLE self_introduction_chunks (
    owner_id             UUID         NOT NULL,
    self_introduction_id UUID         NOT NULL,
    part                 VARCHAR(8)   NOT NULL CHECK (part IN ('QUESTION','ANSWER')),
    chunk_no             INT          NOT NULL,
    text_hash            VARCHAR(64)  NOT NULL,      -- 바뀐 조각만 다시 계산한다(모델과 함께 비교)
    embedding_model      VARCHAR(100) NOT NULL,      -- 모델이 바뀌면 다른 모델의 벡터와 섞지 않는다
    embedding            REAL[]       NOT NULL,      -- 정규화된 벡터
    embedded_at          TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (self_introduction_id, part, chunk_no),
    CONSTRAINT self_introduction_chunks_dim_ck CHECK (cardinality(embedding) = 384),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX self_introduction_chunks_owner_idx ON self_introduction_chunks (owner_id);
