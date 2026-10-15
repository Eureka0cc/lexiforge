package com.justinsight.lexiforge.integration;

import com.justinsight.lexiforge.model.dto.AuthDto;
import com.justinsight.lexiforge.security.AuthMailCipher;
import com.justinsight.lexiforge.security.JwtService;
import com.justinsight.lexiforge.service.AccountRecoveryService;
import com.justinsight.lexiforge.service.AuthMailDelivery;
import com.justinsight.lexiforge.service.AuthService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "spring.ai.model.chat=none", "app.ai.enabled=false",
        "app.jwt.secret=test-only-secret-never-use-in-production-0123456789", "app.auth.secure-cookie=true",
        "app.auth.mail.mode=file", "app.auth.mail.secret=fixture-encryption-secret-never-use-outside-tests",
        "app.auth.mail.directory=../tmp/auth-mail-acceptance", "app.auth.mail.dispatch-ms=3600000"})
@EnabledIfEnvironmentVariable(named = "LEXIFORGE_TEST_DB_URL", matches = ".+")
class AccountRecoveryIT {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) { ScenePersistenceIT.database(registry); }
    @Autowired AuthService auth;
    @Autowired AccountRecoveryService recovery;
    @Autowired JdbcClient jdbc;
    @Autowired JwtService jwt;
    @MockitoSpyBean AuthMailDelivery mail;
    @Autowired org.springframework.core.env.Environment environment;
    private int port;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private String email;
    private AuthService.Tokens original;
    private long userId;
    @BeforeEach void setup() {
        port = environment.getRequiredProperty("local.server.port", Integer.class);
        email = "recovery-" + UUID.randomUUID() + "@example.test";
        original = auth.register(email, "Original123!", "恢复验收用户");
        userId = Long.parseLong(original.user().id());
    }
    @AfterEach void cleanup() { jdbc.sql("DELETE FROM app_user WHERE id=:id").param("id", userId).update(); }

    @Test void verificationUsesOneTimePurposeBoundTokenAndEncryptedOutbox() throws Exception {
        recovery.request(email, "VERIFY_EMAIL");
        String token = deliveredToken("VERIFY_EMAIL");
        String cipher = jdbc.sql("SELECT payload_ciphertext FROM auth_mail_outbox WHERE user_id=:id")
                .param("id", userId).query(String.class).single();
        assertThat(cipher).doesNotContain(token).doesNotContain(email);
        assertThatThrownBy(() -> recovery.reset(token, "Changed123!")).hasMessageContaining("过期或失效");
        recovery.verify(token);
        assertThat(auth.current(userId).emailVerified()).isTrue();
        assertThatThrownBy(() -> recovery.verify(token)).hasMessageContaining("过期或失效");
        assertThat(getAccount(original.accessToken())).isEqualTo(200);
    }

    @Test void passwordResetRevokesAllAccessAndRefreshSessionsImmediately() throws Exception {
        var other = auth.login(email, "Original123!");
        recovery.request(email, "RESET_PASSWORD");
        String token = deliveredToken("RESET_PASSWORD");
        recovery.reset(token, "Changed123!");
        assertThatThrownBy(() -> auth.login(email, "Original123!")).hasMessageContaining("不正确");
        assertThatThrownBy(() -> auth.refresh(original.refreshToken())).hasMessageContaining("不正确");
        assertThatThrownBy(() -> auth.refresh(other.refreshToken())).hasMessageContaining("不正确");
        assertThat(getAccount(original.accessToken())).isEqualTo(401);
        assertThat(getAccount(other.accessToken())).isEqualTo(401);
        var updated = auth.login(email, "Changed123!");
        assertThat(getAccount(updated.accessToken())).isEqualTo(200);
        assertThatThrownBy(() -> recovery.reset(token, "Another123!")).hasMessageContaining("过期或失效");
    }

    @Test void expiredTokenDoesNotChangePassword() {
        recovery.request(email, "RESET_PASSWORD");
        String token = deliveredToken("RESET_PASSWORD");
        jdbc.sql("UPDATE auth_action_token SET expires_at=now()-interval '1 second' WHERE user_id=:id").param("id", userId).update();
        assertThatThrownBy(() -> recovery.reset(token, "Changed123!")).hasMessageContaining("过期或失效");
        assertThat(auth.login(email, "Original123!").user().email()).isEqualTo(email);
    }

    @Test void repeatedRequestsAreThrottledAndUnknownEmailGetsSamePublicResponse() throws Exception {
        var known = post("/api/auth/password-reset/request/v1", Map.of("email", email), null);
        var unknown = post("/api/auth/password-reset/request/v1", Map.of("email", "unknown-" + UUID.randomUUID() + "@example.test"), null);
        assertThat(known.statusCode()).isEqualTo(200);
        assertThat(unknown.statusCode()).isEqualTo(known.statusCode());
        assertThat(json.readTree(known.body()).path("message").asText()).isEqualTo(json.readTree(unknown.body()).path("message").asText());
        recovery.request(email, "RESET_PASSWORD");
        assertThat(jdbc.sql("SELECT count(*) FROM auth_action_token WHERE user_id=:id").param("id", userId).query(Long.class).single()).isEqualTo(1);
    }

    @Test void concurrentRefreshProducesOnlyOneSuccessAndOldCookieLogoutRevokesItsDescendant() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<AuthService.Tokens> rotate = () -> { start.await(); return auth.refresh(original.refreshToken()); };
            var first = executor.submit(rotate); var second = executor.submit(rotate); start.countDown();
            AuthService.Tokens successful = null; int successes = 0, failures = 0;
            for (var future : java.util.List.of(first, second)) {
                try { successful = future.get(10, TimeUnit.SECONDS); successes++; }
                catch (ExecutionException failure) { failures++; }
            }
            assertThat(successes).isEqualTo(1); assertThat(failures).isEqualTo(1);
            assertThat(successful).isNotNull();
            auth.logout(original.refreshToken());
            assertThat(getAccount(successful.accessToken())).isEqualTo(401);
            String descendant = successful.refreshToken();
            assertThatThrownBy(() -> auth.refresh(descendant)).hasMessageContaining("不正确");
        } finally { executor.shutdownNow(); }
    }

    @Test void mailQueueFailureRollsBackTheOneTimeToken() {
        doThrow(new IllegalStateException("队列失败")).when(mail).enqueue(anyLong(), anyString(), anyString(), anyString());
        assertThatThrownBy(() -> recovery.request(email, "RESET_PASSWORD")).hasMessage("队列失败");
        assertThat(jdbc.sql("SELECT count(*) FROM auth_action_token WHERE user_id=:id").param("id", userId).query(Long.class).single()).isZero();
    }

    @Test void browserOriginGuardAndSecureCookieAreApplied() throws Exception {
        var rejected = post("/api/auth/login/v1", Map.of("email", email, "password", "Original123!"), "https://untrusted.example.test");
        assertThat(rejected.statusCode()).isEqualTo(403);
        var accepted = post("/api/auth/login/v1", Map.of("email", email, "password", "Original123!"), "http://localhost:5173");
        assertThat(accepted.statusCode()).isEqualTo(200);
        assertThat(accepted.headers().firstValue("Set-Cookie").orElseThrow()).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/auth");
    }

    @Test void fileMailDeliveryWritesOnlyToTheDesignatedScratchDirectory() throws Exception {
        recovery.request(email, "VERIFY_EMAIL");
        long id = jdbc.sql("SELECT id FROM auth_mail_outbox WHERE user_id=:id").param("id", userId).query(Long.class).single();
        mail.dispatch();
        Path path = Path.of("../tmp/auth-mail-acceptance", id + ".txt").toAbsolutePath().normalize();
        assertThat(Files.readString(path)).contains("本地邮件测试模式", "#type=verify&token=");
        assertThat(jdbc.sql("SELECT status FROM auth_mail_outbox WHERE id=:id").param("id", id).query(String.class).single()).isEqualTo("SENT");
    }

    private String deliveredToken(String purpose) {
        String encrypted = jdbc.sql("SELECT payload_ciphertext FROM auth_mail_outbox WHERE user_id=:id AND purpose=:purpose ORDER BY created_at DESC LIMIT 1")
                .param("id", userId).param("purpose", purpose).query(String.class).single();
        var message = json.readValue(AuthMailCipher.decrypt(encrypted, "fixture-encryption-secret-never-use-outside-tests"), AuthMailDelivery.Message.class);
        return message.link().substring(message.link().indexOf("&token=") + 7);
    }
    private int getAccount(String accessToken) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/account/v1"))
                .header("Authorization", "Bearer " + accessToken).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }
    private HttpResponse<String> post(String path, Object payload, String origin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)));
        if (origin != null) request.header("Origin", origin);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
