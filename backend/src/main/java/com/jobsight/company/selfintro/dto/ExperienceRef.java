package com.jobsight.company.selfintro.dto;

import java.util.UUID;

/**
 * 자소서 작성 근거 한 쌍 — 이 답변을 쓸 때 본 경험과 그 개정(설계 6.8). 두 키 모두 필수다(빠지거나 개정이 1 미만이면
 * 400 EXPERIENCE_REFERENCE_REQUIRED). 서버는 받은 쌍을 그대로 저장·조회하고 지금 개정으로 다시 계산하지 않는다.
 */
public record ExperienceRef(UUID experienceId, Integer experienceRevision) {
}
