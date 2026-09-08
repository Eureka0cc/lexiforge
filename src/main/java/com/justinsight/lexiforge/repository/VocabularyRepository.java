package com.justinsight.lexiforge.repository;

import com.justinsight.lexiforge.model.entity.VocabularyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VocabularyRepository extends JpaRepository<VocabularyEntity, Long> {
    List<VocabularyEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<VocabularyEntity> findByIdAndUserId(Long id, Long userId);

    Optional<VocabularyEntity> findByUserIdAndWordIgnoreCase(Long userId, String word);

    Optional<VocabularyEntity> findByUserIdAndSenseKey(Long userId, String senseKey);
}
