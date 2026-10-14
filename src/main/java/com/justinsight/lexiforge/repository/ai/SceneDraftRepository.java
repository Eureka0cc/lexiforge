package com.justinsight.lexiforge.repository.ai;

import com.justinsight.lexiforge.model.entity.ai.SceneDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SceneDraftRepository extends JpaRepository<SceneDraftEntity, Long> {
    Optional<SceneDraftEntity> findByIdAndUserId(Long id, Long userId);
}
