package com.reclamos.backend.ai;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Cliente de la API REST de Gemini (tier gratuito de Google AI Studio).
 *
 * <p>La clave viaja en un header y nunca en la URL; ni la clave ni el contenido
 * de los pedidos se escriben en logs ni en mensajes de error.</p>
 */
@Component
public class GeminiLlmClient implements LlmClient {
    private static final int MAX_OUTPUT_TOKENS = 700;

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiLlmClient(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build();
    }

    @Override
    public String complete(String systemInstruction, String userContent) {
        if (!properties.isConfigured()) {
            throw new LlmUnavailableException("La IA no está configurada");
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl()
                        + "/v1beta/models/" + properties.getModel() + ":generateContent"))
                .timeout(properties.getTimeout())
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(systemInstruction, userContent), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Pedido a la IA interrumpido", interrupted);
        } catch (IOException | RuntimeException failure) {
            throw new LlmUnavailableException("No se pudo consultar a la IA", failure);
        }
        if (response.statusCode() != 200) {
            throw new LlmUnavailableException("La IA respondió con estado " + response.statusCode());
        }
        return extractText(response.body());
    }

    private String buildBody(String systemInstruction, String userContent) {
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", systemInstruction);
        ObjectNode content = body.putArray("contents").addObject();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", userContent);
        ObjectNode generation = body.putObject("generationConfig");
        generation.put("temperature", 0);
        generation.put("responseMimeType", "application/json");
        generation.put("maxOutputTokens", MAX_OUTPUT_TOKENS);
        return body.toString();
    }

    private String extractText(String responseBody) {
        try {
            JsonNode text = objectMapper.readTree(responseBody).path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");
            if (!text.isString() || text.asString().isBlank()) {
                throw new LlmUnavailableException("La IA no devolvió contenido");
            }
            return text.asString();
        } catch (LlmUnavailableException unavailable) {
            throw unavailable;
        } catch (RuntimeException invalid) {
            throw new LlmUnavailableException("Respuesta de la IA ilegible", invalid);
        }
    }
}
