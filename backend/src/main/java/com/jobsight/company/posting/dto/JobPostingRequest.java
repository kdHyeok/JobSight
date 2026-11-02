package com.jobsight.company.posting.dto;

import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 공고 생성·수정 요청. positions 와 steps 는 null 이면 유지, 배열이면 그 목록으로 맞춘다([] 는 비움).
 * deadlineAt 은 UTC Instant. 프런트가 datetime-local 값을 toISOString() 으로 변환해 보낸다.
 */
public record JobPostingRequest(
        /** 있으면 그 기업. 없으면 companyName 으로 찾거나 만든다. */
        UUID companyId,

        @Size(max = 120, message = "고용회사는 120자 이하여야 합니다.")
        String companyName,

        @NotBlank(message = "모집 부문은 필수입니다.")
        @Size(max = 160, message = "모집 부문은 160자 이하여야 합니다.")
        String title,

        @Size(max = 500, message = "공고 링크는 500자 이하여야 합니다.")
        @Pattern(regexp = "^$|https?://.+", message = "공고 링크는 http 또는 https URL이어야 합니다.")
        String postingUrl,

        @NotNull(message = "고용형태는 필수입니다.")
        EmploymentType employmentType,

        /** 비우면 상시채용으로 저장된다. */
        Instant deadlineAt,

        @NotNull(message = "지원 상태는 필수입니다.")
        ApplicationStatus status,

        @Size(max = 5000, message = "지원자격은 5,000자 이하여야 합니다.")
        String qualifications,

        @Size(max = 5000, message = "메모는 5,000자 이하여야 합니다.")
        String memo,

        /** 생성 때 비우거나 수정 때 [] 면 title 이름의 직무 하나만 남는다. 수정 때 null 이면 기존 직무 유지. */
        @Size(max = 30, message = "직무는 30개 이하여야 합니다.")
        List<@Valid PositionNameRequest> positions,

        @Size(max = 20, message = "절차는 20단계 이하여야 합니다.")
        List<@Valid StepRequest> steps
) {
}
