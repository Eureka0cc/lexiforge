package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.common.ApiException;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.repository.UserRepository;
import com.justinsight.lexiforge.security.AuthTokens;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class AccountRecoveryService {
    private final JdbcClient jdbc;
    private final UserRepository users;
    private final AuthMailDelivery mail;
    private final SnowflakeIdGenerator ids;
    private final PasswordEncoder encoder;
    public AccountRecoveryService(JdbcClient jdbc, UserRepository users, AuthMailDelivery mail,
                                   SnowflakeIdGenerator ids, PasswordEncoder encoder) {
        this.jdbc = jdbc; this.users = users; this.mail = mail; this.ids = ids; this.encoder = encoder;
    }
    @Transactional
    public void request(String email, String purpose) {
        if (!mail.enabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_MAIL_UNAVAILABLE", "验证邮件服务尚未配置");
        var found = users.findByEmailForUpdate(email.trim());
        if (found.isEmpty()) return; // 统一响应，不披露邮箱是否已注册。
        var user = found.get();
        if (purpose.equals("VERIFY_EMAIL") && user.emailVerified) return;
        long recent = jdbc.sql("SELECT count(*) FROM auth_action_token WHERE user_id=:uid AND purpose=:purpose AND created_at>now()-interval '60 seconds'")
                .param("uid", user.id).param("purpose", purpose).query(Long.class).single();
        if (recent > 0) return;
        jdbc.sql("UPDATE auth_action_token SET used_at=now() WHERE user_id=:uid AND purpose=:purpose AND used_at IS NULL")
                .param("uid", user.id).param("purpose", purpose).update();
        jdbc.sql("UPDATE auth_mail_outbox SET status='FAILED' WHERE user_id=:uid AND purpose=:purpose AND status='PENDING'")
                .param("uid", user.id).param("purpose", purpose).update();
        String raw = AuthTokens.random();
        jdbc.sql("INSERT INTO auth_action_token(id,user_id,purpose,token_hash,expires_at) VALUES(:id,:uid,:purpose,:hash,:expires)")
                .param("id", ids.nextId()).param("uid", user.id).param("purpose", purpose).param("hash", AuthTokens.hash(raw))
                .param("expires", java.sql.Timestamp.from(Instant.now().plusSeconds(900))).update();
        mail.enqueue(user.id, user.email, purpose, raw);
    }
    @Transactional
    public void verify(String token) { consume(token, "VERIFY_EMAIL", null); }
    @Transactional
    public void reset(String token, String password) { AuthTokens.validatePassword(password); consume(token, "RESET_PASSWORD", password); }

    private void consume(String raw, String purpose, String password) {
        var rows = jdbc.sql("SELECT id,user_id FROM auth_action_token WHERE token_hash=:hash AND purpose=:purpose AND used_at IS NULL AND expires_at>now()")
                .param("hash", AuthTokens.hash(raw)).param("purpose", purpose).query().listOfRows();
        if (rows.isEmpty()) throw invalid();
        var row = rows.getFirst();
        long userId = ((Number) row.get("user_id")).longValue();
        var user = users.findByIdForUpdate(userId).orElseThrow(this::invalid);
        int consumed = jdbc.sql("UPDATE auth_action_token SET used_at=now() WHERE id=:id AND used_at IS NULL AND expires_at>now()")
                .param("id", row.get("id")).update();
        if (consumed != 1) throw invalid();
        if (purpose.equals("VERIFY_EMAIL")) user.emailVerified = true;
        else {
            user.passwordHash = encoder.encode(password); user.sessionVersion++;
            jdbc.sql("UPDATE refresh_token SET revoked_at=now() WHERE user_id=:uid AND revoked_at IS NULL").param("uid", userId).update();
            jdbc.sql("UPDATE auth_session SET revoked_at=now() WHERE user_id=:uid AND revoked_at IS NULL").param("uid", userId).update();
            jdbc.sql("UPDATE auth_action_token SET used_at=now() WHERE user_id=:uid AND purpose='RESET_PASSWORD' AND used_at IS NULL").param("uid", userId).update();
        }
        user.updatedAt = Instant.now();
        users.saveAndFlush(user);
    }
    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "AUTH_LINK_INVALID", "链接已过期或失效，请重新申请"); }
}
