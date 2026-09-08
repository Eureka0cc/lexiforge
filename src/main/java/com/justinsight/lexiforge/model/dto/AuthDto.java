package com.justinsight.lexiforge.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDto {
    private AuthDto() {}

    public record RegisterRequest(
            @Email @NotBlank String email,
            @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName
    ) {}

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    public record UserView(String id, String email, String displayName, String role) {}
    public record AuthResponse(String accessToken, UserView user) {}
}
