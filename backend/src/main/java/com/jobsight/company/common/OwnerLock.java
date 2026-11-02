package com.jobsight.company.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 계정 단위 직렬화 — 설계 1.4 T9 잠금 규약. 경험 관계를 바꾸는 쓰기(이력서 쓰기·경험 쓰기·자소서 작성 근거)는
 * 트랜잭션 맨 앞에서 이것을 부른 뒤 이력서(id 오름차순) → 경험 순서로만 행을 잠근다.
 * <ul>
 *   <li>{@code SET LOCAL lock_timeout} <b>바로 다음 문장</b>에서 권고 잠금을 잡는다. lock_timeout 은 권고 잠금 대기에도
 *   걸리므로 대기에 상한이 생긴다(넘으면 55P03 → 공용 변환이 409 BUSY). 순서를 바꾸면 연결 풀이 마를 수 있다(R36).</li>
 *   <li>키는 이름공간 'exp-graph:' 를 붙인 해시다. 다른 용도의 권고 잠금과 겹치지 않는다. 해시 충돌은 두 계정을 잠깐 줄 세울 뿐이다.</li>
 *   <li>app_users 행을 잠그지 않는다 — 로그인 쓰기와 엉키지 않게.</li>
 * </ul>
 * 트랜잭션 밖에서 부르면 잠금이 바로 풀려 의미가 없으므로 MANDATORY 다.
 */
@Component
public class OwnerLock {
    private final JdbcTemplate jdbc;

    public OwnerLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void acquire(UUID ownerId) {
        jdbc.execute("set local lock_timeout = '3s'");
        jdbc.query("select pg_advisory_xact_lock(hashtextextended('exp-graph:' || ?::text, 0))",
                rs -> null, ownerId);
    }
}
