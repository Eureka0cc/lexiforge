package com.justinsight.lexiforge.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class SceneDto {
    private SceneDto() {}
    public static final String CONFIG_VERSION = "scene-config-v1";
    public static final String GENERATION_VERSION = "scene-generation-v1";

    public record Config(@NotBlank @Size(max = 120) String domain,
                         @Size(max = 120) String documentType,
                         @Size(max = 120) String audience,
                         @Size(max = 300) String style,
                         @Size(max = 10) List<@NotBlank @Size(max = 200) String> preserveRules,
                         @Size(max = 300) String explanationPreference) {
        public Config {
            documentType = documentType == null ? "" : documentType;
            audience = audience == null ? "" : audience;
            style = style == null ? "" : style;
            preserveRules = preserveRules == null ? List.of() : List.copyOf(preserveRules);
            explanationPreference = explanationPreference == null ? "" : explanationPreference;
        }
    }

    public record GenerateRequest(@NotBlank @Size(max = 64) String requestId,
                                  @NotBlank @Size(max = 500) String description) {}
    public record Generated(@NotBlank @Size(max = 60) String name, @NotNull @Valid Config configuration) {}
    public record Draft(String id, String name, String description, Config configuration, String generationVersion) {}
    public record PreviewRequest(@NotBlank @Size(max = 64) String requestId,
                                 @NotBlank @Size(max = 60) String name,
                                 @NotBlank @Size(max = 500) String description,
                                 @Valid Config configuration,
                                 @NotBlank @Size(max = 5000) String sourceText,
                                 @NotBlank @Pattern(regexp = "AUTO|EN|ZH") String sourceLanguage,
                                 @NotBlank @Pattern(regexp = "EN|ZH") String targetLanguage) {}
}
