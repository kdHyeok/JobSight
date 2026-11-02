package com.jobsight.company.resume.dto;

import com.jobsight.company.resume.Resume;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 목록용. content 없음. positionIds 는 자소서 편집기의 "어느 지원용?" 후보 칩
 * (연결 이력서들의 직무 전부, 설계 3절)을 이력서마다 다시 부르지 않고 그리려고 싣는다.
 */
public record ResumeSummary(UUID id, String name, Instant createdAt, Instant updatedAt, List<UUID> positionIds) {
    public static ResumeSummary of(Resume resume) {
        return new ResumeSummary(resume.getId(), resume.getName(), resume.getCreatedAt(), resume.getUpdatedAt(),
                resume.getPositionIds().stream().sorted().toList());
    }
}
