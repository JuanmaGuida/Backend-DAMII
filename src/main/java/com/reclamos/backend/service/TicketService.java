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
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketService {
    /**
     * "M2" identifica gestión propia de Atención Ciudadana (Eventos v1.6 §2.1):
     * un ticket de esa "área" inicia gestión propia en IN_PROGRESS y nunca
     * genera ROUTED; los identificados sí actualizan la proyección de M1.
     */
    private static final String SELF_MANAGED_AREA_ID = "M2";

    /**
     * Whitelist de propiedades de Ticket habilitadas para {@code ?sort=}.
     * Se valida acá, antes de tocar el repository, en lugar de dejar que
     * Spring Data traduzca el Sort contra la entidad recién al ejecutar la
     * query (lo que devolvería un error interno del framework sin manejar
     * para un campo inexistente). Quedan afuera relaciones (requestType,
     * mainTicket), el mapa formData y trackingCodeHash (dato sensible). Si
     * el equipo quiere habilitar más/menos campos, este es el único lugar
     * que hay que tocar.
     */
    private static final Set<String> SORTABLE_TICKET_PROPERTIES = Set.of(
            "id", "publicId", "ticketType", "responsibleAreaId", "assignedAgent.id",
            "summary", "currentStatus", "currentPriority", "estimatedAffectedCount",
            "escalated", "escalationReasonCode", "escalatedAt", "reopenCount",
            "statusChangedAt", "resolutionConfirmationDueAt", "classificationFinalizedAt",
            "currentProgress", "createdAt", "updatedAt"
    );

    private final RequestTypeRepository requestTypeRepository;
    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketLocationRepository locationRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final TicketSlaService ticketSlaService;
    private final FormValidationService formValidationService;
    private final RiskCalculationService riskCalculationService;
    private final TicketOutboxService ticketOutboxService;
    private final TrackingCodeService trackingCodeService;
    private final TicketPublicIdGenerator publicIdGenerator;
    private final AttachmentService attachmentService;
    private final ModuleUserRepository moduleUserRepository;
    private final Clock clock;

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
        ResolvedForm resolvedForm = formValidationService.resolveAndValidate(requestType, request.formData());
        RiskAssessment assessment = riskCalculationService.calculateRisk(requestType, resolvedForm);
        Risk risk = assessment.calculatedRisk();
        List<AttachmentService.ValidatedAttachment> validatedAttachments = attachmentService.validate(evidence);
        if ((risk == Risk.HIGH || risk == Risk.CRITICAL) && validatedAttachments.isEmpty()) {
            throw new EvidenceRequiredException();
        }
        validateLocation(requestType, request.location());

        String trackingCode;
        String trackingHash;
        do {
            trackingCode = trackingCodeService.generate();
            trackingHash = trackingCodeService.hash(trackingCode);
        } while (ticketRepository.existsByTrackingCodeHash(trackingHash));

        Instant now = clock.instant();
        String publicId = publicIdGenerator.generate(now);
        Ticket ticket = new Ticket();
        ticket.setPublicId(publicId);
        ticket.setTrackingCodeHash(trackingHash);
        ticket.setCitizenId(identity.citizenId());
        ticket.setAnonymous(false);
        ticket.setRequestType(requestType);
        ticket.setFormTemplateId(resolvedForm.formTemplateId());
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId(requestType.getResponsibleAreaId());
        ticket.setSummary(request.summary());
        ticket.setDescription(request.description());
        ticket.setFormData(new HashMap<>(resolvedForm.formData()));
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        ticket.setCurrentPriority(max(requestType.getMinimumPriority(), risk));
        ticket.setCreatedAt(now);
        ticket.setEstimatedAffectedCount(0);
        ticket.setReopenCount(0);
        ticket.setEscalated(false);
        ticket.setPublic(false);
        ticket.setStatusChangedAt(now);
        ticket = ticketRepository.save(ticket);
        ticketRepository.flush();
        ticketSlaService.startFirstResponseCycle(ticket, now);
        Optional<TicketSla> resolutionSla = ticketSlaService.startInitialResolutionCycle(ticket, now);

        List<Attachment> storedAttachments = validatedAttachments.isEmpty()
                ? List.of()
                : attachmentService.storeForTicket(ticket, identity, validatedAttachments, now);

        TicketLocation location = null;
        if (request.location() != null) {
            location = locationRepository.save(toLocation(ticket, request.location()));
        }
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(1);
        activity.setActionType(ActivityType.TICKET_CREATED);
        activity.setPreviousStatus(null);
        activity.setNewStatus(TicketStatus.REGISTERED);
        activity.setActorType(ActorType.CITIZEN);
        activity.setActorId(identity.citizenId().toString());
        activity.setOccurredAt(now);
        activityRepository.save(activity);
        activateCriticalEscalationIfNeeded(ticket, now);
        ticketOutboxService.ticketCreated(ticket, location, storedAttachments,
                resolutionSla.map(TicketSla::getDueAt).orElse(null));
        ticketRepository.flush();
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
        requireAdministrativeActor(ticket, actor);

        if (ticket.getCurrentStatus() != TicketStatus.REGISTERED) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede tomarse a revisión: sólo se puede tomar un ticket REGISTERED");
        }

        TicketStatus previousStatus = ticket.getCurrentStatus();
        Instant reviewedAt = clock.instant();
        ticketSlaService.completeFirstResponseCycle(ticket, reviewedAt);
        ticket.setCurrentStatus(TicketStatus.IN_REVIEW);
        ticket.setStatusChangedAt(reviewedAt);
        // Entidades V1.49 §"CONVENCIÓN DE IDENTIFICADORES DE ACTOR": a diferencia
        // de la mayoría de los actorId (que guardan citizenId), Ticket.assignedAgentId
        // es una FK real a ModuleUser.id, reservada para relaciones puramente
        // internas de M2. Por eso acá se resuelve el ModuleUser del agente
        // autenticado (por su citizenId) en lugar de guardar actor.subjectId()
        // directamente. Decisión (a confirmar con el equipo): si el agente
        // autenticado no tiene un ModuleUser registrado, se corta con 404 en
        // lugar de dejar el ticket sin asignar silenciosamente — un agente
        // autenticado que no existe como ModuleUser es un estado inconsistente
        // que conviene visibilizar, no absorber.
        if (ticket.getAssignedAgent() == null) {
            ModuleUser agent = moduleUserRepository.findByCitizenId(actor.citizenId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "El agente autenticado no está registrado como ModuleUser"));
            ticket.setAssignedAgent(agent);
        }
        ticketRepository.save(ticket);

        recordActivity(ticket, ActivityType.REVIEW_STARTED, previousStatus, TicketStatus.IN_REVIEW,
                actor, null, null, null, reviewedAt);
        ticketOutboxService.statusChanged(ticket, null, reviewedAt);

        return toResponse(ticket, locationRepository.findByTicket_Id(ticketId).orElse(null));
    }

    /**
     * Story 3.2 (BE - Endpoint de corrección de clasificación). Sólo se permite
     * mientras el ticket está en su primera IN_REVIEW y todavía no se finalizó la
     * clasificación (Entidades V1.49 §4.1 / Guía funcional complementaria M2
     * V1.09 §6).
     * <p>
     * Recalcula responsibleAreaId, estimatedAffectedCount, formTemplateId,
     * formData, currentPriority y el SLA inicial (Guía funcional §5 /
     * Decisiones #2: "si RequestType se corrige durante la primera IN_REVIEW,
     * se recalcula el SLA inicial desde createdAt con la nueva
     * clasificación").
     * <p>
     * Prioridad: a diferencia del recálculo periódico automático (donde, por
     * la "REGLA DE EVOLUCIÓN" de la Guía funcional §3, currentPriority nunca
     * baja), una corrección de RequestType durante la primera IN_REVIEW
     * todavía forma parte de la clasificación inicial y sí puede recalcularla
     * libremente. Como formData se resetea a {} más abajo, el riesgo
     * recalculado da exactamente newRequestType.baseRisk sin incrementos, así
     * que se usa baseRisk directamente. La prioridad resultante se floorea
     * únicamente contra newRequestType.minimumPriority (mismo patrón que
     * create()), pudiendo quedar por debajo de la anterior.
     */
    @Transactional
    public TicketResponse correctClassification(UUID ticketId, Long newRequestTypeId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        requireAdministrativeActor(ticket, actor);

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
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        if (Objects.equals(previousRequestType.getId(), newRequestType.getId())) {
            return toResponse(ticket, location);
        }
        Priority previousPriority = ticket.getCurrentPriority();

        int estimatedAffectedCount = estimateAffectedCount(newRequestType, location);
        Priority newPriority = max(newRequestType.getMinimumPriority(), newRequestType.getBaseRisk());
        FormTemplate newFormTemplate = formValidationService.resolveActiveTemplate(newRequestType);

        ticket.setRequestType(newRequestType);
        ticket.setTicketType(newRequestType.getTicketType());
        ticket.setResponsibleAreaId(newRequestType.getResponsibleAreaId());
        ticket.setEstimatedAffectedCount(estimatedAffectedCount);
        ticket.setCurrentPriority(newPriority);
        ticket.setFormTemplateId(newFormTemplate == null ? null : newFormTemplate.getId());
        // Las respuestas del formData anterior están validadas contra el
        // FormTemplate del RequestType viejo y no corresponden necesariamente
        // a los campos del nuevo, así que se resetea a {} (formTemplateId ya
        // queda registrado con la nueva plantilla más arriba).
        ticket.setFormData(new HashMap<>());
        // Guía funcional §5 / Decisiones #2: la corrección de clasificación
        // durante la primera IN_REVIEW recalcula el SLA inicial desde
        // createdAt (no desde "ahora") con la nueva prioridad.
        ticketRepository.save(ticket);

        String message = "RequestType corregido de '" + previousRequestType.getCode()
                + "' a '" + newRequestType.getCode() + "' durante la revisión inicial";
        Instant reclassifiedAt = clock.instant();
        recordActivity(ticket, ActivityType.REQUEST_TYPE_CHANGED, ticket.getCurrentStatus(), ticket.getCurrentStatus(),
                actor, previousPriority, newPriority, message, reclassifiedAt);
        activateCriticalEscalationIfNeeded(ticket, reclassifiedAt);
        Optional<TicketSla> resolutionSla = ticketSlaService.recalculateInitialResolutionCycle(ticket, reclassifiedAt);
        ticketOutboxService.contentUpdated(ticket,
                resolutionSla.map(TicketSla::getDueAt).orElse(null), reclassifiedAt);

        return toResponse(ticket, location, resolutionSla.orElse(null));
    }

    /**
     * Finaliza la clasificación: inicia gestión propia M2 en IN_PROGRESS o
     * deriva una gestión externa a ROUTED y publica su snapshot inicial.
     * <p>
     * INTERPRETACIÓN DE "SELECCIONAR UN ÁREA VÁLIDA" (AC): este endpoint no
     * recibe un área por parámetro. El área ya quedó fijada en
     * responsibleAreaId durante la clasificación (Story 3.2 / RequestType), así
     * que acá "seleccionar" se interpreta como confirmar/validar esa área ya
     * asignada, no como un input nuevo del agente. Si el equipo necesita que el
     * agente pueda cambiar el área en el momento de derivar, este método hay
     * que extenderlo para recibir un areaId explícito.
     * <p>
     * No recalcula el SLA: ya quedó fijado en create() o en
     * correctClassification() y nada cambia entre la primera IN_REVIEW y esta
     * derivación que lo afecte.
     */
    @Transactional
    public TicketResponse routeToArea(UUID ticketId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        requireAdministrativeActor(ticket, actor);

        if (ticket.getCurrentStatus() != TicketStatus.IN_REVIEW) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede derivarse: sólo se puede derivar un ticket IN_REVIEW");
        }
        if (ticket.getResponsibleAreaId() == null || ticket.getResponsibleAreaId().isBlank()) {
            throw new TicketStateConflictException(
                    "El ticket no tiene un área responsable válida asignada; no puede derivarse");
        }
        if (ticket.getRequestType() == null) {
            throw new TicketStateConflictException("El ticket no tiene una clasificación válida; no puede iniciar gestión");
        }

        TicketStatus previousStatus = ticket.getCurrentStatus();
        Instant now = clock.instant();
        boolean selfManaged = SELF_MANAGED_AREA_ID.equalsIgnoreCase(ticket.getResponsibleAreaId());
        TicketStatus nextStatus = selfManaged ? TicketStatus.IN_PROGRESS : TicketStatus.ROUTED;
        ticket.setCurrentStatus(nextStatus);
        ticket.setStatusChangedAt(now);
        // classificationFinalizedAt se fija sólo la primera vez que el ticket
        // sale de IN_REVIEW hacia gestión (Entidades §4.1): a partir de acá,
        // correctClassification() ya no acepta correcciones ni siquiera después
        // de un ROUTED -> RETURNED -> IN_REVIEW posterior.
        if (ticket.getClassificationFinalizedAt() == null) {
            ticket.setClassificationFinalizedAt(now);
        }
        ticketRepository.save(ticket);

        ActivityType activityType = selfManaged ? ActivityType.STATE_CHANGED : ActivityType.ROUTED;
        String message = selfManaged
                ? "Comenzó la gestión propia de Atención Ciudadana"
                : "Derivado al área responsable '" + ticket.getResponsibleAreaId() + "'";
        recordActivity(ticket, activityType, previousStatus, nextStatus, actor, null, null, message, now);

        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        TicketSla resolutionSla = ticketSlaService.findLatestResolutionCycle(ticket).orElse(null);

        // La gestión externa recibe el snapshot ROUTED. En gestión propia sólo
        // se proyecta STATUS_CHANGED para identificados; el helper omite anónimos.
        if (selfManaged) {
            ticketOutboxService.statusChanged(ticket, null, now);
        } else {
            ticketOutboxService.routed(ticket, location,
                    resolutionSla == null ? null : resolutionSla.getDueAt(), now);
        }

        return toResponse(ticket, location, resolutionSla);
    }

    /**
     * Story 3.1 (BE - Endpoint de listado con filtros por categoría, prioridad,
     * barrio/rol y estado). "Rol" del work item se interpretó como
     * responsibleAreaId — ver el javadoc de TicketFilter.
     */
    @Transactional(readOnly = true)
    public Page<TicketResponse> listTickets(TicketFilter filter, Pageable pageable) {
        validateSort(pageable.getSort());
        Specification<Ticket> specification = TicketSpecifications.build(filter);
        Page<Ticket> page = ticketRepository.findAll(specification, pageable);

        List<UUID> ticketIds = page.getContent().stream().map(Ticket::getId).toList();
        Map<UUID, TicketLocation> locationsByTicket = locationRepository
                .findAllByTicket_IdIn(ticketIds).stream()
                .collect(Collectors.toMap(location -> location.getTicket().getId(), Function.identity()));
        Map<UUID, TicketSla> latestResolutionByTicket =
                ticketSlaService.findLatestResolutionCycles(page.getContent());

        return page.map(ticket -> toResponse(ticket, locationsByTicket.get(ticket.getId()),
                latestResolutionByTicket.get(ticket.getId())));
    }

    private void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_TICKET_PROPERTIES.contains(order.getProperty())) {
                throw new InvalidTicketRequestException(
                        "El campo de ordenamiento '" + order.getProperty() + "' no es válido");
            }
        }
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

    private void recordActivity(Ticket ticket, ActivityType actionType, TicketStatus previousStatus,
                                 TicketStatus newStatus, AuthenticatedIdentity actor,
                                 Priority previousPriority, Priority newPriority, String message,
                                 Instant occurredAt) {
        long nextSequence = activityRepository.countByTicketId(ticket.getId()) + 1;

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) nextSequence);
        activity.setActionType(actionType);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(newStatus);
        activity.setActorType(actor.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT);
        activity.setActorId(actor.citizenId().toString());
        activity.setSourceModuleId(SELF_MANAGED_AREA_ID);
        activity.setPreviousPriority(previousPriority);
        activity.setNewPriority(newPriority);
        activity.setMessage(message);
        activity.setOccurredAt(occurredAt);
        activityRepository.save(activity);
    }

    /**
     * Activa una sola vez el escalamiento automático por prioridad crítica.
     * La marca es independiente del estado y la actividad representa una
     * decisión del sistema, incluso cuando la prioridad surgió de una
     * reclasificación iniciada por un agente.
     */
    private void activateCriticalEscalationIfNeeded(Ticket ticket, Instant activatedAt) {
        if (ticket.getCurrentPriority() != Priority.CRITICAL || ticket.isEscalated()) {
            return;
        }

        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(activatedAt);
        ticketRepository.save(ticket);

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.ESCALATED);
        activity.setPreviousStatus(ticket.getCurrentStatus());
        activity.setNewStatus(ticket.getCurrentStatus());
        activity.setActorType(ActorType.SYSTEM);
        activity.setActorId(null);
        activity.setReasonCode(EscalationReasonCode.CRITICAL_PRIORITY.name());
        activity.setMessage("Escalamiento automático por prioridad crítica");
        activity.setOccurredAt(activatedAt);
        activityRepository.save(activity);
    }

    private TicketResponse toResponse(Ticket ticket, TicketLocation location) {
        return toResponse(ticket, location, ticketSlaService.findLatestResolutionCycle(ticket).orElse(null));
    }

    private TicketResponse toResponse(Ticket ticket, TicketLocation location, TicketSla resolutionSla) {
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
        response.setAssignedAgentId(ticket.getAssignedAgent() != null
                ? String.valueOf(ticket.getAssignedAgent().getId()) : null);
        response.setAnonymous(ticket.isAnonymous());
        response.setEstimatedAffectedCount(ticket.getEstimatedAffectedCount());
        response.setEscalated(ticket.isEscalated());
        response.setEscalationReasonCode(ticket.getEscalationReasonCode());
        response.setEscalatedAt(ticket.getEscalatedAt());
        applySlaSignals(response, resolutionSla);
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

    private void applySlaSignals(TicketResponse response, TicketSla resolutionSla) {
        if (resolutionSla == null) {
            response.setSlaNearDue(false);
            response.setSlaBreached(false);
            response.setResolutionNearDueAt(null);
            return;
        }
        response.setSlaNearDue(resolutionSla.getStatus() == SlaStatus.NEAR_DUE);
        response.setSlaBreached(resolutionSla.getStatus() == SlaStatus.BREACHED);
        response.setResolutionNearDueAt(resolutionSla.getNearDueAt());
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
        return priorityRank(minimum) >= priorityRank(fromRisk) ? minimum : fromRisk;
    }

    private int priorityRank(Priority priority) {
        return switch (priority) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }

    private void requireAdministrativeActor(Ticket ticket, AuthenticatedIdentity actor) {
        if (actor == null || (actor.role() != ModuleRole.AGENT && actor.role() != ModuleRole.ADMIN)
                || Objects.equals(actor.citizenId(), ticket.getCitizenId())) {
            throw new com.reclamos.backend.exception.UnauthorizedTicketOperationException();
        }
    }

}
