package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.VocabularyDto;

import java.util.List;

public interface VocabularyService {
    List<VocabularyDto.View> list(long userId);

    VocabularyDto.View create(long userId, VocabularyDto.Request request);

    VocabularyDto.View collect(long userId, long termId);

    VocabularyDto.View collectLookup(long userId, long lookupId);

    VocabularyDto.View update(long userId, long id, VocabularyDto.Request request);

    void delete(long userId, long id);
}
