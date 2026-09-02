package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * "M2" identifica gestión propia de Atención Ciudadana (Eventos v1.6 §2.1):
     * un ticket derivado a esa "área" no tiene consumidor externo y no debe
     * generar OutboxEvent.
     */
    private static final String SELF_MANAGED_AREA_ID = "M2";

    private final RequestTypeRepository requestTypeRepository;
    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketLocationRepository locationRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final FormValidationService formValidationService;
    private final RiskCalculationService riskCalculationService;
    private final OutboxEventRepository outboxEventRepository;

    @Value("${app.events.producer.module-id:M2}")
    private String producerModuleId;

    @Value("${app.events.producer.service:help-center-api}")
    private String producerService;

    @Transactional
    public CreateTicketResponse create(CreateTicketRequest request, AuthenticatedIdentity identity,
                                       MultipartFile[] evidence) {
        if (identity == null) {
            throw new InvalidTicketRequestException("Se requiere la identidad del ciudadano");
        }
        RequestType requestType = requestTypeRepository.findById(request.requestTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Request Type no encontrado"));
        if (!requestType.isActive()) {
            throw new InvalidTicketRequestException("El Request Type seleccionado está inactivo");
        }
        var fields = formValidationService.validateAndGetFields(requestType, request.formData());
        Risk risk = riskCalculationService.calculateRisk(requestType, request.formData(), fields);
        // TODO Attachment: evidence is only checked for presence in this US. Persist it when storage exists.
        boolean validEvidence = evidence != null && java.util.Arrays.stream(evidence)
                .anyMatch(file -> file != null && !file.isEmpty() && file.getSize() > 0);
        if ((risk == Risk.HIGH || risk == Risk.CRITICAL) && !validEvidence) {
            throw new EvidenceRequiredException();
        }
        validateLocation(requestType, request.location());

        String trackingCode;
        String trackingHash;
        do {
            trackingCode = generateTrackingCode();
            trackingHash = hash(trackingCode);
        } while (ticketRepository.existsByTrackingCodeHash(trackingHash));

        String publicId;
        do {
            publicId = generatePublicId();
        } while (ticketRepository.existsByPublicId(publicId));

        Instant now = Instant.now();
        Ticket ticket = new Ticket();
        ticket.setPublicId(publicId);
        ticket.setTrackingCodeHash(trackingHash);
        ticket.setCitizenId(identity.citizenId());
        ticket.setAnonymous(false);
        ticket.setRequestType(requestType);
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId(requestType.getResponsibleAreaId());
        ticket.setSummary(request.summary());
        ticket.setDescription(request.description());
        ticket.setFormData(new HashMap<>(request.formData()));
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        ticket.setCurrentPriority(max(requestType.getMinimumPriority(), risk));
        ticket.setEstimatedAffectedCount(0);
        ticket.setReopenCount(0);
        ticket.setEscalated(false);
        ticket.setPublic(false);
        ticket.setStatusChangedAt(now);
        ticket = ticketRepository.save(ticket);

        if (request.location() != null) {
            locationRepository.save(toLocation(ticket, request.location()));
        }
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(1);
        activity.setActionType(ActivityType.TICKET_CREATED);
        activity.setPreviousStatus(null);
        activity.setNewStatus(TicketStatus.REGISTERED);
        activity.setActorType(ActorType.CITIZEN);
        activity.setActorId(identity.subjectId());
        activity.setOccurredAt(now);
        activityRepository.save(activity);
        return new CreateTicketResponse(ticket.getId(), ticket.getPublicId(), trackingCode,
                TicketStatus.REGISTERED);
    }

    /**
     * Story 3.2 (BE - Endpoint de transición de estados del ticket): la única
     * transición que corresponde a esta story es la toma de ticket por un agente,
     * REGISTERED -&gt; IN_REVIEW. Las demás transiciones (ROUTED, RESOLVED, etc.) son
     * stories aparte más adelante en el plan y no se tocan acá.
     */
    @Transactional
    public TicketResponse startReview(UUID ticketId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);

        if (ticket.getCurrentStatus() != TicketStatus.REGISTERED) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede tomarse a revisión: sólo se puede tomar un ticket REGISTERED");
        }

        TicketStatus previousStatus = ticket.getCurrentStatus();
        ticket.setCurrentStatus(TicketStatus.IN_REVIEW);
        ticket.setStatusChangedAt(Instant.now());
        if (actor != null && ticket.getAssignedAgentId() == null) {
            ticket.setAssignedAgentId(actor.subjectId());
        }
        ticketRepository.save(ticket);

        recordActivity(ticket, ActivityType.REVIEW_STARTED, previousStatus, TicketStatus.IN_REVIEW,
                actor, null, null, null);

        return toResponse(ticket, locationRepository.findByTicket_Id(ticketId).orElse(null));
    }

    /**
     * Story 3.2 (BE - Endpoint de corrección de clasificación). Sólo se permite
     * mientras el ticket está en su primera IN_REVIEW y todavía no se finalizó la
     * clasificación (Entidades v1.3 §4.1 / Guía funcional §6).
     * <p>
     * OJO: esto recalcula responsibleAreaId, estimatedAffectedCount y aplica el piso
     * de minimumPriority sobre currentPriority, tal como pide Decisiones #2. NO
     * recalcula el SLA inicial (Decisiones #2 también lo pide) porque el módulo de
     * SLA todavía no existe en este backend (Epic 6, Sprint 4). Tampoco vuelve a
     * correr el motor de riesgo completo (RiskCalculationService necesita formData
     * y form fields del alta original, que esta operación no recibe) — sólo aplica
     * el piso de minimumPriority sin bajar la prioridad vigente, que es la única
     * parte de la fórmula de prioridad que se puede recalcular sin volver a pedir
     * las respuestas del formulario.
     */
    @Transactional
    public TicketResponse correctClassification(UUID ticketId, Long newRequestTypeId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);

        if (ticket.getCurrentStatus() != TicketStatus.IN_REVIEW || ticket.getClassificationFinalizedAt() != null) {
            throw new TicketStateConflictException(
                    "La clasificación sólo puede corregirse durante la primera revisión del ticket, "
                            + "antes de derivarlo, iniciar gestión o vincularlo como duplicado");
        }

        RequestType newRequestType = requestTypeRepository.findById(newRequestTypeId)
                .filter(RequestType::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El Request Type solicitado no existe o está inactivo"));

        RequestType previousRequestType = ticket.getRequestType();
        Priority previousPriority = ticket.getCurrentPriority();

        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        int estimatedAffectedCount = estimateAffectedCount(newRequestType, location);
        Priority newPriority = applyMinimumPriorityFloor(previousPriority, newRequestType.getMinimumPriority());

        ticket.setRequestType(newRequestType);
        ticket.setTicketType(newRequestType.getTicketType());
        ticket.setResponsibleAreaId(newRequestType.getResponsibleAreaId());
        ticket.setEstimatedAffectedCount(estimatedAffectedCount);
        ticket.setCurrentPriority(newPriority);
        ticketRepository.save(ticket);

        String message = "RequestType corregido de '" + previousRequestType.getCode()
                + "' a '" + newRequestType.getCode() + "' durante la revisión inicial";
        recordActivity(ticket, ActivityType.REQUEST_TYPE_CHANGED, ticket.getCurrentStatus(), ticket.getCurrentStatus(),
                actor, previousPriority, newPriority, message);

        return toResponse(ticket, location);
    }

    /**
     * Story 3.3 (BE - Endpoint de derivación IN_REVIEW -&gt; ROUTED + publicación
     * de ticketUpdated al outbox / DDA2-59).
     * <p>
     * INTERPRETACIÓN DE "SELECCIONAR UN ÁREA VÁLIDA" (AC): este endpoint no
     * recibe un área por parámetro. El área ya quedó fijada en
     * responsibleAreaId durante la clasificación (Story 3.2 / RequestType), así
     * que acá "seleccionar" se interpreta como confirmar/validar esa área ya
     * asignada, no como un input nuevo del agente. Si el equipo necesita que el
     * agente pueda cambiar el área en el momento de derivar, este método hay
     * que extenderlo para recibir un areaId explícito.
     */
    @Transactional
    public TicketResponse routeToArea(UUID ticketId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);

        if (ticket.getCurrentStatus() != TicketStatus.IN_REVIEW) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede derivarse: sólo se puede derivar un ticket IN_REVIEW");
        }
        if (ticket.getResponsibleAreaId() == null || ticket.getResponsibleAreaId().isBlank()) {
            throw new TicketStateConflictException(
                    "El ticket no tiene un área responsable válida asignada; no puede derivarse");
        }

        TicketStatus previousStatus = ticket.getCurrentStatus();
        ticket.setCurrentStatus(TicketStatus.ROUTED);
        ticket.setStatusChangedAt(Instant.now());
        ticketRepository.save(ticket);

        recordActivity(ticket, ActivityType.ROUTED, previousStatus, TicketStatus.ROUTED, actor, null, null,
                "Derivado al área responsable '" + ticket.getResponsibleAreaId() + "'");

        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);

        // Política de publicación (Eventos v1.6 §2.1): siempre se publica en
        // ROUTED, salvo que el área responsable sea la propia M2 (gestión
        // interna sin consumidor externo).
        if (!SELF_MANAGED_AREA_ID.equalsIgnoreCase(ticket.getResponsibleAreaId())) {
            writeOutboxEvent(ticket, location);
        }

        return toResponse(ticket, location);
    }

    /**
     * Construye el envelope + data de ticketUpdated/ROUTED (Eventos v1.6 §4 y
     * §7.4) y lo inserta como OutboxEvent PENDING en la misma transacción que
     * el cambio de estado (Entidades v1.3 §19.2). Todavía no existe un
     * publisher asíncrono real (Sprint 3: "sin consumidor real todavía"), así
     * que el evento queda en PENDING hasta que se implemente ese publisher.
     */
    private void writeOutboxEvent(Ticket ticket, TicketLocation location) {
        RequestType requestType = ticket.getRequestType();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("requestType", requestType.getName());
        routing.put("ticketType", ticket.getTicketType());
        routing.put("summary", ticket.getSummary());
        routing.put("description", ticket.getDescription());
        routing.put("formData", ticket.getFormData());
        routing.put("location", toEventLocation(location));
        routing.put("resolutionDueAt", null); // SLA no implementado todavía (Sprint 4)
        routing.put("escalation", null); // Escalamiento no implementado todavía (Sprint 4)

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("routing", routing);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("citizenId", ticket.getCitizenId());
        data.put("isAnonymous", ticket.isAnonymous());
        data.put("responsibleAreaId", ticket.getResponsibleAreaId());
        data.put("updateType", TicketUpdatedType.ROUTED.name());
        data.put("currentStatus", ticket.getCurrentStatus().name());
        data.put("currentPriority", ticket.getCurrentPriority().name());
        data.put("progress", ticket.getCurrentProgress());
        data.put("publicMessage", "El ticket fue derivado al área responsable.");
        data.put("details", details);
        data.put("attachments", List.of());
        data.put("updatedAt", ticket.getUpdatedAt() != null ? ticket.getUpdatedAt().toString() : now.toString());

        Map<String, Object> producer = new LinkedHashMap<>();
        producer.put("moduleId", producerModuleId);
        producer.put("service", producerService);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("specVersion", "1.0");
        payload.put("eventId", eventId.toString());
        payload.put("eventType", "ticketUpdated");
        payload.put("occurredAt", now.toString());
        payload.put("producer", producer);
        payload.put("subject", "tickets/" + ticket.getId());
        payload.put("data", data);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setEventType("ticketUpdated");
        event.setUpdateType(TicketUpdatedType.ROUTED);
        event.setTicket(ticket);
        event.setPayload(payload);
        event.setStatus(OutboxStatus.PENDING);
        event.setRetryCount(0);
        outboxEventRepository.save(event);
    }

    private Map<String, Object> toEventLocation(TicketLocation location) {
        if (location == null) {
            return null;
        }
        Map<String, Object> eventLocation = new LinkedHashMap<>();
        eventLocation.put("addressLine", location.getAddressLine());
        eventLocation.put("street", location.getStreet());
        eventLocation.put("streetNumber", location.getStreetNumber());
        eventLocation.put("neighborhoodId", location.getNeighborhood() != null
                ? location.getNeighborhood().getId() : null);
        eventLocation.put("reference", location.getReference());
        return eventLocation;
    }

    /**
     * Story 3.1 (BE - Endpoint de listado con filtros por categoría, prioridad,
     * barrio/rol y estado). "Rol" del work item se interpretó como
     * responsibleAreaId — ver el javadoc de TicketFilter.
     */
    @Transactional(readOnly = true)
    public Page<TicketResponse> listTickets(TicketFilter filter, Pageable pageable) {
        Specification<Ticket> specification = TicketSpecifications.build(filter);
        Page<Ticket> page = ticketRepository.findAll(specification, pageable);

        List<UUID> ticketIds = page.getContent().stream().map(Ticket::getId).toList();
        Map<UUID, TicketLocation> locationsByTicket = locationRepository
                .findAllByTicket_IdIn(ticketIds).stream()
                .collect(Collectors.toMap(location -> location.getTicket().getId(), Function.identity()));

        return page.map(ticket -> toResponse(ticket, locationsByTicket.get(ticket.getId())));
    }

    private Ticket loadForUpdate(UUID ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
    }

    private int estimateAffectedCount(RequestType requestType, TicketLocation location) {
        if (location == null || location.getNeighborhood() == null
                || requestType.getAffectedPopulationFactor() == null) {
            return 0;
        }
        BigDecimal population = BigDecimal.valueOf(location.getNeighborhood().getPopulation());
        return population.multiply(requestType.getAffectedPopulationFactor())
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private Priority applyMinimumPriorityFloor(Priority current, Priority floor) {
        if (current == null) {
            return floor;
        }
        return current.ordinal() >= floor.ordinal() ? current : floor;
    }

    private void recordActivity(Ticket ticket, ActivityType actionType, TicketStatus previousStatus,
                                 TicketStatus newStatus, AuthenticatedIdentity actor,
                                 Priority previousPriority, Priority newPriority, String message) {
        long nextSequence = activityRepository.countByTicket_Id(ticket.getId()) + 1;

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) nextSequence);
        activity.setActionType(actionType);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(newStatus);
        // ActorType hoy sólo tiene CITIZEN/AGENT/AREA_RESPONSIBLE/ADMIN (aviso aparte:
        // esto no matchea el contrato de Eventos v1.6 §5.2, que espera SYSTEM en vez de
        // ADMIN para un actor no identificado). Se usa ADMIN acá como el más parecido a
        // "actor no identificado", pero hay que revisarlo cuando se corrija el enum o el
        // contrato de eventos.
        activity.setActorType(actor != null ? ActorType.AGENT : ActorType.ADMIN);
        activity.setActorId(actor != null ? actor.subjectId() : null);
        activity.setPreviousPriority(previousPriority);
        activity.setNewPriority(newPriority);
        activity.setMessage(message);
        activity.setOccurredAt(Instant.now());
        activityRepository.save(activity);
    }

    private TicketResponse toResponse(Ticket ticket, TicketLocation location) {
        RequestType requestType = ticket.getRequestType();
        Subcategory subcategory = requestType.getSubcategory();
        Category category = subcategory.getCategory();

        TicketResponse response = new TicketResponse();
        response.setId(ticket.getId());
        response.setPublicId(ticket.getPublicId());
        response.setRequestTypeCode(requestType.getCode());
        response.setRequestTypeName(requestType.getName());
        response.setCategoryName(category.getName());
        response.setSubcategoryName(subcategory.getName());
        response.setTicketType(ticket.getTicketType());
        response.setSummary(ticket.getSummary());
        response.setCurrentStatus(ticket.getCurrentStatus());
        response.setCurrentPriority(ticket.getCurrentPriority());
        response.setResponsibleAreaId(ticket.getResponsibleAreaId());
        response.setAssignedAgentId(ticket.getAssignedAgentId());
        response.setAnonymous(ticket.isAnonymous());
        response.setEstimatedAffectedCount(ticket.getEstimatedAffectedCount());
        response.setEscalated(ticket.isEscalated());
        if (location != null && location.getNeighborhood() != null) {
            response.setNeighborhoodId(location.getNeighborhood().getId());
            response.setNeighborhoodName(location.getNeighborhood().getName());
        }
        response.setClassificationFinalizedAt(ticket.getClassificationFinalizedAt());
        response.setStatusChangedAt(ticket.getStatusChangedAt());
        response.setCreatedAt(ticket.getCreatedAt());
        response.setUpdatedAt(ticket.getUpdatedAt());
        return response;
    }

    private void validateLocation(RequestType type, CreateTicketRequest.LocationData location) {
        if (type.isRequiresLocation() && location == null) {
            throw new InvalidTicketRequestException("La ubicación es obligatoria para el Request Type seleccionado");
        }
        if (location == null) return;
        boolean hasLatitude = location.latitude() != null;
        boolean hasLongitude = location.longitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new InvalidTicketRequestException("La latitud y longitud deben informarse juntas");
        }
        boolean hasCoordinates = hasLatitude;
        boolean hasUsefulLocation = location.addressLine() != null && !location.addressLine().isBlank()
                || location.neighborhoodId() != null || hasCoordinates;
        if (type.isRequiresLocation() && !hasUsefulLocation) {
            throw new InvalidTicketRequestException("La ubicación es obligatoria para el Request Type seleccionado");
        }
        if (hasCoordinates && (location.latitude().doubleValue() < -90 || location.latitude().doubleValue() > 90
                || location.longitude().doubleValue() < -180 || location.longitude().doubleValue() > 180)) {
            throw new InvalidTicketRequestException("Las coordenadas de ubicación son inválidas");
        }
        if (location.neighborhoodId() != null && !neighborhoodRepository.existsById(location.neighborhoodId())) {
            throw new ResourceNotFoundException("Barrio no encontrado");
        }
    }

    private TicketLocation toLocation(Ticket ticket, CreateTicketRequest.LocationData data) {
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setAddressLine(data.addressLine());
        location.setStreet(data.street());
        location.setStreetNumber(data.streetNumber());
        location.setLatitude(data.latitude());
        location.setLongitude(data.longitude());
        location.setReference(data.reference());
        if (data.neighborhoodId() != null) {
            location.setNeighborhood(neighborhoodRepository.getReferenceById(data.neighborhoodId()));
        }
        return location;
    }

    private Priority max(Priority minimum, Risk risk) {
        Priority fromRisk = Priority.valueOf(risk.name());
        return minimum.ordinal() >= fromRisk.ordinal() ? minimum : fromRisk;
    }

    private String generateTrackingCode() {
        byte[] random = new byte[24];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    private String generatePublicId() {
        StringBuilder value = new StringBuilder("OP-");
        for (int index = 0; index < 10; index++) {
            value.append(SECURE_RANDOM.nextInt(10));
        }
        return value.toString();
    }

    private String hash(String code) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 no está disponible", impossible);
        }
    }
}
