package com.jobsight.company.position.dto;

import java.util.UUID;

/**
 * 직무 이름과 소속 공고·기업만. MCP 자소서 출처처럼 이름만 그리면 되는 곳에서 직무 상세·EAGER 컬렉션을 읽지 않으려고 쓴다
 * (PositionRepository.findLabels, 설계 1.3 P4).
 */
public record PositionLabel(UUID id, String name, UUID postingId, UUID companyId, String companyName) {
}
