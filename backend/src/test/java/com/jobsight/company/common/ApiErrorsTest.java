package com.jobsight.company.common;

import com.jobsight.company.company.CompanyController;
import com.jobsight.company.company.CompanyService;
import com.jobsight.company.mcp.McpController;
import com.jobsight.company.mcp.McpPrincipal;
import com.jobsight.company.mcp.McpTools;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionSystemException;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 같은 예외는 REST 와 MCP 에서 같은 코드다 — docs/mcp-plugin.md, docs/decisions.md.
 * 한쪽만 고치면 같은 실패가 화면과 AI 에 다른 코드로 보인다.
 */
class ApiErrorsTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    private final CompanyService companies = mock(CompanyService.class);
    private final McpTools tools = mock(McpTools.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final McpController mcp = new McpController(tools, null, mapper);
    private MockMvc rest;

    @BeforeEach
    void setUp() {
        rest = MockMvcBuilders.standaloneSetup(new CompanyController(companies))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new McpPrincipal(UUID.randomUUID(), Set.of()), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    /** 잠금 대기 초과(55P03)·교착(40P01)은 커밋 시점에 다른 예외로 감싸져 와도 원인 체인으로 알아본다. */
    static Stream<Arguments> cases() {
        return Stream.of(
                arguments(new ResourceNotFoundException(ID), 404, "NOT_FOUND"),
                arguments(new ApiRuleException(HttpStatus.CONFLICT, "RESUME_STALE", "다시 읽으세요"), 409, "RESUME_STALE"),
                arguments(new ObjectOptimisticLockingFailureException(Object.class, ID), 409, "STALE_WRITE"),
                arguments(new CannotAcquireLockException("lock timeout"), 409, "BUSY"),
                arguments(new TransactionSystemException("commit failed",
                        new RuntimeException(new SQLException("canceling statement due to lock timeout", "55P03"))), 409, "BUSY"),
                arguments(new JpaSystemException(
                        new RuntimeException(new SQLException("deadlock detected", "40P01"))), 409, "BUSY"),
                arguments(new AccessDeniedException("scope"), 403, "FORBIDDEN"),
                arguments(new IllegalStateException("boom"), 500, "INTERNAL_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void restAndMcpUseTheSameCode(RuntimeException thrown, int status, String code) throws Exception {
        given(companies.findById(ID)).willThrow(thrown);
        rest.perform(get(ApiPaths.COMPANIES + "/" + ID))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.code").value(code));

        given(tools.call(any(), any(), any())).willThrow(thrown);
        var body = mapper.valueToTree(mcp.rpc(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "company_get", "arguments", Map.of())), null).getBody());
        assertThat(body.at("/result/isError").asBoolean()).isTrue();
        assertThat(body.at("/result/content/0/text").asText()).startsWith(code + ": ");
    }
}
