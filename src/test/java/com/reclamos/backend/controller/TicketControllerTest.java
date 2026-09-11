package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
}
