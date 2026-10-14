package com.justinsight.lexiforge.repository;

import java.time.Instant;
import java.util.Optional;

import com.justinsight.lexiforge.model.entity.WordDictionaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WordDictionaryRepository extends JpaRepository<WordDictionaryEntity, Long> {
    Optional<WordDictionaryEntity> findByWordAndSourceLanguageAndTargetLanguageAndDomainId(
            String word, String sourceLanguage, String targetLanguage, Long domainId);

    /**
     * Counts distinct users who already translated this word in this domain. The
     * normalization expression must stay byte-identical to the
     * idx_translation_word_admission index (V5) so the count can use it.
     * detected_language is used instead of source_language because stored rows may
     * carry the raw AUTO request value.
     */
    @Query(value = """
            SELECT COUNT(DISTINCT t.user_id) FROM translation t
            WHERE t.domain_id = :domainId
              AND t.target_language = :targetLanguage
              AND upper(t.detected_language) = :sourceLanguage
              AND lower(regexp_replace(normalize(trim(t.source_text), NFC), '\\s+', ' ', 'g')) = :word
              AND (t.context IS NULL OR t.context = '')
            """, nativeQuery = true)
    long countDistinctRequesters(@Param("domainId") Long domainId,
                                 @Param("sourceLanguage") String sourceLanguage,
                                 @Param("targetLanguage") String targetLanguage,
                                 @Param("word") String word);

    @Modifying
    @Query("update WordDictionaryEntity w set w.hitCount = w.hitCount + 1, w.updatedAt = :now where w.id = :id")
    void incrementHitCount(@Param("id") long id, @Param("now") Instant now);
}
