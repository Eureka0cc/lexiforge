package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.common.ApiResponse;
import com.justinsight.lexiforge.model.dto.AuthDto;
import com.justinsight.lexiforge.security.CurrentUser;
import com.justinsight.lexiforge.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {
    private final AuthService auth;
    public AccountController(AuthService auth) { this.auth = auth; }
    @GetMapping("/api/account/v1")
    public ApiResponse<AuthDto.UserView> current() { return ApiResponse.ok(auth.current(CurrentUser.id())); }
}
