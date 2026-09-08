package com.justinsight.lexiforge.service.impl;

import com.justinsight.lexiforge.service.AuthService;
import com.justinsight.lexiforge.service.EventService;
import com.justinsight.lexiforge.security.JwtService;
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
        String normalized = email.trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(normalized)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_EXISTS", "该邮箱已注册");
        }
        UserEntity user = new UserEntity();
        user.id = ids.nextId();
        user.email = normalized;
        user.passwordHash = encoder.encode(password);
        user.displayName = displayName.trim();
        users.saveAndFlush(user);
        jdbc.sql("INSERT INTO subscription(id,user_id,plan_code,status) VALUES (:id,:uid,'FREE','FREE')")
                .param("id", ids.nextId()).param("uid", user.id).update();
        events.track(user.id, "USER_REGISTERED", null);
        return tokens(user);
    }

    @Override
    @Transactional
    public Tokens login(String email, String password) {
        UserEntity user = users.findByEmailIgnoreCase(email.trim()).orElseThrow(this::unauthorized);
        if (!encoder.matches(password, user.passwordHash)) {
            throw unauthorized();
        }
        events.track(user.id, "USER_LOGGED_IN", null);
        return tokens(user);
    }

    @Override
    @Transactional
    public Tokens refresh(String raw) {
        RefreshTokenEntity old = refreshTokens.findByTokenHashAndRevokedAtIsNull(hash(raw))
                .filter(token -> token.expiresAt.isAfter(Instant.now()))
                .orElseThrow(this::unauthorized);
        old.revokedAt = Instant.now();
        refreshTokens.save(old);
        return tokens(users.findById(old.userId).orElseThrow(this::unauthorized));
    }

    @Override
    @Transactional
    public void logout(String raw) {
        if (raw == null) return;
        refreshTokens.findByTokenHashAndRevokedAtIsNull(hash(raw)).ifPresent(token -> {
            token.revokedAt = Instant.now();
            refreshTokens.save(token);
        });
    }

    private Tokens tokens(UserEntity user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        RefreshTokenEntity token = new RefreshTokenEntity();
        token.id = ids.nextId();
        token.userId = user.id;
        token.tokenHash = hash(raw);
        token.expiresAt = Instant.now().plus(jwt.refreshDays(), ChronoUnit.DAYS);
        refreshTokens.save(token);
        AuthDto.UserView view = new AuthDto.UserView(user.id.toString(), user.email,
                user.displayName, user.role);
        return new Tokens(jwt.issue(user), raw, jwt.refreshDays() * 86400, view);
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
