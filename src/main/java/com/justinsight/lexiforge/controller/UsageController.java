package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.service.EntitlementService;
import com.justinsight.lexiforge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {
    private final EntitlementService service;

    public UsageController(EntitlementService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<EntitlementService.Usage> usage() {
        return ApiResponse.ok(service.usage(CurrentUser.id()));
    }
}
