package com.jobsight.company.selfintro.dto;

import com.jobsight.company.selfintro.Competency;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * MCP self_intro_list 응답(설계 5.3). 웹 REST 목록과 다른 모양이다 — 미리보기·limit·출처·trust 를 싣는다.
 * fallback 이 "indirect" 면 이 직무·공고·기업용 직접 출처가 0건이라 연결 이력서로 찾은 "출처 미상" 답변을 준 것이다.
 * complete 가 false 면 total 중 limit 만큼만 실었다(조용히 자르지 않는다).
 */
public record SelfIntroductionList(String fallback, int total, boolean complete, List<Item> results) {

    /**
     * 목록에서는 answerPreview(300자)만, self_intro_get 에서는 answer 전문만 채운다.
     * sourceStatus 는 "직접 출처"(written_for 가 있음) 또는 "출처 미상".
     */
    public record Item(UUID id, String question, String answerPreview, String answer, int answerLength,
                       Instant updatedAt, Double score, List<String> matchedTerms, String sourceStatus,
                       Sources sources, List<QuestionType> questionTypes, List<UsedFor> alreadyUsedFor) {}

    /** direct: 이 답변을 어느 지원용으로 썼나(written_for). indirect: 연결 이력서가 지원하는 직무(추정). */
    public record Sources(List<Direct> direct, List<Indirect> indirect) {}

    /** trust: "확정"(웹에서 사용자가 연결) 또는 "AI 기록"(MCP 가 남김, 웹에서 확정 전). 성과 검증이 아니다. */
    public record Direct(UUID positionId, String position, UUID companyId, String company, String trust) {}

    public record Indirect(UUID resumeId, String resume, UUID positionId, String position, String company) {}

    /** from: USER·AI(저장된 태그) 또는 REGEX(정규식 추정, confidence 있음). trust: 확정·AI 기록·추정. */
    public record QuestionType(Competency code, String name, Double confidence, String from, String trust) {}

    /**
     * 이 답변을 이미 낸 회사·직무. sameCompany 는 필터로 회사가 정해졌을 때만 true/false 다.
     * otherCompanySpecific 이 true 면 다른 회사용 지원동기·포부·직무 이해 답변이다 — 본문을 재사용하지 않는다.
     */
    public record UsedFor(UUID companyId, String company, String position, Boolean sameCompany, boolean otherCompanySpecific) {}
}
