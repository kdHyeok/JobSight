package com.jobsight.company.selfintro.dto;

import com.jobsight.company.selfintro.Competency;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 웹 전용 확정(✓). 이미 연결된 AI 기록을 USER 로 바꾼다. MCP 도구로 등록하지 않는다 — 설계 3절 규칙 3.
 * expectedVersion 은 화면이 연 문항의 version 이다. 보내면 저장과 같이 확인한다(다르면 409 SELF_INTRO_STALE) —
 * 확인 없이 version 을 올려 주면 화면이 그 값을 이어받아 옛 초안 저장이 남의 수정을 덮는다(불변 조건 12).
 */
public record SelfIntroductionConfirmRequest(
        @Size(max = 10, message = "지원 직무는 10개 이하여야 합니다.") List<@NotNull UUID> positionIds,
        @Size(max = 3, message = "문항 유형은 3개 이하여야 합니다.") List<@NotNull Competency> questionTypes,
        Long expectedVersion
) {
    public SelfIntroductionConfirmRequest(List<UUID> positionIds, List<Competency> questionTypes) {
        this(positionIds, questionTypes, null);
    }
}
