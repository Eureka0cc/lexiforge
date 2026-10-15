package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_token")
public class RefreshTokenEntity {
    @Id
    public Long id;
    @Column(name = "user_id", nullable = false)
    public Long userId;
    @Column(name = "session_id", nullable = false) public Long sessionId;
    @Column(name = "token_hash", nullable = false, unique = true)
    public String tokenHash;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "revoked_at")
    public Instant revokedAt;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
