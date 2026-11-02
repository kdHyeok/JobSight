package com.jobsight.company.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    /**
     * 도메인 예외(404·업무 규칙·@Version 충돌·잠금 경합·403)와 나머지 500 은 MCP 와 같은 변환을 쓴다.
     * 500 의 ERROR 로그도 거기서 남긴다 — ApiErrors.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleDomain(Exception exception) {
        ApiError error = ApiErrors.translate(exception);
        return ResponseEntity.status(error.status()).body(error);
    }
}
