package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.service.AdminMetricsService;
import com.justinsight.lexiforge.common.ApiResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/metrics")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMetricsController {
    private final AdminMetricsService service;

    public AdminMetricsController(AdminMetricsService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<Map<String, Object>> metrics() {
        return ApiResponse.ok(service.metrics());
    }
}
