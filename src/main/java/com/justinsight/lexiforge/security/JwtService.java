package com.justinsight.lexiforge.security;

import com.justinsight.lexiforge.config.JwtProperties;
import com.justinsight.lexiforge.model.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {
    private final JwtProperties properties;
    private final SecretKey key;

    JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(UserEntity user) {
        return issue(user, null);
    }
    public String issue(UserEntity user, Long sessionId) {
        Instant now = Instant.now();
        return Jwts.builder().subject(user.id.toString()).claim("role", user.role).claim("sessionVersion", user.sessionVersion).claim("sid", sessionId)
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(properties.accessMinutes() * 60)))
                .signWith(key).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public long refreshDays() {
        return properties.refreshDays();
    }
}
