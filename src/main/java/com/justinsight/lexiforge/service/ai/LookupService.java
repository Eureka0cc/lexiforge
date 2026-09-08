package com.justinsight.lexiforge.service.ai;

import com.justinsight.lexiforge.model.dto.ai.LookupDto;
import com.justinsight.lexiforge.model.entity.ai.TranslationLookupEntity;

public interface LookupService {
    LookupDto.Response lookup(long userId, long translationId, LookupDto.Request request);

    LookupDto.DetailsResponse details(long userId, long lookupId, String requestId);

    TranslationLookupEntity owned(long userId, long lookupId);
}
