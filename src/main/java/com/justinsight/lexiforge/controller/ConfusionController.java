package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.ConfusionDto;
import com.justinsight.lexiforge.service.ConfusionService;
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
@RequestMapping("/api/confusion-groups")
public class ConfusionController {
    private final ConfusionService service;

    public ConfusionController(ConfusionService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<List<ConfusionDto.GroupView>> list() {
        return ApiResponse.ok(service.list(CurrentUser.id()));
    }

    @PostMapping("/v1")
    public ApiResponse<ConfusionDto.GroupView> create(
            @Valid @RequestBody ConfusionDto.CreateRequest request) {
        return ApiResponse.ok(service.create(CurrentUser.id(), request));
    }

    @DeleteMapping("/{id}/v1")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(CurrentUser.id(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{groupId}/mnemonics/v1")
    public ApiResponse<ConfusionDto.MnemonicView> mnemonic(
            @PathVariable long groupId,
            @Valid @RequestBody ConfusionDto.MnemonicRequest request) {
        return ApiResponse.ok(service.createMnemonic(CurrentUser.id(), groupId, request));
    }

    @PatchMapping("/{groupId}/mnemonics/{mnemonicId}/v1")
    public ApiResponse<ConfusionDto.MnemonicView> update(
            @PathVariable long groupId,
            @PathVariable long mnemonicId,
            @RequestBody ConfusionDto.MnemonicUpdate request) {
        return ApiResponse.ok(service.updateMnemonic(CurrentUser.id(), groupId, mnemonicId, request));
    }
}
