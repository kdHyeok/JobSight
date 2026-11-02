package com.jobsight.company.selfintro;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 역량·문항 유형의 시스템 고정 코드. V23 competencies 표와 순서·값이 같아야 한다(CompetencyCatalogCheck).
 * MCP 입력 스키마에 enum 으로 나가므로 AI 가 없는 값을 지어낼 수 없다 — 설계 2.5.
 * pattern 은 저장하지 않은 문항의 유형을 추정하는 키워드다(설계 4.1). 코드 추가는 INSERT 한 줄과 여기 한 줄이다.
 */
public enum Competency {
    COLLABORATION("협업·팀워크", false, SearchMode.COMPETENCY, "협업|팀워크|팀\\s*프로젝트|팀원|동료"),
    COMMUNICATION("의사소통·설득", false, SearchMode.COMPETENCY, "의사\\s*소통|소통|커뮤니케이션|설득|협상|경청"),
    PROBLEM_SOLVING("문제 해결", false, SearchMode.COMPETENCY, "문제\\s*(를|을)?\\s*해결|해결\\s*(한|했|하|방안|과정)|난관|어려움"),
    CONFLICT("갈등 조정", false, SearchMode.COMPETENCY, "갈등|의견\\s*(차이|충돌|대립)|충돌|대립"),
    LEADERSHIP("리더십·주도성", false, SearchMode.COMPETENCY, "리더|주도|이끌|솔선|주인\\s*의식"),
    CHALLENGE("도전·목표 달성", false, SearchMode.COMPETENCY, "도전|목표\\s*(를|을)?\\s*(달성|세우|이루)|성취|한계"),
    FAILURE("실패와 극복", false, SearchMode.COMPETENCY, "실패|좌절|극복|실수"),
    LEARNING("학습·자기개발", false, SearchMode.COMPETENCY, "학습|자기\\s*[계개]발|배움|배운\\s*점|습득|공부"),
    // "기술하시오"·"기술해"의 '기술'은 동사라 세지 않는다.
    TECH_EXPERTISE("기술 전문성", false, SearchMode.COMPETENCY, "기술(?![하해])|전문성|전문\\s*지식|개발\\s*역량"),
    DATA_ANALYSIS("데이터·수리 분석", false, SearchMode.COMPETENCY, "데이터|분석|통계|수치|지표"),
    IMPROVEMENT("개선·창의", false, SearchMode.COMPETENCY, "개선|창의|혁신|아이디어|효율"),
    CUSTOMER("고객·사용자 지향", false, SearchMode.COMPETENCY, "고객|사용자|이용자"),
    ETHICS("원칙·직업윤리", false, SearchMode.COMPETENCY, "윤리|원칙|정직|도덕|청렴|규정"),
    MOTIVATION("지원동기", true, SearchMode.FIT, "지원\\s*(동기|이유|하게\\s*된|한\\s*이유)|선택한\\s*이유"),
    ASPIRATION("입사 후 포부", true, SearchMode.FIT, "입사\\s*후|포부|향후|앞으로의?\\s*(계획|목표)|비전|커리어\\s*(계획|목표)"),
    JOB_FIT("직무 이해·적합성", true, SearchMode.FIT, "직무\\s*(이해|적합|관련|수행|역량|에\\s*대한)|해당\\s*직무"),
    GROWTH("성장과정·가치관", true, SearchMode.SEMANTIC, "성장\\s*(과정|배경)|가치관|생활\\s*신조|좌우명|인생관"),
    PERSONALITY("성격 장단점", true, SearchMode.SEMANTIC, "성격|장단점|장점|단점|강점|약점");

    /** 문항 유형이 어떤 검색 신호를 쓰는지(설계 4.1). FIT 은 회사·직무에 묶인 답변이다. */
    public enum SearchMode { COMPETENCY, FIT, SEMANTIC }

    /** 정규식 추정 결과. confidence 는 전체 키워드 일치 중 이 유형의 몫(소수 둘째 자리). */
    public record Estimate(Competency code, double confidence) {}

    private final String label;
    private final boolean questionOnly;
    private final SearchMode searchMode;
    private final Pattern pattern;

    Competency(String label, boolean questionOnly, SearchMode searchMode, String pattern) {
        this.label = label;
        this.questionOnly = questionOnly;
        this.searchMode = searchMode;
        this.pattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
    }

    public String label() { return label; }
    public boolean questionOnly() { return questionOnly; }
    public SearchMode searchMode() { return searchMode; }

    /**
     * 키워드가 가장 많이 맞은 유형 상위 2개(다중 라벨). 맞는 것이 없으면 비운다 — 지어내지 않는다.
     * 일치 수가 같으면 표 순서(sort_order)를 따른다. 형태소 분석 없는 추정이라 trust 는 "추정"이다.
     */
    public static List<Estimate> estimate(String question) {
        if (question == null || question.isBlank()) return List.of();
        Map<Competency, Integer> hits = new EnumMap<>(Competency.class);
        for (Competency value : values()) {
            Matcher matcher = value.pattern.matcher(question);
            int count = 0;
            while (matcher.find()) count++;
            if (count > 0) hits.put(value, count);
        }
        int total = hits.values().stream().mapToInt(Integer::intValue).sum();
        return hits.entrySet().stream()
                .sorted(Map.Entry.<Competency, Integer>comparingByValue().reversed())
                .limit(2)
                .map(entry -> new Estimate(entry.getKey(), Math.round(100.0 * entry.getValue() / total) / 100.0))
                .toList();
    }
}
