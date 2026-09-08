package com.justinsight.lexiforge.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TranslationCacheConfiguration.Settings.class)
public class TranslationCacheConfiguration {
    @ConfigurationProperties("app.ai.translation-cache")
    public record Settings(@DefaultValue("500") int maximumSize,
                           @DefaultValue("5m") Duration ttl,
                           @DefaultValue("1") String revision) {
        public Settings {
            if (maximumSize < 1 || ttl == null || ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("Translation cache size and TTL must be positive");
            }
        }
    }
}
