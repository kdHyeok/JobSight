-- 성능 트랙 인덱스(설계 1.5, 1.3 P10).
-- CONCURRENTLY 를 쓰지 않는다: 표가 작아 잠깐의 잠금으로 충분하고, 쓰면 파일을 나눠야 한다.

-- MCP 요청마다 findByToken(…, ACCESS_TOKEN) 이 access_token_value = ? 로 찾는다(McpOAuthConfig). 리프레시도 같다.
-- 토큰 문자열이 길어 btree 행 크기 상한(약 2.7KB)을 넘을 수 있어 hash 다. 등호 검색만 하므로 충분하다.
-- 타입을 지정한 조회만 이 인덱스를 탄다. 타입 없는 revoke·introspect 는 인덱스 없는 컬럼까지 OR 로 묶어
-- seq scan 이 남을 수 있다 — 빈도가 낮아 허용한다. 합성 5,000행 EXPLAIN 실측은 docs/backend.md 「조회 정리」.
CREATE INDEX oauth2_authorization_access_token_idx  ON oauth2_authorization USING hash (access_token_value);
CREATE INDEX oauth2_authorization_refresh_token_idx ON oauth2_authorization USING hash (refresh_token_value);

-- ON DELETE 의 참조 쪽. 직무·참고 정보를 지울 때 SET NULL·CASCADE 대상 행을 표 전체를 읽지 않고 찾는다.
CREATE INDEX job_postings_target_position_idx
    ON job_postings (target_position_id) WHERE target_position_id IS NOT NULL;
CREATE INDEX reference_items_related_position_idx
    ON reference_items (related_position_id) WHERE related_position_id IS NOT NULL;
CREATE INDEX position_references_reference_idx ON position_references (reference_id);
