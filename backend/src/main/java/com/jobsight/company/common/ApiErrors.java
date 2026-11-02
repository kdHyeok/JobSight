package com.jobsight.company.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import java.sql.SQLException;
import java.util.Set;

/**
 * 공통 도메인 예외 → 상태·코드·메시지. REST(GlobalExceptionHandler)와 MCP(McpController)가 같이 쓴다.
 * 한쪽만 고치면 같은 실패가 화면과 AI 에 다른 코드로 보인다 — docs/mcp-plugin.md, docs/decisions.md.
 * 진입점 전용 오류(REST 검증·본문 파싱, MCP INVALID_ARGUMENT·JSON-RPC)는 각자 둔다.
 */
public final class ApiErrors {
    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    /** PostgreSQL 잠금 대기 초과(lock_timeout)·교착. */
    private static final Set<String> BUSY_STATES = Set.of("55P03", "40P01");
    /** 원인 체인이 순환해도 멈추도록 둔 상한. 실제 체인은 서너 단계다. */
    private static final int MAX_CAUSE_DEPTH = 20;

    private ApiErrors() {
    }

    public static ApiError translate(Throwable exception) {
        if (exception instanceof ResourceNotFoundException) {
            return error(HttpStatus.NOT_FOUND, "NOT_FOUND", exception.getMessage());
        }
        if (exception instanceof ApiRuleException rule) {
            return error(rule.getStatus(), rule.getCode(), rule.getMessage());
        }
        // @Version 충돌. 서비스가 잠금과 expectedVersion 으로 먼저 막으므로 보통은 오지 않지만,
        // 오면 다시 읽고 고치면 되는 상황이라 500 이 아니라 409 다(docs/resumes.md 불변 조건 8).
        if (exception instanceof OptimisticLockingFailureException) {
            return error(HttpStatus.CONFLICT, "STALE_WRITE", "다른 곳에서 먼저 저장했습니다. 다시 읽고 고칠 대상을 확인한 뒤 보내세요.");
        }
        if (isLockContention(exception)) {
            return error(HttpStatus.CONFLICT, "BUSY", "다른 저장과 겹쳤습니다. 잠시 뒤 다시 시도하세요.");
        }
        if (exception instanceof AccessDeniedException) {
            return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "권한이 없습니다. 계정 권한(MCP 는 OAuth scope 도)을 확인하세요.");
        }
        log.error("Unhandled API error", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "서버 오류가 발생했습니다. 다시 시도하기 전에 조회해서 반영 여부를 확인하세요.");
    }

    /**
     * 잠금을 못 얻었거나 교착으로 끊긴 실패. 쿼리 중이면 Spring 이 PessimisticLockingFailureException 으로 바꿔 주지만,
     * 커밋 시점 flush 실패는 TransactionSystemException·JpaSystemException 에 감싸져 오므로 원인의 SQLState 까지 본다.
     */
    private static boolean isLockContention(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof PessimisticLockingFailureException) return true;
            if (current instanceof SQLException sql && BUSY_STATES.contains(sql.getSQLState())) return true;
            current = current.getCause();
        }
        return false;
    }

    private static ApiError error(HttpStatus status, String code, String message) {
        return ApiError.of(status.value(), code, message);
    }
}
