package com.reclamos.backend.service;

import com.fasterxml.jackson.databind.*;
import com.reclamos.backend.dto.*;
import com.reclamos.backend.dto.request.*;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ticket.priority.scan-delay=3600000", "ticket.sla.milestone-scan-delay=3600000",
        "ticket.information-request.expiration-scan-delay=3600000", "ticket.resolution.confirmation-scan-delay=3600000",
        "notification.pending-scan-delay=3600000"})
@ActiveProfiles("dev") @AutoConfigureMockMvc
class TicketReclassificationPostgresIntegrationTest {
    @Autowired TicketService tickets;
    @Autowired TicketReclassificationService proposals;
    @Autowired InformationRequestService information;
    @Autowired InformationRequestExpirationService expiration;
    @Autowired TicketStatusUpdateService external;
    @Autowired TicketReclassificationProposalRepository proposalRepository;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired com.reclamos.backend.storage.FileStorage storage;
    @org.springframework.beans.factory.annotation.Value("${attachment.storage.root}") String storageRoot;
    final ObjectMapper json = new ObjectMapper();
    final UUID owner = UUID.randomUUID(), agent = UUID.randomUUID();
    final String marker = "H0304-" + UUID.randomUUID();
    final List<UUID> ticketIds = new ArrayList<>(), eventIds = new ArrayList<>();
    final List<Long> typeIds = new ArrayList<>();
    final Map<UUID, CreateTicketResponse> credentials = new HashMap<>();

    @BeforeEach void setup() {
        assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
        for (UUID id : List.of(owner, agent)) db.update("INSERT INTO module_users(citizen_id,first_name,last_name,role,active,last_synced_at,created_at,updated_at) "
                + "VALUES (?,'H03','H04',?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, id.equals(agent) ? "AGENT" : "CITIZEN");
    }

    @AfterEach void cleanup() {
        for (UUID id : ticketIds) {
            db.queryForList("SELECT storage_key FROM attachments WHERE ticket_id=?", String.class, id).forEach(storage::delete);
            db.update("DELETE FROM information_request_attachments WHERE information_request_id IN (SELECT id FROM information_requests WHERE ticket_id=?)", id);
            db.update("DELETE FROM attachments WHERE ticket_id=?", id);
            db.update("DELETE FROM ticket_reclassification_proposals WHERE ticket_id=?", id);
            for (String table : List.of("information_requests", "notification_logs", "outbox_events", "ticket_messages", "ticket_activities", "ticket_sla", "ticket_locations", "ticket_cancellations"))
                db.update("DELETE FROM " + table + " WHERE ticket_id=?", id);
            db.update("DELETE FROM tickets WHERE id=?", id);
        }
        for (UUID id : eventIds) db.update("DELETE FROM inbox_events WHERE event_id=?", id);
        for (Long id : typeIds) {
            db.update("DELETE FROM risk_rules WHERE form_field_id IN (SELECT f.id FROM form_fields f JOIN form_templates t ON t.id=f.form_template_id WHERE t.request_type_id=?)", id);
            db.update("DELETE FROM form_fields WHERE form_template_id IN (SELECT id FROM form_templates WHERE request_type_id=?)", id);
            db.update("DELETE FROM form_templates WHERE request_type_id=?", id);
            db.update("DELETE FROM request_types WHERE id=?", id);
        }
        for (UUID id : List.of(owner, agent)) db.update("DELETE FROM module_users WHERE citizen_id=?", id);
    }

    @ParameterizedTest @ValueSource(strings = {"M2", "M6"})
    void apiInitialReadyHasNoPrematureEffectsAndConfirmationIsIdempotent(String area) throws Exception {
        UUID id = create(false, "CRITICAL"); Long target = type(area, "LOW", true);
        var before = classification(id); var firstResponse = firstResponse(id);
        JsonNode p = body(mvc.perform(post(base(id)).with(authentication(auth(agent, ModuleRole.AGENT)))
                .contentType("application/json").content("{\"requestTypeId\":" + target + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("READY")).andReturn().getResponse().getContentAsString());
        long proposal = p.get("id").asLong();
        assertThat(classification(id)).isEqualTo(before);
        assertThat(events(id, "ROUTED")).isZero(); assertThat(events(id, "CONTENT_UPDATED")).isZero();
        mvc.perform(post("/api/tickets/{id}/route", id).with(authentication(auth(agent, ModuleRole.AGENT))))
                .andExpect(status().isConflict());
        for (int retry = 0; retry < 2; retry++) mvc.perform(post(base(id) + "/" + proposal + "/confirm")
                .with(authentication(auth(agent, ModuleRole.ADMIN)))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(classification(id)).containsEntry("request_type_id", target).containsEntry("responsible_area_id", area)
                .containsEntry("current_priority", "LOW").containsEntry("is_escalated", false);
        assertThat(ticketStatus(id)).isEqualTo(area.equals("M2") ? "IN_PROGRESS" : "ROUTED");
        assertThat(firstResponse(id)).isEqualTo(firstResponse);
        assertThat(cycles(id)).hasSize(1);
        assertThat(db.queryForObject("SELECT s.started_at=t.created_at FROM ticket_sla s JOIN tickets t ON t.id=s.ticket_id WHERE s.ticket_id=? AND sla_type='RESOLUTION'", Boolean.class, id)).isTrue();
        assertThat(events(id, area.equals("M2") ? "CONTENT_UPDATED" : "ROUTED")).isEqualTo(1);
        assertThat(events(id, area.equals("M2") ? "ROUTED" : "CONTENT_UPDATED")).isZero();
        validateEvents(id);
    }

    @Test void structuredApiPreservesFalseZeroAndExplicitUnknownAndRequiresStaffConfirmation() throws Exception {
        UUID id = create(false, "LOW"); Long target = type("M6", "LOW", true); Long template = template(target);
        field(template, "flag", "BOOLEAN", false, "{}"); field(template, "number", "NUMBER", false, "{\"min\":0,\"max\":10}");
        field(template, "unknown", "SELECT", true, "{\"options\":[{\"value\":\"yes\",\"label\":\"Sí\"}]}");
        var before = classification(id);
        var p = propose(id, target, null);
        assertThat(p.status()).isEqualTo(ReclassificationProposalStatus.WAITING_INFORMATION);
        assertThat(ticketStatus(id)).isEqualTo("PENDING_INFORMATION");
        assertThat(classification(id)).isEqualTo(before);
        mvc.perform(get("/api/tickets/{id}", id).with(authentication(auth(owner, ModuleRole.CITIZEN))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingInformationRequest.purpose").value("RECLASSIFICATION"))
                .andExpect(jsonPath("$.pendingInformationRequest.reclassification.missingFields.length()").value(3));
        for (String invalid : List.of("{}", "{\"flag\":false,\"number\":11,\"unknown\":null}",
                "{\"flag\":\"false\",\"number\":0,\"unknown\":null}", "{\"flag\":false,\"number\":0,\"unknown\":\"bad\"}")) {
            mvc.perform(post("/api/tickets/{id}/information-response", id).with(authentication(auth(owner, ModuleRole.CITIZEN)))
                    .contentType("application/json").content("{\"formData\":" + invalid + "}"))
                    .andExpect(status().isBadRequest());
            assertThat(ticketStatus(id)).isEqualTo("PENDING_INFORMATION");
            assertThat(db.queryForObject("SELECT status FROM information_requests WHERE id=?", String.class, p.informationRequestId())).isEqualTo("PENDING");
        }
        mvc.perform(post("/api/tickets/{id}/information-response", id).with(authentication(auth(owner, ModuleRole.CITIZEN)))
                .contentType("application/json").content("{\"formData\":{\"flag\":false,\"number\":0,\"unknown\":null}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ANSWERED"));
        assertThat(ticketStatus(id)).isEqualTo("IN_REVIEW"); assertThat(classification(id)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT paused_at IS NULL FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION'", Boolean.class, id)).isTrue();
        proposals.confirm(id, p.id(), staff());
        assertThat(db.queryForObject("SELECT form_data::text FROM tickets WHERE id=?", String.class, id))
                .contains("false", "0", "null");
        var info = outbox.findAll().stream().filter(e -> e.getTicket().getId().equals(id))
                .filter(e -> e.getUpdateType() == TicketUpdatedType.INFORMATION_REQUIRED || e.getUpdateType() == TicketUpdatedType.INFORMATION_PROVIDED).toList();
        assertThat(info).hasSize(2);
        for (var e : info) {
            String payload = json.writeValueAsString(e.getPayload());
            assertThat(payload).contains("RECLASSIFICATION").doesNotContain("formData", "FormTemplate", "proposalId", "internalMessage", "flag", "unknown");
        }
        validateEvents(id);
    }

    @ParameterizedTest @ValueSource(strings = {"M2", "M6"})
    void exceptionalReturnReplacesOnlyActiveCycleAndPublishesConfirmedSnapshot(String area) {
        UUID id = create(false, "LOW"); tickets.routeToArea(id, staff());
        var oldCycle = cycles(id).getFirst(); var first = firstResponse(id);
        returned(id, "M6", "REQUEST_TYPE_MISMATCH");
        long eventCount = totalEvents(id);
        var p = propose(id, type(area, "HIGH", true), null);
        assertThat(cycles(id)).containsExactly(oldCycle); assertThat(totalEvents(id)).isEqualTo(eventCount);
        proposals.confirm(id, p.id(), staff());
        var cycles = cycles(id); assertThat(cycles).hasSize(2);
        assertThat(cycles.getFirst()).containsEntry("started_at", oldCycle.get("started_at"))
                .containsEntry("due_at", oldCycle.get("due_at")).containsEntry("status", "STOPPED");
        assertThat(cycles.getLast().get("completed_at")).isNull(); assertThat(firstResponse(id)).isEqualTo(first);
        assertThat(ticketStatus(id)).isEqualTo(area.equals("M2") ? "IN_PROGRESS" : "ROUTED");
        if (area.equals("M6")) assertThat(db.queryForObject("SELECT payload->'data'->'details'->'routing'->>'reassignment' FROM outbox_events WHERE ticket_id=? AND update_type='ROUTED' ORDER BY id DESC LIMIT 1", String.class, id)).isEqualTo("true");
        else assertThat(events(id, "ROUTED")).isEqualTo(1); // Only the original external assignment.
        validateEvents(id);
    }

    @Test void exceptionNeedsAcceptedProducerAndReasonAndLegacyCannotBypass() throws Exception {
        UUID id = create(false, "LOW"); tickets.routeToArea(id, staff()); Long target = type("M2", "LOW", true);
        assertThatThrownBy(() -> returned(id, "M7", "REQUEST_TYPE_MISMATCH")).isInstanceOf(com.reclamos.backend.exception.InvalidTicketRequestException.class);
        assertThatThrownBy(() -> propose(id, target, null)).isInstanceOf(com.reclamos.backend.exception.TicketStateConflictException.class);
        returned(id, "M6", "OTHER");
        assertThatThrownBy(() -> propose(id, target, null)).hasMessageContaining("REQUEST_TYPE_MISMATCH");
        mvc.perform(patch("/api/tickets/{id}/classification", id).with(authentication(auth(agent, ModuleRole.AGENT)))
                .contentType("application/json").content("{\"requestTypeId\":" + target + "}"))
                .andExpect(status().isConflict());
        tickets.routeToArea(id, staff()); returned(id, "M6", "REQUEST_TYPE_MISMATCH");
        assertThatThrownBy(() -> tickets.routeToArea(id, staff())).hasMessageContaining("propuesta");
        assertThat(propose(id, target, null).origin()).isEqualTo(ReclassificationOrigin.REQUEST_TYPE_MISMATCH);
    }

    @Test void abandonCancelsOnlyLinkedRequestAndKeepsSnapshotAndExceptionForNextAttempt() throws Exception {
        UUID id = create(false, "LOW"); tickets.routeToArea(id, staff()); returned(id, "M6", "REQUEST_TYPE_MISMATCH");
        Long target = type("M2", "LOW", true); field(template(target), "required", "TEXT", false, "{}");
        var before = classification(id); var p = propose(id, target, null); var snapshot = p.previousClassificationSnapshot();
        assertThatThrownBy(() -> proposals.abandon(id, p.id(), " ", staff())).isInstanceOf(com.reclamos.backend.exception.InvalidTicketRequestException.class);
        assertThatThrownBy(() -> propose(id, type("M6", "LOW", true), null)).isInstanceOf(com.reclamos.backend.exception.TicketStateConflictException.class);
        proposals.abandon(id, p.id(), "Tipo alternativo", staff());
        assertThat(ticketStatus(id)).isEqualTo("IN_REVIEW"); assertThat(classification(id)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT status FROM information_requests WHERE id=?", String.class, p.informationRequestId())).isEqualTo("CANCELLED");
        var next = propose(id, type("M6", "LOW", true), null);
        assertThat(next.id()).isNotEqualTo(p.id()); assertThat(json.readTree(json.writeValueAsString(proposals.get(id, p.id(), staff()).previousClassificationSnapshot())))
                .isEqualTo(json.readTree(json.writeValueAsString(snapshot)));
        assertThatThrownBy(() -> db.update("UPDATE ticket_reclassification_proposals SET previous_classification_snapshot='{}'::jsonb WHERE id=?", p.id())).hasMessageContaining("Immutable");
        assertThatThrownBy(() -> proposals.confirm(id, p.id(), staff())).isInstanceOf(com.reclamos.backend.exception.TicketStateConflictException.class);
    }

    @Test void riskEvidenceIsAtomicAndProvisionalDownloadIsHiddenFromAreaUntilConfirmation() throws Exception {
        UUID id = create(false, "LOW"); Long target = type("M6", "LOW", true); Long f = field(template(target), "danger", "BOOLEAN", false, "{}");
        db.update("INSERT INTO risk_rules(form_field_id,operator,expected_value,risk_increment,active) VALUES (?,'EQUALS','true'::jsonb,75,TRUE)", f);
        var p = propose(id, target, null); var before = classification(id);
        mvc.perform(post("/api/tickets/{id}/information-response", id).with(authentication(auth(owner, ModuleRole.CITIZEN)))
                .contentType("application/json").content("{\"formData\":{\"danger\":true}}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EVIDENCE_REQUIRED"));
        assertThat(ticketStatus(id)).isEqualTo("PENDING_INFORMATION"); assertThat(classification(id)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM attachments WHERE ticket_id=?", Integer.class, id)).isZero();
        byte[] bytes = AttachmentFixtures.bytes("image/png");
        mvc.perform(multipart("/api/tickets/{id}/information-response", id)
                .file(new MockMultipartFile("data", "data.json", "application/json", "{\"formData\":{\"danger\":true},\"responseMessage\":\"detalle privado\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("attachments", "prueba.png", "image/png", bytes))
                .with(authentication(auth(owner, ModuleRole.CITIZEN)))).andExpect(status().isOk());
        Long file = db.queryForObject("SELECT id FROM attachments WHERE ticket_id=?", Long.class, id);
        mvc.perform(get("/api/attachments/{id}/content", file).with(authentication(auth(UUID.randomUUID(), ModuleRole.AREA_RESPONSIBLE))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/{id}/content", file).with(authentication(auth(owner, ModuleRole.CITIZEN))))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes));
        assertThat(classification(id)).isEqualTo(before);
        assertThat(json.writeValueAsString(outbox.findAll().stream().filter(e -> e.getTicket().getId().equals(id)).map(OutboxEvent::getPayload).toList()))
                .doesNotContain("detalle privado", "prueba.png");
        proposals.confirm(id, p.id(), staff());
        assertThat(db.queryForObject("SELECT reclassification_proposal_id FROM attachments WHERE id=?", Long.class, file)).isNull();
        mvc.perform(get("/api/attachments/{id}/content", file).with(authentication(auth(UUID.randomUUID(), ModuleRole.AREA_RESPONSIBLE))))
                .andExpect(status().isOk());
        validateEvents(id);
    }

    @Test void anonymousAccreditedStructuredResponseNeverBroadcastsAndIdentificationPolicyBlocksConfirmation() throws Exception {
        UUID id = create(true, "LOW"); Long target = type("M2", "LOW", false); field(template(target), "answer", "TEXT", false, "{}");
        var p = propose(id, target, null); var c = credentials.get(id);
        String payload = json.writeValueAsString(Map.of("trackingCode", c.trackingCode(), "anonymousAccessPassword", c.generatedAnonymousAccessPassword(),
                "payload", Map.of("formData", Map.of("answer", "dato"))));
        mvc.perform(post("/api/tracking/actions/information-response").contentType("application/json").content(payload))
                .andExpect(status().isOk());
        assertThat(ticketStatus(id)).isEqualTo("IN_REVIEW"); assertThat(totalEvents(id)).isZero();
        assertThatThrownBy(() -> proposals.confirm(id, p.id(), staff())).hasMessageContaining("identificación");
        assertThat(db.queryForObject("SELECT is_anonymous FROM tickets WHERE id=?", Boolean.class, id)).isTrue();
    }

    @Test void timeoutAbandonsProposalAndLateAnswerCannotReactivate() {
        UUID id = create(false, "LOW"); Long target = type("M2", "LOW", true); field(template(target), "answer", "TEXT", false, "{}");
        var p = propose(id, target, null);
        db.update("UPDATE information_requests SET due_at=CURRENT_TIMESTAMP-INTERVAL '1 second',requested_at=CURRENT_TIMESTAMP-INTERVAL '2 seconds' WHERE id=?", p.informationRequestId());
        expiration.expireIfDue(p.informationRequestId(), id, Instant.now());
        assertThat(ticketStatus(id)).isEqualTo("CANCELLED");
        assertThat(proposals.get(id, p.id(), staff()).status()).isEqualTo(ReclassificationProposalStatus.ABANDONED);
        assertThatThrownBy(() -> information.answerInformation(id, new AnswerInformationRequest("tarde"), identity(owner, ModuleRole.CITIZEN)))
                .isInstanceOf(com.reclamos.backend.exception.InformationRequestConflictException.class);
        assertThat(db.queryForObject("SELECT reason_code FROM ticket_cancellations WHERE ticket_id=?", String.class, id)).isEqualTo("INFO_TIMEOUT");
    }

    @ParameterizedTest @EnumSource(value = ModuleRole.class, names = {"CITIZEN", "AREA_RESPONSIBLE"})
    void rolesCannotProposeOrInspectAndStaffCannotMutateOwnTicket(ModuleRole role) throws Exception {
        UUID id = create(false, "LOW"); Long target = type("M2", "LOW", true);
        mvc.perform(post(base(id)).with(authentication(auth(UUID.randomUUID(), role)))
                .contentType("application/json").content("{\"requestTypeId\":" + target + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(base(id)).with(authentication(auth(UUID.randomUUID(), role)))).andExpect(status().isForbidden());
        mvc.perform(post(base(id)).with(authentication(auth(owner, ModuleRole.ADMIN)))
                .contentType("application/json").content("{\"requestTypeId\":" + target + "}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(base(id)).contentType("application/json").content("{\"requestTypeId\":" + target + "}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void ownershipAndProposalTicketAssociationAreEnforced() throws Exception {
        UUID id = create(false, "LOW"), other = create(false, "LOW");
        Long target = type("M6", "LOW", true); field(template(target), "answer", "TEXT", false, "{}");
        var p = propose(id, target, null);
        mvc.perform(get(base(other) + "/" + p.id()).with(authentication(auth(agent, ModuleRole.ADMIN))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/tickets/{id}/information-response", id).with(authentication(auth(UUID.randomUUID(), ModuleRole.CITIZEN)))
                .contentType("application/json").content("{\"formData\":{\"answer\":\"otro\"}}"))
                .andExpect(status().isForbidden());
    }

    @Test void concurrentProposalsAndConfirmationsProduceOneEffectiveChange() throws Exception {
        UUID id = create(false, "LOW"); Long target = type("M6", "LOW", true);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        race(() -> { try { propose(id, target, null); } catch (Throwable e) { failures.add(e); } },
                () -> { try { propose(id, target, null); } catch (Throwable e) { failures.add(e); } });
        assertThat(failures).hasSize(1).allMatch(e -> e instanceof com.reclamos.backend.exception.TicketStateConflictException);
        var p = proposals.list(id, staff()).getFirst();
        race(() -> proposals.confirm(id, p.id(), staff()), () -> proposals.confirm(id, p.id(), staff()));
        assertThat(events(id, "ROUTED")).isEqualTo(1); assertThat(cycles(id)).hasSize(1);
    }

    @Test void outboxFailureRollsBackClassificationProposalSlaAndAudit() {
        UUID id = create(false, "LOW"); var p = propose(id, type("M6", "CRITICAL", true), null);
        var before = classification(id); var cycles = cycles(id); int activityCount = countActivities(id);
        db.execute("CREATE FUNCTION h0304_fail_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ticket_id='" + id
                + "'::uuid AND NEW.update_type='ROUTED' THEN RAISE EXCEPTION 'H0304 rollback'; END IF; RETURN NEW; END; $$");
        db.execute("CREATE TRIGGER h0304_fail_outbox BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION h0304_fail_outbox()");
        try {
            assertThatThrownBy(() -> proposals.confirm(id, p.id(), staff())).hasMessageContaining("H0304 rollback");
            assertThat(classification(id)).isEqualTo(before); assertThat(cycles(id)).isEqualTo(cycles);
            assertThat(countActivities(id)).isEqualTo(activityCount); assertThat(ticketStatus(id)).isEqualTo("IN_REVIEW");
            assertThat(proposals.get(id, p.id(), staff()).status()).isEqualTo(ReclassificationProposalStatus.READY);
            assertThat(events(id, "ROUTED")).isZero();
        } finally {
            db.execute("DROP TRIGGER h0304_fail_outbox ON outbox_events"); db.execute("DROP FUNCTION h0304_fail_outbox()");
        }
    }

    @Test void pinnedTemplateIsUsedAfterCatalogPublishesANewVersion() {
        UUID id = create(false, "LOW"); Long target = type("M6", "LOW", true); Long version = template(target);
        field(version, "answer", "BOOLEAN", false, "{}");
        var p = propose(id, target, Map.of("answer", false));
        db.update("UPDATE form_templates SET active=FALSE WHERE id=?", version);
        Long next = db.queryForObject("INSERT INTO form_templates(request_type_id,version,active) VALUES (?,2,TRUE) RETURNING id", Long.class, target);
        field(next, "incompatible", "DATE", false, "{}");
        proposals.confirm(id, p.id(), staff());
        assertThat(db.queryForObject("SELECT form_template_id FROM tickets WHERE id=?", Long.class, id)).isEqualTo(version);
        validateEvents(id);
    }

    @Test void confirmationRechecksRequiredDataAndActiveTypeRatherThanTrustingReady() {
        UUID id = create(false, "LOW"); Long target = type("M6", "LOW", true); field(template(target), "answer", "BOOLEAN", false, "{}");
        var p = propose(id, target, Map.of("answer", false));
        db.update("UPDATE ticket_reclassification_proposals SET proposed_data='{\"formData\":{}}'::jsonb WHERE id=?", p.id());
        assertThatThrownBy(() -> proposals.confirm(id, p.id(), staff())).isInstanceOf(com.reclamos.backend.exception.FormValidationException.class);
        assertThat(ticketStatus(id)).isEqualTo("IN_REVIEW");
        db.update("UPDATE request_types SET active=FALSE WHERE id=?", target);
        assertThatThrownBy(() -> proposals.confirm(id, p.id(), staff())).hasMessageContaining("habilitado");
    }

    @Test void oldAnswerWithSameCodeCannotSatisfyASemanticallyDifferentTargetField() {
        Long original = type("M6", "LOW", true); Long originalTemplate = template(original);
        field(originalTemplate, "answer", "BOOLEAN", false, "{}");
        var c = tickets.create(new CreateTicketRequest(original, "Anterior", "Contexto", Map.of("answer", true), null), identity(owner, ModuleRole.CITIZEN), null);
        ticketIds.add(c.ticketId()); tickets.startReview(c.ticketId(), staff());
        Long target = type("M2", "LOW", true); field(template(target), "answer", "BOOLEAN", false, "{}");
        var p = propose(c.ticketId(), target, null);
        assertThat(p.status()).isEqualTo(ReclassificationProposalStatus.WAITING_INFORMATION);
        assertThat(p.requirements().missingFields()).containsExactly("answer");
        assertThat(p.requirements().availableAnswers()).isEmpty();
    }

    @Test void locationAndNeighborhoodAreProvisionalAndAffectConfirmedPriority() {
        UUID id = create(false, "LOW"); Long target = type("M2", "LOW", true); UUID neighborhood = UUID.randomUUID();
        db.update("INSERT INTO neighborhood(id,name,population) VALUES (?,?,10000)", neighborhood, marker);
        db.update("UPDATE request_types SET requires_location=TRUE,affected_population_factor=.5 WHERE id=?", target);
        try {
            var p = propose(id, target, null);
            assertThat(p.requirements().locationRequired()).isTrue(); assertThat(p.requirements().neighborhoodRequired()).isFalse();
            AnswerInformationRequest a = new AnswerInformationRequest();
            a.setLocation(new CreateTicketRequest.LocationData("Lima 123", null, null, neighborhood, null, null, null));
            information.answerInformation(id, a, identity(owner, ModuleRole.CITIZEN));
            assertThat(db.queryForObject("SELECT count(*) FROM ticket_locations WHERE ticket_id=?", Integer.class, id)).isZero();
            proposals.confirm(id, p.id(), staff());
            assertThat(classification(id)).containsEntry("estimated_affected_count", 5000).containsEntry("current_priority", "MEDIUM");
            validateEvents(id);
        } finally {
            db.update("DELETE FROM ticket_locations WHERE ticket_id=?", id);
            db.update("DELETE FROM neighborhood WHERE id=?", neighborhood);
        }
    }

    @Test void completedBreachHistoryAndManualEscalationSurviveExceptionalConfirmation() {
        UUID id = create(false, "LOW"); tickets.routeToArea(id, staff());
        db.update("UPDATE ticket_sla SET started_at=CURRENT_TIMESTAMP-INTERVAL '3 hours',near_due_at=CURRENT_TIMESTAMP-INTERVAL '2 hours',due_at=CURRENT_TIMESTAMP-INTERVAL '1 hour',status='BREACHED' WHERE ticket_id=? AND sla_type='RESOLUTION'", id);
        db.update("UPDATE tickets SET is_escalated=TRUE,escalation_reason_code='MANUAL',escalated_at=created_at WHERE id=?", id);
        var old = cycles(id).getFirst(); returned(id, "M6", "REQUEST_TYPE_MISMATCH");
        var p = propose(id, type("M2", "LOW", true), null); proposals.confirm(id, p.id(), staff());
        assertThat(cycles(id).getFirst()).containsEntry("status", "BREACHED").containsEntry("due_at", old.get("due_at"));
        assertThat(db.queryForObject("SELECT escalation_reason_code FROM tickets WHERE id=?", String.class, id)).isEqualTo("MANUAL");
        assertThat(cycles(id)).hasSize(2);
    }

    @Test void generalPendingAndReclassificationPendingCannotBeOverlapped() {
        UUID id = create(false, "LOW"); Long target = type("M2", "LOW", true);
        information.requestInformation(id, new CreateInformationRequest("Contexto", null), staff());
        assertThatThrownBy(() -> propose(id, target, null)).isInstanceOf(com.reclamos.backend.exception.TicketStateConflictException.class);
        information.answerInformation(id, new AnswerInformationRequest("Respuesta general"), identity(owner, ModuleRole.CITIZEN));
        field(template(target), "answer", "TEXT", false, "{}");
        var p = propose(id, target, null);
        assertThatThrownBy(() -> information.requestInformation(id, new CreateInformationRequest("Otro", null), staff()))
                .isInstanceOf(com.reclamos.backend.exception.InformationRequestConflictException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM information_requests WHERE ticket_id=? AND status='PENDING'", Integer.class, id)).isEqualTo(1);
        assertThat(p.status()).isEqualTo(ReclassificationProposalStatus.WAITING_INFORMATION);
    }

    @Test void lateResponseBeforeExpiryScanStillCannotAnswerOrAbandonAnExpiredRequest() {
        UUID id = create(false, "LOW"); Long target = type("M2", "LOW", true); field(template(target), "answer", "TEXT", false, "{}");
        var p = propose(id, target, null);
        db.update("UPDATE information_requests SET due_at=CURRENT_TIMESTAMP-INTERVAL '1 second',requested_at=CURRENT_TIMESTAMP-INTERVAL '2 seconds' WHERE id=?", p.informationRequestId());
        AnswerInformationRequest a = new AnswerInformationRequest(); a.setFormData(Map.of("answer", "tarde"));
        assertThatThrownBy(() -> information.answerInformation(id, a, identity(owner, ModuleRole.CITIZEN)))
                .isInstanceOf(com.reclamos.backend.exception.InformationRequestExpiredException.class);
        assertThatThrownBy(() -> proposals.abandon(id, p.id(), "Evitar timeout", staff()))
                .isInstanceOf(com.reclamos.backend.exception.InformationRequestExpiredException.class);
        assertThat(ticketStatus(id)).isEqualTo("PENDING_INFORMATION");
        expiration.expireIfDue(p.informationRequestId(), id, Instant.now()); assertThat(ticketStatus(id)).isEqualTo("CANCELLED");
    }

    @Test void anonymousReadOnlyTrackingOmitsStructureAndAccreditedAccessIncludesIt() throws Exception {
        UUID id = create(true, "LOW"); Long target = type("M2", "LOW", true); Long template = template(target);
        field(template, "answer", "TEXT", false, "{}"); field(template, "known", "TEXT", false, "{}");
        propose(id, target, Map.of("known", "Dato provisional")); var c = credentials.get(id);
        mvc.perform(post("/api/tracking/access").contentType("application/json")
                .content(json.writeValueAsString(Map.of("trackingCode", c.trackingCode()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingInformationRequest.reclassification").isEmpty());
        mvc.perform(post("/api/tracking/access").contentType("application/json")
                .content(json.writeValueAsString(Map.of("trackingCode", c.trackingCode(), "anonymousAccessPassword", c.generatedAnonymousAccessPassword()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pendingInformationRequest.reclassification.availableAnswers.known").value("Dato provisional"));
    }

    @Test void concurrentCitizenAnswerAndStaffAbandonCannotReactivateAbandonedProposal() throws Exception {
        UUID id = create(false, "LOW"); Long target = type("M2", "LOW", true); field(template(target), "answer", "TEXT", false, "{}");
        var p = propose(id, target, null); var before = classification(id);
        AnswerInformationRequest a = new AnswerInformationRequest(); a.setFormData(Map.of("answer", "Dato"));
        race(() -> { try { information.answerInformation(id, a, identity(owner, ModuleRole.CITIZEN)); }
                    catch (com.reclamos.backend.exception.InformationRequestConflictException ignored) { } },
                () -> proposals.abandon(id, p.id(), "Retiro", staff()));
        assertThat(ticketStatus(id)).isEqualTo("IN_REVIEW"); assertThat(classification(id)).isEqualTo(before);
        assertThat(proposals.get(id, p.id(), staff()).status()).isEqualTo(ReclassificationProposalStatus.ABANDONED);
        assertThat(db.queryForObject("SELECT status FROM information_requests WHERE id=?", String.class, p.informationRequestId())).isIn("ANSWERED", "CANCELLED");
    }

    @Test void postgresEnforcesOneOpenAttemptAndImmutableSnapshotEvenBeforeResolution() {
        UUID id = create(false, "LOW"); var p = propose(id, type("M6", "LOW", true), null);
        assertThatThrownBy(() -> db.update("UPDATE ticket_reclassification_proposals SET previous_classification_snapshot='{}'::jsonb WHERE id=?", p.id()))
                .hasMessageContaining("Immutable");
        assertThatThrownBy(() -> db.update("INSERT INTO ticket_reclassification_proposals(ticket_id,origin,status,target_request_type_id,target_form_template_id,proposed_data,previous_classification_snapshot,proposed_by,created_at,updated_at) "
                + "SELECT ticket_id,origin,status,target_request_type_id,target_form_template_id,proposed_data,previous_classification_snapshot,proposed_by,created_at,updated_at FROM ticket_reclassification_proposals WHERE id=?", p.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(proposals.list(id, staff())).hasSize(1);
    }

    @Test void otherFallbackCanBeCorrectedThroughTheSameProposalFlow() {
        UUID id = create(false, "LOW");
        db.update("UPDATE request_types SET code='OTHER' WHERE id=(SELECT request_type_id FROM tickets WHERE id=?)", id);
        Long target = type("M2", "LOW", true);
        var p = propose(id, target, null); proposals.confirm(id, p.id(), staff());
        assertThat(classification(id)).containsEntry("request_type_id", target);
        assertThat(ticketStatus(id)).isEqualTo("IN_PROGRESS");
    }

    @Test void administrativeTicketCancellationResolvesAReadyProposalWithoutConfirmingIt() {
        UUID id = create(false, "LOW"); var before = classification(id);
        var p = propose(id, type("M2", "LOW", true), null);
        CancelTicketRequest cancel = new CancelTicketRequest(); cancel.setReasonCode(CancellationReasonCode.OTHER);
        cancel.setInternalMessage("Cancelación administrativa"); tickets.cancelTicket(id, cancel, staff());
        assertThat(ticketStatus(id)).isEqualTo("CANCELLED"); assertThat(classification(id)).isEqualTo(before);
        assertThat(proposals.get(id, p.id(), staff()).status()).isEqualTo(ReclassificationProposalStatus.ABANDONED);
        assertThat(events(id, "ROUTED")).isZero();
    }

    @Test void positivePopulationFactorAloneDoesNotRequireNeighborhood() {
        UUID id = create(false, "LOW"); Long target = type("M2", "LOW", true);
        db.update("UPDATE request_types SET affected_population_factor=.5 WHERE id=?", target);
        var p = propose(id, target, null);
        assertThat(p.status()).isEqualTo(ReclassificationProposalStatus.READY);
        assertThat(p.informationRequestId()).isNull();
        assertThat(p.requirements().neighborhoodRequired()).isFalse();
        proposals.confirm(id, p.id(), staff());
        assertThat(classification(id)).containsEntry("estimated_affected_count", 0);
    }

    @Test void existingLocationAndNeighborhoodCanBeReusedWithoutCitizenResubmission() {
        UUID neighborhood = UUID.randomUUID();
        db.update("INSERT INTO neighborhood(id,name,population) VALUES (?,?,10000)", neighborhood, marker);
        UUID id = null;
        try {
            Long initial = type("M6", "LOW", true);
            var location = new CreateTicketRequest.LocationData("Lima 123", null, null, neighborhood, null, null, null);
            var c = tickets.create(new CreateTicketRequest(initial, "Contexto", "Ubicación existente", Map.of(), location), identity(owner, ModuleRole.CITIZEN), null);
            id = c.ticketId(); ticketIds.add(id); tickets.startReview(id, staff());
            Long target = type("M2", "LOW", true);
            db.update("UPDATE request_types SET requires_location=TRUE,affected_population_factor=.5 WHERE id=?", target);
            var p = propose(id, target, null);
            assertThat(p.status()).isEqualTo(ReclassificationProposalStatus.READY);
            assertThat(p.informationRequestId()).isNull();
            proposals.confirm(id, p.id(), staff());
            assertThat(classification(id)).containsEntry("estimated_affected_count", 5000);
        } finally {
            if (id != null) db.update("DELETE FROM ticket_locations WHERE ticket_id=?", id);
            db.update("DELETE FROM neighborhood WHERE id=?", neighborhood);
        }
    }

    @Test void legacyRoutingRejectsHistoricallyIncompleteFormsAndMissingRiskEvidence() {
        UUID id = create(false, "LOW");
        Long type = db.queryForObject("SELECT request_type_id FROM tickets WHERE id=?", Long.class, id);
        Long template = template(type); field(template, "answer", "BOOLEAN", false, "{}");
        db.update("UPDATE tickets SET form_template_id=? WHERE id=?", template, id);
        assertThatThrownBy(() -> tickets.routeToArea(id, staff())).isInstanceOf(com.reclamos.backend.exception.FormValidationException.class);
        db.update("UPDATE tickets SET form_data='{\"answer\":false}'::jsonb WHERE id=?", id);
        db.update("UPDATE request_types SET base_risk='HIGH' WHERE id=?", type);
        assertThatThrownBy(() -> tickets.routeToArea(id, staff())).isInstanceOf(com.reclamos.backend.exception.EvidenceRequiredException.class);
        assertThat(ticketStatus(id)).isEqualTo("IN_REVIEW"); assertThat(events(id, "ROUTED")).isZero();
    }

    @Test void responseOutboxFailureRemovesFilesAndRollsBackEveryProvisionalWrite() throws Exception {
        UUID id = create(false, "LOW"); Long target = type("M6", "LOW", true); field(template(target), "answer", "BOOLEAN", false, "{}");
        var p = propose(id, target, null); long files = files(); int activityCount = countActivities(id);
        db.execute("CREATE FUNCTION h0304_fail_response() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.ticket_id='" + id
                + "'::uuid AND NEW.update_type='INFORMATION_PROVIDED' THEN RAISE EXCEPTION 'H0304 response rollback'; END IF; RETURN NEW; END; $$");
        db.execute("CREATE TRIGGER h0304_fail_response BEFORE INSERT ON outbox_events FOR EACH ROW EXECUTE FUNCTION h0304_fail_response()");
        try {
            AnswerInformationRequest answer = new AnswerInformationRequest(); answer.setFormData(Map.of("answer", false));
            var file = new MockMultipartFile("attachments", "test.png", "image/png", AttachmentFixtures.bytes("image/png"));
            assertThatThrownBy(() -> information.answerInformation(id, answer, identity(owner, ModuleRole.CITIZEN), new org.springframework.web.multipart.MultipartFile[]{file}))
                    .hasMessageContaining("H0304 response rollback");
            assertThat(files()).isEqualTo(files); assertThat(countActivities(id)).isEqualTo(activityCount);
            assertThat(ticketStatus(id)).isEqualTo("PENDING_INFORMATION");
            assertThat(proposals.get(id, p.id(), staff()).status()).isEqualTo(ReclassificationProposalStatus.WAITING_INFORMATION);
            assertThat(db.queryForObject("SELECT status FROM information_requests WHERE id=?", String.class, p.informationRequestId())).isEqualTo("PENDING");
            assertThat(db.queryForObject("SELECT count(*) FROM attachments WHERE ticket_id=?", Integer.class, id)).isZero();
            assertThat(events(id, "INFORMATION_PROVIDED")).isZero();
        } finally {
            db.execute("DROP TRIGGER h0304_fail_response ON outbox_events"); db.execute("DROP FUNCTION h0304_fail_response()");
        }
    }

    Long type(String area, String minimum, boolean anonymous) {
        String name = marker + "-" + typeIds.size();
        Long id = db.queryForObject("INSERT INTO request_types(code,subcategory_id,name,description,ticket_type,responsible_area_id,minimum_priority,base_risk,affected_population_factor,allows_anonymous,requires_location,active) "
                + "SELECT ?,id,?,?,'REQUEST',?,?,'LOW',0,?,FALSE,TRUE FROM subcategories ORDER BY id LIMIT 1 RETURNING id", Long.class,
                name, name, marker, area, minimum, anonymous); typeIds.add(id); return id;
    }
    Long template(Long type) { return db.queryForObject("INSERT INTO form_templates(request_type_id,version,active) VALUES (?,1,TRUE) RETURNING id", Long.class, type); }
    Long field(Long template, String code, String type, boolean unknown, String config) {
        return db.queryForObject("INSERT INTO form_fields(form_template_id,code,label,type,required,allow_unknown,display_order,config) VALUES (?,?,?,?,TRUE,?,0,?::jsonb) RETURNING id", Long.class, template, code, code, type, unknown, config);
    }
    UUID create(boolean anonymous, String minimum) {
        var c = tickets.create(new CreateTicketRequest(type("M6", minimum, true), "H03 H04", "Integración", Map.of(), null),
                anonymous ? null : identity(owner, ModuleRole.CITIZEN), null);
        ticketIds.add(c.ticketId()); credentials.put(c.ticketId(), c); tickets.startReview(c.ticketId(), staff()); return c.ticketId();
    }
    ReclassificationProposalResponse propose(UUID id, Long type, Map<String,Object> answers) { return proposals.propose(id, new ProposeReclassificationRequest(type, answers, null), staff()); }
    void returned(UUID id, String producer, String reason) {
        UUID event = UUID.randomUUID(); eventIds.add(event); Instant now = Instant.now();
        external.applyUpdate(id, new UpdateTicketStatusEnvelope("1.0", event, "updateTicketStatus", now,
                new UpdateTicketStatusEnvelope.Producer(producer, "h03-test"), "tickets/" + id,
                new UpdateTicketStatusRequest(id, UpdateTicketStatusType.RETURNED, "Revisar clasificación", null, null,
                        new UpdateTicketStatusRequest.Details(null, new UpdateTicketStatusRequest.ReturnInfo(reason), null, null),
                        new UpdateTicketStatusRequest.Actor("EXTERNAL_USER", UUID.randomUUID().toString()), now)));
    }
    AuthenticatedIdentity staff() { return identity(agent, ModuleRole.AGENT); }
    AuthenticatedIdentity identity(UUID id, ModuleRole role) { return new AuthenticatedIdentity(id.toString(), id, "H03 H04", "M6", role); }
    UsernamePasswordAuthenticationToken auth(UUID id, ModuleRole role) { return new UsernamePasswordAuthenticationToken(identity(id, role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))); }
    Map<String,Object> classification(UUID id) { return db.queryForMap("SELECT request_type_id,responsible_area_id,form_template_id,form_data::text,current_priority,estimated_affected_count,is_escalated FROM tickets WHERE id=?", id); }
    Map<String,Object> firstResponse(UUID id) { return db.queryForMap("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='FIRST_RESPONSE'", id); }
    List<Map<String,Object>> cycles(UUID id) { return db.queryForList("SELECT * FROM ticket_sla WHERE ticket_id=? AND sla_type='RESOLUTION' ORDER BY cycle_number", id); }
    String ticketStatus(UUID id) { return db.queryForObject("SELECT current_status FROM tickets WHERE id=?", String.class, id); }
    int events(UUID id, String type) { return db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=? AND update_type=?", Integer.class, id, type); }
    long totalEvents(UUID id) { return db.queryForObject("SELECT count(*) FROM outbox_events WHERE ticket_id=?", Long.class, id); }
    int countActivities(UUID id) { return db.queryForObject("SELECT count(*) FROM ticket_activities WHERE ticket_id=?", Integer.class, id); }
    String base(UUID id) { return "/api/staff/tickets/" + id + "/reclassification-proposals"; }
    JsonNode body(String value) throws Exception { return json.readTree(value); }
    void validateEvents(UUID id) { outbox.findAll().stream().filter(e -> e.getTicket().getId().equals(id)).forEach(e -> { PriorityEventContractAssertions.assertValid(e.getPayload()); PriorityEventContractAssertions.assertFullEventValid(e.getPayload()); }); }
    void race(Runnable a, Runnable b) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { await(start); a.run(); }); var second = pool.submit(() -> { await(start); b.run(); });
            start.countDown(); first.get(25, TimeUnit.SECONDS); second.get(25, TimeUnit.SECONDS);
        }
    }
    void await(CountDownLatch start) { try { if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    long files() throws Exception {
        var root = java.nio.file.Path.of(storageRoot);
        if (!java.nio.file.Files.exists(root)) return 0;
        try (var paths = java.nio.file.Files.walk(root)) { return paths.filter(java.nio.file.Files::isRegularFile).count(); }
    }
}
