package com.jobsight.company.resume.dto;

import com.jobsight.company.resume.ResumeContent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * MCP resume_update 입력 — 웹 PUT(ResumeRequest)과 같고 두 가지만 다르다(설계 5.3).
 * removedExperienceIds: 경험이 연결된 행을 지우려면 그 경험 id 를 모두 적는다. 적지 않은 연결 행이 사라지면 409 LINKED_ROWS_MISSING.
 * refreshExperienceIds(웹 "원본으로 갱신")는 받지 않는다 — 개정을 올리는 MCP 길은 resume_add_experiences 뿐이다.
 */
public record ResumeAgentUpdateRequest(
        @NotBlank(message = "이력서 이름은 필수입니다.")
        @Size(max = 120, message = "이력서 이름은 120자 이하여야 합니다.")
        String name,
        @Valid ResumeContent content,
        @Size(max = 30, message = "연결 직무는 30개 이하여야 합니다.")
        List<UUID> positionIds,
        Long expectedVersion,
        @Size(max = 50, message = "지울 경험은 50개 이하여야 합니다.")
        List<UUID> removedExperienceIds
) {
    public ResumeRequest toRequest() {
        return new ResumeRequest(name, content, positionIds, expectedVersion);
    }
}
