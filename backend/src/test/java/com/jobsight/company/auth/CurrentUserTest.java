package com.jobsight.company.auth;

import com.jobsight.company.mcp.McpOAuthConfig;
import com.jobsight.company.mcp.McpPrincipal;
import com.jobsight.company.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관계 출처(USER·AI)의 유일한 근거가 viaMcp() 다 — 설계 3절 규칙 2. 목 없이 실제 인증 객체로 본다.
 * 이 판정이 틀어지면 MCP 가 만든 연결이 조용히 "확정"으로 저장된다.
 */
class CurrentUserTest {
    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @Test
    void mcpTokenIsViaMcpAndOwnsItsUser() {
        UUID userId = UUID.randomUUID();
        authenticate(new McpPrincipal(userId, Set.copyOf(McpOAuthConfig.SCOPES)));

        assertThat(currentUser.viaMcp()).isTrue();
        assertThat(currentUser.id()).isEqualTo(userId);
    }

    @Test
    void webSessionIsNotViaMcp() {
        UUID userId = UUID.randomUUID();
        authenticate(new AppSessionUser(userId, "user@example.com", UserRole.USER));

        assertThat(currentUser.viaMcp()).isFalse();
        assertThat(currentUser.id()).isEqualTo(userId);
    }

    @Test
    void noOrAnonymousAuthenticationIsNotViaMcp() {
        assertThat(currentUser.viaMcp()).isFalse();

        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThat(currentUser.viaMcp()).isFalse();
    }
}
