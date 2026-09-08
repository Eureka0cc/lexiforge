package com.justinsight.lexiforge.controller;

import com.justinsight.lexiforge.model.dto.AuthDto;
import com.justinsight.lexiforge.service.AuthService;
import com.justinsight.lexiforge.common.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String COOKIE = "lexiforge_refresh";
    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register/v1")
    public ApiResponse<AuthDto.AuthResponse> register(
            @Valid @RequestBody AuthDto.RegisterRequest request,
            HttpServletResponse response) {
        return respond(service.register(request.email(), request.password(), request.displayName()),
                response);
    }

    @PostMapping("/login/v1")
    public ApiResponse<AuthDto.AuthResponse> login(
            @Valid @RequestBody AuthDto.LoginRequest request,
            HttpServletResponse response) {
        return respond(service.login(request.email(), request.password()), response);
    }

    @PostMapping("/refresh/v1")
    public ApiResponse<AuthDto.AuthResponse> refresh(
            @CookieValue(name = COOKIE) String token,
            HttpServletResponse response) {
        return respond(service.refresh(token), response);
    }

    @PostMapping("/logout/v1")
    public ApiResponse<Void> logout(
            @CookieValue(name = COOKIE, required = false) String token,
            HttpServletResponse response) {
        service.logout(token);
        clearCookie(response);
        return ApiResponse.ok();
    }

    private ApiResponse<AuthDto.AuthResponse> respond(AuthService.Tokens tokens,
                                                       HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE, tokens.refreshToken())
                .httpOnly(true).sameSite("Lax").path("/api/auth")
                .maxAge(tokens.refreshMaxAge()).build();
        response.addHeader("Set-Cookie", cookie.toString());
        return ApiResponse.ok(new AuthDto.AuthResponse(tokens.accessToken(), tokens.user()));
    }

    private void clearCookie(HttpServletResponse response) {
        response.addHeader("Set-Cookie", ResponseCookie.from(COOKIE, "")
                .httpOnly(true).path("/api/auth").maxAge(0).build().toString());
    }
}
