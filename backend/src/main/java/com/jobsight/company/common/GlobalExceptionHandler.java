package com.jobsight.company.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(error.getField(), error.getDefaultMessage())
        );
        ApiError body = new ApiError(
                Instant.now(), HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED",
                "입력값을 확인해 주세요.", errors
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleInvalidPath(MethodArgumentTypeMismatchException exception) {
        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", "요청 경로를 확인해 주세요."
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiError.of(
                HttpStatus.BAD_REQUEST.value(), "INVALID_REQUEST", "요청 본문을 확인해 주세요."
        ));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
                HttpStatus.NOT_FOUND.value(), "COMPANY_NOT_FOUND", exception.getMessage()
        ));
    }

    /**
     * @Version 충돌. 서비스가 잠금과 expectedVersion 으로 먼저 막으므로 보통은 오지 않지만,
     * 오면 500 이 아니라 409 다 — 다시 읽고 고치면 되는 상황이기 때문이다(docs/resumes.md 불변 조건 8).
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleStaleWrite(OptimisticLockingFailureException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiError.of(
                HttpStatus.CONFLICT.value(), "STALE_WRITE", "다른 곳에서 먼저 저장했습니다. 다시 읽고 고칠 대상을 확인한 뒤 보내세요."
        ));
    }

    /** 업무 규칙 위반은 예외가 들고 온 상태코드와 코드값을 그대로 사용한다. */
    @ExceptionHandler(ApiRuleException.class)
    ResponseEntity<ApiError> handleRule(ApiRuleException exception) {
        return ResponseEntity.status(exception.getStatus()).body(ApiError.of(
                exception.getStatus().value(), exception.getCode(), exception.getMessage()
        ));
    }

    /**
     * 존재하지 않는 경로(예: /api/companies/ 처럼 끝에 슬래시가 붙은 요청)는 404다.
     * 이 핸들러가 없으면 아래 catch-all이 잡아 500과 ERROR 로그를 남긴다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError.of(
                HttpStatus.NOT_FOUND.value(), "NOT_FOUND", "요청 경로를 찾을 수 없습니다."
        ));
    }

    /** catch-all이 인가 실패를 500으로 바꿔버리지 않도록 명시적으로 403을 유지한다. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiError.of(
                HttpStatus.FORBIDDEN.value(), "FORBIDDEN", "권한이 없습니다."
        ));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("Unhandled company API error", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(), "INTERNAL_ERROR", "서버 오류가 발생했습니다."
        ));
    }
}
