package com.justinsight.lexiforge.integration;

import com.justinsight.lexiforge.service.AuthService;
import com.justinsight.lexiforge.service.EntitlementService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;

/** Real downstream socket -> Tomcat -> Spring AI SDK -> local upstream socket -> PostgreSQL. No paid AI. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.model.chat=openai", "app.ai.enabled=true", "app.ai.provider-structured-output=false",
        "spring.ai.openai.api-key=transport-fixture-key", "spring.ai.openai.chat.options.model=transport-fixture",
        "spring.ai.openai.chat.options.extra-body.thinking.type=disabled",
        "app.jwt.secret=test-only-secret-never-use-in-production-0123456789"})
@EnabledIfEnvironmentVariable(named="LEXIFORGE_TEST_DB_URL", matches=".+")
class TranslationDisconnectIT {
    private static HttpServer provider;
    private static final AtomicInteger providerCalls = new AtomicInteger();
    private static final AtomicInteger chunks = new AtomicInteger();
    private static final AtomicBoolean upstreamAborted = new AtomicBoolean();
    private static final AtomicBoolean finishNormally = new AtomicBoolean();
    private static final AtomicReference<String> thinkingMode = new AtomicReference<>();
    private static final CountDownLatch stopped = new CountDownLatch(1);
    private static final ObjectMapper mapper = new ObjectMapper();
    @Autowired AuthService auth;
    @Autowired EntitlementService entitlements;
    @Autowired JdbcClient jdbc;
    @Value("${local.server.port}") int port;
    private long uid;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        ScenePersistenceIT.database(registry);
        registry.add("spring.ai.openai.base-url", TranslationDisconnectIT::providerUrl);
    }
    private static synchronized String providerUrl() {
        if (provider == null) {
            try {
                provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                provider.createContext("/chat/completions", exchange -> {
                    var request = mapper.readTree(exchange.getRequestBody().readAllBytes());
                    thinkingMode.set(request.path("thinking").path("type").asText());
                    providerCalls.incrementAndGet();
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                    exchange.sendResponseHeaders(200, 0);
                    try {
                        if (finishNormally.get()) {
                            frame(exchange.getResponseBody(), "{\"translatedText\":\"Retry complete\",\"detectedLanguage\":\"EN\",\"alternatives\":[]}", null);
                            frame(exchange.getResponseBody(), "", "stop");
                            exchange.getResponseBody().write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
                        } else {
                            frame(exchange.getResponseBody(), "{\"translatedText\":\"", null);
                            for (int index = 0; index < 500; index++) {
                                frame(exchange.getResponseBody(), "partial-content-".repeat(4), null);
                                chunks.incrementAndGet(); Thread.sleep(10);
                            }
                        }
                    } catch (IOException error) { upstreamAborted.set(true); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                    finally { exchange.close(); stopped.countDown(); }
                });
                provider.start();
            } catch (IOException error) { throw new UncheckedIOException(error); }
        }
        return "http://127.0.0.1:" + provider.getAddress().getPort();
    }
    private static void frame(OutputStream output, String content, String finish) throws IOException {
        var choice = new LinkedHashMap<String,Object>();
        choice.put("index", 0); choice.put("delta", Map.of("content", content));
        if (finish != null) choice.put("finish_reason", finish);
        String value = mapper.writeValueAsString(Map.of("id", "fixture", "object", "chat.completion.chunk",
                "created", 1, "model", "transport-fixture", "choices", List.of(choice)));
        output.write(("data: " + value + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    @AfterAll static void closeProvider() { if (provider != null) provider.stop(0); }
    @AfterEach void cleanup() {
        if (uid != 0) jdbc.sql("DELETE FROM app_user WHERE id=:uid").param("uid", uid).update();
    }

    @Test void clientDisconnectStopsUpstreamReleasesQuotaAndAllowsFreshRetry() throws Exception {
        var account = auth.register("disconnect-"+UUID.randomUUID()+"@example.test", "Acceptance123!", "Disconnect fixture");
        uid = Long.parseLong(account.user().id());
        byte[] payload = requestBody();
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15000);
            String headers = "POST /api/translations/stream/v1 HTTP/1.1\r\nHost: 127.0.0.1:"+port+
                    "\r\nAuthorization: Bearer "+account.accessToken()+"\r\nContent-Type: application/json\r\nAccept: text/event-stream\r\nContent-Length: "+payload.length+"\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(payload); socket.getOutputStream().flush();
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            boolean received = false;
            for (int lines = 0; lines < 100; lines++) {
                String line = input.readLine(); if (line == null) break;
                if (line.startsWith("data:") && line.contains("\"text\"")) { received = true; break; }
            }
            assertThat(received).as("received real downstream delta before disconnect").isTrue();
            socket.setSoLinger(true, 0); // Real TCP reset while the upstream is still streaming.
        }
        assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(upstreamAborted.get()).as("SDK closed its upstream HTTP connection").isTrue();
        assertThat(chunks.get()).isLessThan(500);
        await(() -> count("ai_usage_reservation", "status='RELEASED'") == 1);
        await(() -> count("ai_request", "status='CANCELLED'") == 1);
        assertThat(count("translation", "true")).isZero();
        assertThat(entitlements.usage(uid).translationsUsed()).isZero();
        assertThat(count("ai_usage_reservation", "status='COMMITTED'")).isZero();

        finishNormally.set(true);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/translations/stream/v1"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer "+account.accessToken())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(requestBody())).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("completed", "Retry complete");
        }
        assertThat(providerCalls.get()).isEqualTo(2);
        assertThat(thinkingMode.get()).as("Spring property binding reaches actual SDK request").isEqualTo("disabled");
        assertThat(count("translation", "true")).isEqualTo(1);
        assertThat(count("ai_usage_reservation", "status='COMMITTED'")).isEqualTo(1);
        assertThat(entitlements.usage(uid).translationsUsed()).isEqualTo(1);
    }
    private byte[] requestBody() {
        return mapper.writeValueAsBytes(Map.of("requestId", UUID.randomUUID().toString(), "sourceText", "Disconnect fixture source",
                "sourceLanguage", "EN", "targetLanguage", "ZH", "forceRefresh", true));
    }
    private long count(String table, String condition) {
        return jdbc.sql("SELECT count(*) FROM "+table+" WHERE user_id=:uid AND "+condition).param("uid",uid).query(Long.class).single();
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
