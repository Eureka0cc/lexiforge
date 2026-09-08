package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.VocabularyDto;
import com.justinsight.lexiforge.service.VocabularyService;
import com.justinsight.lexiforge.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/vocabulary")
public class VocabularyController {
    private final VocabularyService service;

    public VocabularyController(VocabularyService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<List<VocabularyDto.View>> list() {
        return ApiResponse.ok(service.list(CurrentUser.id()));
    }

    @PostMapping("/v1")
    public ApiResponse<VocabularyDto.View> create(
            @Valid @RequestBody VocabularyDto.Request request) {
        return ApiResponse.ok(service.create(CurrentUser.id(), request));
    }

    @PostMapping("/translation-term/{termId}/v1")
    public ApiResponse<VocabularyDto.View> collect(@PathVariable long termId) {
        return ApiResponse.ok(service.collect(CurrentUser.id(), termId));
    }

    @PostMapping("/lookup/{lookupId}/v1")
    public ApiResponse<VocabularyDto.View> collectLookup(@PathVariable long lookupId) {
        return ApiResponse.ok(service.collectLookup(CurrentUser.id(), lookupId));
    }

    @PatchMapping("/{id}/v1")
    public ApiResponse<VocabularyDto.View> update(
            @PathVariable long id,
            @Valid @RequestBody VocabularyDto.Request request) {
        return ApiResponse.ok(service.update(CurrentUser.id(), id, request));
    }

    @DeleteMapping("/{id}/v1")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(CurrentUser.id(), id);
        return ApiResponse.ok();
    }
}
