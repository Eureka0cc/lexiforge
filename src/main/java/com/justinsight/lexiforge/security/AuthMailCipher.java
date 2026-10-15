package com.justinsight.lexiforge.security;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/** 邮件队列中的一次性链接加密保存；访问令牌表仅保存摘要。 */
public final class AuthMailCipher {
    private AuthMailCipher() {}
    private static SecretKeySpec key(String secret) throws Exception {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalArgumentException("邮件加密密钥至少 32 字节");
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), "AES");
    }
    public static String encrypt(String plain, String secret) {
        try {
            byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(secret), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, envelope, nonce.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (Exception exception) { throw new IllegalStateException("邮件加密失败"); }
    }
    public static String decrypt(String encrypted, String secret) {
        try {
            byte[] envelope = Base64.getUrlDecoder().decode(encrypted);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(secret), new GCMParameterSpec(128, Arrays.copyOf(envelope, 12)));
            return new String(cipher.doFinal(Arrays.copyOfRange(envelope, 12, envelope.length)), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("邮件解密失败"); }
    }
}
