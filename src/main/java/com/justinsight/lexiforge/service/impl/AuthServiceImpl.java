package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.AuthService;
import com.justinsight.lexiforge.service.EventService;
import com.justinsight.lexiforge.security.JwtService;
import com.justinsight.lexiforge.security.AuthTokens;
import com.justinsight.lexiforge.model.entity.RefreshTokenEntity;
import com.justinsight.lexiforge.repository.RefreshTokenRepository;
import com.justinsight.lexiforge.model.entity.UserEntity;
import com.justinsight.lexiforge.repository.UserRepository;
import com.justinsight.lexiforge.model.dto.AuthDto;
import com.justinsight.lexiforge.common.id.SnowflakeIdGenerator;
import com.justinsight.lexiforge.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final SnowflakeIdGenerator ids;
    private final JwtService jwt;
    private final JdbcClient jdbc;
    private final EventService events;
    private final SecureRandom random = new SecureRandom();

    public AuthServiceImpl(UserRepository users, RefreshTokenRepository refreshTokens,
                           PasswordEncoder encoder, SnowflakeIdGenerator ids, JwtService jwt,
                           JdbcClient jdbc, EventService events) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.ids = ids;
        this.jwt = jwt;
        this.jdbc = jdbc;
        this.events = events;
    }

    @Override
    @Transactional
    public Tokens register(String email, String password, String displayName) {
        AuthTokens.validatePassword(password);
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "该邮箱已注册");
        }
        UserEntity user = new UserEntity();
        user.id = ids.nextId();
        user.email = normalized;
        user.passwordHash = encoder.encode(password);
        user.displayName = displayName.trim();
        try { users.saveAndFlush(user); }
        catch (org.springframework.dao.DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "该邮箱已注册");
        }
        jdbc.sql("INSERT INTO subscription(id,user_id,plan_code,status) VALUES (:id,:uid,'FREE','FREE')")
                .param("id", ids.nextId()).param("uid", user.id).update();
        events.track(user.id, "USER_REGISTERED", null);
        return tokens(user);
    }

    @Override
    @Transactional
    public Tokens login(String email, String password) {
        UserEntity user = users.findByEmailForUpdate(email.trim()).orElseThrow(this::unauthorized);
        if (!encoder.matches(password, user.passwordHash)) {
            throw unauthorized();
        }
        events.track(user.id, "USER_LOGGED_IN", null);
        return tokens(user);
    }

    @Override
    @Transactional
    public Tokens refresh(String raw) {
        RefreshTokenEntity candidate = refreshTokens.findByTokenHashAndRevokedAtIsNull(hash(raw)).orElseThrow(this::unauthorized);
        UserEntity user = users.findByIdForUpdate(candidate.userId).orElseThrow(this::unauthorized);
        RefreshTokenEntity old = refreshTokens.findByTokenHashAndRevokedAtIsNull(hash(raw))
                .filter(token -> token.expiresAt.isAfter(Instant.now()))
                .orElseThrow(this::unauthorized);
        long active = jdbc.sql("SELECT count(*) FROM auth_session WHERE id=:id AND user_id=:uid AND revoked_at IS NULL AND expires_at>now()")
                .param("id", old.sessionId).param("uid", user.id).query(Long.class).single();
        if (active != 1) throw unauthorized();
        old.revokedAt = Instant.now();
        refreshTokens.save(old);
        return tokens(user, old.sessionId);
    }

    @Override
    @Transactional
    public void logout(String raw) {
        if (raw == null) return;
        refreshTokens.findByTokenHash(hash(raw)).ifPresent(token -> {
            users.findByIdForUpdate(token.userId).orElseThrow(this::unauthorized);
            jdbc.sql("UPDATE auth_session SET revoked_at=now() WHERE id=:id AND user_id=:uid AND revoked_at IS NULL")
                    .param("id", token.sessionId).param("uid", token.userId).update();
            jdbc.sql("UPDATE refresh_token SET revoked_at=now() WHERE session_id=:id AND revoked_at IS NULL")
                    .param("id", token.sessionId).update();
        });
    }

    @Override @Transactional(readOnly = true)
    public AuthDto.UserView current(long userId) { return userView(users.findById(userId).orElseThrow(this::unauthorized)); }

    private AuthDto.UserView userView(UserEntity user) {
        return new AuthDto.UserView(user.id.toString(), user.email, user.displayName, user.role, user.emailVerified);
    }

    private Tokens tokens(UserEntity user) {
        long sessionId = ids.nextId();
        jdbc.sql("INSERT INTO auth_session(id,user_id,expires_at) VALUES(:id,:uid,:expires)")
                .param("id", sessionId).param("uid", user.id).param("expires", java.sql.Timestamp.from(Instant.now().plus(jwt.refreshDays(), ChronoUnit.DAYS))).update();
        return tokens(user, sessionId);
    }

    private Tokens tokens(UserEntity user, long sessionId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.id = ids.nextId();
        token.userId = user.id;
        token.sessionId = sessionId;
        token.tokenHash = hash(raw);
        token.expiresAt = Instant.now().plus(jwt.refreshDays(), ChronoUnit.DAYS);
        refreshTokens.save(token);
        jdbc.sql("UPDATE auth_session SET expires_at=:expires WHERE id=:id").param("expires", java.sql.Timestamp.from(token.expiresAt)).param("id", sessionId).update();
        AuthDto.UserView view = userView(user);
        return new Tokens(jwt.issue(user, sessionId), raw, jwt.refreshDays() * 86400, view);
    }

    private ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "邮箱或密码不正确");
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
