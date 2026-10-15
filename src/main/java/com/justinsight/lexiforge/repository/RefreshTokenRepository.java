package com.justinsight.lexiforge.repository;

import com.justinsight.lexiforge.model.entity.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByTokenHashAndRevokedAtIsNull(String tokenHash);
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);
}
