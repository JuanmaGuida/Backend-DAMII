package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CancelTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.dto.response.TicketDetailResponse;
import com.reclamos.backend.dto.response.TicketActivityResponse;
import com.reclamos.backend.dto.response.TicketAttachmentResponse;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.CancellationReasonCode;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.InformationRequestService;
import com.reclamos.backend.service.TicketResolutionService;
import com.reclamos.backend.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TicketController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class TicketControllerTest {
    private static final AuthenticatedIdentity AGENT = new AuthenticatedIdentity(
            "test-agent", UUID.fromString("10000000-0000-0000-0000-000000000002"),
            "Agente de prueba", null, ModuleRole.AGENT);
    private static final UsernamePasswordAuthenticationToken AGENT_AUTHENTICATION =
            new UsernamePasswordAuthenticationToken(
                    AGENT, null, List.of(new SimpleGrantedAuthority("ROLE_AGENT")));

    private static final AuthenticatedIdentity ADMIN = new AuthenticatedIdentity(
            "test-admin", UUID.fromString("10000000-0000-0000-0000-000000000003"),
            "Admin de prueba", null, ModuleRole.ADMIN);
    private static final UsernamePasswordAuthenticationToken ADMIN_AUTHENTICATION =
            new UsernamePasswordAuthenticationToken(
                    ADMIN, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    private static final AuthenticatedIdentity AREA_RESPONSIBLE = new AuthenticatedIdentity(
            "test-area-responsible", UUID.fromString("10000000-0000-0000-0000-000000000004"),
            "Responsable de área de prueba", "M2", ModuleRole.AREA_RESPONSIBLE);
    private static final UsernamePasswordAuthenticationToken AREA_RESPONSIBLE_AUTHENTICATION =
            new UsernamePasswordAuthenticationToken(
                    AREA_RESPONSIBLE, null, List.of(new SimpleGrantedAuthority("ROLE_AREA_RESPONSIBLE")));

    private static final AuthenticatedIdentity CITIZEN = new AuthenticatedIdentity(
            "test-citizen", UUID.fromString("10000000-0000-0000-0000-000000000005"),
            "Vecino de prueba", null, ModuleRole.CITIZEN);
    private static final UsernamePasswordAuthenticationToken CITIZEN_AUTHENTICATION =
            new UsernamePasswordAuthenticationToken(
                    CITIZEN, null, List.of(new SimpleGrantedAuthority("ROLE_CITIZEN")));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private InformationRequestService informationRequestService;

    @MockitoBean
    private TicketResolutionService ticketResolutionService;

    @MockitoBean
    private AuthService authService;

    @Test
    void createReturnsServerGeneratedPublicId() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.create(any(), eq(AGENT), any()))
                .thenReturn(new CreateTicketResponse(
                        ticketId, "TK-2026-000123", "tracking-secret", TicketStatus.REGISTERED));

        mockMvc.perform(post("/api/tickets")
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestTypeId": 1,
                                  "summary": "Resumen",
                                  "description": "Descripción",
                                  "formData": {}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticketId").value(ticketId.toString()))
                .andExpect(jsonPath("$.publicId").value("TK-2026-000123"));
    }

    @Test
    void listPassesFiltersAndPagingToServiceAndReturnsPagedBody() throws Exception {
        TicketResponse response = new TicketResponse();
        response.setId(UUID.randomUUID());
        response.setCurrentStatus(TicketStatus.ROUTED);
        Page<TicketResponse> page = new PageImpl<>(List.of(response));
        when(ticketService.listTickets(any(TicketFilter.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/tickets").with(authentication(AGENT_AUTHENTICATION))
                        .param("priority", "HIGH")
                        .param("status", "ROUTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currentStatus").value("ROUTED"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * ?sort=notAField,desc: la validación no vive en este controller ni en
     * GlobalExceptionHandler — TicketService.listTickets valida el Sort
     * contra una whitelist propia de propiedades de Ticket ANTES de llegar
     * al repository, y tira InvalidTicketRequestException (400) si el campo
     * no es válido. Este slice test sólo verifica que ese 400 llega bien al
     * cliente HTTP; la whitelist en sí está cubierta en TicketServiceTest.
     */
    @Test
    void listWithInvalidSortFieldReturnsBadRequestInsteadOf500() throws Exception {
        when(ticketService.listTickets(any(TicketFilter.class), any(Pageable.class)))
                .thenThrow(new InvalidTicketRequestException("El campo de ordenamiento 'notAField' no es válido"));

        mockMvc.perform(get("/api/tickets").with(authentication(AGENT_AUTHENTICATION))
                        .param("sort", "notAField,desc"))
                .andExpect(status().isBadRequest());
    }

    /**
     * La bandeja (Story 3.1) es para quienes gestionan tickets del lado
     * staff (Guía funcional M2 §7): AGENT, AREA_RESPONSIBLE y ADMIN. CITIZEN
     * sólo tiene capacidades ciudadanas sobre sus propios tickets, así que
     * no debe poder listar la bandeja completa vía este endpoint.
     */
    @Test
    void listIsForbiddenForCitizen() throws Exception {
        mockMvc.perform(get("/api/tickets").with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listIsReachableOnlyForGlobalAdministrativeRoles() throws Exception {
        TicketResponse response = new TicketResponse();
        response.setId(UUID.randomUUID());
        Page<TicketResponse> page = new PageImpl<>(List.of(response));
        when(ticketService.listTickets(any(TicketFilter.class), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/tickets").with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tickets").with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tickets").with(authentication(ADMIN_AUTHENTICATION)))
                .andExpect(status().isOk());
    }

    /**
     * GET /tickets/{id} (Entidades V1.49): "Ciudadano owner". El controller
     * sólo delega; el 403 por no-ownership lo produce
     * TicketService.requireOwner vía UnauthorizedTicketOperationException.
     */
    @Test
    void getByIdDelegatesToServiceAndReturnsOk() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketDetailResponse response = new TicketDetailResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.REGISTERED);
        response.setDescription("Descripción completa");
        response.setNeighborhoodName("Recoleta");
        response.setAttachments(List.of(new TicketAttachmentResponse(
                1L, "foto.jpg", "image/jpeg", 123L, MessageVisibility.PUBLIC, Instant.EPOCH,
                "https://m2.example/api/attachments/1/content")));
        response.setTicketActivities(List.of(new TicketActivityResponse(
                1, ActivityType.TICKET_CREATED, null, TicketStatus.REGISTERED, Instant.EPOCH,
                null, null, null, null, null)));
        when(ticketService.getById(eq(ticketId), eq(AGENT))).thenReturn(response);

        mockMvc.perform(get("/api/tickets/{ticketId}", ticketId).with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.description").value("Descripción completa"))
                .andExpect(jsonPath("$.neighborhoodName").value("Recoleta"))
                .andExpect(jsonPath("$.neighborhoodId").doesNotExist())
                .andExpect(jsonPath("$.anonymousContact").doesNotExist())
                .andExpect(jsonPath("$.attachments[0].fileName").value("foto.jpg"))
                .andExpect(jsonPath("$.attachments[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.attachments[0].downloadUrl")
                        .value("https://m2.example/api/attachments/1/content"))
                .andExpect(jsonPath("$.ticketActivities[0].sequence").value(1))
                .andExpect(jsonPath("$.ticketActivities[0].actorId").doesNotExist())
                .andExpect(jsonPath("$.ticketActivities[0].sourceModuleId").doesNotExist())
                .andExpect(jsonPath("$.ticketActivities[0].externalEventId").doesNotExist())
                .andExpect(jsonPath("$.ticketActivities[0].metadata").doesNotExist())
                .andExpect(jsonPath("$.ticketActivities[0].ticketVersion").doesNotExist());
    }

    @Test
    void getByIdOnMissingTicketReturnsNotFound() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.getById(eq(ticketId), any()))
                .thenThrow(new ResourceNotFoundException("El ticket solicitado no existe"));

        mockMvc.perform(get("/api/tickets/{ticketId}", ticketId).with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByIdOnNonOwnerReturnsForbidden() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.getById(eq(ticketId), any()))
                .thenThrow(new UnauthorizedTicketOperationException());

        mockMvc.perform(get("/api/tickets/{ticketId}", ticketId).with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * QA - Story 2.3: review/classification/route son triage, no lectura —
     * CITIZEN nunca las ejecuta, y AREA_RESPONSIBLE tampoco (Guía funcional
     * M2 §7: su capacidad staff se limita a leer y enviar mensajes, ni
     * siquiera sobre tickets de su propia área). Sólo AGENT/ADMIN llegan al
     * controller para estos tres endpoints.
     */
    @Test
    void reviewIsForbiddenForCitizenAndAreaResponsible() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId)
                        .with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isForbidden());
    }

    @Test
    void startReviewDelegatesToServiceAndReturnsOk() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.IN_REVIEW);
        when(ticketService.startReview(eq(ticketId), any())).thenReturn(response);

        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("IN_REVIEW"));
    }

    @Test
    void startReviewOnWrongStateReturnsConflict() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.startReview(eq(ticketId), any()))
                .thenThrow(new TicketStateConflictException("El ticket no está REGISTERED"));

        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El ticket no está REGISTERED"));
    }

    @Test
    void startReviewOnMissingTicketReturnsNotFound() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.startReview(eq(ticketId), any()))
                .thenThrow(new ResourceNotFoundException("El ticket solicitado no existe"));

        mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isNotFound());
    }

    @Test
    void classificationIsForbiddenForCitizenAndAreaResponsible() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .with(authentication(CITIZEN_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\":20}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\":20}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void correctClassificationRequiresRequestTypeId() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void correctClassificationDelegatesToService() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setRequestTypeCode("FLOODING");
        when(ticketService.correctClassification(eq(ticketId), eq(20L), any())).thenReturn(response);

        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestTypeCode").value("FLOODING"));
    }

    @Test
    void correctClassificationOnStateConflictReturnsConflict() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.correctClassification(eq(ticketId), eq(20L), any()))
                .thenThrow(new TicketStateConflictException("La clasificación ya fue finalizada"));

        mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestTypeId\":20}"))
                .andExpect(status().isConflict());
    }

    @Test
    void routeIsForbiddenForCitizenAndAreaResponsible() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(post("/api/tickets/{ticketId}/route", ticketId)
                        .with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tickets/{ticketId}/route", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isForbidden());
    }

    /**
     * Story 3.3 / DDA2-59: derivación a área (IN_REVIEW -&gt; ROUTED).
     */
    @Test
    void routeToAreaDelegatesToServiceAndReturnsOk() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.ROUTED);
        when(ticketService.routeToArea(eq(ticketId), any())).thenReturn(response);

        mockMvc.perform(post("/api/tickets/{ticketId}/route", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("ROUTED"));
    }

    @Test
    void routeToAreaOnWrongStateReturnsConflict() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.routeToArea(eq(ticketId), any()))
                .thenThrow(new TicketStateConflictException("El ticket no está IN_REVIEW"));

        mockMvc.perform(post("/api/tickets/{ticketId}/route", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El ticket no está IN_REVIEW"));
    }

    // ---- cancel (Entidades V1.49 §24) ----

    /**
     * Ciudadano owner o AGENT/ADMIN. El controller sólo delega; el
     * ownership/rol real lo valida TicketService.requireCancelAuthority.
     */
    @Test
    void cancelDelegatesToServiceAndReturnsOk() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.CANCELLED);
        when(ticketService.cancelTicket(eq(ticketId), any(CancelTicketRequest.class), eq(AGENT)))
                .thenReturn(response);

        mockMvc.perform(post("/api/tickets/{ticketId}/cancel", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"" + CancellationReasonCode.OUT_OF_SCOPE + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
    }

    @Test
    void identifiedInformationResponseAcceptsMultipartDataAndAttachments() throws Exception {
        UUID ticketId = UUID.randomUUID();
        MockPart data = new MockPart("data", "{\"responseMessage\":\"Detalle\"}".getBytes());
        data.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        MockMultipartFile attachment = new MockMultipartFile(
                "attachments", "proof.png", "image/png", new byte[]{1});

        mockMvc.perform(multipart("/api/tickets/{ticketId}/information-response", ticketId)
                        .file(attachment)
                        .part(data)
                        .with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isOk());

        verify(informationRequestService).answerInformation(eq(ticketId), any(), eq(CITIZEN),
                org.mockito.ArgumentMatchers.argThat(files -> files.length == 1
                        && "proof.png".equals(files[0].getOriginalFilename())));
    }

    @Test
    void cancelOnNonOwnerReturnsForbidden() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.cancelTicket(eq(ticketId), any(CancelTicketRequest.class), eq(CITIZEN)))
                .thenThrow(new UnauthorizedTicketOperationException());

        mockMvc.perform(post("/api/tickets/{ticketId}/cancel", ticketId)
                        .with(authentication(CITIZEN_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"" + CancellationReasonCode.WITHDRAWN_BY_CITIZEN + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void cancelOnWrongStateReturnsConflict() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.cancelTicket(eq(ticketId), any(CancelTicketRequest.class), eq(AGENT)))
                .thenThrow(new TicketStateConflictException(
                        "El ticket está en estado ROUTED y no puede cancelarse por este endpoint"));

        mockMvc.perform(post("/api/tickets/{ticketId}/cancel", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"" + CancellationReasonCode.OTHER + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cancelOnMissingTicketReturnsNotFound() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.cancelTicket(eq(ticketId), any(CancelTicketRequest.class), eq(AGENT)))
                .thenThrow(new ResourceNotFoundException("El ticket solicitado no existe"));

        mockMvc.perform(post("/api/tickets/{ticketId}/cancel", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"" + CancellationReasonCode.OTHER + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelRequiresReasonCode() throws Exception {
        UUID ticketId = UUID.randomUUID();

        mockMvc.perform(post("/api/tickets/{ticketId}/cancel", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void citizenAndAreaResponsibleCannotUseAdministrativeLifecycleEndpoints() throws Exception {
        UUID ticketId = UUID.randomUUID();
        for (ModuleRole role : List.of(ModuleRole.CITIZEN, ModuleRole.AREA_RESPONSIBLE)) {
            UsernamePasswordAuthenticationToken authentication = authenticationFor(role);
            mockMvc.perform(post("/api/tickets/{ticketId}/review", ticketId).with(authentication(authentication)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(patch("/api/tickets/{ticketId}/classification", ticketId)
                            .with(authentication(authentication))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"requestTypeId\":20}"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(post("/api/tickets/{ticketId}/route", ticketId).with(authentication(authentication)))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(ticketService);
    }

    private UsernamePasswordAuthenticationToken authenticationFor(ModuleRole role) {
        AuthenticatedIdentity identity = new AuthenticatedIdentity(
                "subject-" + role, UUID.randomUUID(), role.name(), "M6", role);
        return new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
    }
}
