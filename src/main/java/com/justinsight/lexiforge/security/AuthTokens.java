package com.justinsight.lexiforge.security;

import com.justinsight.lexiforge.common.ApiException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

public final class AuthTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private AuthTokens() {}
    public static String random() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法计算凭据摘要"); }
    }
    public static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "密码至少 8 个字符，UTF-8 编码不能超过 72 字节");
        }
    }
}
