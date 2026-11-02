package com.jobsight.company.embedding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DENSE 신호(설계 4.2·4.3): 질의 벡터를 받아 계정의 조각 벡터와 Java 에서 정확 cosine(정규화 벡터라 내적)을 잰다.
 * 벡터는 REAL[] 로 두고 소유자로 거른 뒤 계정 전부를 읽는다(D11 — pgvector 를 들이지 않았다. 실측은 docs/experience-graph-search.md).
 * 모든 조회는 owner_id 조건이다. 질의 벡터는 트랜잭션 밖에서 받는다 — 2초 안에 못 받으면 빈 값이고 검색은 degraded 로 계속한다.
 */
@Component
public class DenseIndex {
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(2);

    /** 질의 벡터와 그것을 만든 모델. 같은 모델의 조각만 비교한다. */
    public record Query(String model, float[] vector) {
    }

    /** 경험 하나의 점수: 조각들 중 최고 cosine(max-sim)과 그 조각의 출처 필드. */
    public record Hit(double score, String field) {
    }

    /** 재현 정보(설계 6.7): 계정 경험 조각의 마지막 적재 시각과, 아직 적재되지 않은 경험이 있는지. */
    public record Status(String indexGeneration, boolean indexBehind) {
    }

    private final EmbedderClient client;
    private final JdbcTemplate jdbc;

    public DenseIndex(EmbedderClient client, JdbcTemplate jdbc) {
        this.client = client;
        this.jdbc = jdbc;
    }

    /** 자유 문장·문항·공고 본문을 질의로 임베딩한다. embedder 가 없거나 느리면 empty — 호출자는 degraded:["dense"] 를 단다. */
    public Optional<Query> query(String text) {
        if (text == null || text.isBlank() || !client.configured()) return Optional.empty();
        try {
            // 질의는 한 조각이다. 공고 다섯 칸을 다 이어 붙이면 길 수 있어 조각 상한만큼 앞부분만 쓴다.
            String input = text.strip();
            if (input.length() > EmbeddingTexts.MAX_CHARS) input = input.substring(0, EmbeddingTexts.MAX_CHARS);
            EmbedderClient.Embedded embedded = client.embed(EmbedderClient.Mode.query, List.of(input), QUERY_TIMEOUT);
            return Optional.of(new Query(embedded.model(), embedded.vectors().get(0)));
        } catch (EmbedderClient.Unavailable unavailable) {
            return Optional.empty();
        }
    }

    public Map<UUID, Hit> experiences(UUID owner, Query query) {
        Map<UUID, Hit> hits = new HashMap<>();
        jdbc.query("select experience_id, field, embedding from experience_chunks where owner_id = ? and embedding_model = ?",
                rs -> {
                    double score = dot(query.vector(), SystemEmbeddingSweepRepository.vector(rs, "embedding"));
                    hits.merge(rs.getObject("experience_id", UUID.class), new Hit(score, rs.getString("field")),
                            (a, b) -> a.score() >= b.score() ? a : b);
                }, owner, query.model());
        return hits;
    }

    /** 자소서 하나의 점수: 문항·답변 조각 중 최고 cosine. */
    public Map<UUID, Double> selfIntros(UUID owner, Query query) {
        Map<UUID, Double> scores = new HashMap<>();
        jdbc.query("select self_introduction_id, embedding from self_introduction_chunks where owner_id = ? and embedding_model = ?",
                rs -> {
                    scores.merge(rs.getObject("self_introduction_id", UUID.class),
                            dot(query.vector(), SystemEmbeddingSweepRepository.vector(rs, "embedding")), Math::max);
                }, owner, query.model());
        return scores;
    }

    public Status experienceStatus(UUID owner) {
        Timestamp last = jdbc.queryForObject("select max(embedded_at) from experience_chunks where owner_id = ?",
                Timestamp.class, owner);
        Boolean behind = jdbc.queryForObject("select exists (select 1 from experiences where owner_id = ? and embedding_dirty)",
                Boolean.class, owner);
        return new Status(last == null ? null : last.toInstant().toString(), Boolean.TRUE.equals(behind));
    }

    public Status selfIntroStatus(UUID owner) {
        Timestamp last = jdbc.queryForObject("select max(embedded_at) from self_introduction_chunks where owner_id = ?",
                Timestamp.class, owner);
        Boolean behind = jdbc.queryForObject(
                "select exists (select 1 from self_introductions where owner_id = ? and embedding_dirty)", Boolean.class, owner);
        return new Status(last == null ? null : last.toInstant().toString(), Boolean.TRUE.equals(behind));
    }

    static double dot(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double sum = 0;
        for (int i = 0; i < a.length; i++) sum += a[i] * b[i];
        return sum;
    }
}
