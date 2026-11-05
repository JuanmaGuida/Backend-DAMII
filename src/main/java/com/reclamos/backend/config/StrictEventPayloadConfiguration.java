package com.reclamos.backend.config;

import com.reclamos.backend.dto.StrictEventPayload;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;

/**
 * El resto de la API tolera propiedades desconocidas en el body, pero los
 * eventos de integración deben cumplir su schema de forma estricta. En lugar
 * de cambiar esa tolerancia para todo el back, se rechazan las propiedades no
 * declaradas únicamente en los tipos que implementan {@link StrictEventPayload}.
 */
@Configuration
public class StrictEventPayloadConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictEventPayloadCustomizer() {
        return builder -> builder.addHandler(new RejectUnknownEventProperties());
    }

    static final class RejectUnknownEventProperties extends DeserializationProblemHandler {
        @Override
        public boolean handleUnknownProperty(DeserializationContext context, JsonParser parser,
                                             ValueDeserializer<?> deserializer, Object beanOrClass,
                                             String propertyName) {
            Class<?> type = beanOrClass instanceof Class<?> declared ? declared : beanOrClass.getClass();
            if (StrictEventPayload.class.isAssignableFrom(type)) {
                return context.reportInputMismatch(type, "Propiedad no admitida en %s: '%s'",
                        type.getSimpleName(), propertyName);
            }
            return false;
        }
    }
}
