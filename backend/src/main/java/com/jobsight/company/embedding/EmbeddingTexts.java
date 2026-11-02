package com.jobsight.company.embedding;

import com.jobsight.company.experience.dto.ExperienceDossier;
import com.jobsight.company.experience.dto.ExperienceFacts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 임베딩할 조각 만들기(설계 4.4 길이 처리). e5 는 512토큰을 넘으면 오류 없이 자른다 — 그래서 미리 나눈다.
 * 토크나이저는 embedder 에만 있으므로 글자 수로 자른다. {@link #MAX_CHARS} 는 실제 토크나이저로 잰 값에서 정했다
 * (docs/experience-graph-search.md "길이 처리"). 그래도 넘치면 embedder 의 truncated 를 스위퍼가 WARN 으로 남긴다.
 */
public final class EmbeddingTexts {
    /**
     * 한 조각 글자 상한. e5-small-ko-v2 토크나이저 실측(2026-11-02)으로 한국어 서술은 글자당 0.50토큰(1200자 = 596토큰, 잘림),
     * 기술 이름 섞인 문장은 0.41토큰이었다. 800자 ≈ 400토큰이라 480토큰 목표 안에 20% 여유가 있다.
     */
    static final int MAX_CHARS = 800;

    /**
     * 조각 하나. part 는 자소서의 QUESTION·ANSWER(경험은 null), field 는 경험 조각의 출처 필드(why 표시용).
     * 문항은 문항↔문항 비교를 위해 query 모드, 답변·경험은 passage 모드다(설계 4.4 접두 규칙 표).
     */
    public record Chunk(String part, int no, String field, String text, EmbedderClient.Mode mode) {
        public String hash() {
            return sha256(mode + "\n" + text);
        }
    }

    private EmbeddingTexts() {
    }

    public static List<Chunk> selfIntro(String question, String answer) {
        List<Chunk> chunks = new ArrayList<>();
        int no = 0;
        for (String piece : split(question)) chunks.add(new Chunk("QUESTION", no++, null, piece, EmbedderClient.Mode.query));
        no = 0;
        for (String piece : split(answer)) chunks.add(new Chunk("ANSWER", no++, null, piece, EmbedderClient.Mode.passage));
        return chunks;
    }

    /**
     * 경험 원본의 검색 텍스트 — 키워드 검색(ExperienceSearch.candidate)과 같은 범위다: 제목·메모·기술, 사실의 기간·역할·한 일·결과·근거 인용,
     * 소재집 서술·표현 예시. 미확인·충돌·주의사항은 넣지 않는다(6.5). 필드마다 따로 나누고, 조각마다 제목을 머리에 붙인다
     * (제목 없는 "40분에서 6분으로" 조각은 어느 경험인지 모른다).
     */
    public static List<Chunk> experience(String title, String memo, List<String> skills, ExperienceFacts facts,
                                         ExperienceDossier dossier) {
        List<String[]> fields = new ArrayList<>();
        add(fields, "memo", Stream.of(memo, skills == null || skills.isEmpty() ? null : String.join(", ", skills)));
        if (facts != null) {
            add(fields, "facts.role", Stream.of(facts.period(), facts.role()));
            add(fields, "facts.actions", items(facts.actions()));
            add(fields, "facts.results", items(facts.results()));
            add(fields, "facts.evidence", facts.evidence() == null ? Stream.empty()
                    : facts.evidence().stream().filter(Objects::nonNull).map(ExperienceFacts.Evidence::quote));
        }
        if (dossier != null) {
            add(fields, "dossier.narrative", Stream.of(dossier.narrative()));
            add(fields, "dossier.phrasings", dossier.phrasings() == null ? Stream.empty() : dossier.phrasings().stream());
        }
        String head = title == null ? "" : title.strip();
        List<Chunk> chunks = new ArrayList<>();
        for (String[] field : fields) {
            for (String piece : split(field[1], MAX_CHARS - head.length() - 1)) {
                chunks.add(new Chunk(null, chunks.size(), field[0], (head + "\n" + piece).strip(), EmbedderClient.Mode.passage));
            }
        }
        // 사실이 빈 경험(가져오기 직후 등)도 제목 한 조각은 둔다 — 의미 검색에서 통째로 빠지지 않게.
        if (chunks.isEmpty()) chunks.add(new Chunk(null, 0, "title", head, EmbedderClient.Mode.passage));
        return chunks;
    }

    private static Stream<String> items(List<ExperienceFacts.Item> items) {
        return items == null ? Stream.empty() : items.stream().filter(Objects::nonNull).map(ExperienceFacts.Item::text);
    }

    private static void add(List<String[]> fields, String field, Stream<String> parts) {
        String text = String.join("\n", parts.filter(part -> part != null && !part.isBlank()).map(String::strip).toList());
        if (!text.isEmpty()) fields.add(new String[]{field, text});
    }

    static List<String> split(String text) {
        return split(text, MAX_CHARS);
    }

    /** 문단 → 줄 → 문장 경계를 지키며 max 글자 이하로 모은다. 경계가 없는 긴 덩어리만 글자로 자른다. 빈 텍스트는 조각 없음. */
    static List<String> split(String text, int max) {
        List<String> pieces = new ArrayList<>();
        if (text == null || text.isBlank()) return pieces;
        StringBuilder current = new StringBuilder();
        for (String unit : text.strip().split("(?<=[.!?。])\\s+|\\n+")) {
            String part = unit.strip();
            if (part.isEmpty()) continue;
            while (part.length() > max) {          // 경계 없는 긴 문장
                flush(pieces, current);
                pieces.add(part.substring(0, max));
                part = part.substring(max).strip();
            }
            if (current.length() > 0 && current.length() + 1 + part.length() > max) flush(pieces, current);
            if (current.length() > 0) current.append(' ');
            current.append(part);
        }
        flush(pieces, current);
        return pieces;
    }

    private static void flush(List<String> pieces, StringBuilder current) {
        if (current.length() > 0) pieces.add(current.toString());
        current.setLength(0);
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
