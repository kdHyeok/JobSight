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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 임베딩 dirty 스위퍼(설계 4.4, D13·D14). 쓰기 경로는 dirty·source_revision 만 표시하고, 여기서 3초마다 따라잡는다.
 * ① dirty 행을 잠그지 않고 읽는다 ② 해시·모델이 같은 조각은 재사용하고 바뀐 조각만 embedder 로 계산한다(트랜잭션 밖)
 * ③ 행마다 짧은 트랜잭션: CAS 한 문장으로 dirty 를 내리고, 1 행일 때만 조각을 교체한다. 여러 행을 한 트랜잭션에서 잠그지 않는다.
 * embedder 가 죽어 있으면 아무것도 쓰지 않는다 — dirty 가 남아 재기동 뒤 따라잡는다(꺼진 기간을 건너뛰지 않는다).
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
        } catch (EmbedderClient.Unavailable unavailable) {
            log.debug("embedding sweep skipped: {}", unavailable.getMessage());
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
        for (SystemEmbeddingSweepRepository.DirtySelfIntro row : repository.dirtySelfIntros(BATCH)) {
            written += sweep(SystemEmbeddingSweepRepository.Table.SELF_INTRO, row.id(), row.ownerId(), row.sourceRevision(),
                    EmbeddingTexts.selfIntro(row.question(), row.answer()), model);
        }
        for (SystemEmbeddingSweepRepository.DirtyExperience row : repository.dirtyExperiences(BATCH)) {
            ExperienceFacts facts = row.facts() == null ? null : mapper.readValue(row.facts(), ExperienceFacts.class);
            ExperienceDossier dossier = row.dossier() == null ? null : mapper.readValue(row.dossier(), ExperienceDossier.class);
            written += sweep(SystemEmbeddingSweepRepository.Table.EXPERIENCE, row.id(), row.ownerId(), row.sourceRevision(),
                    EmbeddingTexts.experience(row.title(), row.memo(), row.skills(), facts, dossier), model);
        }
        return written;
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
