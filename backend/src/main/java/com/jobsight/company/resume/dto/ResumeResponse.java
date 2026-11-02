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
 * unlinkedWarnings 는 이번 저장으로 연결이 끊긴 경험 id 다(저장 전에는 행에 있었는데 사라짐). 웹은 토스트로 알린다(설계 6.2-9).
 * 읽기 응답에서는 늘 비어 있다.
 */
public record ResumeResponse(UUID id, String name, ResumeContent content, List<UUID> positionIds, long version,
                             Map<UUID, AttachmentResponse> attachments, Instant createdAt, Instant updatedAt,
                             List<UUID> unlinkedWarnings) {
    public static ResumeResponse of(Resume resume, ResumeContent content, Map<UUID, AttachmentResponse> attachments,
                                    List<UUID> unlinkedWarnings) {
        return new ResumeResponse(resume.getId(), resume.getName(), content, List.copyOf(resume.getPositionIds()),
                resume.getVersion(), attachments, resume.getCreatedAt(), resume.getUpdatedAt(), unlinkedWarnings);
    }
}
