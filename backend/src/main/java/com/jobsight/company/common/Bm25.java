package com.jobsight.company.common;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BM25 유사 점수(LEXICAL 신호). 자소서 목록 q(SelfIntroductionService)와 experience_search 가 같이 쓴다.
 * 검색어는 단어 안 포함으로 맞춘다(형태소 분석 없음). 문서 빈도(df)는 검색어마다 한 번만 센다 —
 * 예전 판은 문서×검색어마다 다시 세어 O(N²·T) 였다(설계 4.2 LEXICAL "df 를 루프 밖으로 뺀 판").
 */
public final class Bm25 {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private Bm25() {
    }

    /** 검색어가 없거나 문서가 없으면 null(정렬하지 않음). 점수가 0 인 문서는 빠진다. */
    public static <T> Map<T, Double> scores(Map<T, List<String>> documents, List<String> terms) {
        if (terms.isEmpty() || documents.isEmpty()) return null;
        int n = documents.size();
        double averageLength = documents.values().stream().mapToInt(List::size).average().orElse(1);
        Map<String, Double> idf = new HashMap<>();
        for (String term : terms) {
            long df = documents.values().stream().filter(doc -> doc.stream().anyMatch(word -> word.contains(term))).count();
            idf.put(term, Math.log(1 + (n - df + 0.5) / (df + 0.5)));
        }
        Map<T, Double> scores = new HashMap<>();
        documents.forEach((key, words) -> {
            double score = 0;
            for (String term : terms) {
                long frequency = words.stream().filter(word -> word.contains(term)).count();
                if (frequency == 0) continue;
                double denominator = frequency + K1 * (1 - B + B * words.size() / averageLength);
                score += idf.get(term) * frequency * (K1 + 1) / denominator;
            }
            if (score > 0) scores.put(key, score);
        });
        return scores;
    }

    /** 중복 없는 검색어. */
    public static List<String> terms(String query) {
        return tokenize(query).stream().distinct().toList();
    }

    public static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) return List.of();
        Matcher matcher = WORD.matcher(value.toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (matcher.find()) words.add(matcher.group());
        return words;
    }
}
