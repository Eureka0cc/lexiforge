package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.common.ApiResponse;
import com.justinsight.lexiforge.model.dto.AuthDto;
import com.justinsight.lexiforge.service.AccountRecoveryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AccountRecoveryController {
    private final AccountRecoveryService recovery;
    public AccountRecoveryController(AccountRecoveryService recovery) { this.recovery = recovery; }
    @PostMapping("/password-reset/request/v1")
    public ApiResponse<Void> requestReset(@Valid @RequestBody AuthDto.EmailRequest request) {
        recovery.request(request.email(), "RESET_PASSWORD"); return ApiResponse.ok();
    }
    @PostMapping("/password-reset/confirm/v1")
    public ApiResponse<Void> reset(@Valid @RequestBody AuthDto.ResetRequest request) {
        recovery.reset(request.token(), request.password()); return ApiResponse.ok();
    }
    @PostMapping("/email-verification/request/v1")
    public ApiResponse<Void> requestVerify(@Valid @RequestBody AuthDto.EmailRequest request) {
        recovery.request(request.email(), "VERIFY_EMAIL"); return ApiResponse.ok();
    }
    @PostMapping("/email-verification/confirm/v1")
    public ApiResponse<Void> verify(@Valid @RequestBody AuthDto.VerifyRequest request) {
        recovery.verify(request.token()); return ApiResponse.ok();
    }
}
