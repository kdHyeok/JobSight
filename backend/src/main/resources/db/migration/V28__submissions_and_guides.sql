-- 제출 기록과 작성 기준 — 설계 docs/02-design/features/experience-graph.design.md 2.5, 6.6, 6.7(제출·작성 기준 트랙).
-- 제출 기록은 고치지 않고 쌓는다. 확인 수준: LINKED(기록 당시 연결 자료) < USER_CONFIRMED(사용자가 확인한 제출본)
-- < RECEIPT_VERIFIED(접수 근거와 대조한 제출본). 상태 변경만으로 생긴 LINKED 를 제출본이라 부르지 않는다.

ALTER TABLE job_postings ADD CONSTRAINT job_postings_id_owner_key UNIQUE (id, owner_id);
ALTER TABLE attachments  ADD CONSTRAINT attachments_id_owner_key  UNIQUE (id, owner_id);

CREATE TABLE submissions (
    id           UUID PRIMARY KEY,
    owner_id     UUID        NOT NULL,
    posting_id   UUID        NOT NULL,
    level        VARCHAR(16) NOT NULL CHECK (level IN ('LINKED','USER_CONFIRMED','RECEIPT_VERIFIED')),
    content_hash VARCHAR(64) NOT NULL,          -- 스냅샷 내용의 SHA-256. 같은 내용을 다시 보내면 같은 기록을 돌려준다
    resumes      JSONB       NOT NULL,          -- [{resumeId, name, version, content}]
    self_intros  JSONB       NOT NULL,          -- [{id, question, answer, updatedAt, version, positionIds, experienceRevisions}]
    receipt      JSONB,                         -- RECEIPT_VERIFIED 의 근거 {excerpt, receiptNumber, attachmentIds}
    recorded_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT submissions_content_key UNIQUE (posting_id, content_hash, level),
    CONSTRAINT submissions_id_owner_key UNIQUE (id, owner_id),
    FOREIGN KEY (posting_id, owner_id) REFERENCES job_postings (id, owner_id) ON DELETE CASCADE
);
CREATE INDEX submissions_owner_idx ON submissions (owner_id, posting_id);

-- 스냅샷이 가리키는 첨부. 첨부 쪽 FK 는 지연 NO ACTION 이다 — 기록이 가리키는 첨부의 단독 삭제는 커밋에서 막히고,
-- 계정 삭제 연쇄(app_users → attachments, app_users → job_postings → submissions → 이 표)는 같은 트랜잭션에서 둘 다 사라져 통과한다.
CREATE TABLE submission_attachments (
    owner_id      UUID NOT NULL,
    submission_id UUID NOT NULL,
    attachment_id UUID NOT NULL,
    PRIMARY KEY (submission_id, attachment_id),
    FOREIGN KEY (submission_id, owner_id) REFERENCES submissions (id, owner_id) ON DELETE CASCADE,
    CONSTRAINT submission_attachments_attachment_fk
      FOREIGN KEY (attachment_id, owner_id) REFERENCES attachments (id, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX submission_attachments_attachment_idx ON submission_attachments (attachment_id);

CREATE TABLE writing_guides (                   -- 계정당 작성 기준 하나(Markdown). 선호 문체, 피할 표현, 성과 설명 범위
    owner_id   UUID PRIMARY KEY REFERENCES app_users (id) ON DELETE CASCADE,
    body       TEXT        NOT NULL,
    version    INT         NOT NULL DEFAULT 1,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE self_introductions ADD COLUMN writing_context JSONB;  -- MCP 로 저장할 때의 재현 정보(작성 기준 version, 쓴 경험 개정, 검색 context)
