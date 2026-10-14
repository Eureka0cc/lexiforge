package com.justinsight.lexiforge.controller.ai;

import com.justinsight.lexiforge.common.ApiResponse;
import com.justinsight.lexiforge.model.dto.SceneDto;
import com.justinsight.lexiforge.model.dto.TranslationDto;
import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.service.ai.SceneService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scenes")
public class SceneController {
    private final SceneService service;
    public SceneController(SceneService service) { this.service = service; }
    @PostMapping("/generate/v1")
    public ApiResponse<SceneDto.Draft> generate(@Valid @RequestBody SceneDto.GenerateRequest request) {
        return ApiResponse.ok(service.generate(CurrentUser.id(), request));
    }
    @PostMapping("/preview/v1")
    public ApiResponse<TranslationDto.View> preview(@Valid @RequestBody SceneDto.PreviewRequest request) {
        return ApiResponse.ok(service.preview(CurrentUser.id(), request));
    }
}
