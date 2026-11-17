package com.reclamos.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.header.HeaderWriter;

/**
 * Escribe {@code Content-Security-Policy} en todas las respuestas del backend (DDA2-262, SEG-09).
 *
 * <p>La política por defecto es la de una API JSON: no carga ningún recurso
 * ({@code default-src 'none'}) y no admite ser embebida. Hay dos excepciones
 * acotadas por ruta:</p>
 * <ul>
 *   <li>Swagger UI ({@code /swagger-ui.html}, {@code /swagger-ui/**}) necesita scripts y
 *       estilos propios, incluidos los inline que genera la librería.</li>
 *   <li>El contenido de adjuntos ({@code /api/attachments/{id}/content} y su variante de
 *       tracking) se sirve inline (PDF, imágenes): se le deja sólo
 *       {@code frame-ancestors 'none'} para no interferir con el visor del navegador.
 *       {@code X-Content-Type-Options: nosniff} y {@code Content-Disposition} ya los
 *       fija el controlador.</li>
 * </ul>
 */
public final class ApiContentSecurityPolicyHeaderWriter implements HeaderWriter {
    static final String HEADER = "Content-Security-Policy";
    static final String API_POLICY =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String DOCS_POLICY =
            "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";
    static final String ATTACHMENT_POLICY = "frame-ancestors 'none'";

    @Override
    public void writeHeaders(HttpServletRequest request, HttpServletResponse response) {
        if (response.containsHeader(HEADER)) {
            return;
        }
        response.setHeader(HEADER, policyFor(path(request)));
    }

    static String policyFor(String path) {
        if (path.equals("/swagger-ui.html") || path.equals("/swagger-ui") || path.startsWith("/swagger-ui/")) {
            return DOCS_POLICY;
        }
        if (path.startsWith("/api/attachments/") && path.endsWith("/content")
                || path.startsWith("/api/tracking/actions/attachments/") && path.endsWith("/content")) {
            return ATTACHMENT_POLICY;
        }
        return API_POLICY;
    }

    private static String path(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        return contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
    }
}
