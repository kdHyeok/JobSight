package com.jobsight.company.resume.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 이력서 목록 한 줄: 이력서×지원 직무(직무가 없으면 positionId null). content JSONB 를 읽지 않는다 —
 * ResumeRepository.findListRows, 설계 1.3 P4. ResumeService.findAll 이 이력서 단위로 묶어 ResumeSummary 로 만든다.
 */
public record ResumeListRow(UUID id, String name, Instant createdAt, Instant updatedAt, UUID positionId) {
}
