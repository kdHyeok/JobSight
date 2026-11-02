package com.jobsight.company.resume.dto;

import com.jobsight.company.resume.ResumeContent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 생성·저장 공용. 생성 때 content 가 null 이면 빈 문서다.
 * 저장(수정) 때는 content·positionIds·content 안의 빠진 섹션이 null 이면 유지, [] 면 비움이고
 * expectedVersion 이 필수다 — docs/resumes.md 불변 조건 8·9. 생성은 expectedVersion 을 보지 않는다.
 */
public record ResumeRequest(
        @NotBlank(message = "이력서 이름은 필수입니다.")
        @Size(max = 120, message = "이력서 이름은 120자 이하여야 합니다.")
        String name,
        @Valid ResumeContent content,
        @Size(max = 30, message = "연결 직무는 30개 이하여야 합니다.")
        List<UUID> positionIds,
        Long expectedVersion
) {
}
