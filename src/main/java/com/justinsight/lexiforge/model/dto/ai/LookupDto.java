package com.justinsight.lexiforge.model.dto.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class LookupDto {
    private LookupDto() {
    }

    public record Request(
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank @Pattern(regexp = "SOURCE|TARGET") String side,
            @Min(0) int start,
            @Min(1) @Max(5000) int end
    ) {
    }

    public record Response(String lookupId, String selectedText, String meaning, String explanation,
                           String counterpartText, String learningWord, String chineseMeaning,
                           boolean collectable) {
    }

    public record DetailsRequest(@NotBlank @Size(max = 64) String requestId) {
    }

    public record DetailsResponse(String ipa, String partOfSpeech, String exampleSentence,
                                  String exampleTranslation) {
    }
}
