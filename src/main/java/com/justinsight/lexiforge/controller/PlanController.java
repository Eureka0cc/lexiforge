package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.model.dto.CommercialDto;
import com.justinsight.lexiforge.service.PlanService;
import com.justinsight.lexiforge.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/plans")
public class PlanController {
    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping("/v1")
    public ApiResponse<List<CommercialDto.PlanView>> plans() {
        return ApiResponse.ok(service.list());
    }
}
