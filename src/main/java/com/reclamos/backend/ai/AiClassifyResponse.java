package com.reclamos.backend.ai;

/**
 * Resultado de revisar un reclamo con IA. Es una sugerencia: nunca cambia el reclamo.
 *
 * @param source   {@code AI} si respondió el modelo; {@code UNAVAILABLE} si hay que usar la alternativa sin IA
 * @param quality  {@code OK}, {@code TOO_VAGUE} (no se entiende qué se reclama) u {@code OFF_TOPIC}
 *                 (no es un tema municipal); nulo si la IA no estuvo disponible
 * @param mismatch verdadero sólo si el tipo sugerido difiere del elegido con confianza suficiente
 */
public record AiClassifyResponse(
        String source,
        String quality,
        Long suggestedRequestTypeId,
        String suggestedRequestTypeName,
        String suggestedCategoryName,
        Double confidence,
        boolean mismatch,
        String reason
) {
    public static final String SOURCE_AI = "AI";
    public static final String SOURCE_UNAVAILABLE = "UNAVAILABLE";

    public static AiClassifyResponse unavailable() {
        return new AiClassifyResponse(SOURCE_UNAVAILABLE, null, null, null, null, null, false, null);
    }
}
