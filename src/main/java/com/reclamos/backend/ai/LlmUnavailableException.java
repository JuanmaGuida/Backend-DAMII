package com.reclamos.backend.ai;

/** El proveedor de IA no está disponible: apagado, sin clave, lento, caído o con respuesta inválida. */
public class LlmUnavailableException extends RuntimeException {
    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
