package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.WordPackDto;

import java.util.List;

public interface WordPackService {
    List<WordPackDto.Pack> list(long userId);

    WordPackDto.ImportResult importPack(long userId, long packId);
}
