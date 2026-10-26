package com.reclamos.backend.service;

import com.reclamos.backend.support.AttachmentFixtures;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reclamos.backend.dto.UpdateTicketStatusEnvelope;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.dto.request.AnswerInformationRequest;
import com.reclamos.backend.dto.request.CreateInformationRequest;
import com.reclamos.backend.dto.request.ReopenTicketRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.InformationRequestConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.RequestTypeRepository;
import com.reclamos.backend.repository.TicketRepository;
import com.reclamos.backend.repository.OutboxEventRepository;
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.repository.FormTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

/** Real commits and queries after each service call, including rejected requests. */
@SpringBootTest(properties = {
        "ticket.information-request.expiration-scan-delay=3600000",
        "ticket.resolution.confirmation-scan-delay=3600000",
        "ticket.sla.milestone-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"
})
@ActiveProfiles("dev")
class TicketEventCorrectionsPostgresIntegrationTest {
    @Autowired private TicketStatusUpdateService updates;
    @Autowired private TicketService ticketService;
    @Autowired private AttachmentRepository attachments;
    @Autowired private FormTemplateRepository templates;
    @Autowired private TicketResolutionService resolutions;
    @Autowired private ResolutionConfirmationTimeoutService timeouts;
    @Autowired private InformationRequestService information;
    @Autowired private TicketRepository tickets;
    @Autowired private RequestTypeRepository requestTypes;
    @Autowired private TicketSlaService sla;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate db;
    @Autowired private TicketOutboxService outbox;
    @Autowired private OutboxEventRepository outboxEvents;
    @Autowired private com.reclamos.backend.storage.FileStorage fileStorage;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
    private final List<UUID> ids = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final List<UUID> citizenIds = new ArrayList<>();
    private final Map<Long, Boolean> originalAnonymousPolicy = new HashMap<>();
    private final List<Long> localTypes = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : eventIds) db.update("DELETE FROM inbox_events WHERE event_id=?", id);
        for (UUID id : ids) {
            for (String key : db.queryForList("SELECT a.storage_key FROM attachments a "
                    + "JOIN information_request_attachments r ON r.attachment_id=a.id WHERE a.ticket_id=?",
                    String.class, id)) fileStorage.delete(key);
            db.update("DELETE FROM information_request_attachments WHERE information_request_id IN "
                    + "(SELECT id FROM information_requests WHERE ticket_id=?)", id);
            for (String table : List.of("notification_logs", "outbox_events", "ticket_messages",
                    "ticket_activities", "ticket_resolutions", "information_requests", "ticket_cancellations", "ticket_sla",
                    "attachments", "ticket_locations")) {
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            }
            db.update("UPDATE tickets SET main_ticket_id=NULL WHERE id=?", id);
        }
        for (UUID id : ids) db.update("DELETE FROM tickets WHERE id=?", id);
        for (UUID id : citizenIds) db.update("DELETE FROM module_users WHERE citizen_id=?", id);
        localTypes.forEach(id -> db.update("DELETE FROM request_types WHERE id=?",id));
        originalAnonymousPolicy.forEach((id,allowed) -> db.update("UPDATE request_types SET allows_anonymous=? WHERE id=?",allowed,id));
    }

    static Stream<Arguments> externalVariants() {
        return Arrays.stream(UpdateTicketStatusType.values()).flatMap(type ->
                Stream.of(false, true).map(anonymous -> Arguments.of(type, anonymous)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void routedCommitsCompleteV177SnapshotWithConfirmedClassificationAndPublicEvidence(boolean anonymous) throws Exception {
        Ticket ticket = routingFixture(anonymous);
        Long publicFile = evidence(ticket, MessageVisibility.PUBLIC, "evidence.pdf");
        evidence(ticket, MessageVisibility.INTERNAL, "private-note.pdf");
        Ticket other = routingFixture(true);
        evidence(other, MessageVisibility.PUBLIC, "other-ticket.pdf");
        UUID neighborhood = db.queryForObject("SELECT id FROM neighborhood ORDER BY id LIMIT 1", UUID.class);
        db.update("INSERT INTO ticket_locations (ticket_id,address_line,street,street_number,neighborhood_id,reference,"
                        + "latitude,longitude,created_at,updated_at) VALUES (?, 'Lima 123','Lima','123',?,'Esquina',"
                        + "-34.6,-58.4,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", ticket.getId(), neighborhood);
        String due = transactions.execute(ignored -> {
            Ticket managed = tickets.findById(ticket.getId()).orElseThrow();
            sla.startInitialResolutionCycle(managed, managed.getCreatedAt());
            return sla.findLatestResolutionCycle(managed).orElseThrow().getDueAt().toString();
        });

        var result = ticketService.routeToArea(ticket.getId(), triageAgent());

        var payload = payloads(ticket).getFirst();
        EventEnvelopeContractAssertions.assertValid(payload);
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        var data = (Map<String, Object>) payload.get("data");
        var routing = (Map<String, Object>) ((Map<String, Object>) data.get("details")).get("routing");
        assertThat(eventTypes(ticket)).containsExactly("ROUTED");
        assertThat(data).containsEntry("currentStatus", "ROUTED").containsEntry("isAnonymous", anonymous)
                .containsEntry("citizenId", anonymous ? null : ticket.getCitizenId().toString())
                .containsEntry("responsibleAreaId", result.getResponsibleAreaId())
                .containsEntry("currentPriority", "HIGH");
        assertThat(routing).containsEntry("requestType", "Informar una luminaria apagada")
                .containsEntry("category", "Alumbrado y equipamiento urbano")
                .containsEntry("subcategory", "Alumbrado público").containsEntry("ticketType", "COMPLAINT")
                .containsEntry("resolutionDueAt", due).containsEntry("reassignment", false)
                .containsEntry("formData", Map.of("cantidadLuminarias", "UNA", "zonaSinIluminacion", false, "cablesVisibles", false));
        assertThat((Map<String, Object>) routing.get("location")).containsOnlyKeys(
                        "addressLine", "street", "streetNumber", "neighborhoodId", "reference")
                .containsEntry("neighborhoodId", neighborhood.toString()).containsEntry("addressLine", "Lima 123");
        assertThat((Map<String, Object>) routing.get("escalation")).containsEntry("active", true)
                .containsEntry("reasonCode", "MANUAL");
        assertThat((List<Map<String, Object>>) data.get("attachments")).singleElement().satisfies(file ->
                assertThat(file).containsOnlyKeys("fileName", "contentType", "url", "sizeBytes")
                        .containsEntry("fileName", "evidence.pdf").containsEntry("contentType", "application/pdf")
                        .containsEntry("sizeBytes", 42).containsEntry("url", "http://localhost:8080/api/attachments/" + publicFile + "/content"));
        assertThat(payload.toString()).doesNotContain("private-note", "other-ticket", "storage/", "trackingCodeHash",
                "anonymousContact", "test@example", "password", "latitude", "longitude", "formTemplateId",
                "policyId", "cycleNumber", "pausedAt", "totalPausedSeconds", "previousAnswer", "private-form-key");
        assertThat(db.queryForObject("SELECT status FROM outbox_events WHERE ticket_id=?", String.class, ticket.getId()))
                .isEqualTo("PENDING");
        assertThat(db.queryForObject("SELECT classification_finalized_at IS NOT NULL FROM tickets WHERE id=?",
                Boolean.class, ticket.getId())).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=? AND action_type='ROUTED'",
                Integer.class, ticket.getId())).isEqualTo(1);
        assertThat(payloads(other)).isEmpty();

        // Outbox is the stable delivery snapshot, even if ticket/file metadata later changes.
        db.update("UPDATE tickets SET form_data='{}'::jsonb WHERE id=?", ticket.getId());
        db.update("UPDATE attachments SET file_name='renamed.pdf' WHERE id=?", publicFile);
        db.update("UPDATE outbox_events SET status='FAILED',retry_count=1 WHERE ticket_id=?", ticket.getId());
        assertThat(payloads(ticket)).containsExactly(payload);
        assertThatThrownBy(() -> ticketService.routeToArea(ticket.getId(), triageAgent()))
                .isInstanceOf(com.reclamos.backend.exception.TicketStateConflictException.class);
        assertThat(payloads(ticket)).containsExactly(payload);
    }

    @Test
    @SuppressWarnings("unchecked")
    void routedSupportsNoFilesNoLocationNoSlaAndEmptyConfirmedAnswers() throws Exception {
        Ticket ticket = routingFixture(false);
        transactions.executeWithoutResult(ignored -> {
            String name = "Event empty form " + UUID.randomUUID();
            Long typeId = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                    + "SELECT ?,id,?,?,'REQUEST','M6','LOW','LOW',0,TRUE,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id", Long.class, name, name, name);
            localTypes.add(typeId);
            RequestType noForm = requestTypes.findById(typeId).orElseThrow();
            Ticket managed = tickets.findById(ticket.getId()).orElseThrow();
            managed.setRequestType(noForm); managed.setTicketType(noForm.getTicketType());
            managed.setResponsibleAreaId(noForm.getResponsibleAreaId()); managed.setFormTemplateId(null);
            managed.setFormData(Map.of()); managed.setEscalated(false); managed.setEscalationReasonCode(null); managed.setEscalatedAt(null);
        });

        ticketService.routeToArea(ticket.getId(), triageAgent());

        var payload = payloads(ticket).getFirst();
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        var data = (Map<String, Object>) payload.get("data");
        var routing = (Map<String, Object>) ((Map<String, Object>) data.get("details")).get("routing");
        assertThat(routing).containsEntry("location", null).containsEntry("resolutionDueAt", null)
                .containsEntry("formData", Map.of()).containsEntry("reassignment", false);
        assertThat((Map<String, Object>) routing.get("escalation")).containsEntry("active", false)
                .containsEntry("reasonCode", null).containsEntry("escalatedAt", null);
        assertThat((List<?>) data.get("attachments")).isEmpty();
        assertThat(eventTypes(ticket)).containsExactly("ROUTED");
    }

    @Test
    void routedSnapshotFiltersUnexpectedHistoricalKeysAsDefenseInDepth() throws Exception {
        Ticket ticket = routingFixture(false);
        transactions.executeWithoutResult(ignored -> {
            Ticket managed = tickets.findById(ticket.getId()).orElseThrow();
            Map<String,Object> answers = new HashMap<>(managed.getFormData());
            answers.put("previousAnswer", "outdated"); answers.put("formTemplateId", "private-form-key");
            managed.setFormData(answers); managed.setCurrentStatus(TicketStatus.ROUTED);
            outbox.routed(managed, null, null, Instant.now());
        });
        var payload = payloads(ticket).getFirst();
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        assertThat(payload.toString()).contains("cantidadLuminarias").doesNotContain("previousAnswer", "formTemplateId", "private-form-key");
    }

    @Test
    void routedRollsBackStateActivityAndOutboxTogether() {
        Ticket ticket = routingFixture(true);
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            ticketService.routeToArea(ticket.getId(), triageAgent());
            outboxEvents.flush();
            assertThat(count("outbox_events", ticket)).isEqualTo(1);
            throw new IllegalStateException("forced ROUTED rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticket.getId()))
                .isEqualTo("IN_REVIEW");
        assertThat(db.queryForObject("SELECT classification_finalized_at IS NULL FROM tickets WHERE id=?", Boolean.class, ticket.getId()))
                .isTrue();
        assertThat(count("outbox_events", ticket)).isZero();
        assertThat(count("ticket_activities", ticket)).isZero();
    }

    @Test
    void invalidConfirmedAnswerRollsBackRoutingBeforePublication() {
        Ticket ticket = routingFixture(false);
        db.update("UPDATE tickets SET form_data='{\"cablesVisibles\":{\"token\":\"private\"}}'::jsonb WHERE id=?", ticket.getId());
        assertThatThrownBy(() -> ticketService.routeToArea(ticket.getId(), triageAgent()))
                .isInstanceOf(com.reclamos.backend.exception.FormValidationException.class);
        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticket.getId()))
                .isEqualTo("IN_REVIEW");
        assertThat(count("outbox_events", ticket)).isZero();
        assertThat(count("ticket_activities", ticket)).isZero();
    }

    private AuthenticatedIdentity triageAgent() {
        return new AuthenticatedIdentity("h10-agent", UUID.randomUUID(), "Agent", null, ModuleRole.AGENT);
    }

    private Ticket routingFixture(boolean anonymous) {
        Ticket ticket = create(TicketStatus.IN_REVIEW, anonymous, "M3");
        return transactions.execute(ignored -> {
            Ticket managed = tickets.findById(ticket.getId()).orElseThrow();
            RequestType confirmed = requestTypes.findAll().stream()
                    .filter(rt -> "INFORMAR_UNA_LUMINARIA_APAGADA".equals(rt.getCode())).findFirst().orElseThrow();
            managed.setRequestType(confirmed);
            managed.setTicketType(confirmed.getTicketType());
            managed.setResponsibleAreaId(confirmed.getResponsibleAreaId());
            managed.setFormTemplateId(templates.findFirstByRequestType_IdAndActiveTrueOrderByVersionDesc(confirmed.getId())
                    .orElseThrow().getId());
            // A lifecycle fixture must satisfy creation/confirmation rules. Snapshot filtering
            // of historical unexpected keys is exercised separately as defense in depth.
            managed.setFormData(Map.of("cantidadLuminarias", "UNA", "zonaSinIluminacion", false, "cablesVisibles", false));
            if (anonymous) {
                originalAnonymousPolicy.putIfAbsent(confirmed.getId(), confirmed.isAllowsAnonymous());
                confirmed.setAllowsAnonymous(true);
            }
            managed.setCurrentPriority(Priority.HIGH);
            managed.setEscalated(true);
            managed.setEscalationReasonCode(EscalationReasonCode.MANUAL);
            managed.setEscalatedAt(Instant.now().minusSeconds(30));
            return tickets.saveAndFlush(managed);
        });
    }

    private Long evidence(Ticket ticket, MessageVisibility visibility, String fileName) {
        return transactions.execute(ignored -> {
            Attachment file = new Attachment();
            file.setTicket(tickets.findById(ticket.getId()).orElseThrow());
            file.setFileName(fileName);
            file.setContentType("application/pdf");
            file.setSizeBytes(42);
            file.setStorageKey("storage/" + UUID.randomUUID());
            file.setVisibility(visibility);
            file.setUploadedByType(ActorType.CITIZEN);
            return attachments.saveAndFlush(file).getId();
        });
    }

    @ParameterizedTest
    @MethodSource("externalVariants")
    void externalFactCommitsLocalEffectsWithoutEchoAndReplayIsIdempotent(UpdateTicketStatusType type,
                                                                       boolean anonymous) {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        Ticket ticket = create(type == UpdateTicketStatusType.PROGRESS ? TicketStatus.IN_PROGRESS : TicketStatus.ROUTED,
                anonymous, "M6");
        var envelope = envelope(ticket, type, "REQUEST_TYPE_MISMATCH");
        var expectedStatus = switch (type) {
            case STARTED, PROGRESS -> TicketStatus.IN_PROGRESS;
            case RETURNED -> TicketStatus.IN_REVIEW;
            case INFORMATION_REQUIRED -> TicketStatus.PENDING_INFORMATION;
            case RESOLVED -> TicketStatus.RESOLVED;
            case REJECTED -> TicketStatus.CANCELLED;
        };

        assertThat(updates.applyUpdate(ticket.getId(), envelope).getCurrentStatus()).isEqualTo(expectedStatus);
        assertThat(count("outbox_events", ticket)).isZero();
        assertThat(count("ticket_activities", ticket)).isEqualTo(1);
        assertThat(count("ticket_messages", ticket)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT external_event_id FROM ticket_activities WHERE ticket_id=?",
                UUID.class, ticket.getId())).isEqualTo(envelope.eventId());
        assertThat(db.queryForObject("SELECT status FROM inbox_events WHERE event_id=?",
                String.class, envelope.eventId())).isEqualTo("PROCESSED");
        int notifications = type == UpdateTicketStatusType.PROGRESS ? 0 : 1;
        assertThat(count("notification_logs", ticket)).isEqualTo(notifications);
        if (type == UpdateTicketStatusType.PROGRESS) {
            assertThat(db.queryForObject("SELECT current_progress FROM tickets WHERE id=?", Integer.class,
                    ticket.getId())).isEqualTo(40);
        }
        if (type == UpdateTicketStatusType.INFORMATION_REQUIRED) {
            assertThat(count("information_requests", ticket)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT paused_at IS NOT NULL FROM ticket_sla WHERE ticket_id=?",
                    Boolean.class, ticket.getId())).isTrue();
        }
        if (type == UpdateTicketStatusType.RESOLVED || type == UpdateTicketStatusType.REJECTED) {
            assertThat(db.queryForObject("SELECT completed_at IS NOT NULL FROM ticket_sla WHERE ticket_id=?",
                    Boolean.class, ticket.getId())).isTrue();
            assertThat(count(type == UpdateTicketStatusType.RESOLVED ? "ticket_resolutions" : "ticket_cancellations",
                    ticket)).isEqualTo(1);
        }
        var beforeReplay = snapshot(ticket);
        updates.applyUpdate(ticket.getId(), envelope);
        assertThat(snapshot(ticket)).isEqualTo(beforeReplay);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_INFORMATION", "REQUEST_TYPE_MISMATCH", "INSUFFICIENT_CONTEXT", "OTHER"})
    void returnedAcceptsEveryContractCodeWithoutReclassification(String reasonCode) {
        for (TicketStatus status : List.of(TicketStatus.ROUTED, TicketStatus.IN_PROGRESS)) {
            Ticket ticket = create(status, false, "M6");
            updates.applyUpdate(ticket.getId(), envelope(ticket, UpdateTicketStatusType.RETURNED, reasonCode));
            assertThat(db.queryForObject("SELECT reason_code FROM ticket_activities WHERE ticket_id=?",
                    String.class, ticket.getId())).isEqualTo(reasonCode);
            assertThat(db.queryForObject("SELECT responsible_area_id FROM tickets WHERE id=?",
                    String.class, ticket.getId())).isEqualTo("M6");
            assertThat(count("outbox_events", ticket)).isZero();
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "UNKNOWN", "WRONG_AREA", "request_type_mismatch", "OTHER ", " OTHER"})
    void invalidReturnedCodeLeavesNoPersistedEffects(String reasonCode) {
        for (TicketStatus status : List.of(TicketStatus.ROUTED, TicketStatus.IN_PROGRESS)) {
            Ticket ticket = create(status, false, "M6");
            var event = envelope(ticket, UpdateTicketStatusType.RETURNED, reasonCode);
            assertRejectedWithoutWrites(ticket, event);
        }
    }

    @Test
    void returnedWithoutDetailsOrReturnInfoLeavesNoPersistedEffects() {
        Ticket ticket = create(TicketStatus.ROUTED, false, "M6");
        for (UpdateTicketStatusRequest.Details details : Arrays.asList(null,
                new UpdateTicketStatusRequest.Details(null, null, null, null))) {
            var data = new UpdateTicketStatusRequest(ticket.getId(), UpdateTicketStatusType.RETURNED,
                    "Explicación", null, null, details,
                    new UpdateTicketStatusRequest.Actor("EXTERNAL_USER", "external-user"), Instant.now());
            assertRejectedWithoutWrites(ticket, wrap(ticket, data));
        }
    }

    private void assertRejectedWithoutWrites(Ticket ticket, UpdateTicketStatusEnvelope event) {
        var before = snapshot(ticket);
        assertThatThrownBy(() -> updates.applyUpdate(ticket.getId(), event))
                .isInstanceOf(InvalidTicketRequestException.class);
        assertThat(snapshot(ticket)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM inbox_events WHERE event_id=?", Integer.class,
                event.eventId())).isZero();
    }

    static Stream<Arguments> closures() {
        return Stream.of("M2", "M6").flatMap(area -> Stream.of(false, true).flatMap(anonymous ->
                Stream.of(false, true).map(timeout -> Arguments.of(area, anonymous, timeout))));
    }

    @ParameterizedTest
    @MethodSource("closures")
    @SuppressWarnings("unchecked")
    void realConfirmationAndTimeoutClosePrincipalAndDuplicateWithContractData(String area, boolean anonymous,
                                                                            boolean timeout) throws Exception {
        Ticket main = create(TicketStatus.RESOLVED, anonymous, area);
        Ticket duplicate = create(TicketStatus.DUPLICATE, anonymous, area);
        Instant now = Instant.now();
        db.update("UPDATE tickets SET main_ticket_id=? WHERE id=?", main.getId(), duplicate.getId());
        db.update("UPDATE tickets SET resolution_confirmation_due_at=? WHERE id=?",
                java.sql.Timestamp.from(now.minusSeconds(1)), main.getId());
        if (timeout) timeouts.closeIfConfirmationExpired(main.getId(), now);
        else if (anonymous) resolutions.confirmAnonymous(main.getId());
        else resolutions.confirm(main.getId(), owner(main));

        for (Ticket ticket : List.of(main, duplicate)) {
            assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class,
                    ticket.getId())).isEqualTo("CLOSED");
            assertThat(db.queryForObject("SELECT reason_code FROM ticket_activities WHERE ticket_id=?", String.class,
                    ticket.getId())).isEqualTo(timeout ? "CONFIRMATION_TIMEOUT" : "CITIZEN_CONFIRMED");
            int eventCount = anonymous && area.equals("M2") ? 0 : 1;
            assertThat(count("outbox_events", ticket)).isEqualTo(eventCount);
            if (eventCount == 1) {
                String json = db.queryForObject("SELECT payload::text FROM outbox_events WHERE ticket_id=?",
                        String.class, ticket.getId());
                var payload = new ObjectMapper().readValue(json, Map.class);
                EventDataContractAssertions.assertValid(payload.get("data"));
            }
        }
    }

    @Test
    void externalResolutionStillAllowsIndependentM2ReopenAndClose() {
        Ticket ticket = create(TicketStatus.ROUTED, false, "M6");
        updates.applyUpdate(ticket.getId(), envelope(ticket, UpdateTicketStatusType.RESOLVED, null));
        resolutions.reopen(ticket.getId(), new ReopenTicketRequest("El problema persiste"), owner(ticket));
        assertThat(eventTypes(ticket)).containsExactly("REOPENED");
        updates.applyUpdate(ticket.getId(), envelope(ticket, UpdateTicketStatusType.RESOLVED, null));
        resolutions.confirm(ticket.getId(), owner(ticket));
        assertThat(eventTypes(ticket)).containsExactlyInAnyOrder("REOPENED", "CLOSED");
    }

    @Test
    void citizenInformationResponseAfterExternalRequestStillPublishesIndependentFact() {
        Ticket ticket = create(TicketStatus.ROUTED, false, "M6");
        updates.applyUpdate(ticket.getId(), envelope(ticket, UpdateTicketStatusType.INFORMATION_REQUIRED, null));
        information.answerInformation(ticket.getId(), new AnswerInformationRequest("Información del ciudadano"), owner(ticket));
        assertThat(eventTypes(ticket)).containsExactly("INFORMATION_PROVIDED");
    }

    static Stream<Arguments> internalInformationRequests() {
        return Stream.of(Arguments.of(TicketStatus.IN_REVIEW, "M2"), Arguments.of(TicketStatus.IN_REVIEW, "M6"),
                Arguments.of(TicketStatus.IN_PROGRESS, "M2"), Arguments.of(TicketStatus.IN_PROGRESS, "M6"),
                Arguments.of(TicketStatus.ROUTED, "M6"));
    }

    @ParameterizedTest
    @MethodSource("internalInformationRequests")
    @SuppressWarnings("unchecked")
    void internalRequestCommitsV177ProjectionWithMessageDeadlineAndPrivateAudit(TicketStatus status, String area) throws Exception {
        Ticket ticket = create(status, false, area);
        AuthenticatedIdentity actor = triageAgent();
        var result = information.requestInformation(ticket.getId(),
                new CreateInformationRequest("Indique la altura", "private-staff-note"), actor);

        assertThat(eventTypes(ticket)).containsExactly("INFORMATION_REQUIRED");
        var payload = payloads(ticket).getFirst();
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        var data = (Map<String, Object>) payload.get("data");
        assertThat(data).containsEntry("currentStatus", "PENDING_INFORMATION")
                .containsEntry("responsibleAreaId", area).containsEntry("publicMessage", result.getMessageForCitizen())
                .containsEntry("updatedAt", result.getRequestedAt().toString());
        var details = (Map<String, Object>) data.get("details");
        assertThat(details).containsOnlyKeys("informationRequest");
        assertThat((Map<String, Object>) details.get("informationRequest"))
                .containsOnlyKeys("messageForCitizen", "requiredBy")
                .containsEntry("messageForCitizen", "Indique la altura")
                .containsEntry("requiredBy", result.getDueAt().toString());
        var request = db.queryForMap("SELECT * FROM information_requests WHERE ticket_id=?", ticket.getId());
        assertThat(request).containsEntry("resume_status", status.name())
                .containsEntry("requested_by_module_id", "M2")
                .containsEntry("requested_by_actor_id", actor.citizenId().toString())
                .containsEntry("internal_message", "private-staff-note");
        assertThat(payload.toString()).doesNotContain("private-staff-note", actor.citizenId().toString(),
                request.get("id").toString(), "informationRequestId", "internalMessage", "formData", "formTemplateId");
        if (status != TicketStatus.IN_REVIEW) {
            assertThat(db.queryForObject("SELECT paused_at IS NOT NULL FROM ticket_sla WHERE ticket_id=?",
                    Boolean.class, ticket.getId())).isTrue();
        }
        var beforeRetry = snapshot(ticket);
        assertThatThrownBy(() -> information.requestInformation(ticket.getId(),
                new CreateInformationRequest("Indique la altura", "private-staff-note"), actor))
                .isInstanceOf(InformationRequestConflictException.class);
        assertThat(snapshot(ticket)).isEqualTo(beforeRetry);

        information.answerInformation(ticket.getId(), new AnswerInformationRequest("Altura 1240"), owner(ticket));
        assertThat(eventTypes(ticket)).containsExactly("INFORMATION_REQUIRED", "INFORMATION_PROVIDED");
        EventEnvelopeContractAssertions.assertFullEventValid(payloads(ticket).getLast());
        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticket.getId()))
                .isEqualTo(status.name());
    }

    @Test
    void anonymousSelfManagedInternalRequestAndAnswerRemainLocal() {
        Ticket ticket = create(TicketStatus.IN_PROGRESS, true, "M2");
        information.requestInformation(ticket.getId(), new CreateInformationRequest("Aporte el dato", "private-note"), triageAgent());
        assertThat(count("outbox_events", ticket)).isZero();
        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticket.getId()))
                .isEqualTo("PENDING_INFORMATION");
        assertThat(db.queryForObject("SELECT paused_at IS NOT NULL FROM ticket_sla WHERE ticket_id=?",
                Boolean.class, ticket.getId())).isTrue();

        information.answerAnonymousFromTracking(ticket.getId(), new AnswerInformationRequest("Respuesta anónima"));
        assertThat(count("outbox_events", ticket)).isZero();
        assertThat(db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, ticket.getId()))
                .isEqualTo("IN_PROGRESS");
        assertThat(db.queryForObject("SELECT paused_at IS NULL FROM ticket_sla WHERE ticket_id=?",
                Boolean.class, ticket.getId())).isTrue();
        assertThat(db.queryForMap("SELECT answered_by_type,answered_by_id FROM information_requests WHERE ticket_id=?",
                ticket.getId())).containsEntry("answered_by_type", "CITIZEN").containsEntry("answered_by_id", null);
    }

    static Stream<Arguments> informationResponses() {
        return Stream.of("internal", "external", "anonymous-external").flatMap(origin ->
                Stream.of("text", "attachments", "both").map(content -> Arguments.of(origin, content)));
    }

    @ParameterizedTest
    @MethodSource("informationResponses")
    @SuppressWarnings("unchecked")
    void informationProvidedCommitsFullV177EventForTextAttachmentsOrBoth(String origin, String content) throws Exception {
        boolean anonymous = origin.equals("anonymous-external");
        boolean external = !origin.equals("internal");
        Ticket ticket = create(TicketStatus.IN_PROGRESS, anonymous, external ? "M6" : "M2");
        evidence(ticket, MessageVisibility.INTERNAL, "private-history.pdf");
        evidence(ticket, MessageVisibility.PUBLIC, "old-public-history.pdf");
        if (external) {
            var incoming = envelope(ticket, UpdateTicketStatusType.INFORMATION_REQUIRED, null);
            updates.applyUpdate(ticket.getId(), incoming);
            var beforeReplay = snapshot(ticket);
            updates.applyUpdate(ticket.getId(), incoming);
            assertThat(snapshot(ticket)).isEqualTo(beforeReplay);
            assertThat(count("outbox_events", ticket)).isZero();
        } else {
            information.requestInformation(ticket.getId(), new CreateInformationRequest("Aporte el dato", "private-note"), triageAgent());
        }
        String text = content.equals("attachments") ? null : "  Altura 1240  ";
        MultipartFile[] files = content.equals("text") ? new MultipartFile[0] : new MultipartFile[]{
                AttachmentFixtures.file("attachments", "evidence.pdf", "application/pdf")};
        var result = anonymous
                ? information.answerAnonymousFromTracking(ticket.getId(), new AnswerInformationRequest(text), files)
                : information.answerInformation(ticket.getId(), new AnswerInformationRequest(text), owner(ticket), files);

        assertThat(eventTypes(ticket)).containsExactlyElementsOf(external
                ? List.of("INFORMATION_PROVIDED") : List.of("INFORMATION_REQUIRED", "INFORMATION_PROVIDED"));
        var payload = payloads(ticket).getLast();
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        var data = (Map<String, Object>) payload.get("data");
        assertThat(data).containsEntry("currentStatus", "IN_PROGRESS").containsEntry("isAnonymous", anonymous)
                .containsEntry("citizenId", anonymous ? null : ticket.getCitizenId().toString())
                .containsEntry("publicMessage", "El ciudadano aportó la información solicitada.")
                .doesNotContainKeys("updatedBy", "internalMessage", "informationRequestId", "formTemplateId", "formData");
        var response = (Map<String, Object>) ((Map<String, Object>) data.get("details")).get("informationResponse");
        assertThat(response).containsOnlyKeys("message").containsEntry("message", text == null ? null : "Altura 1240");
        var publishedFiles = (List<Map<String, Object>>) data.get("attachments");
        assertThat(publishedFiles).hasSize(files.length);
        if (files.length > 0) {
            assertThat(publishedFiles.getFirst()).containsOnlyKeys("fileName", "contentType", "url", "sizeBytes")
                    .containsEntry("fileName", "evidence.pdf")
                    .containsEntry("sizeBytes", AttachmentFixtures.bytes("application/pdf").length);
            assertThat(publishedFiles.getFirst().get("url")).isEqualTo(result.getAttachments().getFirst().getDownloadUrl());
            assertThat(db.queryForObject("SELECT count(*) FROM information_request_attachments r JOIN attachments a "
                    + "ON a.id=r.attachment_id WHERE a.ticket_id=? AND r.role='RESPONSE'", Integer.class, ticket.getId())).isEqualTo(1);
        }
        assertThat(payload.toString()).doesNotContain("private-", "old-public-history", "storageKey", "trackingCode", "anonymousContact");
        var audit = db.queryForMap("SELECT answered_by_type,answered_by_id,status FROM information_requests WHERE ticket_id=?", ticket.getId());
        assertThat(audit).containsEntry("answered_by_type", "CITIZEN").containsEntry("status", "ANSWERED")
                .containsEntry("answered_by_id", anonymous ? null : ticket.getCitizenId().toString());
        assertThat(db.queryForMap("SELECT actor_type,actor_id FROM ticket_activities "
                        + "WHERE ticket_id=? AND action_type='INFORMATION_PROVIDED'", ticket.getId()))
                .containsEntry("actor_type", "CITIZEN").containsEntry("actor_id", audit.get("answered_by_id"));
        assertThat(db.queryForObject("SELECT paused_at IS NULL FROM ticket_sla WHERE ticket_id=?", Boolean.class, ticket.getId())).isTrue();
        var beforeRetry = snapshot(ticket);
        assertThatThrownBy(() -> {
            if (anonymous) information.answerAnonymousFromTracking(ticket.getId(), new AnswerInformationRequest(text), files);
            else information.answerInformation(ticket.getId(), new AnswerInformationRequest(text), owner(ticket), files);
        }).isInstanceOf(InformationRequestConflictException.class);
        assertThat(snapshot(ticket)).isEqualTo(beforeRetry);
    }

    @Test
    void informationRequestAndAnswerRollbackIncludeOutboxAndAllowSafeRetry() {
        Ticket ticket = create(TicketStatus.IN_PROGRESS, false, "M2");
        var initial = snapshot(ticket);
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            information.requestInformation(ticket.getId(), new CreateInformationRequest("Aporte el dato", "private-note"), triageAgent());
            outboxEvents.flush();
            assertThat(eventTypes(ticket)).containsExactly("INFORMATION_REQUIRED");
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(ticket)).isEqualTo(initial);
        information.requestInformation(ticket.getId(), new CreateInformationRequest("Aporte el dato", "private-note"), triageAgent());
        var pending = snapshot(ticket);
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            information.answerInformation(ticket.getId(), new AnswerInformationRequest("Respuesta"), owner(ticket));
            outboxEvents.flush();
            assertThat(eventTypes(ticket)).containsExactly("INFORMATION_REQUIRED", "INFORMATION_PROVIDED");
            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(snapshot(ticket)).isEqualTo(pending);
        information.answerInformation(ticket.getId(), new AnswerInformationRequest("Respuesta"), owner(ticket));
        assertThat(eventTypes(ticket)).containsExactly("INFORMATION_REQUIRED", "INFORMATION_PROVIDED");
    }

    @Test
    void persistedSlaEscalationClearedDuringExternalResolutionPublishesItsOwnFact() throws Exception {
        Ticket ticket = create(TicketStatus.ROUTED, false, "M6");
        Instant now = Instant.now();
        db.update("UPDATE ticket_sla SET status='BREACHED',started_at=?,near_due_at=?,due_at=? WHERE ticket_id=?",
                java.sql.Timestamp.from(now.minusSeconds(300)), java.sql.Timestamp.from(now.minusSeconds(200)),
                java.sql.Timestamp.from(now.minusSeconds(100)), ticket.getId());
        db.update("UPDATE tickets SET is_escalated=TRUE,escalation_reason_code='SLA_BREACHED',escalated_at=? WHERE id=?",
                java.sql.Timestamp.from(now.minusSeconds(100)), ticket.getId());
        var incoming = envelope(ticket, UpdateTicketStatusType.RESOLVED, null);
        updates.applyUpdate(ticket.getId(), incoming);
        assertThat(eventTypes(ticket)).containsExactly("ESCALATION_CHANGED");
        var payload = payloads(ticket).getFirst();
        EventEnvelopeContractAssertions.assertValid(payload);
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        assertThat(payload).containsEntry("correlationId", null)
                .containsEntry("causationId", incoming.eventId().toString());
        assertThat(db.queryForMap("SELECT is_escalated,escalation_reason_code,escalated_at FROM tickets WHERE id=?", ticket.getId()))
                .containsEntry("is_escalated", false).containsEntry("escalation_reason_code", null).containsEntry("escalated_at", null);
        assertThat(db.queryForMap("SELECT status,completed_at IS NOT NULL AS completed FROM ticket_sla WHERE ticket_id=?", ticket.getId()))
                .containsEntry("status", "BREACHED").containsEntry("completed", true);
        var beforeReplay = snapshot(ticket);
        updates.applyUpdate(ticket.getId(), incoming);
        assertThat(snapshot(ticket)).isEqualTo(beforeReplay);
        // A later direct action starts its own process; it must not inherit the external cause.
        resolutions.confirm(ticket.getId(), owner(ticket));
        var closed = payloads(ticket).getLast();
        assertThat(closed.get("causationId")).isNull();
        assertThat(UUID.fromString((String) closed.get("correlationId"))).isNotEqualTo(ticket.getId());
    }

    @Test
    void outboxSnapshotSurvivesPersistenceFailedRetriesAndPublication() throws Exception {
        Ticket ticket = create(TicketStatus.CLOSED, false, "M6");
        Instant businessAt = Instant.now().minusSeconds(30);
        transactions.executeWithoutResult(ignored ->
                outbox.closed(tickets.findById(ticket.getId()).orElseThrow(), "CITIZEN_CONFIRMED", businessAt));
        var original = payloads(ticket).getFirst();
        EventEnvelopeContractAssertions.assertValid(original);
        EventEnvelopeContractAssertions.assertFullEventValid(original);
        assertThat(Instant.parse((String) original.get("occurredAt"))).isAfter(businessAt);
        assertThat(original.get("eventId")).isNotEqualTo(original.get("correlationId"));
        Long rowId = db.queryForObject("SELECT id FROM outbox_events WHERE ticket_id=?", Long.class, ticket.getId());
        for (OutboxStatus status : List.of(OutboxStatus.FAILED, OutboxStatus.PENDING, OutboxStatus.PUBLISHED)) {
            transactions.executeWithoutResult(ignored -> {
                OutboxEvent row = outboxEvents.findById(rowId).orElseThrow();
                assertThat(row.getPayload()).isEqualTo(original);
                assertThat(row.getEventId().toString()).isEqualTo(original.get("eventId"));
                row.setStatus(status);
                row.setRetryCount(row.getRetryCount() + 1);
                if (status == OutboxStatus.PUBLISHED) row.setPublishedAt(Instant.now());
                outboxEvents.saveAndFlush(row);
            });
            assertThat(payloads(ticket)).containsExactly(original);
        }
    }

    @Test
    void directBatchSharesCorrelationAndRequiresNewTransactionIsIsolated() throws Exception {
        Ticket first = create(TicketStatus.CLOSED, false, "M6");
        Ticket second = create(TicketStatus.CLOSED, false, "M6");
        Ticket independent = create(TicketStatus.CLOSED, false, "M6");
        var requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.executeWithoutResult(ignored -> {
            outbox.closed(tickets.findById(first.getId()).orElseThrow(), "CITIZEN_CONFIRMED", Instant.now());
            requiresNew.executeWithoutResult(nested ->
                    outbox.closed(tickets.findById(independent.getId()).orElseThrow(), "CITIZEN_CONFIRMED", Instant.now()));
            outbox.closed(tickets.findById(second.getId()).orElseThrow(), "CITIZEN_CONFIRMED", Instant.now());
        });
        var a = payloads(first).getFirst();
        var b = payloads(second).getFirst();
        var c = payloads(independent).getFirst();
        assertThat(a.get("correlationId")).isEqualTo(b.get("correlationId")).isNotEqualTo(c.get("correlationId"));
        for (var payload : List.of(a, b, c)) {
            EventEnvelopeContractAssertions.assertValid(payload);
            assertThat(payload.get("causationId")).isNull();
        }
    }

    @Test
    void outgoingTracePropagatesKnownIncomingCorrelationWithoutChangingDevInputContract() throws Exception {
        Ticket ticket = create(TicketStatus.CLOSED, false, "M6");
        UUID correlation = UUID.randomUUID();
        UUID cause = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> {
            try (var scope = OutgoingEventEnvelope.incoming(correlation, cause)) {
                outbox.closed(tickets.findById(ticket.getId()).orElseThrow(), "CITIZEN_CONFIRMED", Instant.now());
            }
        });
        var payload = payloads(ticket).getFirst();
        EventEnvelopeContractAssertions.assertValid(payload);
        assertThat(payload).containsEntry("correlationId", correlation.toString()).containsEntry("causationId", cause.toString());
    }

    @Test
    void rollbackKeepsBusinessAndOutboxAtomicAndDoesNotLeakTrace() throws Exception {
        Ticket ticket = create(TicketStatus.CLOSED, false, "M6");
        UUID rolledBackCorrelation = UUID.randomUUID();
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            try (var scope = OutgoingEventEnvelope.incoming(rolledBackCorrelation, UUID.randomUUID())) {
                Ticket managed = tickets.findById(ticket.getId()).orElseThrow();
                managed.setSummary("must roll back");
                outbox.closed(managed, "CITIZEN_CONFIRMED", Instant.now());
                outboxEvents.flush();
                throw new IllegalStateException("forced rollback");
            }
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("outbox_events", ticket)).isZero();
        assertThat(db.queryForObject("SELECT summary FROM tickets WHERE id=?", String.class, ticket.getId()))
                .isEqualTo(ticket.getSummary());
        transactions.executeWithoutResult(ignored ->
                outbox.closed(tickets.findById(ticket.getId()).orElseThrow(), "CITIZEN_CONFIRMED", Instant.now()));
        var payload = payloads(ticket).getFirst();
        assertThat(payload.get("causationId")).isNull();
        assertThat(payload.get("correlationId")).isNotEqualTo(rolledBackCorrelation.toString());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> payloads(Ticket ticket) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String json : db.queryForList("SELECT payload::text FROM outbox_events WHERE ticket_id=? ORDER BY id",
                String.class, ticket.getId())) {
            result.add(new ObjectMapper().readValue(json, Map.class));
        }
        return result;
    }

    private List<String> eventTypes(Ticket ticket) {
        return db.queryForList("SELECT update_type FROM outbox_events WHERE ticket_id=? ORDER BY id", String.class,
                ticket.getId());
    }

    private AuthenticatedIdentity owner(Ticket ticket) {
        return new AuthenticatedIdentity("owner", ticket.getCitizenId(), "CITIZEN", null, ModuleRole.CITIZEN);
    }

    private UpdateTicketStatusEnvelope envelope(Ticket ticket, UpdateTicketStatusType type, String reasonCode) {
        var details = new UpdateTicketStatusRequest.Details(
                type == UpdateTicketStatusType.INFORMATION_REQUIRED
                        ? new UpdateTicketStatusRequest.InformationRequest("Aporte más información", null) : null,
                type == UpdateTicketStatusType.RETURNED ? new UpdateTicketStatusRequest.ReturnInfo(reasonCode) : null,
                type == UpdateTicketStatusType.RESOLVED
                        ? new UpdateTicketStatusRequest.Resolution(ResolutionType.ACTION_COMPLETED) : null,
                type == UpdateTicketStatusType.REJECTED ? new UpdateTicketStatusRequest.Cancellation("OUT_OF_SCOPE") : null);
        return wrap(ticket, new UpdateTicketStatusRequest(ticket.getId(), type, "Mensaje público", "Mensaje interno",
                type == UpdateTicketStatusType.PROGRESS ? 40 : null, details,
                new UpdateTicketStatusRequest.Actor("EXTERNAL_USER", "external-user"), Instant.now()));
    }

    private UpdateTicketStatusEnvelope wrap(Ticket ticket, UpdateTicketStatusRequest data) {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        return new UpdateTicketStatusEnvelope("1.0", eventId, "updateTicketStatus", Instant.now(),
                new UpdateTicketStatusEnvelope.Producer("M6", "external-service"), "tickets/" + ticket.getId(), data);
    }

    private int count(String table, Ticket ticket) {
        return db.queryForObject("SELECT count(*) FROM " + table + " WHERE ticket_id=?", Integer.class, ticket.getId());
    }

    private Map<String, Object> snapshot(Ticket ticket) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket", db.queryForMap("SELECT * FROM tickets WHERE id=?", ticket.getId()));
        for (String table : List.of("ticket_activities", "ticket_messages", "ticket_sla", "ticket_resolutions",
                "ticket_cancellations", "information_requests", "notification_logs", "outbox_events")) {
            result.put(table, db.queryForList("SELECT * FROM " + table + " WHERE ticket_id=? ORDER BY id", ticket.getId()));
        }
        return result;
    }

    private Ticket create(TicketStatus status, boolean anonymous, String area) {
        return transactions.execute(ignored -> {
            Ticket ticket = new Ticket();
            ticket.setPublicId("TK-2026-%06d".formatted(
                    db.queryForObject("SELECT nextval('ticket_public_id_seq')", Long.class)));
            ticket.setTrackingCodeHash("test-" + UUID.randomUUID());
            ticket.setAnonymous(anonymous);
            ticket.setCitizenId(anonymous ? null : UUID.randomUUID());
            if (!anonymous) {
                db.update("INSERT INTO module_users (citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                                + "VALUES (?, 'Event', 'Test', 'CITIZEN', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                        ticket.getCitizenId());
                citizenIds.add(ticket.getCitizenId());
            }
            if (anonymous) {
                ticket.setAnonymousAccessPasswordHash("test-only-password-hash");
                ticket.setAnonymousContactChannel(AnonymousContactChannel.EMAIL);
                ticket.setAnonymousContactValue("test@example.test");
            }
            var requestType = requestTypes.findAll().stream().filter(RequestType::isActive).findFirst().orElseThrow();
            ticket.setRequestType(requestType);
            ticket.setTicketType(requestType.getTicketType());
            ticket.setResponsibleAreaId(area);
            ticket.setSummary("Correcciones de eventos");
            ticket.setDescription("Prueba transaccional");
            ticket.setFormData(Map.of());
            ticket.setCurrentStatus(status);
            ticket.setCurrentPriority(Priority.MEDIUM);
            ticket.setEstimatedAffectedCount(0);
            ticket.setEscalated(false);
            ticket.setReopenCount(0);
            ticket.setCreatedAt(Instant.now().minusSeconds(60));
            ticket.setStatusChangedAt(ticket.getCreatedAt());
            ticket = tickets.saveAndFlush(ticket);
            ids.add(ticket.getId());
            if (status == TicketStatus.ROUTED || status == TicketStatus.IN_PROGRESS) {
                assertThat(sla.startInitialResolutionCycle(ticket, ticket.getCreatedAt())).isPresent();
            }
            return ticket;
        });
    }
}
