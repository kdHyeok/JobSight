package com.jobsight.company.experience;

import java.util.UUID;

/** 경험의 정체와 지금 개정만. 링커·목차·missing 이 facts JSONB 없이 쓴다. */
public record ExperienceHead(UUID id, ExperienceKind kind, String title, String titleKey, String startYm,
                             int revision, Integer reviewedRevision) {
    public boolean reviewed() {
        return reviewedRevision != null && reviewedRevision == revision;
    }

    /** 행의 시작 연월과 충돌하지 않나. 둘 중 하나가 비었거나, 연도만 적힌 쪽이 다른 쪽의 앞자리면 충돌이 아니다. */
    public boolean startCompatible(String rowStartYm) {
        String mine = KeyNormalizer.yearMonth(startYm);
        String theirs = KeyNormalizer.yearMonth(rowStartYm);
        return mine.isEmpty() || theirs.isEmpty() || mine.startsWith(theirs) || theirs.startsWith(mine);
    }
}
