package com.reclamos.backend.controller;

import com.reclamos.backend.dto.response.AgentDirectoryEntry;
import com.reclamos.backend.service.AgentDirectoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Directorio de personal de M2 para el panel de administración (sólo ADMIN, ver SecurityConfiguration). */
@RestController
@RequestMapping("/api/admin/agents")
@RequiredArgsConstructor
public class AdminAgentController {
    private final AgentDirectoryService service;

    @GetMapping
    public ResponseEntity<List<AgentDirectoryEntry>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list());
    }
}
