package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.response.InformationRequestResponse;
import com.reclamos.backend.dto.response.TicketResolutionActionResponse;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.service.AnonymousTicketAccessService;
import com.reclamos.backend.service.InformationRequestService;
import com.reclamos.backend.service.TicketResolutionService;
import com.reclamos.backend.service.TicketService;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/tracking")
@RequiredArgsConstructor
public class TrackingController {
    private final TrackingService trackingService;
    private final AnonymousTicketAccessService anonymousTicketAccessService;
    private final TicketService ticketService;
    private final InformationRequestService informationRequestService;
    private final TicketResolutionService ticketResolutionService;

    @PostMapping(value = "/access", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TrackingTicketResponse> access(@Valid @RequestBody TrackingAccessRequest request) {
        if (request.getAnonymousAccessPassword() != null) {
            anonymousTicketAccessService.authenticate(
                    request.getTrackingCode(), request.getAnonymousAccessPassword());
        }
        return noStore(trackingService.findByTrackingCode(request.getTrackingCode()));
    }

    @PostMapping(value = "/actions/cancel", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TicketResponse> cancel(
            @Valid @RequestBody AnonymousTicketActionRequest<@Valid CancelTicketRequest> request) {
        var access = anonymousTicketAccessService.authenticate(
                request.trackingCode(), request.anonymousAccessPassword());
        return noStore(ticketService.cancelAnonymousTicket(access.ticketId(), request.payload()));
    }

    @PostMapping(value = "/actions/information-response", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InformationRequestResponse> answerInformation(
            @Valid @RequestBody AnonymousTicketActionRequest<@Valid AnswerInformationRequest> request) {
        var access = anonymousTicketAccessService.authenticate(
                request.trackingCode(), request.anonymousAccessPassword());
        return noStore(informationRequestService.answerAnonymousFromTracking(
                access.ticketId(), request.payload()));
    }

    @PostMapping(value = "/actions/information-response", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<InformationRequestResponse> answerInformationMultipart(
            @Valid @RequestPart("data") AnonymousTicketActionRequest<@Valid AnswerInformationRequest> request,
            @RequestPart(value = "attachments", required = false) MultipartFile[] attachments) {
        var access = anonymousTicketAccessService.authenticate(
                request.trackingCode(), request.anonymousAccessPassword());
        return noStore(informationRequestService.answerAnonymousFromTracking(
                access.ticketId(), request.payload(), attachments));
    }

    @PostMapping(value = "/actions/confirm-resolution", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TicketResolutionActionResponse> confirmResolution(
            @Valid @RequestBody AnonymousTicketCredentialsRequest request) {
        var access = anonymousTicketAccessService.authenticate(
                request.trackingCode(), request.anonymousAccessPassword());
        return noStore(ticketResolutionService.confirmAnonymous(access.ticketId()));
    }

    @PostMapping(value = "/actions/reopen", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TicketResolutionActionResponse> reopen(
            @Valid @RequestBody AnonymousTicketActionRequest<@Valid ReopenTicketRequest> request) {
        var access = anonymousTicketAccessService.authenticate(
                request.trackingCode(), request.anonymousAccessPassword());
        return noStore(ticketResolutionService.reopenAnonymous(access.ticketId(), request.payload()));
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
