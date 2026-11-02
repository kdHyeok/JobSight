package com.jobsight.company.resume.dto;

import java.util.Map;
import java.util.UUID;

/**
 * MCP 행 도구의 응답. 이력서 전체 대신 다음 호출에 필요한 것만 싣는다 — docs/resumes.md.
 * version 은 다음 행 도구의 expectedVersion 으로 그대로 쓴다. 삭제면 index·row 가 null 이다.
 */
public record ResumeRowResult(UUID id, long version, String section, int count, Integer index,
                              Map<String, Object> row) {
}
