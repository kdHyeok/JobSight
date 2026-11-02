package com.jobsight.company.experience;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 제목 키·기술 키 정규화와 별칭 시드 사전 — 설계 2.5(3단계 산출물, 10.7 R42).
 * 3단계는 묶음 키 (kind, title_key, 시작 연월)와 experience_search 의 요구 키에 쓰고,
 * 4단계는 같은 클래스로 experiences.title_key·skills·domains 를 채운다. DB 함수·생성 컬럼을 두지 않는다(P-13).
 * 사전은 일반 기술 어휘다. 사용자 어휘가 아니므로 내 자료에 없는 기술도 요구로 뽑힌다(설계 4.2 ①).
 */
public final class KeyNormalizer {
    /** 글자·숫자와 기술 이름에 쓰이는 + # 만 남긴다. 공백·점·하이픈 등은 지운다("Spring Boot" → "springboot"). */
    private static final Pattern NOT_KEY = Pattern.compile("[^\\p{L}\\p{N}+#]");
    /** 기술 목록 칸의 구분자 — 설계 3절 "경험 → 기술"(, / · |) 에 줄바꿈·세미콜론을 더했다. */
    private static final Pattern LIST_SEPARATOR = Pattern.compile("[,/·|;\\n]");
    /** 한글 조사가 붙어도("Kafka를") 영문 토큰을 꺼낸다. */
    private static final Pattern ASCII_TOKEN = Pattern.compile("[a-z0-9][a-z0-9+#.]*");
    private static final Pattern YEAR_MONTH = Pattern.compile("(\\d{4})(?:\\D{1,3}(\\d{1,2}))?");

    /** 별칭(정규화한 키) → 정본 키. 계정별 별칭 편집은 4c keywords 뒤(6단계). */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("k8s", "kubernetes"), Map.entry("쿠버네티스", "kubernetes"),
            Map.entry("js", "javascript"), Map.entry("자바스크립트", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("타입스크립트", "typescript"),
            Map.entry("py", "python"), Map.entry("파이썬", "python"),
            Map.entry("golang", "go"), Map.entry("자바", "java"),
            Map.entry("스프링", "spring"), Map.entry("스프링부트", "springboot"),
            Map.entry("reactjs", "react"), Map.entry("리액트", "react"),
            Map.entry("vuejs", "vue"), Map.entry("node", "nodejs"),
            Map.entry("postgres", "postgresql"), Map.entry("포스트그레스", "postgresql"),
            Map.entry("mongo", "mongodb"), Map.entry("몽고db", "mongodb"),
            Map.entry("도커", "docker"), Map.entry("카프카", "kafka"), Map.entry("레디스", "redis"),
            Map.entry("엘라스틱서치", "elasticsearch"), Map.entry("젠킨스", "jenkins"),
            Map.entry("amazonwebservices", "aws"), Map.entry("코틀린", "kotlin"),
            Map.entry("텐서플로", "tensorflow"), Map.entry("파이토치", "pytorch"));

    /** 서버 사전 추출(요구를 넘기지 않았을 때)이 알아보는 정본 키. 별칭은 위 표로 정본이 된다. */
    private static final Set<String> CANONICAL = Stream.concat(ALIASES.values().stream(), Stream.of(
            "java", "kotlin", "spring", "springboot", "jpa", "python", "django", "fastapi", "flask", "go", "rust",
            "c", "c++", "c#", "swift", "android", "ios", "flutter", "javascript", "typescript", "react", "vue",
            "nextjs", "nodejs", "graphql", "sql", "mysql", "postgresql", "oracle", "mongodb", "redis", "kafka",
            "rabbitmq", "elasticsearch", "hadoop", "spark", "airflow", "docker", "kubernetes", "terraform",
            "jenkins", "git", "linux", "aws", "gcp", "azure", "tableau", "pytorch", "tensorflow", "msa", "grpc"))
            .collect(Collectors.toUnmodifiableSet());

    /** 한글 별칭은 조사가 붙어 토큰으로 못 꺼내므로 본문 부분 문자열로 찾는다. 긴 것부터. */
    private static final List<String> KOREAN_TERMS = ALIASES.keySet().stream()
            .filter(term -> term.codePoints().anyMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HANGUL))
            .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
            .toList();

    private KeyNormalizer() {
    }

    /** NFKC → 소문자 → 기호·공백 제거. 별칭은 적용하지 않는다(제목 키). */
    public static String key(String value) {
        if (value == null) return "";
        String folded = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NOT_KEY.matcher(folded).replaceAll("");
    }

    /** 기술 키: key() 뒤 별칭 시드 사전을 적용한다. */
    public static String skill(String value) {
        String key = key(value);
        return ALIASES.getOrDefault(key, key);
    }

    /** 기술 목록 칸("Java, Spring Boot / k8s")을 기술 키 집합으로. 순서 유지, 중복·빈 값 없음. */
    public static Set<String> skills(String list) {
        if (list == null) return Set.of();
        Set<String> keys = new LinkedHashSet<>();
        for (String part : LIST_SEPARATOR.split(list)) {
            String key = skill(part);
            if (!key.isEmpty()) keys.add(key);
        }
        return keys;
    }

    /**
     * 본문에 언급된 키: 영문 토큰(별칭 적용), 이웃한 두 토큰을 붙인 키("spring boot" → springboot),
     * 본문에 들어 있는 한글 사전어. 짧은 영문 별칭(js, go)은 토큰이 같을 때만 맞춰 "going" 같은 오탐을 피한다.
     */
    public static Set<String> mentions(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = ASCII_TOKEN.matcher(folded);
        String previous = null;
        int previousEnd = -1;
        while (matcher.find()) {
            String token = key(matcher.group());
            if (token.isEmpty()) continue;
            keys.add(ALIASES.getOrDefault(token, token));
            // 공백으로만 떨어진 이웃 토큰을 붙인다. 쉼표·한글로 나뉜 것끼리는 붙이지 않는다.
            if (previous != null && folded.substring(previousEnd, matcher.start()).isBlank()) {
                String joined = previous + token;
                keys.add(ALIASES.getOrDefault(joined, joined));
            }
            previous = token;
            previousEnd = matcher.end();
        }
        // 긴 한글 사전어부터 맞추고 지운다 — "자바스크립트" 안의 "자바" 를 java 로 또 세지 않게.
        String compact = key(folded);
        for (String term : KOREAN_TERMS) {
            if (!compact.contains(term)) continue;
            keys.add(ALIASES.getOrDefault(term, term));
            compact = compact.replace(term, " ");
        }
        return keys;
    }

    /** 사전에 있는 키만. 요구를 클라이언트가 넘기지 않았을 때 서버가 공고 칸에서 뽑는다(REQUIREMENTS_PARTIAL). */
    public static Set<String> dictionaryTerms(String text) {
        return mentions(text).stream().filter(CANONICAL::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** "2024.03"·"2024.3"·"2024년 3월" → "202403", 연도만 있으면 "2024", 없으면 "". 묶음 키의 시작 연월 숫자. */
    public static String yearMonth(String value) {
        if (value == null) return "";
        Matcher matcher = YEAR_MONTH.matcher(value);
        if (!matcher.find()) return "";
        String month = matcher.group(2);
        return month == null ? matcher.group(1) : matcher.group(1) + (month.length() == 1 ? "0" + month : month);
    }

}
