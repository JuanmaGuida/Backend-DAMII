package com.reclamos.backend.ai;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Punto único de salida hacia el modelo de lenguaje. Concentra las defensas
 * comunes a todas las funciones de IA:
 *
 * <ul>
 *   <li>apagado por configuración y tope global por minuto (cuota gratuita);</li>
 *   <li>corte del proveedor tras fallos seguidos, para no esperar el timeout en cada pedido;</li>
 *   <li>texto del usuario acotado, sin datos personales y sin poder cerrar los delimitadores del prompt;</li>
 *   <li>salida siempre tratada como dato no confiable: se exige JSON y cada función valida sus campos.</li>
 * </ul>
 *
 * <p>Si algo falla devuelve {@link Optional#empty()}: quien llama usa su alternativa sin IA.</p>
 */
@Component
@RequiredArgsConstructor
public class AiGateway {
    private static final Logger log = LoggerFactory.getLogger(AiGateway.class);
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern LINKS = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private final AiProperties properties;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final Deque<Instant> recentCalls = new ArrayDeque<>();
    private int consecutiveFailures;
    private Instant circuitOpenUntil = Instant.MIN;

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /**
     * Pide al modelo una respuesta JSON objeto. Vacío si la IA está apagada, sin cupo, cortada,
     * caída, lenta o si respondió algo que no es un objeto JSON.
     */
    public Optional<JsonNode> askForJson(String systemInstruction, String userContent) {
        if (!isAvailable() || !acquirePermit()) {
            return Optional.empty();
        }
        try {
            String raw = llmClient.complete(systemInstruction, userContent);
            JsonNode parsed = objectMapper.readTree(stripCodeFence(raw));
            if (parsed == null || !parsed.isObject()) {
                throw new LlmUnavailableException("La IA no devolvió un objeto JSON");
            }
            recordSuccess();
            return Optional.of(parsed);
        } catch (LlmUnavailableException failure) {
            recordFailure();
            // Estos mensajes los arma el cliente (estado HTTP, timeout) y nunca llevan clave ni contenido.
            log.warn("La consulta a la IA no se pudo completar: {}", failure.getMessage());
            return Optional.empty();
        } catch (RuntimeException failure) {
            recordFailure();
            // Sólo el tipo de falla: ni el contenido enviado ni el recibido llegan al log.
            log.warn("La consulta a la IA no se pudo completar: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * Prepara texto escrito por una persona para incluirlo en un prompt: lo acota, quita
     * datos personales y reemplaza los signos de etiqueta para que no pueda cerrar un delimitador.
     */
    public String untrusted(String text, int maxChars) {
        String redacted = PiiRedactor.redact(text == null ? "" : text);
        String cleaned = CONTROL_CHARS.matcher(redacted).replaceAll(" ")
                .replace('<', '‹').replace('>', '›').strip();
        cleaned = BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
        int limit = Math.min(maxChars, properties.getMaxInputChars());
        return cleaned.length() <= limit ? cleaned : cleaned.substring(0, limit);
    }

    /** Limpia un texto devuelto por el modelo antes de mostrarlo: sin enlaces ni control, acotado. */
    public static String cleanOutput(JsonNode node, int maxChars) {
        if (node == null || !node.isString()) {
            return null;
        }
        String text = CONTROL_CHARS.matcher(node.asString()).replaceAll(" ");
        text = LINKS.matcher(text).replaceAll("").replace('\n', ' ').replaceAll("\\s{2,}", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars).stripTrailing() + "…";
    }

    private synchronized boolean acquirePermit() {
        Instant now = clock.instant();
        if (now.isBefore(circuitOpenUntil)) {
            return false;
        }
        Instant windowStart = now.minusSeconds(60);
        while (!recentCalls.isEmpty() && recentCalls.peekFirst().isBefore(windowStart)) {
            recentCalls.pollFirst();
        }
        if (recentCalls.size() >= properties.getMaxRequestsPerMinute()) {
            return false;
        }
        recentCalls.addLast(now);
        return true;
    }

    private synchronized void recordSuccess() {
        consecutiveFailures = 0;
    }

    private synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= properties.getCircuitFailureThreshold()) {
            circuitOpenUntil = clock.instant().plus(properties.getCircuitOpenFor());
            consecutiveFailures = 0;
        }
    }

    private static String stripCodeFence(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstNewline > 0 && closing > firstNewline) {
                return text.substring(firstNewline + 1, closing).strip();
            }
        }
        return text;
    }
}
