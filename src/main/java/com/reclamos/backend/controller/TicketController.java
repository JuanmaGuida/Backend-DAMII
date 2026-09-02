package com.reclamos.backend.controller;

import com.reclamos.backend.dto.request.AnswerInformationRequest;
import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.dto.response.InformationRequestResponse;
import com.reclamos.backend.service.InformationRequestService;
import com.reclamos.backend.service.TicketService;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;
    private final InformationRequestService informationRequestService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CreateTicketResponse create(@Valid @RequestBody CreateTicketRequest request,
                                       @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketService.create(request, identity, new MultipartFile[0]);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CreateTicketResponse createMultipart(
            @Valid @RequestPart("data") CreateTicketRequest request,
            @RequestPart(value = "evidence", required = false) MultipartFile[] evidence,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return ticketService.create(request, identity, evidence);
    }

    @PostMapping(path = "/{ticketId}/information-request", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public InformationRequestResponse requestInformation(
            @PathVariable UUID ticketId,
            @Valid @RequestBody CreateInformationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return informationRequestService.requestInformation(ticketId, request, identity);
    }

    @PostMapping(path = "/{ticketId}/information-response", consumes = MediaType.APPLICATION_JSON_VALUE)
    public InformationRequestResponse answerInformation(
            @PathVariable UUID ticketId,
            @Valid @RequestBody AnswerInformationRequest request,
            @AuthenticationPrincipal AuthenticatedIdentity identity) {
        return informationRequestService.answerInformation(ticketId, request, identity);
    }

}