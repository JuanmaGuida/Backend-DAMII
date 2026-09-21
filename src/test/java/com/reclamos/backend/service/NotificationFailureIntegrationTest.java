package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.NotificationStatus;
import com.reclamos.backend.entity.NotificationType;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.notification.NotificationDelivery;
import com.reclamos.backend.notification.NotificationSender;
import com.reclamos.backend.repository.InformationRequestRepository;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketRepository;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000"
})
@ActiveProfiles("dev")
@Import(NotificationFailureIntegrationTest.FailingSenderConfiguration.class)
class NotificationFailureIntegrationTest {

    private static final String SENSITIVE_FAILURE =
            "fallo para juan@example.com trackingCode=SECRET";

    @Autowired
    private InformationRequestService informationRequestService;

    @Autowired
    private InformationRequestRepository informationRequestRepository;

    @Autowired
    private TicketActivityRepository activityRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private RequestTypeRepository requestTypeRepository;

    @Autowired
    private JdbcTemplate database;

    @Autowired
    private FailingNotificationSender sender;

    private UUID ticketId;
    private UUID citizenId;

    @AfterEach
    void cleanUp() {
        if (ticketId != null) {
            database.update(
                    "DELETE FROM notification_logs WHERE ticket_id = ?",
                    ticketId
            );

            database.update(
                    "DELETE FROM outbox_events WHERE ticket_id = ?",
                    ticketId
            );

            database.update(
                    "DELETE FROM information_requests WHERE ticket_id = ?",
                    ticketId
            );

            database.update(
                    "DELETE FROM ticket_activities WHERE ticket_id = ?",
                    ticketId
            );

            database.update(
                    "DELETE FROM tickets WHERE id = ?",
                    ticketId
            );
        }

        if (citizenId != null) {
            database.update(
                    "DELETE FROM module_users WHERE citizen_id = ?",
                    citizenId
            );
        }
    }

    @Test
    void failingSenderAfterCommitDoesNotRollBackInformationRequest() {
        Ticket ticket = persistTicketInProgress();

        AuthenticatedIdentity agent = new AuthenticatedIdentity(
                "agent-notification-test",
                UUID.randomUUID(),
                "Agente Test",
                "M2",
                ModuleRole.AGENT
        );

        assertThatCode(() ->
                informationRequestService.requestInformation(
                        ticketId,
                        new CreateInformationRequest(
                                "Necesitamos información adicional",
                                "nota interna"
                        ),
                        agent
                )
        ).doesNotThrowAnyException();

        Ticket persistedTicket = ticketRepository
                .findById(ticketId)
                .orElseThrow();

        assertThat(persistedTicket.getCurrentStatus())
                .isEqualTo(TicketStatus.PENDING_INFORMATION);

        assertThat(
                informationRequestRepository.existsByTicketIdAndStatus(
                        ticketId,
                        InformationRequestStatus.PENDING
                )
        ).isTrue();

        assertThat(
                activityRepository.findAllByTicket_IdOrderBySequenceAsc(ticketId)
        )
                .filteredOn(activity ->
                        activity.getActionType() == ActivityType.INFORMATION_REQUIRED)
                .hasSize(1);

        Map<String, Object> notification = database.queryForMap("""
                SELECT
                    type,
                    status,
                    attempt_count,
                    sent_at,
                    failed_at,
                    failure_reason
                FROM notification_logs
                WHERE ticket_id = ?
                  AND type = ?
                """,
                ticketId,
                NotificationType.INFORMATION_REQUIRED.name()
        );

        assertThat(notification.get("status"))
                .isEqualTo(NotificationStatus.FAILED.name());

        assertThat(notification.get("attempt_count"))
                .isEqualTo(1);

        assertThat(notification.get("sent_at"))
                .isNull();

        assertThat(notification.get("failed_at"))
                .isNotNull();

        assertThat(notification.get("failure_reason").toString())
                .isEqualTo(
                        "Notification delivery failed (IllegalStateException)"
                )
                .doesNotContain(
                        SENSITIVE_FAILURE,
                        "juan@example.com",
                        "trackingCode=SECRET"
                );

        assertThat(sender.attempts())
                .isEqualTo(1);
    }

    private Ticket persistTicketInProgress() {
        RequestType requestType = requestTypeRepository
                .findAll()
                .stream()
                .filter(RequestType::isActive)
                .findFirst()
                .orElseThrow();

        citizenId = UUID.randomUUID();

        database.update("""
                INSERT INTO module_users (
                    citizen_id,
                    first_name,
                    last_name,
                    role,
                    active,
                    last_synced_at,
                    created_at,
                    updated_at
                )
                VALUES (
                    ?,
                    'Notification',
                    'Test',
                    'CITIZEN',
                    TRUE,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                """,
                citizenId
        );

        Instant now = Instant.now();

        Ticket ticket = new Ticket();

        ticket.setPublicId(
                "TN-" + UUID.randomUUID()
        );

        ticket.setTrackingCodeHash(
                "notification-test-" + UUID.randomUUID()
        );

        ticket.setCitizenId(citizenId);
        ticket.setAnonymous(false);

        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());

        ticket.setResponsibleAreaId("M2");

        ticket.setSummary(
                "Ticket para comprobar aislamiento de notificaciones"
        );

        ticket.setDescription(
                "Fixture de integración"
        );

        ticket.setFormData(Map.of());

        ticket.setCurrentStatus(
                TicketStatus.IN_PROGRESS
        );

        ticket.setCurrentPriority(
                Priority.LOW
        );

        ticket.setStatusChangedAt(now);
        ticket.setCreatedAt(now);

        ticket.setPreferredNotificationChannel(
                "EMAIL"
        );

        ticket = ticketRepository.saveAndFlush(ticket);

        ticketId = ticket.getId();

        return ticket;
    }

    @TestConfiguration
    static class FailingSenderConfiguration {

        @Bean
        @Primary
        FailingNotificationSender failingNotificationSender() {
            return new FailingNotificationSender();
        }
    }

    static class FailingNotificationSender implements NotificationSender {

        private final AtomicInteger attempts =
                new AtomicInteger();

        @Override
        public void send(NotificationDelivery notification) {
            attempts.incrementAndGet();

            throw new IllegalStateException(
                    SENSITIVE_FAILURE
            );
        }

        int attempts() {
            return attempts.get();
        }
    }
}