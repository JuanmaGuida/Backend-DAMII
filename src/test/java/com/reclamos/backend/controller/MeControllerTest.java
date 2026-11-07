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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;

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
    private com.reclamos.backend.service.OwnerTrackingCodeService ownerTrackingCodeService;

    @MockitoBean
    private AuthService authService;

    @Test
    void myTicketsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/me/tickets"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recoveryRequiresAuthenticationAndDoesNotCacheUnauthorizedResponse() throws Exception {
        mockMvc.perform(get("/api/me/tickets/" + UUID.randomUUID() + "/tracking-code"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", containsString("no-store")));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ModuleRole.class)
    void recoveryIsAuthenticatedForEveryRoleAndReturnsOnlyTheSecret(ModuleRole role) throws Exception {
        UUID id = UUID.randomUUID();
        var identity = new AuthenticatedIdentity("test", CITIZEN.citizenId(), "Test", null, role);
        var authentication = new UsernamePasswordAuthenticationToken(identity, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        when(ownerTrackingCodeService.get(id, identity)).thenReturn(
                new com.reclamos.backend.dto.response.TrackingCodeResponse("original-code"));
        mockMvc.perform(get("/api/me/tickets/" + id + "/tracking-code").with(authentication(authentication)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"trackingCode\":\"original-code\"}"));
        verify(ownerTrackingCodeService).get(id, identity);
    }

    @Test
    void recoveryErrorsAreControlledAndNonCacheable() throws Exception {
        UUID id = UUID.randomUUID(); String path = "/api/me/tickets/" + id + "/tracking-code";
        when(ownerTrackingCodeService.get(id, CITIZEN))
                .thenThrow(new com.reclamos.backend.exception.OwnerTrackingCodeNotFoundException())
                .thenThrow(new com.reclamos.backend.exception.TrackingCodeNotRecoverableException())
                .thenThrow(new com.reclamos.backend.exception.TrackingCodeUnavailableException());
        int[] statuses = {404, 409, 503};
        String[] codes = {"NOT_FOUND", "TRACKING_CODE_NOT_RECOVERABLE", "TRACKING_CODE_UNAVAILABLE"};
        for (int i = 0; i < statuses.length; i++) {
            mockMvc.perform(get(path).with(authentication(CITIZEN_AUTHENTICATION)))
                    .andExpect(status().is(statuses[i])).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(codes[i])).andExpect(jsonPath("$.trackingCode").doesNotExist());
        }
        mockMvc.perform(get("/api/me/tickets/invalid/tracking-code").with(authentication(CITIZEN_AUTHENTICATION)))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", containsString("no-store")));
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
