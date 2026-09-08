package com.justinsight.lexiforge.service.ai.provider;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AgnesAiConfigurationTest {
    @Test
    void agnesProfileUsesEnvironmentSecretAndOpenAiCompatibleEndpoint() throws Exception {
        try (var input = getClass().getResourceAsStream("/application-agnes.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(yaml)
                    .contains("chat: openai")
                    .contains("https://apihub.agnes-ai.com/v1")
                    .contains("${AGNES_API_KEY}")
                    .contains("options:")
                    .contains("agnes-2.0-flash")
                    .doesNotContain("sk-");
        }
    }
}
