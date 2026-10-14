package com.justinsight.lexiforge.service.ai.provider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    static SpringAiGateway gateway() throws IOException {
        var environment = new org.springframework.core.env.StandardEnvironment();
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader().load("live-ai",
                new org.springframework.core.io.FileSystemResource("src/main/resources/application-local.yml"));
        sources.forEach(source -> environment.getPropertySources().addFirst(source));
        var builder = OpenAiChatOptions.builder()
                .baseUrl(environment.getRequiredProperty("spring.ai.openai.base-url"))
                .apiKey(environment.getRequiredProperty("spring.ai.openai.api-key"))
                .model(environment.getRequiredProperty("spring.ai.openai.chat.options.model"));
        String thinking = environment.getProperty("spring.ai.openai.chat.options.extra-body.thinking.type");
        if (thinking != null) builder.extraBody(java.util.Map.of("thinking", java.util.Map.of("type", thinking)));
        String reasoning = environment.getProperty("spring.ai.openai.chat.options.reasoning-effort");
        if (reasoning != null) builder.reasoningEffort(reasoning);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(builder.build())
                .build();
        return new SpringAiGateway(ChatClient.builder(chatModel),
                environment.getProperty("app.ai.provider-structured-output", Boolean.class, false));
    }
}
