package com.reclamos.backend.service;

import com.reclamos.backend.entity.CancellationReasonCode;
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
import org.junit.jupiter.params.provider.CsvSource;
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
            repository, Clock.fixed(NOW, ZoneOffset.UTC), attachmentReferenceService, attachmentRepository, formValidationService,
            mock(com.reclamos.backend.outbox.CorePublicationPolicy.class));

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
        selfManaged.setTrackingCodeCiphertext("private-ciphertext".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        selfManaged.setTrackingCodeNonce("private-nonce".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        selfManaged.setTrackingCodeKeyId("private-key-id");
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
                            "trackingCodeCiphertext", "trackingCodeNonce", "trackingCodeKeyId",
                            "anonymousAccessPasswordHash", "anonymousContactChannel", "anonymousContactValue");
            assertThat(event.getPayload().toString()).doesNotContain("private-ciphertext", "private-nonce", "private-key-id");
        });
    }

    @ParameterizedTest @CsvSource({"M3,false,false","M3,false,true","M4,false,false","M4,false,true",
            "M7,false,false","M7,false,true","M7,true,false","M7,true,true"})
    @SuppressWarnings("unchecked")
    void externalRoutingHasContractSnapshotAndReassignmentWithoutCreatingATicketEvent(String area,boolean anonymous,boolean reassignment) {
        Ticket ticket=ticket(anonymous,area,TicketStatus.ROUTED);
        service.routed(ticket,null,RESOLUTION_DUE,NOW,reassignment);
        var captor=ArgumentCaptor.forClass(OutboxEvent.class); verify(repository).save(captor.capture());
        var event=captor.getValue(); EventEnvelopeContractAssertions.assertFullEventValid(event.getPayload());
        assertThat(event.getEventType()).isEqualTo("ticketUpdated");
        assertThat(event.getPayload()).containsEntry("sourceModule","atencion-ciudadana").containsEntry("eventVersion","1.0");
        var data=(Map<String,Object>)event.getPayload().get("data");
        assertThat(data).containsEntry("ticketId",ticket.getId()).containsEntry("publicId",ticket.getPublicId())
                .containsEntry("responsibleAreaId",area).containsEntry("updateType","ROUTED");
        var routing=(Map<String,Object>)((Map<String,Object>)data.get("details")).get("routing");
        assertThat(routing).containsEntry("reassignment",reassignment).containsEntry("resolutionDueAt",RESOLUTION_DUE.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void m4RoutedSnapshotPreservesOriginalPdfBusinessFields() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var original=json.readTree(java.nio.file.Path.of("docs/integrations/examples/m4/routed-original.json").toFile());
        var expected=original.path("data"); var routing=expected.path("details").path("routing");
        Ticket ticket=ticket(false,"M4",TicketStatus.ROUTED);
        ticket.setId(UUID.fromString(expected.path("ticketId").asText()));
        ticket.setPublicId(expected.path("publicId").asText());
        ticket.setCitizenId(UUID.fromString(expected.path("citizenId").asText()));
        ticket.setCurrentPriority(Priority.HIGH);
        ticket.setSummary(routing.path("summary").asText()); ticket.setDescription(routing.path("description").asText());
        ticket.getRequestType().setName(routing.path("requestType").asText());
        ticket.getRequestType().getSubcategory().setName(routing.path("subcategory").asText());
        ticket.getRequestType().getSubcategory().getCategory().setName(routing.path("category").asText());
        org.mockito.Mockito.when(formValidationService.snapshotAnswers(ticket)).thenReturn(Map.of());
        service.routed(ticket,null,RESOLUTION_DUE,Instant.parse("2026-10-20T12:10:00Z"));
        var captor=ArgumentCaptor.forClass(OutboxEvent.class); verify(repository).save(captor.capture());
        var payload=captor.getValue().getPayload(); EventEnvelopeContractAssertions.assertFullEventValid(payload);
        var actual=json.valueToTree(payload).path("data");
        for(String field:List.of("ticketId","publicId","citizenId","isAnonymous","responsibleAreaId","updateType","currentStatus","currentPriority"))
            assertThat(actual.path(field)).isEqualTo(expected.path(field));
        routing.fields().forEachRemaining(entry -> assertThat(actual.path("details").path("routing").path(entry.getKey())).isEqualTo(entry.getValue()));
        assertThat(captor.getValue().getEventType()).isEqualTo("ticketUpdated");
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

    @ParameterizedTest @ValueSource(strings={"M6","M4","M7"})
    @SuppressWarnings("unchecked")
    void routedContainsCompleteAuthorizedSnapshotAndIncrementalEventsDoNotReplayEvidence(String area) {
        Ticket ticket = ticket(true, area, TicketStatus.ROUTED);
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
                .containsEntry("progress", (short) 25).containsEntry("responsibleAreaId", area);
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
    @SuppressWarnings("unchecked")
    void internalInformationRequiredPublishesOnlyIdentifiedProjectionWithEffectiveDeadline() {
        service.informationRequired(ticket(false, "M2", TicketStatus.PENDING_INFORMATION), "Aporte el dato", RESOLUTION_DUE, NOW);
        service.informationRequired(ticket(false, "M6", TicketStatus.PENDING_INFORMATION), "Aporte el dato", RESOLUTION_DUE, NOW);
        service.informationRequired(ticket(true, "M2", TicketStatus.PENDING_INFORMATION), "Aporte el dato", RESOLUTION_DUE, NOW);
        service.informationRequired(ticket(true, "M6", TicketStatus.PENDING_INFORMATION), "Aporte el dato", RESOLUTION_DUE, NOW);

        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event -> {
            assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.INFORMATION_REQUIRED);
            EventEnvelopeContractAssertions.assertFullEventValid(event.getPayload());
            var data = (Map<String, Object>) event.getPayload().get("data");
            assertThat(data).containsEntry("currentStatus", "PENDING_INFORMATION")
                    .containsEntry("publicMessage", "Aporte el dato");
            assertThat((Map<String, Object>) data.get("details")).containsOnlyKeys("informationRequest");
            assertThat((Map<String, Object>) ((Map<String, Object>) data.get("details")).get("informationRequest"))
                    .containsOnlyKeys("messageForCitizen", "requiredBy")
                    .containsEntry("messageForCitizen", "Aporte el dato")
                    .containsEntry("requiredBy", RESOLUTION_DUE.toString());
            assertThat((List<?>) data.get("attachments")).isEmpty();
        });
    }

    @ParameterizedTest @ValueSource(strings={"M6","M4","M7"})
    void informationProvidedPublishesForIdentifiedAndForAnonymousExternalRequestOnly(String area) {
        service.informationProvided(ticket(false, "M2", TicketStatus.IN_PROGRESS), "Dato", false, NOW);
        service.informationProvided(ticket(false, area, TicketStatus.IN_PROGRESS), "Dato", true, NOW);
        service.informationProvided(ticket(true, area, TicketStatus.IN_PROGRESS), "Dato", true, NOW);
        service.informationProvided(ticket(true, area, TicketStatus.IN_PROGRESS), "Dato", false, NOW);
        service.informationProvided(ticket(true, "M2", TicketStatus.IN_PROGRESS), "Dato", false, NOW);

        var captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(3)).save(captor.capture());
        captor.getAllValues().forEach(event -> EventEnvelopeContractAssertions.assertFullEventValid(event.getPayload()));
    }

    @ParameterizedTest @ValueSource(strings={"M6","M4","M7"})
    void informationProvidedContainsRestoredTicketTextAndPublicAttachmentWithoutPrivateActorMetadata(String area) {
        Ticket ticket = ticket(true, area, TicketStatus.IN_PROGRESS);
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

        Attachment internal = new Attachment();
        internal.setVisibility(MessageVisibility.INTERNAL);
        internal.setStorageKey("private-storage");
        internal.setFileName("private-note.pdf");
        service.informationProvided(ticket, "Detalle", List.of(attachment, internal), true, NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        EventEnvelopeContractAssertions.assertFullEventValid(event.getPayload());
        assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.INFORMATION_PROVIDED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.getPayload().get("data");
        assertThat(data).containsEntry("citizenId", null)
                .containsEntry("isAnonymous", true)
                .containsEntry("responsibleAreaId", area)
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
        assertThat(data).doesNotContainKeys("updatedBy", "internalMessage", "informationRequestId", "formData", "formTemplateId");
        assertThat(event.getPayload().toString()).doesNotContain("private-", "tickets/one/proof");
        verify(attachmentReferenceService, never()).downloadUrl(internal);
        // Reproduce H10's former producer error against the unmodified full-event schema.
        data.put("updatedBy", Map.of("type", "CITIZEN"));
        EventEnvelopeContractAssertions.assertFullEventInvalid(event.getPayload());
    }

    @ParameterizedTest @ValueSource(strings={"M6","M4","M7"})
    void reopenedPublishesForIdentifiedOrExternalAnonymous(String area) {
        service.reopened(ticket(false, "M2", TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(false, area, TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(true, area, TicketStatus.IN_PROGRESS), "Sigue", NOW);
        service.reopened(ticket(true, "M2", TicketStatus.IN_PROGRESS), "Sigue", NOW);

        verify(repository, times(3)).save(any());
    }

    @ParameterizedTest @ValueSource(strings={"M6","M4","M7"})
    void cancelledPublishesLegitimatePostRoutingExternalFactButSkipsEchoAndSelfManagedAnonymousFact(String area) {
        service.cancelled(ticket(false, "M2", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);
        service.cancelled(ticket(false, area, TicketStatus.CANCELLED),
                CancellationReasonCode.OUT_OF_SCOPE, "Cancelado", false, NOW);
        service.cancelled(ticket(true, area, TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);
        service.cancelled(ticket(true, area, TicketStatus.CANCELLED),
                CancellationReasonCode.OUT_OF_SCOPE, "Cancelado", false, NOW);
        service.cancelled(ticket(true, "M2", TicketStatus.CANCELLED),
                CancellationReasonCode.INFO_TIMEOUT, "Cancelado", true, NOW);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(event ->
                assertThat(event.getUpdateType()).isEqualTo(TicketUpdatedType.CANCELLED));
    }

    @ParameterizedTest @ValueSource(strings={"M6","M4","M7"})
    void resolvedNeverEchoesAnAnonymousExternalFact(String area) {
        service.resolved(ticket(true, area, TicketStatus.RESOLVED),
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
