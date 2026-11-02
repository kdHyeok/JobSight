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
 * version 은 다음 수정의 expectedVersion 이다(불변 조건 12). 쓰기 응답에는 저장 뒤의 새 값이 실린다.
 */
public record SelfIntroductionResponse(UUID id, List<UUID> resumeIds, String question, String answer,
                                       Instant createdAt, Instant updatedAt,
                                       List<PositionLinkView> positionLinks, List<QuestionTypeView> questionTypes,
                                       long version, List<ExperienceLinkView> experiences) {
    public record PositionLinkView(UUID positionId, LinkSource source) {}
    /** 작성 근거 (경험 id, 개정) 쌍과 출처. 저장된 쌍 그대로다(지금 개정으로 다시 계산하지 않는다, 설계 6.8). */
    public record ExperienceLinkView(UUID experienceId, int experienceRevision, LinkSource source) {}
    public record QuestionTypeView(Competency code, LinkSource source) {}

    public static SelfIntroductionResponse of(SelfIntroduction value) {
        return new SelfIntroductionResponse(value.getId(), value.getResumeIds().stream().sorted().toList(), value.getQuestion(), value.getAnswer(),
                value.getCreatedAt(), value.getUpdatedAt(),
                value.getPositionLinks().stream().map(link -> new PositionLinkView(link.positionId(), link.source()))
                        .sorted(Comparator.comparing(PositionLinkView::positionId)).toList(),
                value.getQuestionTypes().stream().map(link -> new QuestionTypeView(link.code(), link.source()))
                        .sorted(Comparator.comparing(QuestionTypeView::code)).toList(),
                value.getVersion(),
                value.getExperienceLinks().stream()
                        .map(link -> new ExperienceLinkView(link.experienceId(), link.experienceRevision(), link.source()))
                        .sorted(Comparator.comparing(ExperienceLinkView::experienceId)).toList());
    }
}
