package com.reclamos.backend.ai;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.function.Function;

/** Piezas compartidas por los tests de IA: un modelo falso que registra lo que recibe y un reloj movible. */
final class AiTestSupport {
    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private AiTestSupport() {
    }

    static AiProperties enabledProperties() {
        AiProperties properties = new AiProperties();
        properties.setEnabled(true);
        properties.setApiKey("clave-de-prueba");
        return properties;
    }

    /** Modelo falso: devuelve lo que se le configure o falla, y guarda los prompts recibidos. */
    static final class FakeLlm implements LlmClient {
        String systemPrompt;
        String userPrompt;
        int calls;
        Function<String, String> responder = prompt -> "{}";

        FakeLlm answering(String json) {
            responder = prompt -> json;
            return this;
        }

        FakeLlm failing() {
            responder = prompt -> {
                throw new LlmUnavailableException("proveedor caído");
            };
            return this;
        }

        @Override
        public String complete(String systemInstruction, String userContent) {
            calls++;
            systemPrompt = systemInstruction;
            userPrompt = userContent;
            return responder.apply(userContent);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-13T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static AiGateway gateway(AiProperties properties, LlmClient llm, Clock clock) {
        return new AiGateway(properties, llm, MAPPER, clock);
    }
}
