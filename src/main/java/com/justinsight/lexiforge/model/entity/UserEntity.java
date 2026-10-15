package com.justinsight.lexiforge.model.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "app_user")
public class UserEntity {
    @Id
    public Long id;
    @Column(nullable = false, unique = true)
    public String email;
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;
    @Column(name = "display_name", nullable = false)
    public String displayName;
    @Column(nullable = false)
    public String role = "USER";
    @Column(name = "email_verified", nullable = false) public boolean emailVerified;
    @Column(name = "session_version", nullable = false) public long sessionVersion;
    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt = Instant.now();
}
