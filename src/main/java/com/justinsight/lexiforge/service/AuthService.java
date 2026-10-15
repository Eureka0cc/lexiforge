package com.justinsight.lexiforge.service;

import com.justinsight.lexiforge.model.dto.AuthDto;

public interface AuthService {
    Tokens register(String email, String password, String displayName);

    Tokens login(String email, String password);

    Tokens refresh(String raw);

    void logout(String raw);
    AuthDto.UserView current(long userId);

    record Tokens(String accessToken, String refreshToken, long refreshMaxAge,
                  AuthDto.UserView user) {}
}
