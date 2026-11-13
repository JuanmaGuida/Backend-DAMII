package com.reclamos.backend.controller;

import com.reclamos.backend.ai.AiClassificationService;
import com.reclamos.backend.ai.AiClassifyRequest;
import com.reclamos.backend.ai.AiClassifyResponse;
import com.reclamos.backend.ai.AiTicketInsightsResponse;
import com.reclamos.backend.ai.AiTicketInsightsService;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Funciones de ayuda con IA. Ninguna modifica datos: devuelven sugerencias y, si la IA no está
 * disponible, responden 200 con {@code source=UNAVAILABLE} para que la pantalla use su alternativa.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AiController {
    private final AiClassificationService classificationService;
    private final AiTicketInsightsService insightsService;

    /** Público, igual que la creación de tickets: el ciudadano puede ser anónimo. */
    @PostMapping("/ai/classify")
    public ResponseEntity<AiClassifyResponse> classify(@Valid @RequestBody AiClassifyRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(classificationService.classify(request));
    }

    /** POST porque cada llamada consume cuota del proveedor de IA. El acceso al ticket lo valida el service. */
    @PostMapping("/staff/tickets/{ticketId}/ai-insights")
    public ResponseEntity<AiTicketInsightsResponse> insights(
            @PathVariable UUID ticketId, @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(insightsService.insights(ticketId, identity));
    }
}
