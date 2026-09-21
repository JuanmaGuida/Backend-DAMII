package com.reclamos.backend.controller;

import com.reclamos.backend.config.SecurityConfiguration;
import com.reclamos.backend.dto.response.AnonymousContactResponse;
import com.reclamos.backend.dto.response.StaffTicketDetailResponse;
import com.reclamos.backend.dto.response.TicketDetailResponse;
import com.reclamos.backend.dto.response.TicketActivityResponse;
import com.reclamos.backend.dto.response.TicketAttachmentResponse;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.security.BearerTokenAuthenticationFilter;
import com.reclamos.backend.service.*;
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
import java.time.Instant;

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

    @MockitoBean
    private DuplicateCandidateService duplicateCandidateService;

    @MockitoBean
    private DuplicateTicketService duplicateTicketService;

    @MockitoBean
    private LabelService labelService;

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
        StaffTicketDetailResponse response = new StaffTicketDetailResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.IN_REVIEW);
        response.setDescription("Descripción staff");
        response.setAttachments(List.of(new TicketAttachmentResponse(
                2L, "interno.pdf", "application/pdf", 456L,
                MessageVisibility.INTERNAL, Instant.EPOCH,
                "https://m2.example/api/attachments/2/content")));
        response.setTicketActivities(List.of(new TicketActivityResponse(
                1, ActivityType.PRIORITY_CHANGED, TicketStatus.REGISTERED, TicketStatus.IN_REVIEW,
                Instant.EPOCH, "STAFF_REASON", ActorType.AGENT, Priority.LOW, Priority.HIGH, "nota staff")));
        when(ticketService.getStaffDetail(eq(ticketId), any())).thenReturn(response);

        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId).with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.description").value("Descripción staff"))
                .andExpect(jsonPath("$.neighborhoodId").doesNotExist())
                .andExpect(jsonPath("$.attachments[0].visibility").value("INTERNAL"))
                .andExpect(jsonPath("$.attachments[0].storageKey").doesNotExist())
                .andExpect(jsonPath("$.attachments[0].downloadUrl")
                        .value("https://m2.example/api/attachments/2/content"))
                .andExpect(jsonPath("$.ticketActivities[0].actorType").value("AGENT"))
                .andExpect(jsonPath("$.ticketActivities[0].message").value("nota staff"))
                .andExpect(jsonPath("$.ticketActivities[0].actorId").doesNotExist());
        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId).with(authentication(ADMIN_AUTHENTICATION)))
                .andExpect(status().isOk());
    }

    @Test
    void getStaffDetailSerializesAnonymousContactOnlyWhenServiceAuthorizesIt() throws Exception {
        UUID ticketId = UUID.randomUUID();
        StaffTicketDetailResponse authorized = new StaffTicketDetailResponse();
        authorized.setId(ticketId);
        authorized.setAnonymous(true);
        authorized.setAnonymousContact(new AnonymousContactResponse(
                AnonymousContactChannel.EMAIL, "private@example.test"));
        StaffTicketDetailResponse sanitized = new StaffTicketDetailResponse();
        sanitized.setId(ticketId);
        sanitized.setAnonymous(true);

        when(ticketService.getStaffDetail(ticketId, AGENT)).thenReturn(authorized);
        when(ticketService.getStaffDetail(ticketId, ADMIN)).thenReturn(authorized);
        when(ticketService.getStaffDetail(ticketId, AREA_RESPONSIBLE)).thenReturn(sanitized);

        for (UsernamePasswordAuthenticationToken authentication :
                List.of(AGENT_AUTHENTICATION, ADMIN_AUTHENTICATION)) {
            mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId)
                            .with(authentication(authentication)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.anonymousContact.channel").value("EMAIL"))
                    .andExpect(jsonPath("$.anonymousContact.value").value("private@example.test"))
                    .andExpect(jsonPath("$.anonymousAccessPassword").doesNotExist())
                    .andExpect(jsonPath("$.anonymousAccessPasswordHash").doesNotExist())
                    .andExpect(jsonPath("$.trackingCode").doesNotExist())
                    .andExpect(jsonPath("$.trackingCodeHash").doesNotExist());
        }

        mockMvc.perform(get("/api/staff/tickets/{ticketId}", ticketId)
                        .with(authentication(AREA_RESPONSIBLE_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anonymousContact").doesNotExist());
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
        TicketDetailResponse response = new TicketDetailResponse();
        response.setId(ticketId);
        response.setCurrentStatus(TicketStatus.IN_REVIEW);
        response.setAttachments(List.of(new TicketAttachmentResponse(
                1L, "publico.pdf", "application/pdf", 123L,
                MessageVisibility.PUBLIC, Instant.EPOCH,
                "https://m2.example/api/attachments/1/content")));
        response.setTicketActivities(List.of(new TicketActivityResponse(
                1, ActivityType.PROGRESS_REPORTED, TicketStatus.IN_PROGRESS, TicketStatus.IN_PROGRESS,
                Instant.EPOCH, null, null, null, null, null)));
        when(ticketService.getStaffCitizenView(eq(ticketId), any())).thenReturn(response);

        mockMvc.perform(get("/api/staff/tickets/{ticketId}/citizen-view", ticketId)
                        .with(authentication(AGENT_AUTHENTICATION)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.attachments[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.anonymousContact").doesNotExist())
                .andExpect(jsonPath("$.ticketActivities[0].message").doesNotExist())
                .andExpect(jsonPath("$.ticketActivities[0].actorType").doesNotExist());
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
