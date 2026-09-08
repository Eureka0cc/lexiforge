package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.model.dto.CommercialDto;
import com.justinsight.lexiforge.service.DashboardService;
import com.justinsight.lexiforge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<CommercialDto.Dashboard> dashboard() {
        return ApiResponse.ok(service.get(CurrentUser.id()));
    }
}
