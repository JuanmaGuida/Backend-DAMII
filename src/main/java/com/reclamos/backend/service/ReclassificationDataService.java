package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AnswerInformationRequest;
import com.reclamos.backend.dto.request.CreateTicketRequest.LocationData;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/** Provisional data validation shared by creation, citizen response and final staff confirmation. */
@Service @RequiredArgsConstructor
public class ReclassificationDataService {
    private final TicketReclassificationProposalRepository proposals;
    private final FormValidationService forms;
    private final RiskCalculationService risks;
    private final TicketLocationRepository locations;
    private final NeighborhoodRepository neighborhoods;
    private final AttachmentRepository attachments;
    private final tools.jackson.databind.ObjectMapper mapper;

    public Map<String, Object> initialData(Ticket ticket, Map<String, Object> answers) {
        Map<String, Object> data = new LinkedHashMap<>();
        // There is no cross-type semantic field identity in this catalog. A matching code/label
        // does not prove compatibility, so only explicitly supplied target answers are accepted.
        data.put("formData", new LinkedHashMap<>(answers == null ? Map.of() : answers));
        locations.findByTicket_Id(ticket.getId()).ifPresent(location -> data.put("location", locationMap(location)));
        return jsonData(data);
    }

    public Map<String, Object> snapshot(Ticket ticket) {
        Map<String, Object> snapshot = initialData(ticket, ticket.getFormData());
        snapshot.put("requestTypeId", ticket.getRequestType().getId());
        snapshot.put("formTemplateId", ticket.getFormTemplateId());
        snapshot.put("responsibleAreaId", ticket.getResponsibleAreaId());
        snapshot.put("priority", ticket.getCurrentPriority().name());
        snapshot.put("evidenceIds", publicEvidence(ticket).stream().map(Attachment::getId).toList());
        return jsonData(snapshot);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> answers(Map<String, Object> data) {
        return (Map<String, Object>) data.getOrDefault("formData", Map.of());
    }

    public LocationData location(Map<String, Object> data) {
        Object value = data.get("location");
        return value == null ? null : mapper.convertValue(value, LocationData.class);
    }

    public ResolvedForm form(TicketReclassificationProposal proposal, Map<String, Object> data, boolean complete) {
        return forms.resolveVersion(proposal.getTargetRequestType(), proposal.getTargetFormTemplateId(), answers(data), complete);
    }

    public ReclassificationRequirementsResponse requirements(TicketReclassificationProposal p) {
        ResolvedForm form = form(p, p.getProposedData(), false);
        List<String> missing = form.fields().stream().filter(FormField::isRequired)
                .map(FormField::getCode).filter(code -> !form.formData().containsKey(code)).toList();
        RequestType target = p.getTargetRequestType();
        LocationData location = location(p.getProposedData());
        boolean missingLocation = target.isRequiresLocation() && !usefulLocation(location);
        // No requiresNeighborhood policy exists in creation; a positive population factor alone
        // must not introduce a new mandatory field during reclassification.
        Risk risk = risks.calculateRisk(target, form).calculatedRisk();
        boolean missingEvidence = (risk == Risk.HIGH || risk == Risk.CRITICAL)
                && publicEvidence(p.getTicket()).isEmpty()
                && (p.getId() == null || attachments.findAllByReclassificationProposalIdOrderByCreatedAtAsc(p.getId()).isEmpty());
        return new ReclassificationRequirementsResponse(form.fields().stream().map(this::fieldResponse).toList(),
                missing, form.formData(), missingLocation, false, missingEvidence,
                p.getTicket().isAnonymous() && !target.isAllowsAnonymous());
    }

    public boolean missingCitizenData(TicketReclassificationProposal p) {
        var r = requirements(p);
        return !r.missingFields().isEmpty() || r.locationRequired() || r.neighborhoodRequired() || r.evidenceRequired();
    }

    public void validateComplete(TicketReclassificationProposal p, Map<String, Object> data, boolean newEvidence) {
        ResolvedForm resolved = form(p, data, true);
        validateLocation(p.getTargetRequestType(), location(data));
        Risk risk = risks.calculateRisk(p.getTargetRequestType(), resolved).calculatedRisk();
        if ((risk == Risk.HIGH || risk == Risk.CRITICAL) && !newEvidence
                && publicEvidence(p.getTicket()).isEmpty()
                && attachments.findAllByReclassificationProposalIdOrderByCreatedAtAsc(p.getId()).isEmpty()) {
            throw new EvidenceRequiredException();
        }
    }

    /** Also protects tickets left incomplete by the former direct-correction endpoint. */
    public void validateCurrentClassification(Ticket ticket) {
        RequestType type = ticket.getRequestType();
        ResolvedForm resolved = forms.resolveVersion(type, ticket.getFormTemplateId(), ticket.getFormData(), true);
        validateLocation(type, location(initialData(ticket, ticket.getFormData())));
        if (ticket.isAnonymous() && !type.isAllowsAnonymous()) {
            throw new TicketStateConflictException("La clasificación exige identificación válida de M1");
        }
        Risk risk = risks.calculateRisk(type, resolved).calculatedRisk();
        if ((risk == Risk.HIGH || risk == Risk.CRITICAL) && publicEvidence(ticket).isEmpty()) {
            throw new EvidenceRequiredException();
        }
    }

    /** Called with Ticket lock, before any file is stored or any request/status is changed. */
    public TicketReclassificationProposal validateAnswer(InformationRequest request, AnswerInformationRequest answer,
                                                         boolean newEvidence) {
        TicketReclassificationProposal p = proposals.findByInformationRequestId(request.getId())
                .orElseThrow(() -> new InformationRequestConflictException("La solicitud no tiene propuesta"));
        if (p.getStatus() != ReclassificationProposalStatus.WAITING_INFORMATION) {
            throw new InformationRequestConflictException("La propuesta ya no admite respuestas");
        }
        Map<String, Object> data = new LinkedHashMap<>(p.getProposedData());
        Map<String, Object> answers = new LinkedHashMap<>(answers(data));
        if (answer.getFormData() != null) answers.putAll(answer.getFormData());
        data.put("formData", answers);
        if (answer.getLocation() != null) {
            Map<String, Object> mergedLocation = new LinkedHashMap<>();
            if (data.get("location") instanceof Map<?, ?> previous) previous.forEach((key,value) -> mergedLocation.put(key.toString(), value));
            Map<String, Object> supplied = mapper.convertValue(answer.getLocation(), Map.class);
            supplied.forEach((key,value) -> { if (value != null) mergedLocation.put(key, value); });
            data.put("location", mergedLocation);
        }
        validateComplete(p, data, newEvidence);
        p.setProposedData(jsonData(data));
        return p;
    }

    public void answered(TicketReclassificationProposal p, List<Attachment> evidence, Instant now) {
        evidence.forEach(a -> a.setReclassificationProposalId(p.getId()));
        attachments.saveAll(evidence);
        Map<String, Object> data = new LinkedHashMap<>(p.getProposedData());
        data.put("evidenceIds", evidence.stream().map(Attachment::getId).toList());
        p.setProposedData(jsonData(data));
        p.setStatus(ReclassificationProposalStatus.READY);
        p.setUpdatedAt(now);
        proposals.save(p);
    }

    public void terminate(Ticket ticket, String reason, Instant now) {
        proposals.findOpen(ticket.getId()).ifPresent(p -> {
            p.setStatus(ReclassificationProposalStatus.ABANDONED);
            p.setAbandonmentReason(reason);
            p.setResolvedAt(now);
            p.setUpdatedAt(now);
            proposals.save(p);
        });
    }

    public TicketLocation applyLocation(TicketReclassificationProposal p) {
        LocationData data = location(p.getProposedData());
        if (data == null) return locations.findByTicket_Id(p.getTicket().getId()).orElse(null);
        TicketLocation location = locations.findByTicket_Id(p.getTicket().getId()).orElseGet(TicketLocation::new);
        location.setTicket(p.getTicket());
        location.setAddressLine(data.addressLine()); location.setStreet(data.street());
        location.setStreetNumber(data.streetNumber()); location.setReference(data.reference());
        location.setLatitude(data.latitude()); location.setLongitude(data.longitude());
        location.setNeighborhood(data.neighborhoodId() == null ? null : neighborhoods.getReferenceById(data.neighborhoodId()));
        return locations.save(location);
    }

    public int estimatedAffected(RequestType type, TicketLocation location) {
        if (location == null || location.getNeighborhood() == null || type.getAffectedPopulationFactor() == null) return 0;
        return BigDecimal.valueOf(location.getNeighborhood().getPopulation())
                .multiply(type.getAffectedPopulationFactor()).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    /** Keep JSON values in the same primitive shape as a database round trip. UUID/Long
     * Java objects nested in mutable JSON maps otherwise remain dirty after Hibernate flush,
     * which could attempt a second update after an attempt has already become immutable. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonData(Map<String, Object> value) {
        return mapper.readValue(mapper.writeValueAsString(value), Map.class);
    }

    private List<Attachment> publicEvidence(Ticket ticket) {
        return attachments.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(ticket.getId(), MessageVisibility.PUBLIC);
    }

    private Map<String, Object> locationMap(TicketLocation l) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("addressLine", l.getAddressLine()); data.put("street", l.getStreet());
        data.put("streetNumber", l.getStreetNumber()); data.put("reference", l.getReference());
        data.put("latitude", l.getLatitude()); data.put("longitude", l.getLongitude());
        data.put("neighborhoodId", l.getNeighborhood() == null ? null : l.getNeighborhood().getId());
        return data;
    }

    private boolean usefulLocation(LocationData l) {
        return l != null && (l.addressLine() != null && !l.addressLine().isBlank()
                || l.neighborhoodId() != null || l.latitude() != null && l.longitude() != null);
    }

    private void validateLocation(RequestType type, LocationData l) {
        if (type.isRequiresLocation() && !usefulLocation(l)) throw new InvalidTicketRequestException("La ubicación es obligatoria");
        if (l == null) return;
        if ((l.latitude() == null) != (l.longitude() == null)) throw new InvalidTicketRequestException("La latitud y longitud deben informarse juntas");
        if (l.latitude() != null && (l.latitude().abs().compareTo(BigDecimal.valueOf(90)) > 0
                || l.longitude().abs().compareTo(BigDecimal.valueOf(180)) > 0)) throw new InvalidTicketRequestException("Las coordenadas son inválidas");
        if (l.neighborhoodId() != null && !neighborhoods.existsById(l.neighborhoodId())) throw new ResourceNotFoundException("Barrio no encontrado");
    }

    private FormFieldResponse fieldResponse(FormField f) {
        FormFieldResponse r = new FormFieldResponse();
        r.setCode(f.getCode()); r.setLabel(f.getLabel()); r.setType(f.getType()); r.setRequired(f.isRequired());
        r.setAllowUnknown(f.isAllowUnknown()); r.setDisplayOrder(f.getDisplayOrder()); r.setConfig(f.getConfig());
        return r;
    }
}
