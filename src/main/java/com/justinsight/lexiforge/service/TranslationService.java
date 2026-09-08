package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.TranslationDto;

import java.util.function.Consumer;

public interface TranslationService {
    TranslationDto.View translate(long userId, TranslationDto.Request request);

    TranslationDto.View translate(long userId, TranslationDto.Request request,
                                   Consumer<String> deltaConsumer);

    TranslationDto.PageView history(long userId, int page, int size);

    TranslationDto.View one(long userId, long id);

    void delete(long userId, long id);
}
