package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.ReviewDto;
import com.justinsight.lexiforge.service.ReviewService;
import com.justinsight.lexiforge.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/review-sessions")
public class ReviewController {
    private final ReviewService service;

    public ReviewController(ReviewService service) {
        this.service = service;
    }

    @PostMapping("/v1")
    public ApiResponse<ReviewDto.Session> start(
            @Valid @RequestBody ReviewDto.StartRequest request) {
        return ApiResponse.ok(service.start(CurrentUser.id(), request));
    }

    @PostMapping("/{sessionId}/answers/v1")
    public ApiResponse<ReviewDto.AnswerResult> answer(
            @PathVariable long sessionId,
            @Valid @RequestBody ReviewDto.AnswerRequest request) {
        return ApiResponse.ok(service.answer(CurrentUser.id(), sessionId, request));
    }

    @PostMapping("/{sessionId}/finish/v1")
    public ApiResponse<Void> finish(@PathVariable long sessionId) {
        service.finish(CurrentUser.id(), sessionId);
        return ApiResponse.ok();
    }
}
