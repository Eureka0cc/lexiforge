package com.justinsight.lexiforge.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ConfusionDto {
    private ConfusionDto() {}

    public record WordRequest(@NotBlank String word, String meaning, String ipa,
                              String partOfSpeech, String exampleSentence) {}
    public record CreateRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(min = 2, max = 8) List<@Valid WordRequest> words,
            String note
    ) {}
    public record MnemonicRequest(
            @Pattern(regexp = "DEFAULT|RHYME|STORY|VISUAL|PROGRAMMER") String style,
            String mistakeContext
    ) {}
    public record MnemonicUpdate(String userNote, Boolean helpful) {}
    public record MnemonicView(String id, String style, String slogan, String visualHook,
                               String itAnalogy, String contrastExample, String recallHook,
                               String userNote, Boolean helpful) {}
    public record GroupView(String id, String name, String note, List<WordRequest> words,
                            List<MnemonicView> mnemonics) {}
}
