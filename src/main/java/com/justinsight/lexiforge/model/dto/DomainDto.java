package com.justinsight.lexiforge.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class DomainDto {
    private DomainDto() {}

    public record DomainRequest(
            @NotBlank @Size(max = 60) String name,
            @NotBlank @Size(max = 500) String description,
            @Valid SceneDto.Config configuration,
            Long expectedRevision
    ) {
        public DomainRequest(String name, String description) { this(name, description, null, null); }
    }

    public record TermRequest(
            @NotBlank @Size(max = 200) String sourceTerm,
            @NotBlank @Size(max = 200) String targetTerm,
            @Size(max = 300) String note
    ) {}

    public record TermView(String id, String sourceTerm, String targetTerm, String note) {}
    public record DomainView(String id, String code, String name, String description,
                             boolean builtIn, boolean medicalDisclaimer, List<TermView> terms,
                             SceneDto.Config configuration, long revision, String configurationVersion) {}
}
