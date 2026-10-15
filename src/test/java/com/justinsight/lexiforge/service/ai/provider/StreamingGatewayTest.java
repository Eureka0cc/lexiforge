package com.justinsight.lexiforge.service.ai.provider;

import com.justinsight.lexiforge.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StreamingGatewayTest {
    private final AiGateway.TranslationPrompt prompt = new AiGateway.TranslationPrompt(
            "We need to address this issue.", "EN", "ZH", "IT", "简洁严谨", List.of());

    @Test
    void silentProviderTimesOutAsTimeoutWithoutRetryOrFormatError() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var requests = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().flush();
            try { release.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var gateway = gateway(server);
            org.springframework.test.util.ReflectionTestUtils.setField(gateway, "timeoutSeconds", 1L);
            assertThatThrownBy(() -> gateway.translateStream(prompt, ignored -> {}))
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.code()).isEqualTo("AI_TIMEOUT");
                        assertThat(error.status()).isEqualTo(org.springframework.http.HttpStatus.GATEWAY_TIMEOUT);
                    });
            assertThat(requests.get()).isEqualTo(1);
        } finally { release.countDown(); server.stop(0); }
    }

    @Test
    void forwardsOnlyDecodedTranslationAndValidatesFinalObject() throws Exception {
        var server = server(List.of("{\"translatedText\":\"需要", "解决这个问题。", "\",\"detectedLanguage\":\"EN\",\"alternatives\":[]}"), "stop");
        try {
            List<String> deltas = new ArrayList<>();
            var result = gateway(server).translateStream(prompt, deltas::add);
            assertThat(deltas).containsExactly("需要", "解决这个问题。");
            assertThat(result.translatedText()).isEqualTo(String.join("", deltas));
            assertThat(result.terms()).isEmpty();
        } finally { server.stop(0); }
    }

    @Test
    void truncatedCompletionCannotBeSuccessfulEvenWhenJsonLooksComplete() throws Exception {
        var server = server(List.of("{\"translatedText\":\"不完整\",\"detectedLanguage\":\"EN\",\"alternatives\":[]}"), "length");
        try {
            assertThatThrownBy(() -> gateway(server).translateStream(prompt, ignored -> {}))
                    .isInstanceOf(ApiException.class).hasMessageContaining("未完整生成");
        } finally { server.stop(0); }
    }

    @Test
    void consumerCancellationIsPreservedRatherThanConvertedToModelFormatError() throws Exception {
        var server = server(List.of("{\"translatedText\":\"需要", "解决\",\"detectedLanguage\":\"EN\"}"), "stop");
        try {
            assertThatThrownBy(() -> gateway(server).translateStream(prompt, ignored -> {
                throw new CancellationException("用户停止");
            })).isInstanceOf(CancellationException.class);
        } finally { server.stop(0); }
    }

    private SpringAiGateway gateway(HttpServer server) {
        var model = OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey("fixture-key").model("fixture-model").build()).build();
        return new SpringAiGateway(ChatClient.builder(model), false);
    }

    private HttpServer server(List<String> chunks, String finishReason) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            var mapper = new ObjectMapper();
            for (String chunk : chunks) {
                String json = mapper.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                        "created", 1, "model", "fixture-model", "choices", List.of(Map.of("index", 0,
                                "delta", Map.of("content", chunk)))));
                exchange.getResponseBody().write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            }
            String finish = mapper.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                    "created", 1, "model", "fixture-model", "choices", List.of(Map.of("index", 0,
                            "delta", Map.of(), "finish_reason", finishReason))));
            exchange.getResponseBody().write(("data: " + finish + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();
        return server;
    }
}
