package com.jobsight.company.selfintro.dto;

import com.jobsight.company.selfintro.Competency;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 생성 요청. 이력서(resumeIds) 또는 지원 직무(positionIds)가 1개 이상이어야 한다 — 설계 5.3, D4.
 * 수정 요청은 0개를 허용하므로 따로 둔다(SelfIntroductionUpdateRequest).
 */
public record SelfIntroductionCreateRequest(
        @Size(max = 30, message = "이력서는 30개 이하여야 합니다.") List<UUID> resumeIds,
        @Size(max = 10, message = "지원 직무는 10개 이하여야 합니다.") List<UUID> positionIds,
        @Size(max = 3, message = "문항 유형은 3개 이하여야 합니다.") List<Competency> questionTypes,
        @NotBlank(message = "질문은 필수입니다.")
        @Size(max = 1000, message = "질문은 1,000자 이하여야 합니다.") String question,
        @Size(max = 10000, message = "답변은 10,000자 이하여야 합니다.") String answer,
        /** 작성 근거 (경험 id, 개정) 쌍, 10개 이하(설계 6.8). MCP 로 남기면 'AI 기록'이다. */
        @Size(max = 10, message = "작성 근거 경험은 10개 이하여야 합니다.") List<ExperienceRef> experiences
) {
    public SelfIntroductionCreateRequest(List<UUID> resumeIds, List<UUID> positionIds, List<Competency> questionTypes,
                                         String question, String answer) {
        this(resumeIds, positionIds, questionTypes, question, answer, null);
    }

    @AssertTrue(message = "이력서 또는 지원 직무를 1개 이상 연결해야 합니다.")
    public boolean isLinked() {
        return (resumeIds != null && !resumeIds.isEmpty()) || (positionIds != null && !positionIds.isEmpty());
    }
}
