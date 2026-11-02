-- 경험 원본·개정 이력·이력서 연결·자소서 작성 근거 (4단계 최소판) — 설계 docs/02-design/features/experience-graph.design.md 2.5, docs/experiences.md.
-- 사건·병합 계보·키워드 테이블은 4c(설계 6.10)라 여기 없다.
-- 경험 쪽을 가리키는 FK 셋(resume_experiences_experience_fk·_revision_fk, self_introduction_experiences_revision_fk)은
-- NO ACTION DEFERRABLE INITIALLY DEFERRED 다: 쓰이는 경험의 단독 삭제는 막고, 계정 삭제 연쇄(app_users CASCADE)는 통과시킨다.
-- 통과 여부는 실제 스키마로 ExperienceBankIntegrationTest.accountDeletionCascadesThroughDeferredForeignKeys 가 본다.

CREATE TABLE experiences (
    id                UUID PRIMARY KEY,
    owner_id          UUID         NOT NULL REFERENCES app_users (id) ON DELETE CASCADE,
    kind              VARCHAR(10)  NOT NULL CHECK (kind IN ('PROJECT','ACTIVITY','CAREER','TRAINING','OTHER')),
    title             VARCHAR(200) NOT NULL,          -- 경력은 회사명
    title_key         VARCHAR(200) NOT NULL,          -- Java KeyNormalizer 가 채운다(생성 컬럼 아님)
    start_ym          VARCHAR(200),                   -- 정체성 구분용. 사용자가 쓴 문자열 그대로
    memo              VARCHAR(2000),
    skills            TEXT[]       NOT NULL DEFAULT '{}', -- KeyNormalizer.skill 로 정규화한 기술 키, 중복 없음
    domains           TEXT[]       NOT NULL DEFAULT '{}',
    facts             JSONB        NOT NULL DEFAULT '{}', -- 경험 원본: 사실의 기준. 배열 항목은 안정 id 를 든다
    dossier           JSONB,                          -- 소재집: 사실에서 만든 작성 재료
    revision          INT          NOT NULL DEFAULT 1,  -- 사실 개정 번호. facts 해시가 바뀔 때만 +1
    dossier_based_on  INT,                            -- 소재집을 만든 사실 개정. revision 과 다르면 '소재집 갱신 필요'
    reviewed_revision INT,                            -- 사용자가 검토를 마친 개정. revision 보다 낮거나 없으면 'AI 기록'
    version           BIGINT       NOT NULL DEFAULT 0,  -- @Version
    embedding_dirty   BOOLEAN      NOT NULL DEFAULT true, -- 4단계는 표시만 한다. 5단계 스위퍼가 읽는다
    source_revision   BIGINT       NOT NULL DEFAULT 0,  -- 검색 텍스트가 바뀔 때마다 +1
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT experiences_id_owner_key UNIQUE (id, owner_id),
    CONSTRAINT experiences_facts_object_ck      CHECK (jsonb_typeof(facts) = 'object'),
    CONSTRAINT experiences_revision_ck          CHECK (revision >= 1),
    CONSTRAINT experiences_dossier_based_on_ck  CHECK (dossier_based_on  BETWEEN 1 AND revision),
    CONSTRAINT experiences_reviewed_revision_ck CHECK (reviewed_revision BETWEEN 1 AND revision)
);
CREATE INDEX experiences_owner_key_idx ON experiences (owner_id, kind, title_key);
CREATE INDEX experiences_embedding_dirty_idx ON experiences (updated_at) WHERE embedding_dirty;

CREATE TABLE experience_revisions (                 -- 개정 이력. 고치지 않고 쌓기만 한다
    seq           BIGINT      GENERATED ALWAYS AS IDENTITY, -- '재검토 필요'는 created_at 이 아니라 seq 로 판정한다
    owner_id      UUID        NOT NULL,
    experience_id UUID        NOT NULL,
    revision      INT         NOT NULL CHECK (revision >= 1),
    change_kind   VARCHAR(8)  NOT NULL CHECK (change_kind IN ('INIT','ADD','CORRECT')),
    facts_hash    VARCHAR(64) NOT NULL,             -- 정규화한 facts 의 SHA-256. 직전 개정과 같으면 개정을 만들지 않는다
    facts         JSONB       NOT NULL,
    dossier       JSONB,
    source        VARCHAR(4)  NOT NULL CHECK (source IN ('USER','AI')),
    created_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (experience_id, revision),
    CONSTRAINT experience_revisions_ref_key UNIQUE (experience_id, revision, owner_id),
    FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id) ON DELETE CASCADE
);

ALTER TABLE resumes ADD CONSTRAINT resumes_id_owner_key UNIQUE (id, owner_id);

CREATE TABLE resume_experiences (                   -- JSON 행 experienceId·experienceRevision 에서 파생. ResumeWriter 만 쓴다
    owner_id               UUID NOT NULL,
    resume_id              UUID NOT NULL,
    experience_id          UUID NOT NULL,
    based_on_experience_id UUID NOT NULL,           -- 4단계에서는 experience_id 와 같다(병합은 4c)
    based_on_revision      INT  NOT NULL CHECK (based_on_revision >= 1),
    PRIMARY KEY (resume_id, experience_id),
    FOREIGN KEY (resume_id, owner_id) REFERENCES resumes (id, owner_id) ON DELETE CASCADE,
    CONSTRAINT resume_experiences_experience_fk
      FOREIGN KEY (experience_id, owner_id) REFERENCES experiences (id, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT resume_experiences_revision_fk
      FOREIGN KEY (based_on_experience_id, based_on_revision, owner_id)
      REFERENCES experience_revisions (experience_id, revision, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX resume_experiences_experience_idx ON resume_experiences (experience_id);
CREATE INDEX resume_experiences_revision_idx   ON resume_experiences (based_on_experience_id, based_on_revision);

CREATE TABLE self_introduction_experiences (        -- 답변의 작성 근거 (경험 id, 개정) 쌍
    owner_id             UUID       NOT NULL,
    self_introduction_id UUID       NOT NULL,
    experience_id        UUID       NOT NULL,
    experience_revision  INT        NOT NULL CHECK (experience_revision >= 1),
    source               VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, experience_id),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE,
    CONSTRAINT self_introduction_experiences_revision_fk
      FOREIGN KEY (experience_id, experience_revision, owner_id)
      REFERENCES experience_revisions (experience_id, revision, owner_id)
      ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX self_introduction_experiences_experience_idx
    ON self_introduction_experiences (experience_id, experience_revision);
