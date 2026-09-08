package com.justinsight.lexiforge.common;

import java.util.Map;

/**
 * Unified HTTP response envelope used by every REST controller.
 */
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        Map<String, String> fieldErrors,
        String requestId
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "SUCCESS", "操作成功", data, Map.of(), null);
    }

    public static ApiResponse<Void> ok() {
        return ok(null);
    }

    public static ApiResponse<Void> error(
            String code,
            String message,
            Map<String, String> fieldErrors,
            String requestId
    ) {
        return new ApiResponse<>(false, code, message, null, fieldErrors, requestId);
    }
}
