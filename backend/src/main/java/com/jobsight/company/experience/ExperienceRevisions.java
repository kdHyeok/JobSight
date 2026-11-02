package com.jobsight.company.experience;

import com.jobsight.company.common.LinkSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * experience_revisions 읽기·쓰기. 개정 이력은 고치지 않고 쌓기만 한다(설계 2.5). JPA 엔티티로 두지 않는 이유:
 * seq 가 DB 의 GENERATED ALWAYS IDENTITY 이고 복합 키라, 넣고 읽는 두 문장이면 되는 일에 매핑이 더 크다.
 */
@Component
public class ExperienceRevisions {
    public enum ChangeKind { INIT, ADD, CORRECT, NONE }

    /** seq 는 순서 비교용이다 — '재검토 필요'는 created_at 이 아니라 seq 로 판정한다(6.1). */
    public record Rev(UUID experienceId, int revision, ChangeKind changeKind, long seq, LinkSource source,
                      Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public ExperienceRevisions(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 호출자가 경험 행을 먼저 flush 한다(개정 → 경험 FK 는 지연이 아니다). */
    void insert(Experience experience, ChangeKind kind, String factsHash, LinkSource source) {
        jdbc.update("insert into experience_revisions (owner_id, experience_id, revision, change_kind, facts_hash, facts, "
                        + "dossier, source, created_at) values (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)",
                experience.getOwnerId(), experience.getId(), experience.getRevision(), kind.name(), factsHash,
                experience.getFacts(), experience.getDossier(), source.name(), Timestamp.from(Instant.now()));
    }

    /** 직전 개정의 facts 해시. 같은 facts 재저장이면 개정을 만들지 않는다. */
    String latestHash(Experience experience) {
        return jdbc.queryForObject("select facts_hash from experience_revisions where experience_id = ? and owner_id = ? "
                + "and revision = ?", String.class, experience.getId(), experience.getOwnerId(), experience.getRevision());
    }

    /** 소유자 조건으로 경험들의 개정 이력(개정 오름차순). 빈 목록이면 조회하지 않는다. */
    public Map<UUID, List<Rev>> of(UUID ownerId, Collection<UUID> experienceIds) {
        Map<UUID, List<Rev>> byExperience = new LinkedHashMap<>();
        if (experienceIds.isEmpty()) return byExperience;
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement("select experience_id, revision, change_kind, seq, "
                    + "source, created_at from experience_revisions where owner_id = ? and experience_id = any(?) "
                    + "order by experience_id, revision");
            Array ids = connection.createArrayOf("uuid", experienceIds.toArray());
            statement.setObject(1, ownerId);
            statement.setArray(2, ids);
            return statement;
        }, rs -> {
            UUID id = rs.getObject(1, UUID.class);
            byExperience.computeIfAbsent(id, key -> new ArrayList<>()).add(new Rev(id, rs.getInt(2),
                    ChangeKind.valueOf(rs.getString(3)), rs.getLong(4), LinkSource.valueOf(rs.getString(5)),
                    rs.getTimestamp(6).toInstant()));
        });
        return byExperience;
    }

    /**
     * 참조한 개정보다 seq 가 큰 CORRECT 개정이 있으면 '재검토 필요'(6.1). ADD 개정·근거만 바뀐 개정·표현 차이에는 붙지 않는다.
     */
    public static boolean needsReview(List<Rev> revisions, int referenced) {
        long seq = revisions.stream().filter(rev -> rev.revision() == referenced).mapToLong(Rev::seq).findFirst()
                .orElse(Long.MAX_VALUE);
        return revisions.stream().anyMatch(rev -> rev.changeKind() == ChangeKind.CORRECT && rev.seq() > seq);
    }
}
