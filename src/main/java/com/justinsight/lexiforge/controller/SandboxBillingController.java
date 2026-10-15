package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.common.ApiResponse;
import com.justinsight.lexiforge.model.dto.BillingDto;
import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.service.billing.*;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@Profile({"dev","test"})
@RestController
@RequestMapping("/api/billing")
public class SandboxBillingController {
    private final BillingService billing;
    private final BillingSettlement settlement;
    public SandboxBillingController(BillingService billing,BillingSettlement settlement) { this.billing=billing; this.settlement=settlement; }
    @PostMapping("/orders/{id}/sandbox-pay/v1") public ApiResponse<BillingDto.Order> pay(@PathVariable long id,@RequestParam(defaultValue="true") boolean notify) {
        return ApiResponse.ok(billing.pay(CurrentUser.id(),id,notify));
    }
    @PostMapping("/orders/{id}/sandbox-refund/v1") public ApiResponse<BillingDto.Order> refund(@PathVariable long id,@RequestParam(defaultValue="true") boolean notify) {
        return ApiResponse.ok(billing.refund(CurrentUser.id(),id,notify));
    }
    @PostMapping("/sandbox-notify/v1") public ApiResponse<Void> notify(@Valid @RequestBody BillingDto.Notify notification) {
        settlement.accept(notification.event(),notification.signature()); return ApiResponse.ok();
    }
}
