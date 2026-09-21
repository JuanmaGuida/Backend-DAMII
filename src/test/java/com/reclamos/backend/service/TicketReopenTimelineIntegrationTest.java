package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.ReopenTicketRequest;
import com.reclamos.backend.dto.response.TicketActivityResponse;
import com.reclamos.backend.dto.response.TicketDetailResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.ModuleUserRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.TicketRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000"
})
@ActiveProfiles("dev")
@Transactional
class TicketReopenTimelineIntegrationTest {
    private static final String REOPEN_REASON = "La solución no resolvió el problema";

    @Autowired private TicketResolutionService resolutionService;
    @Autowired private TicketService ticketService;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private RequestTypeRepository requestTypeRepository;
    @Autowired private EntityManager entityManager;
    @Autowired
    private ModuleUserRepository moduleUserRepository;

    private void createCitizen(UUID citizenId) {
        Instant now = Instant.now();

        ModuleUser user = new ModuleUser();
        user.setCitizenId(citizenId);
        user.setFirstName("Vecino");
        user.setLastName("Test");
        user.setRole(ModuleRole.CITIZEN);
        user.setActive(true);
        user.setLastSyncedAt(now);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        moduleUserRepository.saveAndFlush(user);
    }

    @Test
    void reopenIsRehydratedFromPersistenceWithItsCitizenReason() {
        UUID citizenId = UUID.randomUUID();

        createCitizen(citizenId);

        Ticket ticket = createResolvedTicket(citizenId);

        AuthenticatedIdentity citizen = new AuthenticatedIdentity(
                "citizen-" + citizenId,
                citizenId,
                "Vecino",
                null,
                ModuleRole.CITIZEN
        );

        resolutionService.reopen(
                ticket.getId(),
                new ReopenTicketRequest(REOPEN_REASON),
                citizen
        );

        entityManager.flush();
        entityManager.clear();

        TicketDetailResponse reloaded =
                ticketService.getById(ticket.getId(), citizen);

        assertThat(reloaded.getCurrentStatus())
                .isEqualTo(TicketStatus.IN_PROGRESS);

        assertThat(ticketRepository.findById(ticket.getId())
                .orElseThrow()
                .getReopenCount())
                .isEqualTo(1);

        assertThat(reloaded.getTicketActivities())
                .extracting(TicketActivityResponse::getSequence)
                .isSorted();

        assertThat(reloaded.getTicketActivities())
                .filteredOn(activity ->
                        activity.getActionType() == ActivityType.REOPENED)
                .singleElement()
                .satisfies(activity -> {
                    assertThat(activity.getPreviousStatus())
                            .isEqualTo(TicketStatus.RESOLVED);
                    assertThat(activity.getNewStatus())
                            .isEqualTo(TicketStatus.IN_PROGRESS);
                    assertThat(activity.getMessage())
                            .isEqualTo(REOPEN_REASON);
                    assertThat(activity.getOccurredAt())
                            .isNotNull();
                });
    }

    private Ticket createResolvedTicket(UUID citizenId) {
        RequestType requestType = requestTypeRepository.findAll().stream()
                .filter(RequestType::isActive)
                .findFirst()
                .orElseThrow();

        Ticket ticket = new Ticket();
        ticket.setPublicId("TK-2026-999999");
        ticket.setTrackingCodeHash("HASH-" + UUID.randomUUID());
        ticket.setAnonymous(false);
        ticket.setCitizenId(citizenId);
        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId("M2");
        ticket.setSummary("Rehidratación de reapertura");
        ticket.setDescription("Prueba integrada del historial ciudadano");
        ticket.setFormData(Map.of());
        ticket.setCurrentStatus(TicketStatus.RESOLVED);
        ticket.setCurrentPriority(Priority.LOW);
        ticket.setEstimatedAffectedCount(0);
        ticket.setEscalated(false);
        ticket.setReopenCount(0);
        ticket.setStatusChangedAt(Instant.now());
        ticket.setResolutionConfirmationDueAt(Instant.now().plusSeconds(3600));
        ticket.setCreatedAt(Instant.now());
        ticket.setPublic(false);

        return ticketRepository.saveAndFlush(ticket);
    }
}