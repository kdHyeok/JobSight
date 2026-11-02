package com.jobsight.company.submission.dto;

import com.jobsight.company.submission.SubmissionService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 웹의 제출본 확인(설계 6.6). level 은 USER_CONFIRMED(실제로 낸 것을 골라 확인) 또는 RECEIPT_VERIFIED(접수 근거를 붙임).
 * 고른 이력서·자기소개의 지금 내용을 스냅샷으로 남긴다. 접수 근거(발췌·번호·근거 파일)는 RECEIPT_VERIFIED 에만 붙인다.
 */
public record SubmissionRequest(
        @NotNull(message = "확인 수준은 필수입니다.") SubmissionService.Level level,
        @Size(max = 30, message = "이력서는 30개 이하여야 합니다.") List<UUID> resumeIds,
        @Size(max = 50, message = "자기소개는 50개 이하여야 합니다.") List<UUID> selfIntroIds,
        @Size(max = 2000, message = "접수 메일 발췌는 2,000자 이하여야 합니다.") String receiptExcerpt,
        @Size(max = 100, message = "접수 번호는 100자 이하여야 합니다.") String receiptNumber,
        @Size(max = 10, message = "근거 파일은 10개 이하여야 합니다.") List<UUID> receiptAttachmentIds
) {
}
