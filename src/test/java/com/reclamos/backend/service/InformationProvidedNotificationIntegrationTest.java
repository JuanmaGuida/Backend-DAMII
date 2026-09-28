package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AnswerInformationRequest;
import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.notification.NotificationDelivery;
import com.reclamos.backend.notification.NotificationSender;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000"
})
@ActiveProfiles("dev")
@Import(InformationProvidedNotificationIntegrationTest.RecordingSenderConfiguration.class)
class InformationProvidedNotificationIntegrationTest {

    @Autowired private InformationRequestService informationRequests;
    @Autowired private InformationRequestRepository informationRequestRepository;
    @Autowired private TicketActivityRepository activityRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private RequestTypeRepository requestTypeRepository;
    @Autowired private ModuleUserRepository moduleUserRepository;
    @Autowired private JdbcTemplate database;
    @Autowired private RecordingNotificationSender sender;

    private UUID ticketId;
    private UUID citizenId;
    private UUID agentCitizenId;

    @AfterEach
    void cleanUp() {
        if (ticketId != null) {
            database.update("DELETE FROM notification_logs WHERE ticket_id = ?", ticketId);
            database.update("DELETE FROM outbox_events WHERE ticket_id = ?", ticketId);
            database.update("DELETE FROM information_requests WHERE ticket_id = ?", ticketId);
            database.update("DELETE FROM ticket_sla WHERE ticket_id = ?", ticketId);
            database.update("DELETE FROM ticket_activities WHERE ticket_id = ?", ticketId);
            database.update("DELETE FROM tickets WHERE id = ?", ticketId);
        }
        if (citizenId != null) database.update("DELETE FROM module_users WHERE citizen_id = ?", citizenId);
        if (agentCitizenId != null) database.update("DELETE FROM module_users WHERE citizen_id = ?", agentCitizenId);
        sender.clear();
    }

    @Test
    void citizenAnswerIsCommittedAndSentOnlyToAssignedAgent() {
        Ticket ticket = persistAssignedTicket();
        AuthenticatedIdentity agent = identity(agentCitizenId, ModuleRole.AGENT);
        informationRequests.requestInformation(ticketId,
                new CreateInformationRequest("Aporte el dato faltante", "uso interno"), agent);

        informationRequests.answerInformation(ticketId,
                new AnswerInformationRequest("Información aportada"), identity(citizenId, ModuleRole.CITIZEN));

        Ticket persistedTicket = ticketRepository.findById(ticketId).orElseThrow();
        assertThat(persistedTicket.getCurrentStatus()).isEqualTo(TicketStatus.IN_PROGRESS);
        assertThat(informationRequestRepository.existsByTicketIdAndStatus(
                ticketId, InformationRequestStatus.ANSWERED)).isTrue();
        assertThat(activityRepository.findAllByTicket_IdOrderBySequenceAsc(ticketId))
                .filteredOn(activity -> activity.getActionType() == ActivityType.INFORMATION_PROVIDED)
                .hasSize(1);

        List<Map<String, Object>> logs = database.queryForList("""
                SELECT citizen_id, status, attempt_count
                FROM notification_logs
                WHERE ticket_id = ? AND type = ?
                """, ticketId, NotificationType.INFORMATION_PROVIDED.name());
        assertThat(logs).singleElement().satisfies(log -> {
            assertThat(log.get("citizen_id")).isEqualTo(agentCitizenId);
            assertThat(log.get("status")).isEqualTo(NotificationStatus.SENT.name());
            assertThat(log.get("attempt_count")).isEqualTo(1);
        });
        assertThat(logs).noneMatch(log -> citizenId.equals(log.get("citizen_id")));
        assertThat(sender.deliveries()).anySatisfy(delivery -> {
            assertThat(delivery.getTicketId()).isEqualTo(ticketId);
            assertThat(delivery.getType()).isEqualTo(NotificationType.INFORMATION_PROVIDED);
        });
    }

    private Ticket persistAssignedTicket() {
        RequestType requestType = requestTypeRepository.findAll().stream()
                .filter(RequestType::isActive).findFirst().orElseThrow();
        citizenId = UUID.randomUUID();
        agentCitizenId = UUID.randomUUID();
        ModuleUser citizen = user(citizenId, ModuleRole.CITIZEN, "Citizen");
        ModuleUser agent = user(agentCitizenId, ModuleRole.AGENT, "Agent");
        moduleUserRepository.saveAllAndFlush(List.of(citizen, agent));

        Instant now = Instant.now();
        Ticket ticket = new Ticket();
        ticket.setPublicId("TN-" + UUID.randomUUID());
        ticket.setTrackingCodeHash("information-provided-" + UUID.randomUUID());
        ticket.setCitizenId(citizenId);
        ticket.setAnonymous(false);
        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId("M2");
        ticket.setAssignedAgent(agent);
        ticket.setSummary("Integración INFORMATION_PROVIDED");
        ticket.setDescription("Fixture PostgreSQL");
        ticket.setFormData(Map.of());
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setCurrentPriority(Priority.LOW);
        ticket.setStatusChangedAt(now);
        ticket.setCreatedAt(now);
        ticket.setPreferredNotificationChannel("EMAIL");
        ticket = ticketRepository.saveAndFlush(ticket);
        ticketId = ticket.getId();
        return ticket;
    }

    private ModuleUser user(UUID id, ModuleRole role, String name) {
        ModuleUser user = new ModuleUser();
        user.setCitizenId(id);
        user.setFirstName(name);
        user.setLastName("Notification Test");
        user.setRole(role);
        user.setPreferredNotificationChannel("EMAIL");
        user.setActive(true);
        user.setLastSyncedAt(Instant.now());
        return user;
    }

    private AuthenticatedIdentity identity(UUID id, ModuleRole role) {
        return new AuthenticatedIdentity(role.name().toLowerCase() + "-notification-test", id,
                role.name(), role == ModuleRole.AGENT ? "M2" : null, role);
    }

    @TestConfiguration
    static class RecordingSenderConfiguration {
        @Bean @Primary
        RecordingNotificationSender recordingNotificationSender() {
            return new RecordingNotificationSender();
        }
    }

    static class RecordingNotificationSender implements NotificationSender {
        private final List<NotificationDelivery> deliveries = new CopyOnWriteArrayList<>();
        @Override public void send(NotificationDelivery notification) { deliveries.add(notification); }
        List<NotificationDelivery> deliveries() { return List.copyOf(deliveries); }
        void clear() { deliveries.clear(); }
    }
}