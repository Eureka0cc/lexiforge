package com.justinsight.lexiforge.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiResponse<Void>> api(ApiException ex, HttpServletRequest request) {
        if (ex.status().is5xxServerError()) {
            log.warn("Gateway failure {}: {} requestId={}", ex.code(), ex.getMessage(), requestId(request), ex);
        }
        return ResponseEntity.status(ex.status()).body(error(ex.code(), ex.getMessage(), Map.of(), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(e -> fields.putIfAbsent(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(error("VALIDATION_FAILED", "请求参数不正确", fields, request));
    }

    @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class})
    ResponseEntity<ApiResponse<Void>> badRequest(Exception ex, HttpServletRequest request) {
        return ResponseEntity.badRequest().body(error("BAD_REQUEST", ex.getMessage(), Map.of(), request));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiResponse<Void>> denied(AccessDeniedException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("FORBIDDEN", "无权执行此操作", Map.of(), request));
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiResponse<Void>> versionConflict(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("SCENE_VERSION_CONFLICT",
                "数据已被修改，请刷新后再保存", Map.of(), request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unknown(Exception ex, HttpServletRequest request) {
        String requestId = requestId(request);
        log.error("Unhandled request failure: {} {} requestId={}",
                request.getMethod(), request.getRequestURI(), requestId, ex);
        return ResponseEntity.internalServerError().body(
                ApiResponse.error("INTERNAL_ERROR", "服务暂时不可用", Map.of(), requestId));
    }

    private ApiResponse<Void> error(String code, String message, Map<String, String> fields,
                                    HttpServletRequest request) {
        return ApiResponse.error(code, message, fields, requestId(request));
    }

    private String requestId(HttpServletRequest request) {
        String value = request.getHeader("X-Request-Id");
        return value == null || value.isBlank() ? UUID.randomUUID().toString() : value;
    }
}
