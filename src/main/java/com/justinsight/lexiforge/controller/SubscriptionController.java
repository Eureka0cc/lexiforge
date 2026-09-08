package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.CommercialDto;
import com.justinsight.lexiforge.service.SubscriptionService;
import com.justinsight.lexiforge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {
    private final SubscriptionService service;

    public SubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<CommercialDto.SubscriptionView> current() {
        return ApiResponse.ok(service.current(CurrentUser.id()));
    }
}
