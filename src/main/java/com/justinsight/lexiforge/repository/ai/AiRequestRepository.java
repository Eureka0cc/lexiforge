package com.justinsight.lexiforge.repository.ai;

import com.justinsight.lexiforge.model.entity.ai.AiRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AiRequestRepository extends JpaRepository<AiRequestEntity, Long> {
    Optional<AiRequestEntity> findByUserIdAndRequestId(Long userId, String requestId);
    List<AiRequestEntity> findByStatusAndCreatedAtBefore(String status, Instant cutoff);
}
