package com.jobsight.company.embedding;

import com.jobsight.company.experience.dto.ExperienceDossier;
import com.jobsight.company.experience.dto.ExperienceFacts;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 설계 4.4 길이 처리와 접두 규칙: 조각은 상한 안이고, 문항은 query·답변과 경험은 passage 모드다. */
class EmbeddingTextsTest {

    @Test
    void splitKeepsSentenceBoundariesAndCutsOnlyUnbrokenText() {
        String sentence = "가".repeat(300) + ".";
        List<String> pieces = EmbeddingTexts.split(sentence + " " + sentence + " " + sentence, 700);
        assertThat(pieces).hasSize(2).allMatch(piece -> piece.length() <= 700);
        assertThat(pieces.get(0)).isEqualTo(sentence + " " + sentence);

        List<String> unbroken = EmbeddingTexts.split("나".repeat(2000), 800);
        assertThat(unbroken).extracting(String::length).containsExactly(800, 800, 400);
        assertThat(EmbeddingTexts.split("  ", 800)).isEmpty();
        assertThat(EmbeddingTexts.split(null, 800)).isEmpty();
    }

    @Test
    void selfIntroQuestionIsQueryModeAndLongAnswerIsSplit() {
        String answer = ("협업 과정에서 갈등을 조율했습니다. ").repeat(100);
        List<EmbeddingTexts.Chunk> chunks = EmbeddingTexts.selfIntro("지원 동기", answer);
        assertThat(chunks.get(0)).extracting(EmbeddingTexts.Chunk::part, EmbeddingTexts.Chunk::no, EmbeddingTexts.Chunk::mode)
                .containsExactly("QUESTION", 0, EmbedderClient.Mode.query);
        List<EmbeddingTexts.Chunk> answers = chunks.subList(1, chunks.size());
        assertThat(answers).hasSizeGreaterThan(2)
                .allMatch(chunk -> chunk.part().equals("ANSWER") && chunk.mode() == EmbedderClient.Mode.passage
                        && chunk.text().length() <= EmbeddingTexts.MAX_CHARS);
        assertThat(answers).extracting(EmbeddingTexts.Chunk::no).startsWith(0, 1, 2);
        assertThat(EmbeddingTexts.selfIntro("질문만", null)).hasSize(1);
    }

    @Test
    void experienceChunksPerFieldWithTitleHeadAndNoCautions() {
        ExperienceFacts facts = new ExperienceFacts("2025.03~2025.06", "백엔드 담당", null,
                List.of(new ExperienceFacts.Item("a1", "Kafka 파티션을 재설계했다", null)),
                List.of(new ExperienceFacts.Item("r1", "배치 40분 → 6분", null)), null,
                List.of(new ExperienceFacts.Item("u1", "미확인 사실", null)), null);
        ExperienceDossier dossier = new ExperienceDossier("정산 배치 이야기", null, null, List.of("주의: 과장 금지"));
        List<EmbeddingTexts.Chunk> chunks = EmbeddingTexts.experience("결제 정산 배치", "메모", List.of("kafka"), facts, dossier);

        assertThat(chunks).extracting(EmbeddingTexts.Chunk::field, EmbeddingTexts.Chunk::no).containsExactly(
                tuple("memo", 0), tuple("facts.role", 1), tuple("facts.actions", 2), tuple("facts.results", 3),
                tuple("dossier.narrative", 4));
        assertThat(chunks).allMatch(chunk -> chunk.text().startsWith("결제 정산 배치\n")
                && chunk.mode() == EmbedderClient.Mode.passage);
        assertThat(chunks).noneMatch(chunk -> chunk.text().contains("미확인") || chunk.text().contains("주의"));
        // 사실이 비어도 제목 한 조각은 있다 — 검색 대상에서 빠지지 않는다.
        assertThat(EmbeddingTexts.experience("제목만", null, List.of(), null, null))
                .extracting(EmbeddingTexts.Chunk::text).containsExactly("제목만");
    }

    @Test
    void hashDependsOnModeAndText() {
        EmbeddingTexts.Chunk query = new EmbeddingTexts.Chunk("QUESTION", 0, null, "같은 글", EmbedderClient.Mode.query);
        EmbeddingTexts.Chunk passage = new EmbeddingTexts.Chunk("ANSWER", 0, null, "같은 글", EmbedderClient.Mode.passage);
        assertThat(query.hash()).hasSize(64).isNotEqualTo(passage.hash());
        assertThat(query.hash()).isEqualTo(new EmbeddingTexts.Chunk("QUESTION", 3, null, "같은 글", EmbedderClient.Mode.query).hash());
    }
}
