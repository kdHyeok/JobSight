package com.jobsight.company.common;

/**
 * 관계(간선)를 누가 남겼나. 요청 주체가 정한다: 웹 세션은 USER, MCP 토큰은 항상 AI.
 * AI → USER 확정은 웹에서만 한다 — 설계 3절 규칙 2·3.
 */
public enum LinkSource {
    USER("확정"),
    AI("AI 기록");

    private final String trust;

    LinkSource(String trust) {
        this.trust = trust;
    }

    /** 웹과 MCP 가 같이 쓰는 표시값. 관계를 누가 확인했나만 뜻한다(성과 검증이 아니다). */
    public String trust() {
        return trust;
    }
}
