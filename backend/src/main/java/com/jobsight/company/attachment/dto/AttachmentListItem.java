package com.jobsight.company.attachment.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 이력서 목록의 [첨부 파일] 서랍 한 줄(설계 6.9). usedBy 는 삭제 판정과 같은 질의로 구한 사용처이고,
 * deletable 은 서버가 계산한다: 어디서도 쓰지 않고 올린 지 7일이 지났다. 서버 DELETE 는 7일을 보지 않는다 — 서랍만 거는 조건이다.
 */
public record AttachmentListItem(UUID id, String filename, String contentType, int size, Instant createdAt,
                                 List<UsedBy> usedBy, boolean deletable) {

    /** type: RESUME(이력서) · EXPERIENCE(경험의 지금 근거) · EXPERIENCE_REVISION(경험의 지난 개정) · SUBMISSION(제출 기록, name 은 공고 제목). */
    public record UsedBy(String type, UUID id, String name) {
    }
}
