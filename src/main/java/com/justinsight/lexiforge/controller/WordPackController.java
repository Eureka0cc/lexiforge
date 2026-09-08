package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.WordPackDto;
import com.justinsight.lexiforge.service.WordPackService;
import com.justinsight.lexiforge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/word-packs")
public class WordPackController {
    private final WordPackService service;

    public WordPackController(WordPackService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<List<WordPackDto.Pack>> list() {
        return ApiResponse.ok(service.list(CurrentUser.id()));
    }

    @PostMapping("/{packId}/import/v1")
    public ApiResponse<WordPackDto.ImportResult> importPack(@PathVariable long packId) {
        return ApiResponse.ok(service.importPack(CurrentUser.id(), packId));
    }
}
