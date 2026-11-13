package com.reclamos.backend.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contra un servidor HTTP local que hace de Gemini: no sale ningún pedido a Internet. */
class GeminiLlmClientTest {
    HttpServer server;
    AiProperties properties;
    GeminiLlmClient client;
    final AtomicReference<String> requestPath = new AtomicReference<>();
    final AtomicReference<String> apiKeyHeader = new AtomicReference<>();
    final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        properties = AiTestSupport.enabledProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setTimeout(Duration.ofMillis(400));
        client = new GeminiLlmClient(properties, AiTestSupport.MAPPER);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    void respond(int status, String body, long delayMillis) {
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-goog-api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (IOException clientGaveUp) {
                // El cliente ya cortó por timeout.
            } finally {
                exchange.close();
            }
        });
    }

    @Test
    void sendsTheKeyInAHeaderAndReturnsTheModelText() {
        respond(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"ok\\\":true}\"}]}}]}", 0);

        String text = client.complete("instrucciones fijas", "contenido");

        assertThat(text).isEqualTo("{\"ok\":true}");
        assertThat(apiKeyHeader.get()).isEqualTo("clave-de-prueba");
        assertThat(requestPath.get()).contains(":generateContent").doesNotContain("clave-de-prueba");
        assertThat(requestBody.get()).contains("instrucciones fijas", "contenido", "application/json");
    }

    @Test
    void providerErrorIsReportedWithoutLeakingTheKeyOrContent() {
        respond(500, "{\"error\":\"boom\"}", 0);

        assertThatThrownBy(() -> client.complete("s", "contenido secreto"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("500")
                .hasMessageNotContaining("clave-de-prueba")
                .hasMessageNotContaining("contenido secreto");
    }

    @Test
    void slowProviderTimesOut() {
        respond(200, "{}", 1500);

        assertThatThrownBy(() -> client.complete("s", "u")).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void answerWithoutTextIsUnavailable() {
        respond(200, "{\"candidates\":[]}", 0);

        assertThatThrownBy(() -> client.complete("s", "u")).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void unreachableProviderIsUnavailable() {
        server.stop(0);

        assertThatThrownBy(() -> client.complete("s", "u")).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesToCallWithoutConfiguration() {
        properties.setApiKey("");

        assertThatThrownBy(() -> client.complete("s", "u")).isInstanceOf(LlmUnavailableException.class);
    }
}
