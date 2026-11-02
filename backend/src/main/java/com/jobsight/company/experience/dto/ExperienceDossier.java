package com.jobsight.company.experience.dto;

import com.jobsight.company.selfintro.Competency;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 소재집 — 사실에서 만든 작성 재료(설계 6.5): 상세 서술, 문항 유형별 활용 포인트, 표현 예시, 주의사항.
 * 어느 사실 개정에서 만들었는지는 experiences.dossier_based_on 이 든다.
 */
public record ExperienceDossier(
        @Size(max = 20000) String narrative,
        @Size(max = 30) List<@Valid QuestionUse> questionUses,
        @Size(max = 30) List<@Size(max = 2000) String> phrasings,
        @Size(max = 30) List<@Size(max = 2000) String> cautions
) {
    public record QuestionUse(@NotNull Competency competency, @NotBlank @Size(max = 2000) String point) {
    }
}
