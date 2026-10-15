package com.justinsight.lexiforge.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuthMailCipherTest {
    private final String secret = "fixture-encryption-secret-never-use-outside-tests";
    @Test void encryptsLinksAndRejectsTamperingAndWrongKeys() {
        String plain = "https://example.test/account-action#token=secret-token";
        String encrypted = AuthMailCipher.encrypt(plain, secret);
        assertThat(encrypted).doesNotContain("secret-token");
        assertThat(AuthMailCipher.decrypt(encrypted, secret)).isEqualTo(plain);
        assertThat(AuthMailCipher.encrypt(plain, secret)).isNotEqualTo(encrypted);
        assertThatThrownBy(() -> AuthMailCipher.decrypt(encrypted, secret + "different")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> AuthMailCipher.decrypt(encrypted.substring(2), secret)).isInstanceOf(IllegalStateException.class);
    }
    @Test void preventsBcryptByteTruncationAndRejectsBlankPasswords() {
        assertThatThrownBy(() -> AuthTokens.validatePassword("密".repeat(25))).hasMessageContaining("72 字节");
        assertThatThrownBy(() -> AuthTokens.validatePassword("        ")).hasMessageContaining("8 个字符");
        AuthTokens.validatePassword("Strong-password-123!");
    }
}
