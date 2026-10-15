package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.common.ApiResponse;
import com.justinsight.lexiforge.model.dto.BillingDto;
import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.service.billing.BillingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService billing;
    public BillingController(BillingService billing) { this.billing=billing; }
    @GetMapping("/config/v1") public ApiResponse<BillingDto.Config> config() { return ApiResponse.ok(billing.config()); }
    @GetMapping("/orders/v1") public ApiResponse<List<BillingDto.Order>> list() { return ApiResponse.ok(billing.list(CurrentUser.id())); }
    @PostMapping("/orders/v1") public ApiResponse<BillingDto.Order> create(@Valid @RequestBody BillingDto.Create request) { return ApiResponse.ok(billing.create(CurrentUser.id(),request)); }
    @GetMapping("/orders/{id}/v1") public ApiResponse<BillingDto.Order> query(@PathVariable long id) { return ApiResponse.ok(billing.query(CurrentUser.id(),id)); }
    @PostMapping("/orders/{id}/cancel/v1") public ApiResponse<BillingDto.Order> cancel(@PathVariable long id) { return ApiResponse.ok(billing.cancel(CurrentUser.id(),id)); }
}
