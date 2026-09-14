package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CancelTicketRequest;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketService {
    /**
     * "M2" identifica gestión propia de Atención Ciudadana (Eventos v1.6 §2.1):
     * un ticket derivado a esa "área" no tiene consumidor externo y no debe
     * generar OutboxEvent.
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

    /**
     * Estados desde los que POST /tickets/{id}/cancel permite cancelar
     * (Entidades V1.49 §24: cancelación temprana, antes de la derivación).
     * ROUTED/IN_PROGRESS quedan afuera a propósito: ahí el área externa ya
     * está involucrada y esa cancelación llega por el flujo de integración
     * (updateTicketStatus/REJECTED, ver TicketStatusUpdateService).
     */
    private static final Set<TicketStatus> CANCELLABLE_STATUSES = Set.of(
            TicketStatus.REGISTERED, TicketStatus.IN_REVIEW, TicketStatus.PENDING_INFORMATION);

    private final RequestTypeRepository requestTypeRepository;
    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final TicketLocationRepository locationRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final TicketCancellationRepository cancellationRepository;
    private final SlaCalculationService slaCalculationService;
    private final FormValidationService formValidationService;
    private final RiskCalculationService riskCalculationService;
    private final OutboxEventRepository outboxEventRepository;
    private final TrackingCodeService trackingCodeService;
    private final TicketPublicIdGenerator publicIdGenerator;
    private final AttachmentService attachmentService;
    private final ModuleUserRepository moduleUserRepository;
    private final Clock clock;

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
        ticket.setFirstResponseDueAt(slaCalculationService
                .calculateDueAt(now, ticket.getCurrentPriority(), SlaType.FIRST_RESPONSE).orElse(null));
        ticket.setResolutionDueAt(slaCalculationService
                .calculateResolutionDueAt(now, ticket.getCurrentPriority(), ticket.getTicketType()).orElse(null));
        ticket.setEstimatedAffectedCount(0);
        ticket.setReopenCount(0);
        ticket.setEscalated(false);
        ticket.setPublic(false);
        ticket.setStatusChangedAt(now);
        ticket = ticketRepository.save(ticket);
        ticketRepository.flush();

        if (!validatedAttachments.isEmpty()) {
            attachmentService.storeForTicket(ticket, identity, validatedAttachments, now);
        }

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
        activity.setActorId(identity.citizenId().toString());
        activity.setOccurredAt(now);
        activityRepository.save(activity);
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
        requireTriageAuthority(ticket, actor);

        if (ticket.getCurrentStatus() != TicketStatus.REGISTERED) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede tomarse a revisión: sólo se puede tomar un ticket REGISTERED");
        }

        TicketStatus previousStatus = ticket.getCurrentStatus();
        ticket.setCurrentStatus(TicketStatus.IN_REVIEW);
        ticket.setStatusChangedAt(clock.instant());
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
        if (actor != null && ticket.getAssignedAgent() == null) {
            ModuleUser agent = moduleUserRepository.findByCitizenId(actor.citizenId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "El agente autenticado no está registrado como ModuleUser"));
            ticket.setAssignedAgent(agent);
        }
        ticketRepository.save(ticket);

        recordActivity(ticket, ActivityType.REVIEW_STARTED, previousStatus, TicketStatus.IN_REVIEW,
                actor, null, null, null);

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
        requireTriageAuthority(ticket, actor);

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
        ticket.setFirstResponseDueAt(slaCalculationService
                .calculateDueAt(ticket.getCreatedAt(), newPriority, SlaType.FIRST_RESPONSE).orElse(null));
        ticket.setResolutionDueAt(slaCalculationService
                .calculateResolutionDueAt(ticket.getCreatedAt(), newPriority, ticket.getTicketType()).orElse(null));
        ticketRepository.save(ticket);

        String message = "RequestType corregido de '" + previousRequestType.getCode()
                + "' a '" + newRequestType.getCode() + "' durante la revisión inicial";
        recordActivity(ticket, ActivityType.REQUEST_TYPE_CHANGED, ticket.getCurrentStatus(), ticket.getCurrentStatus(),
                actor, previousPriority, newPriority, message);

        // Eventos V1.69 §7.2/§7.7: tickets identificados publican
        // ticketUpdated/CONTENT_UPDATED al corregir la clasificación durante
        // la primera revisión, para que M1 actualice su proyección. A
        // diferencia de ROUTED, acá NO se excluye SELF_MANAGED_AREA_ID: el
        // consumidor es M1 (tabla §2.1 "Identificado · cambios
        // posteriores"), no el área responsable, así que se publica sin
        // importar a qué área haya quedado asignado el ticket.
        if (!ticket.isAnonymous()) {
            writeContentUpdatedEvent(ticket);
        }

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
     * <p>
     * No recalcula el SLA: ya quedó fijado en create() o en
     * correctClassification() y nada cambia entre la primera IN_REVIEW y esta
     * derivación que lo afecte.
     */
    @Transactional
    public TicketResponse routeToArea(UUID ticketId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        requireTriageAuthority(ticket, actor);

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
        Instant now = clock.instant();
        ticket.setCurrentStatus(TicketStatus.ROUTED);
        ticket.setStatusChangedAt(now);
        // classificationFinalizedAt se fija sólo la primera vez que el ticket
        // sale de IN_REVIEW hacia gestión (Entidades §4.1): a partir de acá,
        // correctClassification() ya no acepta correcciones ni siquiera después
        // de un ROUTED -> RETURNED -> IN_REVIEW posterior.
        if (ticket.getClassificationFinalizedAt() == null) {
            ticket.setClassificationFinalizedAt(now);
        }
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
     * POST /tickets/{id}/cancel (Entidades V1.49 §24: "Ciudadano owner /
     * propietario anónimo acreditado / AGENT / ADMIN"). Cancelación
     * TEMPRANA, antes de que el ticket llegue a gestión externa: sólo cubre
     * {@link #CANCELLABLE_STATUSES} -&gt; CANCELLED. ROUTED/IN_PROGRESS se
     * cancelan por el flujo de integración (updateTicketStatus/REJECTED),
     * no por acá. La cancelación de un ticket DUPLICATE (Entidades §14: "se
     * agrega la transición DUPLICATE -&gt; CANCELLED por
     * WITHDRAWN_BY_CITIZEN") queda pendiente de Story 7.2 (Sprint 5):
     * DUPLICATE todavía no es un estado alcanzable en el sistema.
     * <p>
     * ALCANCE REDUCIDO A PROPÓSITO: el "propietario anónimo acreditado" de
     * la tabla de endpoints no está cubierto acá — requiere el mecanismo de
     * acreditación por trackingAccessCode + contraseña (POST
     * /tracking/access) que todavía no expone una AuthenticatedIdentity
     * utilizable en este endpoint.
     */
    @Transactional
    public TicketResponse cancelTicket(UUID ticketId, CancelTicketRequest request, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        boolean isOwnTicket = actor != null && !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(actor.citizenId());
        requireCancelAuthority(actor, isOwnTicket);

        if (!CANCELLABLE_STATUSES.contains(ticket.getCurrentStatus())) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede cancelarse por este endpoint; sólo se puede cancelar antes de la"
                            + " derivación (REGISTERED, IN_REVIEW o PENDING_INFORMATION)");
        }

        Instant now = clock.instant();
        ActorType actorType = isOwnTicket ? ActorType.CITIZEN
                : (actor.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT);

        TicketCancellation cancellation = new TicketCancellation();
        cancellation.setTicket(ticket);
        cancellation.setReasonCode(request.getReasonCode());
        cancellation.setPublicMessage(request.getPublicMessage());
        cancellation.setInternalMessage(request.getInternalMessage());
        cancellation.setCancelledByType(actorType);
        cancellation.setCancelledById(actor.subjectId());
        cancellation.setCancelledAt(now);
        cancellationRepository.save(cancellation);

        TicketStatus previousStatus = ticket.getCurrentStatus();
        ticket.setCurrentStatus(TicketStatus.CANCELLED);
        ticket.setStatusChangedAt(now);
        ticketRepository.save(ticket);

        recordCancellationActivity(ticket, previousStatus, actorType, actor, request.getReasonCode(),
                request.getPublicMessage() != null ? request.getPublicMessage() : request.getInternalMessage());

        // Eventos V1.69 §2.1/§7.7: sólo tickets identificados publican
        // ticketUpdated/CANCELLED acá. En este punto (pre-ROUTED) nunca hubo
        // un área externa involucrada, así que a diferencia de routeToArea
        // no hay ningún gate por SELF_MANAGED_AREA_ID — lo único que importa
        // es si M1 tiene que actualizar su proyección.
        if (!ticket.isAnonymous()) {
            writeCancelledEvent(ticket, request.getReasonCode(), request.getPublicMessage());
        }

        return toResponse(ticket, locationRepository.findByTicket_Id(ticketId).orElse(null));
    }

    private void requireCancelAuthority(AuthenticatedIdentity actor, boolean isOwnTicket) {
        if (actor == null) {
            throw new UnauthorizedTicketOperationException();
        }
        boolean isStaff = actor.role() == ModuleRole.AGENT || actor.role() == ModuleRole.ADMIN;
        if (!isOwnTicket && !isStaff) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private void recordCancellationActivity(Ticket ticket, TicketStatus previousStatus, ActorType actorType,
                                             AuthenticatedIdentity actor, CancellationReasonCode reasonCode,
                                             String message) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.CANCELLED);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(TicketStatus.CANCELLED);
        activity.setActorType(actorType);
        activity.setActorId(actor.subjectId());
        activity.setReasonCode(reasonCode.name());
        activity.setMessage(message);
        activity.setOccurredAt(clock.instant());
        activityRepository.save(activity);
    }

    /**
     * Arma details.cancellation (Eventos V1.69 §7.7: "reasonCode
     * obligatorio. publicMessage contiene la explicación pública cuando
     * corresponda") y publica ticketUpdated/CANCELLED vía
     * {@link #publishTicketUpdated}.
     */
    private void writeCancelledEvent(Ticket ticket, CancellationReasonCode reasonCode, String publicMessage) {
        Map<String, Object> cancellation = new LinkedHashMap<>();
        cancellation.put("reasonCode", reasonCode.name());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("cancellation", cancellation);

        publishTicketUpdated(ticket, TicketUpdatedType.CANCELLED, publicMessage, details);
    }

    /**
     * Arma details.routing (Eventos v1.6 §7.4) y publica ticketUpdated/ROUTED
     * vía {@link #publishTicketUpdated}.
     */
    private void writeOutboxEvent(Ticket ticket, TicketLocation location) {
        RequestType requestType = ticket.getRequestType();

        Map<String, Object> routing = new LinkedHashMap<>();
        routing.put("requestType", requestType.getName());
        routing.put("ticketType", ticket.getTicketType());
        routing.put("summary", ticket.getSummary());
        routing.put("description", ticket.getDescription());
        routing.put("formData", ticket.getFormData());
        routing.put("location", toEventLocation(location));
        routing.put("resolutionDueAt", ticket.getResolutionDueAt() != null
                ? ticket.getResolutionDueAt().toString() : null);
        routing.put("escalation", null); // Escalamiento no implementado todavía (Sprint 4)

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("routing", routing);

        publishTicketUpdated(ticket, TicketUpdatedType.ROUTED,
                "El ticket fue derivado al área responsable.", details);
    }

    /**
     * Arma details.content (Eventos V1.69 §7.7: "Puede incluir requestType,
     * category, subcategory, ticketType, summary, description, formData y
     * resolutionDueAt actualizados. Se usa, entre otros casos, cuando M2
     * corrige RequestType durante la revisión inicial") y publica
     * ticketUpdated/CONTENT_UPDATED vía {@link #publishTicketUpdated}.
     * currentPriority y responsibleAreaId ya viajan en los campos comunes de
     * data, no se repiten acá.
     */
    private void writeContentUpdatedEvent(Ticket ticket) {
        RequestType requestType = ticket.getRequestType();
        Subcategory subcategory = requestType.getSubcategory();
        Category category = subcategory.getCategory();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("requestType", requestType.getName());
        content.put("category", category.getName());
        content.put("subcategory", subcategory.getName());
        content.put("ticketType", ticket.getTicketType());
        content.put("summary", ticket.getSummary());
        content.put("description", ticket.getDescription());
        content.put("formData", ticket.getFormData());
        content.put("resolutionDueAt", ticket.getResolutionDueAt() != null
                ? ticket.getResolutionDueAt().toString() : null);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("content", content);

        publishTicketUpdated(ticket, TicketUpdatedType.CONTENT_UPDATED,
                "Se actualizó la clasificación del ticket.", details);
    }

    /**
     * Construye el envelope + data comunes de ticketUpdated (Eventos v1.6 §4
     * y §7.1) y lo inserta como OutboxEvent PENDING en la misma transacción
     * que el cambio de negocio (Entidades v1.3 §19.2). Todavía no existe un
     * publisher asíncrono real (Sprint 3: "sin consumidor real todavía"), así
     * que el evento queda en PENDING hasta que se implemente ese publisher.
     */
    private void publishTicketUpdated(Ticket ticket, TicketUpdatedType updateType, String publicMessage,
                                      Map<String, Object> details) {
        UUID eventId = UUID.randomUUID();
        Instant now = clock.instant();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticket.getId());
        data.put("publicId", ticket.getPublicId());
        data.put("citizenId", ticket.getCitizenId());
        data.put("isAnonymous", ticket.isAnonymous());
        data.put("responsibleAreaId", ticket.getResponsibleAreaId());
        data.put("updateType", updateType.name());
        data.put("currentStatus", ticket.getCurrentStatus().name());
        data.put("currentPriority", ticket.getCurrentPriority().name());
        data.put("progress", ticket.getCurrentProgress());
        data.put("publicMessage", publicMessage);
        data.put("details", details);
        data.put("attachments", List.of());
        // No usar ticket.getUpdatedAt(): es @UpdateTimestamp (Hibernate) y sólo
        // se completa en el flush, que todavía no ocurrió acá (estamos en la
        // misma transacción, justo después del save()) — leerlo en este punto
        // devuelve el valor viejo persistido antes de este cambio, no el que
        // se va a persistir. "now" es el mismo instante que ya se usa para
        // occurredAt y para el resto de los campos de auditoría de este
        // método, así que es la fuente correcta para "cuándo pasó esto".
        data.put("updatedAt", now.toString());

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
        event.setUpdateType(updateType);
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
        validateSort(pageable.getSort());
        Specification<Ticket> specification = TicketSpecifications.build(filter);
        Page<Ticket> page = ticketRepository.findAll(specification, pageable);

        List<UUID> ticketIds = page.getContent().stream().map(Ticket::getId).toList();
        Map<UUID, TicketLocation> locationsByTicket = locationRepository
                .findAllByTicket_IdIn(ticketIds).stream()
                .collect(Collectors.toMap(location -> location.getTicket().getId(), Function.identity()));

        return page.map(ticket -> toResponse(ticket, locationsByTicket.get(ticket.getId())));
    }

    /**
     * GET /me/tickets (Entidades V1.49 §"Autenticación, roles y vistas"):
     * cualquier usuario autenticado vía M1 conserva capacidades ciudadanas
     * base sobre sus propios tickets. A diferencia de listTickets (la
     * bandeja staff de la Story 3.1, restringida por rol en
     * SecurityConfiguration), acá no hay scoping por rol ni área: siempre
     * se filtra por el citizenId de quien pregunta.
     */
    @Transactional(readOnly = true)
    public Page<TicketResponse> listMyTickets(AuthenticatedIdentity identity, Pageable pageable) {
        validateSort(pageable.getSort());
        Page<Ticket> page = ticketRepository.findByCitizenId(identity.citizenId(), pageable);

        List<UUID> ticketIds = page.getContent().stream().map(Ticket::getId).toList();
        Map<UUID, TicketLocation> locationsByTicket = locationRepository
                .findAllByTicket_IdIn(ticketIds).stream()
                .collect(Collectors.toMap(location -> location.getTicket().getId(), Function.identity()));

        return page.map(ticket -> toResponse(ticket, locationsByTicket.get(ticket.getId())));
    }

    /**
     * GET /tickets/{id} (Entidades V1.49 §"Autenticación, roles y vistas":
     * "Ciudadano owner | Consultar detalle ciudadano autorizado."). Un
     * ticket anónimo no tiene citizenId, así que nunca es "propio" de
     * ningún autenticado acá; se consulta por POST /tracking/access, no por
     * este endpoint.
     */
    @Transactional(readOnly = true)
    public TicketResponse getById(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        requireOwner(ticket, identity);
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        return toResponse(ticket, location);
    }

    private void requireOwner(Ticket ticket, AuthenticatedIdentity identity) {
        if (identity == null || ticket.isAnonymous() || ticket.getCitizenId() == null
                || !ticket.getCitizenId().equals(identity.citizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    /**
     * GET /staff/tickets/{id} (Guía funcional M2 §7, tabla de roles + §7.1):
     * detalle staff. AGENT y ADMIN acceden a cualquier ticket ajeno sin
     * restricción de área. AREA_RESPONSIBLE sólo accede a tickets de su
     * propia areaId, salvo que el ticket sea suyo como ciudadano (§7.1:
     * "si el usuario interno es owner... puede ver información interna por
     * su rol"), en cuyo caso también puede verlo aunque sea de otra área.
     * La restricción de "toda acción staff queda bloqueada" en ticket propio
     * no aplica acá porque este endpoint es de sólo lectura.
     */
    @Transactional(readOnly = true)
    public TicketResponse getStaffDetail(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        requireStaffAccess(ticket, identity);
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        return toResponse(ticket, location);
    }

    private void requireStaffAccess(Ticket ticket, AuthenticatedIdentity identity) {
        if (identity == null) {
            throw new UnauthorizedTicketOperationException();
        }
        boolean isOwnTicket = !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(identity.citizenId());
        if (identity.role() == ModuleRole.AREA_RESPONSIBLE && !isOwnTicket
                && !ticket.getResponsibleAreaId().equals(identity.areaId())) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    /**
     * Story 2.3 (Enforcement de permisos por rol en backend): autorización
     * para las acciones de triage (startReview/correctClassification/
     * routeToArea). El filtro de rol (sólo AGENT/ADMIN llegan hasta acá; ver
     * SecurityConfiguration — AREA_RESPONSIBLE queda afuera de estas tres
     * acciones porque la Guía funcional M2 §7 limita su capacidad staff a
     * "leer y enviar mensajes PUBLIC/INTERNAL", sin triage, ni siquiera sobre
     * tickets de su propia área) ya se resolvió antes de entrar acá. Lo que
     * falta y no se puede expresar en el filtro es el bloqueo de "acción
     * staff en ticket propio": Entidades V1.49 §3.3 dice que si
     * currentUser.citizenId = ticket.citizenId, "la vista staff queda
     * completamente read-only, incluso para ADMIN" — ningún AGENT/ADMIN
     * puede triagear su propio ticket.
     */
    private void requireTriageAuthority(Ticket ticket, AuthenticatedIdentity actor) {
        if (actor == null) {
            throw new UnauthorizedTicketOperationException();
        }
        boolean isOwnTicket = !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(actor.citizenId());
        if (isOwnTicket) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    /**
     * GET /staff/tickets/{id}/citizen-view (Guía funcional M2 §7.1): "misma
     * proyección visible al ciudadano", pero consultada desde el contexto
     * staff — no cambia la identidad de quien pregunta ni suplanta al
     * propietario. El control de acceso de entrada es el mismo que
     * getStaffDetail (AGENT/ADMIN cualquier ticket ajeno; AREA_RESPONSIBLE
     * sólo su areaId o su propio ticket); la Guía aclara "en ticket ajeno es
     * read-only", pero eso ya lo garantiza que este endpoint sea un GET.
     * <p>
     * Hoy delega directo en getStaffDetail porque TicketResponse todavía no
     * distingue campos exclusivos de staff de la proyección pública del
     * ciudadano — son la misma forma. Si el equipo agrega campos internos al
     * detalle staff (por ejemplo notas internas u otra info no pública), hay
     * que separar los dos mapeos acá, no sólo el nombre del método.
     */
    @Transactional(readOnly = true)
    public TicketResponse getStaffCitizenView(UUID ticketId, AuthenticatedIdentity identity) {
        return getStaffDetail(ticketId, identity);
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
                                 Priority previousPriority, Priority newPriority, String message) {
        long nextSequence = activityRepository.countByTicketId(ticket.getId()) + 1;

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) nextSequence);
        activity.setActionType(actionType);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(newStatus);
        // TODO (a confirmar con el equipo): AGENT/SYSTEM cubre las transiciones
        // internas de M2 (startReview, correctClassification, routeToArea), que
        // siempre corren con un agente autenticado; SYSTEM sólo queda para el
        // caso defensivo de actor == null ("sin actor identificado" según la
        // Guía funcional). Evaluar si ADMIN debería usarse en algún caso acá.
        activity.setActorType(actor != null ? ActorType.AGENT : ActorType.SYSTEM);
        activity.setActorId(actor != null ? actor.subjectId() : null);
        activity.setPreviousPriority(previousPriority);
        activity.setNewPriority(newPriority);
        activity.setMessage(message);
        activity.setOccurredAt(clock.instant());
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
        response.setAssignedAgentId(ticket.getAssignedAgent() != null
                ? String.valueOf(ticket.getAssignedAgent().getId()) : null);
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

    /**
     * Re-derivación / recálculo de SLA de derivación (independiente de
     * routeToArea, que cubre sólo la primera derivación IN_REVIEW -&gt; ROUTED
     * de Story 3.3). Recalcula resolutionDueAt desde createdAt cada vez que
     * se invoca. Nota (a confirmar con el equipo dev): no encontramos ningún
     * llamador todavía en esta rama — puede ser un método pensado para una
     * integración o story que no está visible en este merge; no lo
     * eliminamos porque tiene su propia batería de tests ya aprobada en dev.
     */
    @Transactional
    public Ticket route(UUID ticketId, String responsibleAreaId, Instant routedAt) {
        Ticket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
        if (ticket.getCurrentStatus() == TicketStatus.DUPLICATE)
            throw new InvalidTicketRequestException("Un ticket duplicado hereda el SLA del ticket principal");
        ticket.setResolutionDueAt(slaCalculationService.calculateResolutionDueAt(
                ticket.getCreatedAt(), ticket.getCurrentPriority(), ticket.getTicketType()).orElse(null));
        ticket.setResponsibleAreaId(responsibleAreaId);
        ticket.setCurrentStatus(TicketStatus.ROUTED);
        ticket.setStatusChangedAt(routedAt);
        return ticketRepository.save(ticket);
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

}
