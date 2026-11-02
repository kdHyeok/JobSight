package com.jobsight.company.submission.dto;

import com.jobsight.company.submission.SubmissionService;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 제출 기록 한 건의 요약. resumes 는 [{resumeId, name, version}], selfIntros 는 [{id, question}] 이다 — 이력서 본문과
 * 답변 원문은 스냅샷에만 있고 목록에 싣지 않는다. receipt 는 RECEIPT_VERIFIED 의 {excerpt, receiptNumber, attachmentIds}.
 * LINKED 는 "기록 당시 연결 자료"이지 제출본이 아니다.
 */
public record SubmissionResponse(UUID id, SubmissionService.Level level, Instant recordedAt, JsonNode resumes,
                                 JsonNode selfIntros, List<UUID> attachmentIds, JsonNode receipt) {
}
