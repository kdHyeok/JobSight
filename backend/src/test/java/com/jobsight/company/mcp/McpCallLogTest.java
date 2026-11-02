package com.jobsight.company.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** MCP 호출 로그 한 줄의 이름·대상 id 는 본문 없이, 로그 줄을 깨지 못하게 남는다(설계 7절 운영 메모). */
class McpCallLogTest {
    @Test
    void targetIsFirstUuidArgumentOnly() {
        UUID id = UUID.randomUUID();
        assertThat(McpController.target(Map.of("id", id.toString(), "request", Map.of("answer", "비밀")))).isEqualTo(id.toString());
        assertThat(McpController.target(Map.of("positionId", id.toString()))).isEqualTo(id.toString());
        assertThat(McpController.target(Map.of("q", "검색어"))).isEqualTo("-");
        assertThat(McpController.target(Map.of("id", "not-a-uuid\nforged"))).isEqualTo("-");
    }

    @Test
    void toolNameCannotBreakTheLine() {
        assertThat(McpController.toolName("experience_search")).isEqualTo("experience_search");
        assertThat(McpController.toolName("x\ny=1")).isEqualTo("x?y?1");
        assertThat(McpController.toolName(null)).isEqualTo("-");
        assertThat(McpController.toolName("a".repeat(100))).hasSize(64);
    }
}
