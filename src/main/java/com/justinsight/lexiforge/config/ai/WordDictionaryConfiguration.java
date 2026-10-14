package com.justinsight.lexiforge.config.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WordDictionaryConfiguration.Settings.class)
public class WordDictionaryConfiguration {
    @ConfigurationProperties("app.ai.word-dictionary")
    public record Settings(@DefaultValue("false") boolean enabled,
                           @DefaultValue("2") int minRepeat) {
        public Settings {
            if (minRepeat < 1) {
                throw new IllegalArgumentException("Word dictionary minRepeat must be positive");
            }
        }
    }
}
