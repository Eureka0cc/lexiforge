package com.justinsight.lexiforge.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDto {
    private AuthDto() {}

    public record RegisterRequest(
            @Email @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 80) String displayName
    ) {}

    public record LoginRequest(@Email @NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 72) String password) {}
    public record UserView(String id, String email, String displayName, String role, boolean emailVerified) {}
    public record EmailRequest(@Email @NotBlank @Size(max = 254) String email) {}
    public record VerifyRequest(@NotBlank @Size(max = 64) String token) {}
    public record ResetRequest(@NotBlank @Size(max = 64) String token, @NotBlank @Size(min = 8, max = 72) String password) {}
    public record AuthResponse(String accessToken, UserView user) {}
}
