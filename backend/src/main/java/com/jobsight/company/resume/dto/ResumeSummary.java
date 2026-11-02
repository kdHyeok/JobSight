package com.jobsight.company.resume.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 목록용. content 없음. positionIds 는 자소서 편집기의 "어느 지원용?" 후보 칩
 * (연결 이력서들의 직무 전부, 설계 3절)을 이력서마다 다시 부르지 않고 그리려고 싣는다.
 * ResumeService.findAll 이 projection(ResumeListRow)에서 만든다 — 엔티티에서 만들면 content JSONB 를 읽는다(설계 1.3 P4).
 */
public record ResumeSummary(UUID id, String name, Instant createdAt, Instant updatedAt, List<UUID> positionIds) {
}
