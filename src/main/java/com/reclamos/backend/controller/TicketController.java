package com.reclamos.backend.controller;

import com.reclamos.backend.service.TicketService;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

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

}