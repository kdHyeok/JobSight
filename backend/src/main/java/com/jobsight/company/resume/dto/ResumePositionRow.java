package com.jobsight.company.resume.dto;

import java.util.UUID;

/**
 * 이력서 이름과 지원 직무 한 쌍(직무가 없으면 positionId null). content JSONB 를 읽지 않는다 —
 * MCP 자소서의 간접 출처용(ResumeRepository.findPositionRows, 설계 1.3 P4).
 */
public record ResumePositionRow(UUID resumeId, String name, UUID positionId) {
}
