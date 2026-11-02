package com.jobsight.company.embedding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 시스템 배치 예외(설계 D13): 계정과 무관하게 dirty 행을 훑는 임베딩 스위퍼 전용 저장소. owner_id 조건 없이 id 로 읽는
 * 유일한 곳이라 이 패키지 밖에서 쓰지 않는다. 다루는 것은 dirty·source_revision 열과 조각 테이블뿐이고, 읽은 원문은
 * embedder 외로 내보내지 않는다. 조각을 쓸 때는 읽은 행의 owner_id 를 그대로 싣는다(복합 FK 가 소유자를 다시 확인한다).
 */
@Repository
class SystemEmbeddingSweepRepository {
    record DirtySelfIntro(UUID id, UUID ownerId, long sourceRevision, String question, String answer) {
    }

    record DirtyExperience(UUID id, UUID ownerId, long sourceRevision, String title, String memo, List<String> skills,
                           String facts, String dossier) {
    }

    /** 저장할 조각 하나와 그 벡터. */
    record Stored(EmbeddingTexts.Chunk chunk, float[] vector) {
    }

    private final JdbcTemplate jdbc;

    SystemEmbeddingSweepRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 잠그지 않고 읽는다. 그사이 원문이 바뀌면 claim 의 CAS 가 0 행이라 이 회차는 쓰지 않는다(4.4 ③). */
    List<DirtySelfIntro> dirtySelfIntros(int limit) {
        return jdbc.query("select id, owner_id, source_revision, question, answer from self_introductions "
                        + "where embedding_dirty order by updated_at limit ?",
                (rs, i) -> new DirtySelfIntro(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getLong("source_revision"), rs.getString("question"), rs.getString("answer")), limit);
    }

    List<DirtyExperience> dirtyExperiences(int limit) {
        return jdbc.query("select id, owner_id, source_revision, title, memo, skills, facts::text as facts, "
                        + "dossier::text as dossier from experiences where embedding_dirty order by updated_at limit ?",
                (rs, i) -> new DirtyExperience(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class),
                        rs.getLong("source_revision"), rs.getString("title"), rs.getString("memo"),
                        List.of((String[]) rs.getArray("skills").getArray()), rs.getString("facts"), rs.getString("dossier")),
                limit);
    }

    /** 해시가 같고 모델이 같은 기존 조각의 벡터 — 다시 계산하지 않는다. */
    Map<String, float[]> reusable(Table table, UUID id, String model) {
        Map<String, float[]> vectors = new HashMap<>();
        jdbc.query("select text_hash, embedding from " + table.chunks + " where " + table.key + " = ? and embedding_model = ?",
                rs -> {
                    vectors.put(rs.getString("text_hash"), vector(rs, "embedding"));
                }, id, model);
        return vectors;
    }

    /**
     * 행마다 따로 짧은 트랜잭션의 첫 문장(4.4 ③): 읽은 원문 버전 그대로일 때만 dirty 를 내린다. 1 이면 같은 트랜잭션에서 조각을
     * 교체하고, 0 이면 그사이 원문이 바뀌었거나 지워진 것이라 아무것도 쓰지 않는다. updated_at 은 비교하지 않는다.
     */
    boolean claim(Table table, UUID id, long sourceRevision) {
        return jdbc.update("update " + table.source + " set embedding_dirty = false where id = ? and source_revision = ?",
                id, sourceRevision) == 1;
    }

    void replace(Table table, UUID ownerId, UUID id, String model, List<Stored> chunks) {
        jdbc.update("delete from " + table.chunks + " where " + table.key + " = ?", id);
        Timestamp now = Timestamp.from(Instant.now());
        String sql = table == Table.SELF_INTRO
                ? "insert into self_introduction_chunks (owner_id, self_introduction_id, part, chunk_no, text_hash, "
                  + "embedding_model, embedding, embedded_at) values (?, ?, ?, ?, ?, ?, ?, ?)"
                : "insert into experience_chunks (owner_id, experience_id, field, chunk_no, text_hash, "
                  + "embedding_model, embedding, embedded_at) values (?, ?, ?, ?, ?, ?, ?, ?)";
        jdbc.batchUpdate(sql, chunks, chunks.size(), (ps, stored) -> {
            EmbeddingTexts.Chunk chunk = stored.chunk();
            ps.setObject(1, ownerId);
            ps.setObject(2, id);
            ps.setString(3, table == Table.SELF_INTRO ? chunk.part() : chunk.field());
            ps.setInt(4, chunk.no());
            ps.setString(5, chunk.hash());
            ps.setString(6, model);
            ps.setArray(7, ps.getConnection().createArrayOf("float4", boxed(stored.vector())));
            ps.setTimestamp(8, now);
        });
    }

    /** 모델을 바꾸면 다른 모델로 만든 조각을 가진 행을 dirty 로 되돌린다 — 스위퍼가 새 모델로 다시 채운다. */
    int markOtherModelsDirty(String model) {
        return jdbc.update("update self_introductions set embedding_dirty = true where id in "
                        + "(select self_introduction_id from self_introduction_chunks where embedding_model <> ?)", model)
                + jdbc.update("update experiences set embedding_dirty = true where id in "
                        + "(select experience_id from experience_chunks where embedding_model <> ?)", model);
    }

    enum Table {
        SELF_INTRO("self_introductions", "self_introduction_chunks", "self_introduction_id"),
        EXPERIENCE("experiences", "experience_chunks", "experience_id");

        final String source;
        final String chunks;
        final String key;

        Table(String source, String chunks, String key) {
            this.source = source;
            this.chunks = chunks;
            this.key = key;
        }
    }

    static float[] vector(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        Float[] boxed = (Float[]) array.getArray();
        float[] vector = new float[boxed.length];
        for (int i = 0; i < vector.length; i++) vector[i] = boxed[i];
        return vector;
    }

    private static Float[] boxed(float[] vector) {
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) boxed[i] = vector[i];
        return boxed;
    }
}
