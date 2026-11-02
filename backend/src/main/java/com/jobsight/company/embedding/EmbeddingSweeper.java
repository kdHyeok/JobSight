package com.jobsight.company.embedding;

import com.jobsight.company.experience.dto.ExperienceDossier;
import com.jobsight.company.experience.dto.ExperienceFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * 임베딩 dirty 스위퍼(설계 4.4, D13·D14). 쓰기 경로는 dirty·source_revision 만 표시하고, 여기서 3초마다 따라잡는다.
 * ① dirty 행을 잠그지 않고 읽는다 ② 해시·모델이 같은 조각은 재사용하고 바뀐 조각만 embedder 로 계산한다(트랜잭션 밖)
 * ③ 행마다 짧은 트랜잭션: CAS 한 문장으로 dirty 를 내리고, 1 행일 때만 조각을 교체한다. 여러 행을 한 트랜잭션에서 잠그지 않는다.
 * embedder 가 죽어 있으면 아무것도 쓰지 않는다 — dirty 가 남아 재기동 뒤 따라잡는다(꺼진 기간을 건너뛰지 않는다).
 * 실패는 행 단위로 가둔다: 연결 실패·타임아웃(embedder 장애)만 회차를 멈추고, 그 행만의 실패(embedder 거부, 원문 JSON 파싱,
 * 저장 제약 위반)는 그 행을 지수 간격으로 미뤄 두고 다음 행으로 간다. 오래된 행 하나가 뒤의 모든 행을 막지 않게 한다.
 */
@Component
public class EmbeddingSweeper {
    private static final Logger log = LoggerFactory.getLogger(EmbeddingSweeper.class);
    static final int BATCH = 32;
    private static final int PER_CALL = 64;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final EmbedderClient client;
    private final SystemEmbeddingSweepRepository repository;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;
    private final boolean scheduledEnabled;
    private String checkedModel;
    /** 실패한 행 → 연속 실패 수와 다음 시도 시각. 메모리에만 둔다 — 재기동하면 한 번 다시 시도한다. */
    // ponytail: 지워진 독 행의 항목은 재기동까지 남는다(독 행 수만큼). 많아지면 DB 열(embedding_failed_at)로 옮긴다.
    private final Map<String, Backoff> failing = new ConcurrentHashMap<>();
    private int outages;

    private record Backoff(int failures, Instant retryAt) {
    }

    public EmbeddingSweeper(EmbedderClient client, SystemEmbeddingSweepRepository repository,
                            TransactionTemplate transaction, ObjectMapper mapper,
                            @Value("${app.embedder.sweep:true}") boolean scheduledEnabled) {
        this.scheduledEnabled = scheduledEnabled;
        this.client = client;
        this.repository = repository;
        this.transaction = transaction;
        this.mapper = mapper;
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 3000)
    public void scheduled() {
        if (!scheduledEnabled || !client.configured()) return;
        try {
            sweepOnce();
            if (outages > 0) log.info("embedder is back after {} skipped sweeps", outages);
            outages = 0;
        } catch (EmbedderClient.Unavailable unavailable) {
            // 3초마다 같은 줄을 찍지 않는다: 첫 회와 약 5분(100회)마다만 warn.
            if (outages++ % 100 == 0) {
                log.warn("embedding sweep skipped ({} in a row): {}", outages, unavailable.getMessage());
            }
        } catch (RuntimeException failure) {
            log.warn("embedding sweep failed", failure);
        }
    }

    /** 한 회차. 반환은 조각을 교체한 행 수(테스트·측정용). embedder 가 없으면 Unavailable. */
    public int sweepOnce() {
        String model = client.model();
        if (!model.equals(checkedModel)) {
            int marked = repository.markOtherModelsDirty(model);
            if (marked > 0) log.info("embedding model is {}: {} rows marked for re-embedding", model, marked);
            checkedModel = model;
        }
        int written = 0;
        // 미뤄 둔 행만큼 더 읽어 걸러 낸다 — 미룬 행이 앞에 있어도 한 회차에 BATCH 개를 처리한다.
        Instant now = Instant.now();
        for (SystemEmbeddingSweepRepository.DirtySelfIntro row : repository.dirtySelfIntros(BATCH + failing.size())
                .stream().filter(row -> ready(SystemEmbeddingSweepRepository.Table.SELF_INTRO, row.id(), now)).limit(BATCH).toList()) {
            written += isolated(SystemEmbeddingSweepRepository.Table.SELF_INTRO, row.id(), () -> sweep(
                    SystemEmbeddingSweepRepository.Table.SELF_INTRO, row.id(), row.ownerId(), row.sourceRevision(),
                    EmbeddingTexts.selfIntro(row.question(), row.answer()), model));
        }
        for (SystemEmbeddingSweepRepository.DirtyExperience row : repository.dirtyExperiences(BATCH + failing.size())
                .stream().filter(row -> ready(SystemEmbeddingSweepRepository.Table.EXPERIENCE, row.id(), now)).limit(BATCH).toList()) {
            written += isolated(SystemEmbeddingSweepRepository.Table.EXPERIENCE, row.id(), () -> {
                ExperienceFacts facts = row.facts() == null ? null : mapper.readValue(row.facts(), ExperienceFacts.class);
                ExperienceDossier dossier = row.dossier() == null ? null : mapper.readValue(row.dossier(), ExperienceDossier.class);
                return sweep(SystemEmbeddingSweepRepository.Table.EXPERIENCE, row.id(), row.ownerId(), row.sourceRevision(),
                        EmbeddingTexts.experience(row.title(), row.memo(), row.skills(), facts, dossier), model);
            });
        }
        return written;
    }

    private boolean ready(SystemEmbeddingSweepRepository.Table table, UUID id, Instant now) {
        Backoff backoff = failing.get(table + ":" + id);
        return backoff == null || !backoff.retryAt().isAfter(now);
    }

    /**
     * 행 하나를 처리한다. embedder 장애(연결·타임아웃·모델 변경)는 회차를 멈추게 그대로 던지고, 그 밖의 실패는 이 행만 미룬다
     * (6초, 12초, … 최대 1시간). 로그에는 표·id·원인 종류만 남기고 원문은 남기지 않는다.
     */
    private int isolated(SystemEmbeddingSweepRepository.Table table, UUID id, IntSupplier work) {
        String key = table + ":" + id;
        try {
            int written = work.getAsInt();
            failing.remove(key);
            return written;
        } catch (EmbedderClient.Rejected rejected) {
            postpone(key, table, id, rejected);
        } catch (EmbedderClient.Unavailable outage) {
            throw outage;
        } catch (RuntimeException failure) {
            postpone(key, table, id, failure);
        }
        return 0;
    }

    private void postpone(String key, SystemEmbeddingSweepRepository.Table table, UUID id, RuntimeException failure) {
        Backoff previous = failing.get(key);
        int failures = previous == null ? 1 : previous.failures() + 1;
        Duration delay = Duration.ofSeconds(Math.min(3L << Math.min(failures, 11), 3600));
        failing.put(key, new Backoff(failures, Instant.now().plus(delay)));
        log.warn("embedding row failed table={} id={} failures={} retryIn={}s cause={}: {}", table, id, failures,
                delay.toSeconds(), failure.getClass().getSimpleName(), failure.getMessage());
    }

    private int sweep(SystemEmbeddingSweepRepository.Table table, UUID id, UUID ownerId, long sourceRevision,
                      List<EmbeddingTexts.Chunk> chunks, String model) {
        Map<String, float[]> reusable = repository.reusable(table, id, model);
        List<SystemEmbeddingSweepRepository.Stored> stored = new ArrayList<>();
        Map<EmbedderClient.Mode, List<EmbeddingTexts.Chunk>> missing = new EnumMap<>(EmbedderClient.Mode.class);
        for (EmbeddingTexts.Chunk chunk : chunks) {
            float[] vector = reusable.get(chunk.hash());
            if (vector != null) stored.add(new SystemEmbeddingSweepRepository.Stored(chunk, vector));
            else missing.computeIfAbsent(chunk.mode(), mode -> new ArrayList<>()).add(chunk);
        }
        int truncated = 0;
        for (Map.Entry<EmbedderClient.Mode, List<EmbeddingTexts.Chunk>> entry : missing.entrySet()) {
            List<EmbeddingTexts.Chunk> pending = entry.getValue();
            for (int from = 0; from < pending.size(); from += PER_CALL) {
                List<EmbeddingTexts.Chunk> part = pending.subList(from, Math.min(from + PER_CALL, pending.size()));
                EmbedderClient.Embedded embedded = client.embed(entry.getKey(),
                        part.stream().map(EmbeddingTexts.Chunk::text).toList(), TIMEOUT);
                if (!model.equals(embedded.model())) {
                    checkedModel = null;   // 회차 중에 embedder 모델이 바뀌었다. 다음 회차에 처음부터
                    throw new EmbedderClient.Unavailable("embedder model changed to " + embedded.model(), null);
                }
                for (int i = 0; i < part.size(); i++) {
                    stored.add(new SystemEmbeddingSweepRepository.Stored(part.get(i), embedded.vectors().get(i)));
                    if (i < embedded.truncated().size() && embedded.truncated().get(i)) truncated++;
                }
            }
        }
        // 본문은 남기지 않는다. 잘린 조각이 생기면 EmbeddingTexts.MAX_CHARS 를 다시 잰다.
        if (truncated > 0) log.warn("embedding truncated table={} id={} chunks={}", table, id, truncated);
        Boolean written = transaction.execute(status -> {
            if (!repository.claim(table, id, sourceRevision)) return false;
            repository.replace(table, ownerId, id, model, stored);
            return true;
        });
        return Boolean.TRUE.equals(written) ? 1 : 0;
    }
}
