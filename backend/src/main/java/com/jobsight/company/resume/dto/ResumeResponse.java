package com.jobsight.company.resume.dto;

import com.jobsight.company.resume.Resume;
import com.jobsight.company.resume.ResumeContent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResumeResponse(UUID id, String name, ResumeContent content, List<UUID> positionIds, long version,
                             Instant createdAt, Instant updatedAt) {
    public static ResumeResponse of(Resume resume, ResumeContent content) {
        return new ResumeResponse(resume.getId(), resume.getName(), content, List.copyOf(resume.getPositionIds()),
                resume.getVersion(), resume.getCreatedAt(), resume.getUpdatedAt());
    }
}
