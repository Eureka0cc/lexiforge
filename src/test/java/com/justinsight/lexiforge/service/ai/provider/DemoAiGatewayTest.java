package com.justinsight.lexiforge.service.ai.provider;

import com.justinsight.lexiforge.common.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoAiGatewayTest {
    private final DemoAiGateway gateway = new DemoAiGateway();

    @Test
    void reportsThatAiIsUnavailable() {
        assertThat(gateway.available()).isFalse();
    }

    @Test
    void refusesToCreateFakeTranslationData() {
        var prompt = new AiGateway.TranslationPrompt("farm", "EN", "ZH",
                "IT", "软件开发", List.of(new AiGateway.GlossaryTerm("repository", "仓库")));

        assertThatThrownBy(() -> gateway.translate(prompt))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code())
                .isEqualTo("AI_NOT_CONFIGURED");
    }
}
