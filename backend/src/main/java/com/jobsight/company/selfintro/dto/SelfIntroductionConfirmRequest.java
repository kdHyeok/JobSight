package com.jobsight.company.selfintro.dto;

import com.jobsight.company.selfintro.Competency;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 웹 전용 확정(✓). 이미 연결된 AI 기록을 USER 로 바꾼다. MCP 도구로 등록하지 않는다 — 설계 3절 규칙 3. */
public record SelfIntroductionConfirmRequest(
        @Size(max = 10, message = "지원 직무는 10개 이하여야 합니다.") List<UUID> positionIds,
        @Size(max = 3, message = "문항 유형은 3개 이하여야 합니다.") List<Competency> questionTypes
) {
}
