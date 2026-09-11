package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /me/tickets (Entidades V1.49 §"Autenticación, roles y vistas"):
 * listado propio del ciudadano autenticado, sin scoping por rol.
 */
@WebMvcTest(MeController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class MeControllerTest {
    private static final AuthenticatedIdentity CITIZEN = new AuthenticatedIdentity(
            "test-citizen", UUID.fromString("10000000-0000-0000-0000-000000000005"),
            "Vecino de prueba", null, ModuleRole.CITIZEN);
    private static final UsernamePasswordAuthenticationToken CITIZEN_AUTHENTICATION =
            new UsernamePasswordAuthenticationToken(
                    CITIZEN, null, List.of(new SimpleGrantedAuthority("ROLE_CITIZEN")));

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
    private AuthService authService;

    @Test
    void myTicketsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/me/tickets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void myTicketsDelegatesToServiceScopedToTheCallerAndReturnsPagedBody() throws Exception {
        TicketResponse response = new TicketResponse();
        response.setId(UUID.randomUUID());
        response.setCurrentStatus(TicketStatus.REGISTERED);
        Page<TicketResponse> page = new PageImpl<>(List.of(response));
        when(ticketService.listMyTickets(eq(CITIZEN), any(Pageable.class))).thenReturn(page);

        mockMvc.perform(get("/api/me/tickets").with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currentStatus").value("REGISTERED"))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(ticketService).listMyTickets(eq(CITIZEN), any(Pageable.class));
    }

    /**
     * A diferencia de GET /tickets (bandeja staff, restringida a
     * AGENT/AREA_RESPONSIBLE/ADMIN), /me/tickets no filtra por rol: todo
     * usuario autenticado conserva capacidades ciudadanas base sobre sus
     * propios tickets.
     */
    @Test
    void myTicketsIsAlsoReachableForNonCitizenRoles() throws Exception {
        when(ticketService.listMyTickets(eq(AGENT), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/me/tickets").with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk());
    }
}
