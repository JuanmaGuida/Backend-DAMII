package com.reclamos.backend.controller;

import com.reclamos.backend.dto.response.StaffCapabilitiesResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Capacidades del servidor que el personal necesita conocer (sólo AGENT, AREA_RESPONSIBLE y ADMIN,
 * ver SecurityConfiguration). Hoy informa si el simulador de eventos externos está habilitado:
 * sin esto el front ofrece acciones de "Simulación DEV" que el servidor responde con 404.
 */
@RestController
@RequestMapping("/api/staff/capabilities")
public class StaffCapabilitiesController {
    private final boolean simulatorEnabled;

    public StaffCapabilitiesController(@Value("${app.simulator.enabled:false}") boolean simulatorEnabled) {
        this.simulatorEnabled = simulatorEnabled;
    }

    @GetMapping
    public ResponseEntity<StaffCapabilitiesResponse> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new StaffCapabilitiesResponse(simulatorEnabled));
    }
}
