package com.reclamos.backend.controller;

import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.ExternalAttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ExternalAttachmentController {
    private final ExternalAttachmentService service;

    /** Returns references after ticket authorization; never redirects, fetches or proxies the URL. */
    @GetMapping("/api/tickets/{ticketId}/external-attachments")
    public ResponseEntity<List<ExternalAttachmentService.Reference>> list(@PathVariable UUID ticketId,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(ticketId, identity));
    }
}
