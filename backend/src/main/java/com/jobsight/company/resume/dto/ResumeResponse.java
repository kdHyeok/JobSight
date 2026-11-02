package com.jobsight.company.resume.dto;

import com.jobsight.company.attachment.dto.AttachmentResponse;
import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeContent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * attachments 는 content 가 가리키는 내 첨부의 메타(id → 파일명·형식·크기)다. 편집기가 첨부 칸마다 메타를
 * 다시 부르지 않게 함께 싣는다. 남의 것·지워진 id 는 빠진다 — docs/attachments.md.
 */
public record ResumeResponse(UUID id, String name, ResumeContent content, List<UUID> positionIds, long version,
                             Map<UUID, AttachmentResponse> attachments, Instant createdAt, Instant updatedAt) {
    public static ResumeResponse of(Resume resume, ResumeContent content, Map<UUID, AttachmentResponse> attachments) {
        return new ResumeResponse(resume.getId(), resume.getName(), content, List.copyOf(resume.getPositionIds()),
                resume.getVersion(), attachments, resume.getCreatedAt(), resume.getUpdatedAt());
    }
}
