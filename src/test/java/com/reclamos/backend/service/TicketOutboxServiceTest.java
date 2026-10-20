package com.reclamos.backend.service;

import com.reclamos.backend.entity.CancellationReasonCode;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.AnonymousContactChannel;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.EscalationReasonCode;
import com.reclamos.backend.entity.Neighborhood;
import com.reclamos.backend.entity.OutboxStatus;
import com.reclamos.backend.entity.TicketLocation;
import com.reclamos.backend.entity.OutboxEvent;
import com.reclamos.backend.entity.Priority;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.ResolutionType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.entity.TicketType;
import com.reclamos.backend.entity.TicketUpdatedType;
import com.reclamos.backend.repository.OutboxEventRepository;
import com.reclamos.backend.repository.AttachmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class TicketOutboxServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-12T12:00:00Z");
    private static final Instant RESOLUTION_DUE = Instant.parse("2026-09-15T12:00:00Z");
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final AttachmentReferenceService attachmentReferenceService = mock(AttachmentReferenceService.class);
    private final AttachmentRepository attachmentRepository =
            mock(AttachmentRepository.class);
    private final FormValidationService formValidationService = mock(FormValidationService.class);
    private final TicketOutboxService service = new TicketOutboxService(
            repository, Clock.fixed(NOW, ZoneOffset.UTC), attachmentReferenceService, attachmentRepository, formValidationService);

    @BeforeEach
    void setUp() {
        reset(repository, attachmentReferenceService, attachmentRepository, formValidationService);
        org.mockito.Mockito.when(formValidationService.snapshotAnswers(any())).thenReturn(Map.of());
        org.mockito.Mockito.when(repository.save(any())).thenAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            EventEnvelopeContractAssertions.assertValid(event.getPayload());
            return event;
        });
    }

    @Test
    void ticketCreatedPublishesEveryIdentifiedRegisteredTicketButNeverAnonymous() {
        Ticket selfManaged = ticket(false, "M2", TicketStatus.REGISTERED);
        selfManaged.setAnonymousAccessPasswordHash("must-not-be-published");
        selfManaged.setAnonymousContactChannel(AnonymousContactChannel.EMAIL);
        selfManaged.setAnonymousContactValue("private@example.test");
        Ticket external = ticket(false, "M6", TicketStatus.REGISTERED);
        Ticket anonymous = ticket(true, "M6", TicketStatus.REGISTERED);

        service.ticketCreated(selfManaged, null, List.of(), RESOLUTION_DUE);
        service.ticketCreated(external, null, List.of(), RESOLUTION_DUE);
        service.ticketCreated(anonymous, null, List.of(), RESOLUTION_DUE);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.getEventType()).isEqualTo("ticketCreated");
            EventEnvelopeContractAssertions.assertFullEventValid(event.getPayload());
            assertThat(event.getUpdateType()).isNull();
            assertThat(event.getPayload().get("eventId")).isEqualTo(event.getEventId().toString());
            assertThat(event.getPayload().get("occurredAt")).isEqualTo(NOW.toString());
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) event.getPayload().get("data");
            assertThat(data).containsEntry("publicId", event.getTicket().getPublicId())
                    .containsEntry("isAnonymous", false)
                    .containsEntry("status", "REGISTERED")
                    .containsEntry("resolutionDueAt", RESOLUTION_DUE.toString())
                    .doesNotContainKeys("trackingCode", "trackingCodeHash", "anonymousAccessPassword",
                            "anonymousAccessPasswordHash", "anonymousContactChannel", "anonymousContactValue");
        });
    }

    @Test
    void internalProjectionEventsPublishOnlyForIdentifiedTickets() {
        Ticket identified = ticket(false, "M2", TicketStatus.IN_PROGRESS);
        Ticket anonymous = ticket(true, "M2", TicketStatus.IN_PROGRESS);

        service.statusChanged(identified, null, NOW);
        service.contentUpdated(identified, RESOLUTION_DUE, NOW);
        service.resolved(identified, ResolutionType.ACTION_COMPLETED, "Resuelto", NOW);
        service.statusChanged(anonymous, null, NOW);
        service.contentUpdated(anonymous, RESOLUTION_DUE, NOW);
        service.resolved(anonymous, ResolutionType.ACTION_COMPLETED, "Resuelto", NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(3)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(OutboxEvent::getUpdateType)
                .containsExactly(TicketUpdatedType.STATUS_CHANGED,
                        TicketUpdatedType.CONTENT_UPDATED, TicketUpdatedType.RESOLVED);
    }

    @Test
    void routedIsExclusiveToExternalManagementForIdentifiedAndAnonymous() {
        service.routed(ticket(false, "M6", TicketStatus.ROUTED), null, RESOLUTION_DUE, NOW);
        service.routed(ticket(true, "M6", TicketStatus.ROUTED), null, RESOLUTION_DUE, NOW);
        service.routed(ticket(false, "M2", TicketStatus.IN_PROGRESS), null, RESOLUTION_DUE, NOW);
        service.routed(ticket(true, "M2", TicketStatus.IN_PROGRESS), null, RESOLUTION_DUE, NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.ROUTED);
            assertThat(event.getTicket().getResponsibleAreaId()).isEqualTo("M6");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) event.getPayload().get("data");
            @SuppressWarnings("unchecked")
            Map<String, Object> details = (Map<String, Object>) data.get("details");
            @SuppressWarnings("unchecked")
            Map<String, Object> routing = (Map<String, Object>) details.get("routing");
            assertThat(routing).containsEntry("resolutionDueAt", RESOLUTION_DUE.toString());
            assertThat(routing).containsEntry("category", "Servicios urbanos")
                    .containsEntry("subcategory", "Alumbrado")
                    .containsEntry("requestType", "Informar luminaria apagada")
                    .containsEntry("reassignment", false);
            EventEnvelopeContractAssertions.assertFullEventValid(event.getPayload());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void routedContainsCompleteAuthorizedSnapshotAndIncrementalEventsDoNotReplayEvidence() {
        Ticket ticket = ticket(true, "M6", TicketStatus.ROUTED);
        ticket.setFormTemplateId(999L);
        ticket.setTrackingCodeHash("private-tracking");
        ticket.setAnonymousContactValue("private@example.test");
        ticket.setAnonymousAccessPasswordHash("private-password");
        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(NOW.minusSeconds(100));
        ticket.setCurrentPriority(Priority.CRITICAL);
        ticket.setCurrentProgress((short) 25);
        var answers = new LinkedHashMap<String, Object>();
        answers.put("quantity", 2);
        answers.put("unknown", null);
        org.mockito.Mockito.when(formValidationService.snapshotAnswers(ticket)).thenReturn(answers);
        var location = new TicketLocation();
        var neighborhood = new Neighborhood();
        neighborhood.setId(UUID.randomUUID());
        location.setNeighborhood(neighborhood);
        location.setAddressLine("Lima 123");
        location.setStreet("Lima");
        location.setStreetNumber("123");
        location.setReference("Esquina");
        location.setLatitude(new BigDecimal("-34.600000"));
        location.setLongitude(new BigDecimal("-58.400000"));
        Attachment evidence = new Attachment();
        evidence.setId(44L);
        evidence.setTicket(ticket);
        evidence.setVisibility(MessageVisibility.PUBLIC);
        evidence.setFileName("evidence.pdf");
        evidence.setContentType("application/pdf");
        evidence.setSizeBytes(42);
        evidence.setStorageKey("private-storage-key");
        Attachment internal = new Attachment();
        internal.setVisibility(MessageVisibility.INTERNAL);
        internal.setFileName("private-note.pdf");
        // Defence in depth even if a caller/repository hands back INTERNAL metadata.
        org.mockito.Mockito.when(attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                ticket.getId(), MessageVisibility.PUBLIC)).thenReturn(List.of(evidence, internal));
        org.mockito.Mockito.when(attachmentReferenceService.downloadUrl(evidence))
                .thenReturn("https://m2.example/api/attachments/44/content");

        service.routed(ticket, location, RESOLUTION_DUE, NOW);
        ticket.setAnonymous(false);
        ticket.setCitizenId(UUID.randomUUID());
        service.statusChanged(ticket, null, NOW);

        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        var event = captor.getAllValues().getFirst();
        EventEnvelopeContractAssertions.assertFullEventValid(event.getPayload());
        var data = (Map<String, Object>) event.getPayload().get("data");
        var routing = (Map<String, Object>) ((Map<String, Object>) data.get("details")).get("routing");
        assertThat(data).containsEntry("isAnonymous", true).containsEntry("citizenId", null)
                .containsEntry("currentStatus", "ROUTED").containsEntry("currentPriority", "CRITICAL")
                .containsEntry("progress", (short) 25).containsEntry("responsibleAreaId", "M6");
        assertThat(routing).containsOnlyKeys("requestType", "category", "subcategory", "ticketType",
                        "summary", "description", "formData", "location", "resolutionDueAt", "escalation", "reassignment")
                .containsEntry("ticketType", "COMPLAINT").containsEntry("summary", ticket.getSummary())
                .containsEntry("description", ticket.getDescription()).containsEntry("formData", answers)
                .containsEntry("resolutionDueAt", RESOLUTION_DUE.toString()).containsEntry("reassignment", false);
        assertThat((Map<String, Object>) routing.get("location")).containsOnlyKeys(
                "addressLine", "street", "streetNumber", "neighborhoodId", "reference")
                .containsEntry("neighborhoodId", neighborhood.getId()).containsEntry("street", "Lima")
                .containsEntry("streetNumber", "123").containsEntry("addressLine", "Lima 123")
                .containsEntry("reference", "Esquina");
        assertThat((Map<String, Object>) routing.get("escalation")).containsOnlyKeys("active", "reasonCode", "escalatedAt")
                .containsEntry("active", true).containsEntry("reasonCode", "CRITICAL_PRIORITY")
                .containsEntry("escalatedAt", NOW.minusSeconds(100).toString());
        assertThat((List<Map<String, Object>>) data.get("attachments")).singleElement().satisfies(file ->
                assertThat(file).containsOnlyKeys("fileName", "contentType", "url", "sizeBytes")
                        .containsEntry("fileName", "evidence.pdf").containsEntry("sizeBytes", 42L));
        assertThat(event.getPayload().toString()).doesNotContain("private-", "private@example", "formTemplateId", "latitude", "longitude");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getRetryCount()).isZero();
        verify(attachmentReferenceService, never()).downloadUrl(internal);
        var incremental = (Map<String, Object>) captor.getAllValues().getLast().getPayload().get("data");
        assertThat((List<?>) incremental.get("attachments")).isEmpty();
        EventEnvelopeContractAssertions.assertFullEventValid(captor.getAllValues().getLast().getPayload());
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestType", "category", "subcategory", "ticketType", "summary", "description"})
    @SuppressWarnings("unchecked")
    void fullRoutedContractRejectsMissingRequiredSnapshotFields(String field) {
        service.routed(ticket(false, "M6", TicketStatus.ROUTED), null, null, NOW);
        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        var payload = captor.getValue().getPayload();
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        var data = (Map<String, Object>) payload.get("data");
        var routing = (Map<String, Object>) ((Map<String, Object>) data.get("details")).get("routing");
        routing.remove(field);
        EventEnvelopeContractAssertions.assertFullEventInvalid(payload);
    }

    @ParameterizedTest
    @ValueSource(strings = {"formTemplateId", "formFieldId", "riskRuleId", "ticketSlaId", "policyId", "internalMessage"})
    @SuppressWarnings("unchecked")
    void fullRoutedContractRejectsInternalMetadataInRouting(String field) {
        service.routed(ticket(true, "M6", TicketStatus.ROUTED), null, null, NOW);
        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        var payload = captor.getValue().getPayload();
        EventEnvelopeContractAssertions.assertFullEventValid(payload);
        var data = (Map<String, Object>) payload.get("data");
        var routing = (Map<String, Object>) ((Map<String, Object>) data.get("details")).get("routing");
        routing.put(field, "private");
        EventEnvelopeContractAssertions.assertFullEventInvalid(payload);
    }

    @Test
    void informationProvidedPublishesForIdentifiedAndForAnonymousExternalRequestOnly() {
        service.informationProvided(ticket(false, "M2", TicketStatus.IN_PROGRESS), "Dato", false, NOW);
        service.informationProvided(ticket(false, "M6", TicketStatus.IN_PROGRESS), "Dato", true, NOW);
        service.informationProvided(ticket(true, "M6", TicketStatus.IN_PROGRESS), "Dato", true, NOW);
        service.informationProvided(ticket(true, "M6", TicketStatus.IN_PROGRESS), "Dato", false, NOW);
        service.informationProvided(ticket(true, "M2", TicketStatus.IN_PROGRESS), "Dato", false, NOW);

        verify(repository, times(3)).save(any());
    }

    @Test
    void informationProvidedContainsRestoredTicketTextAttachmentAndCitizenActor() {
        Ticket ticket = ticket(true, "M6", TicketStatus.IN_PROGRESS);
        Attachment attachment = new Attachment();
        attachment.setTicket(ticket);
        attachment.setFileName("proof.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(42);
        attachment.setStorageKey("tickets/one/proof");
        attachment.setVisibility(MessageVisibility.PUBLIC);
        attachment.setId(44L);
        org.mockito.Mockito.when(attachmentReferenceService.downloadUrl(attachment))
                .thenReturn("https://m2.example/api/attachments/44/content");

        service.informationProvided(ticket, "Detalle", List.of(attachment),
                ActorType.CITIZEN, null, true, NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.INFORMATION_PROVIDED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.getPayload().get("data");
        assertThat(data).containsEntry("citizenId", null)
                .containsEntry("isAnonymous", true)
                .containsEntry("responsibleAreaId", "M6")
                .containsEntry("currentStatus", "IN_PROGRESS")
                .containsEntry("currentPriority", "MEDIUM")
                .containsEntry("updatedAt", NOW.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) data.get("details");
        assertThat((Map<String, Object>) details.get("informationResponse"))
                .containsEntry("message", "Detalle");
        assertThat((List<Map<String, Object>>) data.get("attachments"))
                .singleElement().satisfies(value -> assertThat(value)
                        .containsEntry("fileName", "proof.pdf")
                        .containsEntry("contentType", "application/pdf")
                        .containsEntry("url", "https://m2.example/api/attachments/44/content")
                        .doesNotContainValue("tickets/one/proof")
                        .containsEntry("sizeBytes", 42L));
        assertThat((Map<String, Object>) data.get("updatedBy"))
                .containsEntry("type", "CITIZEN")
                .containsEntry("id", null);
    }

    @Test
    void reopenedPublishesForIdentifiedOrExternalAnonymous() {
        service.reopened(ticket(false, "M2", TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(false, "M6", TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(true, "M6", TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(true, "M2", TicketStatus.IN_PROGRESS), "Sigue", NOW);

        verify(repository, times(3)).save(any());
    }

    @Test
    void cancelledPublishesLegitimatePostRoutingExternalFactButSkipsEchoAndSelfManagedAnonymousFact() {
        service.cancelled(ticket(false, "M2", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);
        service.cancelled(ticket(false, "M6", TicketStatus.CANCELLED),
                CancellationReasonCode.OUT_OF_SCOPE, "Cancelado", false, NOW);
        service.cancelled(ticket(true, "M6", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);
        service.cancelled(ticket(true, "M6", TicketStatus.CANCELLED),
                CancellationReasonCode.OUT_OF_SCOPE, "Cancelado", false, NOW);
        service.cancelled(ticket(true, "M2", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event ->
                assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.CANCELLED));
    }

    @Test
    void resolvedNeverEchoesAnAnonymousExternalFact() {
        service.resolved(ticket(true, "M6", TicketStatus.RESOLVED),
                ResolutionType.ACTION_COMPLETED, "Resuelto", NOW);

        verify(repository, never()).save(any());
    }

    private Ticket ticket(boolean anonymous, String areaId, TicketStatus status) {
        Category category = new Category();
        category.setName("Servicios urbanos");
        Subcategory subcategory = new Subcategory();
        subcategory.setName("Alumbrado");
        subcategory.setCategory(category);
        RequestType requestType = new RequestType();
        requestType.setName("Informar luminaria apagada");
        requestType.setSubcategory(subcategory);

        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        ticket.setPublicId("TK-2026-000123");
        ticket.setCitizenId(anonymous ? null : UUID.randomUUID());
        ticket.setAnonymous(anonymous);
        ticket.setRequestType(requestType);
        ticket.setTicketType(TicketType.COMPLAINT);
        ticket.setSummary("Luminaria apagada");
        ticket.setDescription("La luminaria no funciona");
        ticket.setFormData(Map.of());
        ticket.setCurrentStatus(status);
        ticket.setCurrentPriority(Priority.MEDIUM);
        ticket.setResponsibleAreaId(areaId);
        ticket.setCreatedAt(NOW);
        return ticket;
    }
}
