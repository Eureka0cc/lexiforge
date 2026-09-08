package com.justinsight.lexiforge.service.ai.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 直连 Agnes API 的实测：验证 response_format=json_schema（provider structured output）
 * 端到端可用。默认跳过，设置 AI_LIVE_TEST=true 后运行。
 */
@EnabledIfEnvironmentVariable(named = "AI_LIVE_TEST", matches = "true")
class SpringAiGatewayStructuredOutputLiveTest {
    private static final Pattern BASE_URL = Pattern.compile("(?m)^\\s*base-url:\\s*(\\S+)");
    private static final Pattern API_KEY = Pattern.compile("(?m)^\\s*api-key:\\s*(\\S+)");
    private static final Pattern MODEL = Pattern.compile("(?m)^\\s*model:\\s*(\\S+)");

    @Test
    void translateWithProviderStructuredOutputSchema() throws IOException {
        SpringAiGateway gateway = gateway();
        AiGateway.TranslationResult result = gateway.translate(new AiGateway.TranslationPrompt(
                "farm", "en", "zh", "IT", "信息技术领域", List.of()));

        assertThat(result.translatedText()).isNotBlank();
        assertThat(result.detectedLanguage()).isNotBlank();
        assertThat(result.terms()).isNotNull();
    }

    @Test
    void gradeWithProviderStructuredOutputSchema() throws IOException {
        SpringAiGateway gateway = gateway();
        AiGateway.GradeResult result = gateway.grade(new AiGateway.GradePrompt(
                "EN2CN", "address", "解决；处理", "solve the memory leak", "v.", ""));

        assertThat(result.result()).isIn("CORRECT", "PARTIAL", "WRONG");
    }

    private static SpringAiGateway gateway() throws IOException {
        String yaml;
        try (var input = SpringAiGatewayStructuredOutputLiveTest.class.getResourceAsStream("/application-local.yml")) {
            assertThat(input).isNotNull();
            yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var baseUrl = match(yaml, BASE_URL);
        var apiKey = match(yaml, API_KEY);
        var model = match(yaml, MODEL);

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .model(model)
                        .build())
                .build();
        return new SpringAiGateway(ChatClient.builder(chatModel), true);
    }

    private static String match(String yaml, Pattern pattern) {
        var matcher = pattern.matcher(yaml);
        assertThat(matcher.find()).as("application-local.yml 缺少配置项 %s", pattern).isTrue();
        return matcher.group(1);
    }
}
