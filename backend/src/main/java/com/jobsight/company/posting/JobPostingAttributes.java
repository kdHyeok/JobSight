package com.jobsight.company.posting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 정규화가 끝난 공고 속성. 생성·수정이 공통으로 쓴다. 직무는 별도 테이블이라 여기 없다. steps 가 null 이면 기존 절차 유지. */
public record JobPostingAttributes(
        UUID companyId,
        String title,
        String postingUrl,
        EmploymentType employmentType,
        Instant deadlineAt,
        ApplicationStatus status,
        String qualifications,
        String memo,
        List<RecruitmentStep> steps
) {
}
