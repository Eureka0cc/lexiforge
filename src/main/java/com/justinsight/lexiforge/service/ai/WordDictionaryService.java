package com.justinsight.lexiforge.service.ai;

import java.util.List;
import java.util.Optional;

import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.model.entity.TranslationEntity;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;

/**
 * Cross-user shared dictionary for frequently translated single words: any user's hit
 * is served instantly without calling the AI provider or consuming quota.
 */
public interface WordDictionaryService {
    record DictionaryHit(long id, String word, String sourceLanguage, String translatedText,
                          String detectedLanguage, List<AiGateway.TranslationAlternative> alternatives,
                          String modelVersion) {
    }

    Optional<DictionaryHit> lookup(TranslationDto.Request request, DomainEntity domain,
                                    List<AiGateway.GlossaryTerm> glossary);

    void admit(TranslationDto.Request request, DomainEntity domain,
               List<AiGateway.GlossaryTerm> glossary, TranslationEntity saved);

    void registerHit(long dictionaryId);
}
