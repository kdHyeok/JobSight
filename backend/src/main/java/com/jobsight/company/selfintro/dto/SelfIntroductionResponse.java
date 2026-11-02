package com.jobsight.company.selfintro.dto;

import com.jobsight.company.common.LinkSource;
import com.jobsight.company.selfintro.Competency;
import com.jobsight.company.selfintro.SelfIntroduction;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 웹 REST 응답. 목록도 이것이다 — 웹은 목록의 answer 를 그대로 PUT 하므로 답변은 항상 전문이다(설계 5.1).
 * positionLinks·questionTypes 는 편집기의 직무 칩과 "AI 기록" 확정에 쓰는 뒤에 붙인 필드다.
 */
public record SelfIntroductionResponse(UUID id, List<UUID> resumeIds, String question, String answer,
                                       Instant createdAt, Instant updatedAt,
                                       List<PositionLinkView> positionLinks, List<QuestionTypeView> questionTypes) {
    public record PositionLinkView(UUID positionId, LinkSource source) {}
    public record QuestionTypeView(Competency code, LinkSource source) {}

    public static SelfIntroductionResponse of(SelfIntroduction value) {
        return new SelfIntroductionResponse(value.getId(), value.getResumeIds().stream().sorted().toList(), value.getQuestion(), value.getAnswer(),
                value.getCreatedAt(), value.getUpdatedAt(),
                value.getPositionLinks().stream().map(link -> new PositionLinkView(link.positionId(), link.source()))
                        .sorted(Comparator.comparing(PositionLinkView::positionId)).toList(),
                value.getQuestionTypes().stream().map(link -> new QuestionTypeView(link.code(), link.source()))
                        .sorted(Comparator.comparing(QuestionTypeView::code)).toList());
    }
}
