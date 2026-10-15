package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.CommercialDto;
import com.justinsight.lexiforge.service.SubscriptionService;
import com.justinsight.lexiforge.common.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/subscriptions/dev")
@Profile("dev")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.development-subscriptions",havingValue="true")
public class DevelopmentSubscriptionController {
    private final SubscriptionService service;

    public DevelopmentSubscriptionController(SubscriptionService service) {
        this.service = service;
    }

    @PostMapping("/activate/v1")
    public ApiResponse<CommercialDto.SubscriptionView> activate(
            @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(service.activate(CurrentUser.id(), days));
    }

    @PostMapping("/expire/v1")
    public ApiResponse<CommercialDto.SubscriptionView> expire() {
        return ApiResponse.ok(service.expire(CurrentUser.id()));
    }
}
