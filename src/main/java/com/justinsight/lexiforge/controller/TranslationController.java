package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.service.TranslationService;
import com.justinsight.lexiforge.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/translations")
public class TranslationController {
    private final TranslationService service;

    public TranslationController(TranslationService service) {
        this.service = service;
    }

    @PostMapping("/v1")
    public ApiResponse<TranslationDto.View> translate(
            @Valid @RequestBody TranslationDto.Request request) {
        return ApiResponse.ok(service.translate(CurrentUser.id(), request));
    }

    @GetMapping("/v1")
    public ApiResponse<TranslationDto.PageView> history(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.history(CurrentUser.id(), page, size));
    }

    @GetMapping("/{id}/v1")
    public ApiResponse<TranslationDto.View> one(@PathVariable long id) {
        return ApiResponse.ok(service.one(CurrentUser.id(), id));
    }

    @DeleteMapping("/{id}/v1")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(CurrentUser.id(), id);
        return ApiResponse.ok();
    }
}
