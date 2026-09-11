package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.AuthService;
import com.reclamos.backend.service.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /staff/tickets/{id} (Guía funcional M2 §7/§7.1): detalle staff.
 * El filtro de entrada por rol (quién puede llamar al endpoint) vive en
 * SecurityConfiguration; el ownership/areaId real (qué puede ver una vez
 * adentro) lo cubre TicketServiceTest sobre requireStaffAccess.
 */
@WebMvcTest(StaffTicketController.class)
@Import({SecurityConfiguration.class, BearerTokenAuthenticationFilter.class})
class StaffTicketControllerTest {
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
    private AuthService authService;

    @Test
    void getStaffDetailRequiresAuthentication() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId))
                .andExpect(status().isUnauthorized());
    }

    /**
     * CITIZEN no gestiona tickets del lado staff (Guía funcional M2 §7): el
     * filtro de rol en SecurityConfiguration lo rechaza antes de llegar al
     * controller/service.
     */
    @Test
    void getStaffDetailIsForbiddenForCitizen() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId)
                        .with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getStaffDetailIsReachableForEveryStaffRole() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.IN_REVIEW);
        when(ticketService.getStaffDetail(eq(ticketId), any())).thenReturn(response);

        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId).with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("IN_REVIEW"));
        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId).with(authentication(ADMIN_AUTHENTICATION)))
                .andExpect(status().isOk());
    }

    @Test
    void getStaffDetailOnMissingTicketReturnsNotFound() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.getStaffDetail(eq(ticketId), any()))
                .thenThrow(new ResourceNotFoundException("El ticket solicitado no existe"));

        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId).with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isNotFound());
    }

    /**
     * Caso de AREA_RESPONSIBLE sobre un ticket de otra área: pasa el filtro
     * de rol (está en la whitelist), pero TicketService.requireStaffAccess
     * lo rechaza por areaId — ver TicketServiceTest para esa regla.
     */
    @Test
    void getStaffDetailOnTicketOutsideAreaResponsibleAreaReturnsForbidden() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.getStaffDetail(eq(ticketId), eq(AREA_RESPONSIBLE)))
                .thenThrow(new UnauthorizedTicketOperationException());

        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ==================================================================
    // ---- GET /staff/tickets/{id}/citizen-view ----
    // ==================================================================

    @Test
    void getCitizenViewRequiresAuthentication() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getCitizenViewIsForbiddenForCitizen() throws Exception {
        UUID ticketId = UUID.randomUUID();
        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId)
                        .with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isForbidden());
    }

    @Test
    void getCitizenViewIsReachableForEveryStaffRole() throws Exception {
        UUID ticketId = UUID.randomUUID();
        TicketResponse response = new TicketResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.IN_REVIEW);
        when(ticketService.getStaffCitizenView(eq(ticketId), any())).thenReturn(response);

        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("IN_REVIEW"));
        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId)
                        .with(authentication(ADMIN_AUTHENTICATION)))
                .andExpect(status().isOk());
    }

    @Test
    void getCitizenViewOnMissingTicketReturnsNotFound() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.getStaffCitizenView(eq(ticketId), any()))
                .thenThrow(new ResourceNotFoundException("El ticket solicitado no existe"));

        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isNotFound());
    }

    @Test
    void getCitizenViewOnTicketOutsideAreaResponsibleAreaReturnsForbidden() throws Exception {
        UUID ticketId = UUID.randomUUID();
        when(ticketService.getStaffCitizenView(eq(ticketId), eq(AREA_RESPONSIBLE)))
                .thenThrow(new UnauthorizedTicketOperationException());

        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
