package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.TrackingAccessRequest;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.service.TrackingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class TrackingController {
    private final TrackingService trackingService;

    @PostMapping("/access")
    public TrackingTicketResponse access(@Valid @RequestBody TrackingAccessRequest request) {
        return trackingService.findByTrackingCode(request.getTrackingCode());
    }
}