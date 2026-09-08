package com.justinsight.lexiforge.model.dto;

public final class WordPackDto {
    private WordPackDto() {}

    public record Pack(String id, String code, String name, String description,
                       int itemCount, boolean proOnly, boolean imported) {}
    public record ImportResult(int imported) {}
}
