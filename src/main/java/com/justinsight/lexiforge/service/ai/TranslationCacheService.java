package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.model.entity.DomainEntity;
import com.justinsight.lexiforge.service.ai.provider.AiGateway;

import java.util.List;
import java.util.function.Supplier;

public interface TranslationCacheService {
    String PROMPT_VERSION = "translation-context-v3";

    String model();

    String fingerprint(long userId, TranslationDto.Request request, DomainEntity domain,
                      List<AiGateway.GlossaryTerm> glossary);

    <T> T exclusive(String key, Supplier<T> action);

    Long find(String key);

    void remember(String key, long id);

    void evict(long id);
}
