package com.jobsight.company.posting.dto;

import com.jobsight.company.posting.ApplicationStatus;
import com.jobsight.company.posting.EmploymentType;
import com.jobsight.company.posting.JobPosting;
import com.jobsight.company.position.dto.PositionSummary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * D-day 는 담지 않는다. 프런트가 deadlineAt 을 사용자 시계로 계산한다.
 * positions 는 요약(id·name·team…)만. 직무 상세는 /api/positions/{id}.
 */
public record JobPostingResponse(
        UUID id,
        UUID companyId,
        String companyName,
        String title,
        String postingUrl,
        EmploymentType employmentType,
        Instant deadlineAt,
        ApplicationStatus status,
        String qualifications,
        String memo,
        UUID targetPositionId,
        List<StepResponse> steps,
        List<PositionSummary> positions,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        Long linkedSelfIntroCount
) {
    public static JobPostingResponse of(JobPosting posting, String companyName, List<PositionSummary> positions) {
        List<StepResponse> steps = IntStream.range(0, posting.getSteps().size())
                .mapToObj(i -> StepResponse.of(i, posting.getSteps().get(i)))
                .toList();
        return new JobPostingResponse(
                posting.getId(),
                posting.getCompanyId(),
                companyName,
                posting.getTitle(),
                posting.getPostingUrl(),
                posting.getEmploymentType(),
                posting.getDeadlineAt(),
                posting.getStatus(),
                posting.getQualifications(),
                posting.getMemo(),
                posting.getTargetPositionId(),
                steps,
                positions,
                posting.isArchived(),
                posting.getCreatedAt(),
                posting.getUpdatedAt(),
                null
        );
    }

    /**
     * 상세 조회(GET·posting_get)에서만 채운다 — 이 공고를 지우면 소속 직무 전부의 직접 출처 연결이 CASCADE 로 사라지는
     * 내 자소서 수. 삭제 확인창이 보여 준다. 목록·수정 응답은 null.
     */
    public JobPostingResponse withLinkedSelfIntroCount(long count) {
        return new JobPostingResponse(id, companyId, companyName, title, postingUrl, employmentType, deadlineAt, status,
                qualifications, memo, targetPositionId, steps, positions, archived, createdAt, updatedAt, count);
    }
}
