package com.jobsight.company.position.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 직무 선택지 요약(GET /api/positions?view=summary, 설계 1.3 P6). 이력서 편집기의 직무 고르기가 쓰는 값만 싣는다 —
 * 이름·소속 공고/기업·수정 시각. 직무 상세·스택·참고 정보는 PositionResponse(기본 목록)에 있다.
 */
public record PositionOption(UUID id, String name, UUID postingId, String postingTitle, UUID companyId,
                             String companyName, Instant updatedAt) {
}
