package com.reclamos.backend.ai;

/** Cliente de un modelo de lenguaje. Devuelve el texto de la respuesta, que se espera sea JSON. */
public interface LlmClient {

    /**
     * @param systemInstruction instrucciones fijas del sistema, nunca construidas con texto del usuario
     * @param userContent       contenido a analizar, ya acotado y con datos personales removidos
     * @throws LlmUnavailableException si el proveedor no responde a tiempo o devuelve un error
     */
    String complete(String systemInstruction, String userContent);
}
