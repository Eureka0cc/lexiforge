package com.justinsight.lexiforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class VocabularyDto {
    private VocabularyDto() {}

    public record Request(
            @NotBlank @Size(max = 120) String word,
            String partOfSpeech,
            String ipa,
            @NotBlank @Size(max = 500) String chineseMeaning,
            String englishDefinition,
            String exampleSentence,
            String sourceContext,
            String sourceType,
            String sourceId
    ) {}

    public record View(String id, String word, String partOfSpeech, String ipa,
                       String chineseMeaning, String englishDefinition, String exampleSentence,
                       String sourceContext, String sourceType, Instant createdAt) {}
}
