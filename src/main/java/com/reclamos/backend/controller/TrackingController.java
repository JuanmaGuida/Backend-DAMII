package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.TrackingAccessRequest;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.service.TrackingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class TrackingController {
    private final TrackingService trackingService;

    @PostMapping(value = "/access", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TrackingTicketResponse> access(@Valid @RequestBody TrackingAccessRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(trackingService.findByTrackingCode(request.getTrackingCode()));
    }
}