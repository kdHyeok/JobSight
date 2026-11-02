-- 5단계: 경험 의미 검색(설계 2.5·4.4). experiences.embedding_dirty·source_revision 은 V25 에 있다.
-- 경험 원본(사실·소재집·메모)을 480토큰 이하 조각으로 나눈다. 점수는 조각들의 max-sim.
CREATE TABLE experience_chunks (
    owner_id        UUID         NOT NULL,
    experience_id   UUID         NOT NULL,
    chunk_no        INT          NOT NULL,
    field           VARCHAR(40)  NOT NULL,           -- 조각의 출처 필드(facts.results 등). why 표시용
    text_hash       VARCHAR(64)  NOT NULL,
    embedding_model VARCHAR(100) NOT NULL,
    embedding       REAL[]       NOT NULL,
    embedded_at     TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (experience_id, chunk_no),
    CONSTRAINT experience_chunks_dim_ck CHECK (cardinality(embedding) = 384),
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX experience_chunks_owner_idx ON experience_chunks (owner_id);
