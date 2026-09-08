package com.justinsight.lexiforge.service.ai.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekGatewayTest {
    @Test
    void sendsContextAndNonThinkingJsonRequestToCompatibleEndpoint() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var mapper = new ObjectMapper();
            byte[] body = mapper.writeValueAsBytes(Map.of("id", "test", "object", "chat.completion", "created", 1,
                    "model", "deepseek-v4-pro", "choices", List.of(Map.of("index", 0,
                            "message", Map.of("role", "assistant", "content", mapper.writeValueAsString(Map.of(
                                    "detectedLanguage", "en", "translatedText", "解决",
                                    "alternatives", List.of(), "terms", List.of()))),
                            "finish_reason", "stop"))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                    .apiKey("test-key").model("deepseek-v4-pro")
                    .extraBody(Map.of("thinking", Map.of("type", "disabled"))).build()).build();
            var gateway = new SpringAiGateway(ChatClient.builder(model), false);
            var result = gateway.translate(new AiGateway.TranslationPrompt("address", "EN", "ZH", "IT",
                    "技术文档", List.of(), "We need to address this issue.", false));
            assertThat(result.translatedText()).isEqualTo("解决");
            var json = new ObjectMapper().readTree(request.get());
            assertThat(json.path("model").asText()).isEqualTo("deepseek-v4-pro");
            assertThat(json.path("thinking").path("type").asText()).isEqualTo("disabled");
            assertThat(json.path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(json.path("max_tokens").asInt()).isEqualTo(128);
            assertThat(json.path("messages").toString()).contains("We need to address this issue.");
            assertThat(json.path("messages").get(0).path("content").asText())
                    .doesNotContain("farm", "address");
        } finally {
            server.stop(0);
        }
    }
}
