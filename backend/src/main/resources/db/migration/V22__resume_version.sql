-- 이력서 동시 수정 보호. 저장할 때마다 오르고, 행 번호 수정과 문서 전체 교체는 이 값을 확인한다 — docs/resumes.md 불변 조건 8.
ALTER TABLE resumes ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
