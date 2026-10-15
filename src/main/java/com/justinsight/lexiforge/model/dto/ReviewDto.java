package com.justinsight.lexiforge.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ReviewDto {
    private ReviewDto() {}

    public record StartRequest(
            @Pattern(regexp = "EN_TO_ZH|ZH_TO_EN|MIXED") String direction,
            @Min(1) @Max(50) int limit
    ) {}
    public record Item(String vocabularyId, String direction, String prompt,
                       String partOfSpeech, String ipa, String context) {}
    public record Session(String id, String direction, List<Item> items) {}
    public record AnswerRequest(
            @NotBlank @Pattern(regexp = "[0-9]+") String vocabularyId,
            @NotBlank @Pattern(regexp = "EN_TO_ZH|ZH_TO_EN") String direction,
            @NotBlank @Size(max = 500) String answer
    ) {}
    public record AnswerResult(String result, String feedback, String expectedAnswer,
                               String confusedWith, String nextDueAt) {}
}
