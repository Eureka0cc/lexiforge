package com.justinsight.lexiforge.model.dto.ai;

import com.justinsight.lexiforge.model.dto.TranslationDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class AiTranslationDto {
    private AiTranslationDto() {
    }

    public record StreamRequest(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Size(max = 5000) String sourceText,
            @NotBlank @Pattern(regexp = "AUTO|EN|ZH") String sourceLanguage,
            @NotBlank @Pattern(regexp = "EN|ZH") String targetLanguage,
            String domainId,
            @Size(max = 2000) String context,
            boolean forceRefresh
    ) {
        public TranslationDto.Request legacyRequest() {
            return new TranslationDto.Request(sourceText, sourceLanguage, targetLanguage, domainId, context, false, forceRefresh);
        }
    }

    public record Started(String requestId) {
    }

    public record Delta(String text) {
    }

    public record Completed(TranslationDto.View translation) {
    }

    public record Error(String code, String message) {
    }

    public record Alternative(String meaning, String note) {
    }
}
