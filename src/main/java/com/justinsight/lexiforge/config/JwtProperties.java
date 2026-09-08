package com.justinsight.lexiforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret, long accessMinutes, long refreshDays) {
}
