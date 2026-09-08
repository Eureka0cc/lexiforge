package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.DomainDto;
import com.justinsight.lexiforge.service.DomainService;
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
@RequestMapping("/api/domains")
public class DomainController {
    private final DomainService service;

    public DomainController(DomainService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<List<DomainDto.DomainView>> list() {
        return ApiResponse.ok(service.list(CurrentUser.id()));
    }

    @PostMapping("/v1")
    public ApiResponse<DomainDto.DomainView> create(
            @Valid @RequestBody DomainDto.DomainRequest request) {
        return ApiResponse.ok(service.create(CurrentUser.id(), request));
    }

    @PatchMapping("/{id}/v1")
    public ApiResponse<DomainDto.DomainView> update(
            @PathVariable long id,
            @Valid @RequestBody DomainDto.DomainRequest request) {
        return ApiResponse.ok(service.update(CurrentUser.id(), id, request));
    }

    @DeleteMapping("/{id}/v1")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(CurrentUser.id(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/terms/v1")
    public ApiResponse<DomainDto.TermView> addTerm(
            @PathVariable long id,
            @Valid @RequestBody DomainDto.TermRequest request) {
        return ApiResponse.ok(service.addTerm(CurrentUser.id(), id, request));
    }

    @DeleteMapping("/{domainId}/terms/{termId}/v1")
    public ApiResponse<Void> removeTerm(@PathVariable long domainId, @PathVariable long termId) {
        service.removeTerm(CurrentUser.id(), domainId, termId);
        return ApiResponse.ok();
    }
}
