package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.AnonymousTicketCredentialsRequest;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.service.AnonymousTicketAccessService;
import com.reclamos.backend.service.AttachmentAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequiredArgsConstructor
public class AttachmentController {
    private final AttachmentAccessService attachmentAccessService;
    private final AnonymousTicketAccessService anonymousTicketAccessService;

    @GetMapping("/api/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> download(
            @PathVariable Long attachmentId,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return response(attachmentAccessService.download(attachmentId, identity));
    }

    @PostMapping(value = "/api/tracking/actions/attachments/{attachmentId}/content",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> downloadAnonymous(
            @PathVariable Long attachmentId,
            @Valid @RequestBody AnonymousTicketCredentialsRequest credentials) {
        var access = anonymousTicketAccessService.authenticate(
                credentials.trackingCode(), credentials.anonymousAccessPassword());
        return response(attachmentAccessService.downloadAnonymous(attachmentId, access.ticketId()));
    }

    private ResponseEntity<Resource> response(AttachmentAccessService.Download download) {
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(download.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(download.contentType()))
                .contentLength(download.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(download.resource());
    }
}
