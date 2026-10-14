package com.justinsight.lexiforge.service.ai.provider;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class StructuredOutputRetryTest {
    @Test void invalidSceneDoesNotSilentlyTriggerPaidRepairCalls() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = new ObjectMapper().writeValueAsBytes(Map.of("id", "fixture", "object", "chat.completion",
                    "created", 1, "model", "fixture", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                            "message", Map.of("role", "assistant", "content", "{\"name\":42}")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                    .baseUrl("http://127.0.0.1:" + server.getAddress().getPort()).apiKey("fixture").model("fixture").build()).build();
            var gateway = new SpringAiGateway(ChatClient.builder(model), false);
            assertThatThrownBy(() -> gateway.generateScene("翻译技术资料")).isInstanceOf(RuntimeException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally { server.stop(0); }
    }
}
