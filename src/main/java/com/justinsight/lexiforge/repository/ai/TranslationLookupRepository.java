package com.justinsight.lexiforge.repository.ai;

import com.justinsight.lexiforge.model.entity.ai.TranslationLookupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TranslationLookupRepository extends JpaRepository<TranslationLookupEntity, Long> {
    Optional<TranslationLookupEntity> findByTranslationIdAndSideAndSelectionStartAndSelectionEnd(
            Long translationId, String side, int selectionStart, int selectionEnd);

    Optional<TranslationLookupEntity> findByIdAndUserId(Long id, Long userId);
}
