package com.justinsight.lexiforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

public final class TranslationDto {
    private TranslationDto() {
    }

    public record Request(
            @NotBlank @Size(max = 5000) String sourceText,
            @NotBlank @Pattern(regexp = "AUTO|EN|ZH") String sourceLanguage,
            @NotBlank @Pattern(regexp = "EN|ZH") String targetLanguage,
            String domainId,
            @Size(max = 2000) String context,
            boolean includeTerms,
            boolean forceRefresh
    ) {
        public Request(String sourceText, String sourceLanguage, String targetLanguage,
                       String domainId, String context, boolean includeTerms) {
            this(sourceText, sourceLanguage, targetLanguage, domainId, context, includeTerms, false);
        }
    }

    public record TermView(String id, String sourceTerm, String targetTerm, String partOfSpeech,
                           String ipa, String explanation, String sourceContext) {
    }

    public record AlternativeView(String meaning, String note) {
    }

    public record View(String id, String domainId, String sourceLanguage, String targetLanguage,
                       String detectedLanguage, String sourceText, String translatedText,
                       boolean medicalDisclaimer, Instant createdAt, List<TermView> terms,
                       List<AlternativeView> alternatives) {
    }

    public record PageView(List<View> items, int page, int size, long total) {
    }
}
