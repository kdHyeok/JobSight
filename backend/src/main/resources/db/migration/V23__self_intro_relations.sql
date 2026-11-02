-- 자소서 직접 출처(written_for)와 문항 유형 — 설계 docs/02-design/features/experience-graph.design.md 2.5, 7절 2단계.
-- 간선에는 출처(source) 하나만 둔다: 웹 요청은 USER, MCP 요청은 AI. 확정(AI → USER)은 웹에서만 한다.

CREATE TABLE competencies (
    code          VARCHAR(40) PRIMARY KEY,
    name          VARCHAR(60) NOT NULL,
    question_only BOOLEAN     NOT NULL DEFAULT false,   -- true: 문항 유형으로만 쓴다
    search_mode   VARCHAR(10) NOT NULL CHECK (search_mode IN ('COMPETENCY','FIT','SEMANTIC')),
    sort_order    INT         NOT NULL
);
INSERT INTO competencies VALUES
 ('COLLABORATION','협업·팀워크',false,'COMPETENCY',10),     ('COMMUNICATION','의사소통·설득',false,'COMPETENCY',20),
 ('PROBLEM_SOLVING','문제 해결',false,'COMPETENCY',30),     ('CONFLICT','갈등 조정',false,'COMPETENCY',40),
 ('LEADERSHIP','리더십·주도성',false,'COMPETENCY',50),      ('CHALLENGE','도전·목표 달성',false,'COMPETENCY',60),
 ('FAILURE','실패와 극복',false,'COMPETENCY',70),           ('LEARNING','학습·자기개발',false,'COMPETENCY',80),
 ('TECH_EXPERTISE','기술 전문성',false,'COMPETENCY',90),    ('DATA_ANALYSIS','데이터·수리 분석',false,'COMPETENCY',100),
 ('IMPROVEMENT','개선·창의',false,'COMPETENCY',110),        ('CUSTOMER','고객·사용자 지향',false,'COMPETENCY',120),
 ('ETHICS','원칙·직업윤리',false,'COMPETENCY',130),
 ('MOTIVATION','지원동기',true,'FIT',200),                  ('ASPIRATION','입사 후 포부',true,'FIT',210),
 ('JOB_FIT','직무 이해·적합성',true,'FIT',220),             ('GROWTH','성장과정·가치관',true,'SEMANTIC',230),
 ('PERSONALITY','성격 장단점',true,'SEMANTIC',240);
-- Java enum Competency 와 같아야 한다. 기동 시 대조하고 다르면 기동 실패(CompetencyCatalogCheck).

-- 간선이 다른 계정의 노드를 잇지 못하게 (id, owner_id) 복합 FK 를 건다 — 설계 2.4.
ALTER TABLE positions          ADD CONSTRAINT positions_id_owner_key          UNIQUE (id, owner_id);
ALTER TABLE self_introductions ADD CONSTRAINT self_introductions_id_owner_key UNIQUE (id, owner_id);

CREATE TABLE self_introduction_positions (         -- written_for: 이 답변을 어느 지원용으로 썼나(직접 출처)
    owner_id             UUID       NOT NULL,
    self_introduction_id UUID       NOT NULL,
    position_id          UUID       NOT NULL,
    source               VARCHAR(4) NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, position_id),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE,
    FOREIGN KEY (position_id, owner_id)          REFERENCES positions (id, owner_id)          ON DELETE CASCADE
);
CREATE INDEX self_introduction_positions_position_idx ON self_introduction_positions (position_id);

CREATE TABLE self_introduction_competencies (      -- 문항 유형
    owner_id             UUID        NOT NULL,
    self_introduction_id UUID        NOT NULL,
    competency_code      VARCHAR(40) NOT NULL REFERENCES competencies (code),
    source               VARCHAR(4)  NOT NULL CHECK (source IN ('USER','AI')),
    PRIMARY KEY (self_introduction_id, competency_code),
    FOREIGN KEY (self_introduction_id, owner_id) REFERENCES self_introductions (id, owner_id) ON DELETE CASCADE
);
