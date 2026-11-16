package com.reclamos.backend.outbox;

import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.IdentitySource;
import com.reclamos.backend.repository.ModuleUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.reclamos.backend.outbox.OutboxFixtures.*;
import static com.reclamos.backend.outbox.PublicationFailure.Code.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OutgoingEventValidatorTest {
    private final ModuleUserRepository users = mock(ModuleUserRepository.class);
    private final OutgoingEventValidator validator = new OutgoingEventValidator(users);

    @Test void createdSnapshotPreservesEnvelopeIdsAndExcludesTicketSecrets() throws Exception {
        var event = event("ticketCreated"); verified(event);
        event.getTicket().setTrackingCodeHash("private-tracking-hash");
        event.getTicket().setAnonymousContactValue("private@example.test");
        byte[] first = validator.prepare(event); byte[] second = validator.prepare(event);
        assertThat(second).isEqualTo(first);
        String json = new String(first, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json).contains(event.getEventId().toString(), event.getTicket().getId().toString(),
                event.getPayload().get("correlationId").toString()).doesNotContain("private-");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readValue(first, Map.class)).isEqualTo(event.getPayload());
    }
    @ParameterizedTest @EnumSource(TicketUpdatedType.class)
    void validatesEveryUpdatedVariantUsingUnmodifiedV178Schemas(TicketUpdatedType type) {
        var event = event("ticketUpdated"); verified(event); event.setUpdateType(type);
        var data = data(event); data.put("updateType", type.name());
        switch (type) {
            case ROUTED -> {
                data.put("currentStatus", "ROUTED");
                data.put("details", Map.of("routing", Map.of("requestType", "Test", "ticketType", "COMPLAINT",
                        "summary", "Test", "description", "Test", "category", "Test", "subcategory", "Test")));
            }
            case ESCALATION_CHANGED -> data.put("details", Map.of("escalation", Map.of("active", false)));
            case INFORMATION_REQUIRED -> {
                data.put("currentStatus", "PENDING_INFORMATION");
                data.put("details", Map.of("informationRequest", Map.of("messageForCitizen", "Completar", "purpose", "RECLASSIFICATION")));
            }
            case INFORMATION_PROVIDED -> data.put("details", Map.of("informationResponse", Map.of("message", "Información")));
            case DUPLICATE_LINKED -> {
                data.put("currentStatus", "DUPLICATE"); data.put("details", Map.of("duplicate", Map.of("mainTicketId", UUID.randomUUID().toString())));
            }
            case REOPENED -> {
                data.put("currentStatus", "IN_PROGRESS"); data.put("details", Map.of("reopen", Map.of("reason", "Sigue")));
            }
            case RESOLVED -> {
                data.put("currentStatus", "RESOLVED"); data.put("publicMessage", "Resuelto");
                data.put("details", Map.of("resolution", Map.of("type", "ACTION_COMPLETED")));
            }
            case CLOSED -> {
                data.put("currentStatus", "CLOSED"); data.put("details", Map.of("closure", Map.of("reasonCode", "CITIZEN_CONFIRMED")));
            }
            case CANCELLED -> {
                data.put("currentStatus", "CANCELLED"); data.put("details", Map.of("cancellation", Map.of("reasonCode", "OTHER")));
            }
            case CONTENT_UPDATED -> data.put("details", Map.of("content", Map.of("summary", "Actualizado")));
            default -> { }
        }
        assertThat(validator.prepare(event)).isNotEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"internalMessage", "trackingCode", "trackingAccessCode", "anonymousAccessPassword",
            "anonymousContactValue", "trackingCodeCiphertext", "refresh_token", "accessToken", "ticketActivities"})
    void rejectsReservedTechnicalFieldsEvenInsideFormAnswers(String field) {
        var event = event("ticketCreated"); verified(event);
        data(event).put("formData", Map.of("nested", List.of(Map.of(field, "private"))));
        rejected(event, INVALID_PAYLOAD);
    }
    @ParameterizedTest @ValueSource(strings = {"ticketCreated", "ROUTED", "CONTENT_UPDATED"})
    void publicQuestionCodesAreNotInterpretedAsAuthenticationMetadata(String variant) throws Exception {
        var event = event(variant.equals("ticketCreated") ? "ticketCreated" : "ticketUpdated"); verified(event);
        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("sessions", 3); answers.put("session", null); answers.put("cookie", "AVENA");
        answers.put("cookies", 2); answers.put("secret", false); answers.put("authorization", false);
        answers.put("password", false); answers.put("token", 7); answers.put("jwt", null);
        if (variant.equals("ticketCreated")) data(event).put("formData", answers);
        else {
            event.setUpdateType(TicketUpdatedType.valueOf(variant)); data(event).put("updateType", variant);
            Map<String, Object> snapshot = new LinkedHashMap<>(); snapshot.put("formData", answers);
            if (variant.equals("ROUTED")) {
                data(event).put("currentStatus", "ROUTED");
                snapshot.putAll(Map.of("requestType", "Taller", "ticketType", "REQUEST", "summary", "Solicitud",
                        "description", "Sesiones públicas", "category", "Cultura", "subcategory", "Talleres"));
            }
            data(event).put("details", Map.of(variant.equals("ROUTED") ? "routing" : "content", snapshot));
        }
        var parsed = new com.fasterxml.jackson.databind.ObjectMapper().readTree(validator.prepare(event));
        var actual = variant.equals("ticketCreated") ? parsed.path("data").path("formData")
                : parsed.path("data").path("details").path(variant.equals("ROUTED") ? "routing" : "content").path("formData");
        assertThat(actual).isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(answers));
    }
    @ParameterizedTest @ValueSource(strings = {"password", "token", "authorization", "cookie", "session", "accessToken", "trackingCode", "internalMessage"})
    void credentialOrInternalFieldsOutsideThePublicAnswerStructureRemainForbidden(String field) {
        var event = event("ticketCreated"); verified(event);
        data(event).put(field, "synthetic-private-value");
        rejected(event, INVALID_PAYLOAD);
        data(event).remove(field); event.getPayload().put(field, "synthetic-private-value");
        rejected(event, INVALID_PAYLOAD);
    }
    @ParameterizedTest @ValueSource(strings = {"ROUTED", "CONTENT_UPDATED"})
    void nestedCredentialFieldsRemainForbiddenInPublicAnswerSnapshots(String variant) {
        var event = event("ticketUpdated"); verified(event);
        event.setUpdateType(TicketUpdatedType.valueOf(variant)); data(event).put("updateType", variant);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("formData", Map.of("context", List.of(Map.of("accessToken", "synthetic-private-token"))));
        if (variant.equals("ROUTED")) {
            data(event).put("currentStatus", "ROUTED");
            snapshot.putAll(Map.of("requestType", "Test", "ticketType", "COMPLAINT", "summary", "Test",
                    "description", "Test", "category", "Test", "subcategory", "Test"));
        }
        data(event).put("details", Map.of(variant.equals("ROUTED") ? "routing" : "content", snapshot));
        rejected(event, INVALID_PAYLOAD);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anonymousExternalEscalationSupportsActivationAndDeactivation(boolean active) {
        var event = event("ticketUpdated"); event.getTicket().setAnonymous(true); event.getTicket().setCitizenId(null);
        event.setUpdateType(TicketUpdatedType.ESCALATION_CHANGED);
        var data = data(event); data.put("isAnonymous", true); data.put("citizenId", null);
        data.put("updateType", "ESCALATION_CHANGED"); data.put("currentStatus", "IN_PROGRESS");
        data.put("details", Map.of("escalation", active
                ? Map.of("active", true, "reasonCode", "SLA_BREACHED", "escalatedAt", NOW.toString())
                : Map.of("active", false)));
        assertThat(validator.prepare(event)).isNotEmpty(); verifyNoInteractions(users);
        data.put("responsibleAreaId", "M2"); rejected(event, INVALID_PAYLOAD);
    }
    @Test void anonymousEscalationStillRequiresItsContractDetailsAndNoCitizenIdentity() {
        var event = event("ticketUpdated"); event.getTicket().setAnonymous(true); event.getTicket().setCitizenId(null);
        event.setUpdateType(TicketUpdatedType.ESCALATION_CHANGED);
        data(event).put("isAnonymous", true); data(event).put("citizenId", null);
        data(event).put("updateType", "ESCALATION_CHANGED"); data(event).put("currentStatus", "IN_PROGRESS");
        data(event).put("details", Map.of("escalation", Map.of("active", true)));
        rejected(event, INVALID_PAYLOAD);
        data(event).put("details", Map.of("escalation", Map.of("active", false)));
        data(event).put("citizenId", UUID.randomUUID().toString()); rejected(event, INVALID_PAYLOAD);
    }
    @Test void rejectsExtraRootFieldsMissingTraceAndInvalidSourceOrUuid() {
        for (String field : List.of("receivedAt", "producer", "specVersion")) {
            var event = event("ticketUpdated"); event.getPayload().put(field, "extra"); rejected(event, INVALID_PAYLOAD);
        }
        var event = event("ticketCreated"); event.getPayload().remove("correlationId"); rejected(event, INVALID_PAYLOAD);
        event = event("ticketCreated"); event.getPayload().put("sourceModule", "ciudadanos"); rejected(event, INVALID_PAYLOAD);
        event = event("ticketCreated"); event.getPayload().put("eventId", "14"); rejected(event, INVALID_PAYLOAD);
        event = event("ticketCreated"); event.getPayload().put("occurredAt", "2026-02-30T00:00:00Z"); rejected(event, INVALID_PAYLOAD);
    }
    @Test void rejectsMetadataAndSnapshotMismatchWithoutRewritingTheIds() {
        var event = event("ticketCreated"); data(event).put("ticketId", UUID.randomUUID().toString()); rejected(event, INVALID_PAYLOAD);
        event = event("ticketCreated"); data(event).put("publicId", "TK-OTHER"); rejected(event, INVALID_PAYLOAD);
        event = event("ticketUpdated"); event.setUpdateType(TicketUpdatedType.CLOSED); rejected(event, INVALID_PAYLOAD);
        event = event("ticketCreated"); event.setUpdateType(TicketUpdatedType.CLOSED); rejected(event, INVALID_PAYLOAD);
        event = event("ticketCreated"); data(event).put("citizenId", UUID.randomUUID().toString()); rejected(event, INVALID_PAYLOAD);
    }
    @Test void mockOrUnknownCitizenCannotBePublishedEvenIfExplicitlySelected() {
        var event = event("ticketCreated");
        when(users.findByCitizenId(any())).thenReturn(Optional.empty()); rejected(event, UNVERIFIED_IDENTITY);
        var mock = realCitizen(event); mock.setIdentitySource(IdentitySource.MOCK);
        when(users.findByCitizenId(any())).thenReturn(Optional.of(mock)); rejected(event, UNVERIFIED_IDENTITY);
    }
    @Test void anonymousUpdatesNeedNoFakeCitizenAndOnlyExternalOperationalVariantsAreAllowed() {
        var event = event("ticketUpdated"); event.getTicket().setAnonymous(true); event.getTicket().setCitizenId(null);
        data(event).put("isAnonymous", true); data(event).put("citizenId", null);
        rejected(event, INVALID_PAYLOAD);
        event.setUpdateType(TicketUpdatedType.REOPENED); data(event).put("updateType", "REOPENED");
        data(event).put("currentStatus", "IN_PROGRESS"); data(event).put("details", Map.of("reopen", Map.of()));
        assertThat(validator.prepare(event)).isNotEmpty(); verifyNoInteractions(users);
        data(event).put("responsibleAreaId", "M2"); rejected(event, INVALID_PAYLOAD);
    }
    @Test void repositoryOutageRemainsRetryableByTransactionRollbackRatherThanPoisoningAnEvent() {
        var event = event("ticketCreated");
        when(users.findByCitizenId(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private"));
        assertThatThrownBy(() -> validator.prepare(event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
    @Test void runtimeSchemaCopiesAreByteForByteIdenticalToTheExistingOfficialSchemas() throws Exception {
        for (String name : List.of("common", "envelope", "ticketCreated", "ticketCreated.data", "ticketUpdated", "ticketUpdated.data")) {
            assertThat(Files.readAllBytes(Path.of("src/main/resources/events/v1.78/" + name + ".schema.json")))
                    .isEqualTo(Files.readAllBytes(Path.of("src/test/resources/events/v1.78/" + name + ".schema.json")));
        }
    }
    private void verified(OutboxEvent event) { when(users.findByCitizenId(event.getTicket().getCitizenId())).thenReturn(Optional.of(realCitizen(event))); }
    private void rejected(OutboxEvent event, PublicationFailure.Code code) {
        assertThatThrownBy(() -> validator.prepare(event)).isInstanceOfSatisfying(PublicationFailure.class,
                failure -> assertThat(failure.code()).isEqualTo(code)).hasNoCause();
    }
}
